package dev.codecomp;

import dev.codecomp.auth.UserAccounts;
import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.RunResult.RunStatus;
import dev.codecomp.sandbox.Sandbox;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production auth settings: Google sign-in configured (with fake credentials), dev header off. */
@SpringBootTest(properties = {
        "app.warm-on-startup=false",
        "app.auth.dev-header=false",
        "app.auth.google.client-id=test-client-id",
        "app.auth.google.client-secret=test-secret",
        "spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @TestConfiguration
    static class FakeSandbox {
        @Bean
        @Primary
        Sandbox fakeSandbox() {
            return new Sandbox() {
                public Set<String> languages() { return Set.of("java", "python", "java-project"); }
                public RunResult run(String language, String code) { return new RunResult(RunStatus.OK, "ran\n", "", 0, 5, false); }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FilterChainProxy filters;

    @Autowired
    UserAccounts accounts;

    @Test
    void apiRequiresSignInAndIgnoresTheOldHeader() throws Exception {
        mvc.perform(get("/api/exercises")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exercises").header("X-User-Id", "00000000-0000-0000-0000-000000000001")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user", nullValue()))
                .andExpect(jsonPath("$.googleConfigured", is(true)))
                .andExpect(jsonPath("$.devHeader", is(false)));
    }

    @Test
    void signInStartsAtGoogle() throws Exception {
        mvc.perform(get("/api/auth/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://accounts.google.com/o/oauth2/v2/auth?")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("client_id=test-client-id")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString(
                        "redirect_uri=http://localhost/api/auth/oauth2/code/google")));
    }

    @Test
    void signingInCreatesOneAccountPerGoogleIdentityAndOpensTheApi() throws Exception {
        var session = signIn("google-sub-1", "ann@example.com", "Ann");
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.user.name", is("Ann")))
                .andExpect(jsonPath("$.user.email", is("ann@example.com")));
        mvc.perform(get("/api/points").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.total", is(0)));

        // signing in again (with a changed email) is the same account, so progress carries over
        var a = accounts.signIn("google", "google-sub-1", "ann@new.example.com", "Ann", null);
        var b = accounts.signIn("google", "google-sub-1", "ann@new.example.com", "Ann", null);
        var other = accounts.signIn("google", "google-sub-2", "bob@example.com", "Bob", null);
        assertThat(a.id()).isEqualTo(b.id()).isNotEqualTo(other.id());
    }

    @Test
    void stateChangingRequestsNeedTheCsrfToken() throws Exception {
        var session = signIn("google-sub-3", "cy@example.com", "Cy");
        String body = "{\"language\":\"python\",\"code\":\"print(1)\"}";

        mvc.perform(post("/api/runs").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        var token = mvc.perform(get("/api/auth/me").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        mvc.perform(post("/api/runs").session(session).cookie(new Cookie("XSRF-TOKEN", token.getValue()))
                        .header("X-XSRF-TOKEN", token.getValue()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        var session = signIn("google-sub-4", "di@example.com", "Di");
        var token = mvc.perform(get("/api/auth/me").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/auth/logout").session(session).cookie(new Cookie("XSRF-TOKEN", token.getValue()))
                        .header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/exercises").session(session)).andExpect(status().isUnauthorized());
    }

    /** Runs the real sign-in success handler, as if Google had just redirected back with this identity. */
    MockHttpSession signIn(String subject, String email, String name) throws Exception {
        var google = new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES,
                Map.of("sub", subject, "email", email, "name", name), "sub");
        var authentication = new OAuth2AuthenticationToken(google, AuthorityUtils.NO_AUTHORITIES, "google");
        var session = new MockHttpSession();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setSession(session);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        successHandler().onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");

        // what Spring Security stores after a successful login, so the session counts as authenticated
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        return session;
    }

    AuthenticationSuccessHandler successHandler() throws Exception {
        var filter = filters.getFilterChains().stream().flatMap(chain -> chain.getFilters().stream())
                .filter(f -> f instanceof OAuth2LoginAuthenticationFilter).findFirst().orElseThrow();
        var field = org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter.class.getDeclaredField("successHandler");
        field.setAccessible(true);
        return (AuthenticationSuccessHandler) field.get(filter);
    }
}
