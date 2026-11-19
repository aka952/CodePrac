package dev.codecomp.auth;

import dev.codecomp.platform.AuthTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production auth settings: Google sign-in configured (with fake credentials), dev header off. */
@SpringBootTest(properties = {
        "app.auth.dev-header=false",
        "app.auth.google.client-id=test-client-id",
        "app.auth.google.client-secret=test-secret",
        "spring.datasource.url=jdbc:h2:mem:identity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FilterChainProxy filters;

    @Autowired
    UserAccounts accounts;

    @Autowired
    JwtDecoder decoder;

    @Test
    void nobodyIsSignedInWithoutTheCookie() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user", nullValue()))
                .andExpect(jsonPath("$.googleConfigured", is(true)))
                .andExpect(jsonPath("$.devHeader", is(false)));
        // the dev header is ignored when it is switched off
        mvc.perform(get("/api/auth/me").header(AuthTokens.DEV_HEADER, UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.user", nullValue()));
    }

    @Test
    void signInStartsAtGoogle() throws Exception {
        mvc.perform(get("/api/auth/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com/o/oauth2/v2/auth?")))
                .andExpect(header().string("Location", containsString("client_id=test-client-id")))
                .andExpect(header().string("Location", containsString("redirect_uri=http://localhost/api/auth/oauth2/code/google")));
    }

    @Test
    void signingInSetsASignedTokenCookie() throws Exception {
        var cookie = signIn("google-sub-1", "ann@example.com", "Ann");
        assertThat(cookie.isHttpOnly()).isTrue();

        var jwt = decoder.decode(cookie.getValue());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(AuthTokens.ISSUER);
        assertThat(UUID.fromString(jwt.getSubject())).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt().plusSeconds(6 * 24 * 3600));

        mvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(jsonPath("$.user.name", is("Ann")))
                .andExpect(jsonPath("$.user.email", is("ann@example.com")));
    }

    @Test
    void oneAccountPerGoogleIdentity() {
        // signing in again (with a changed email) is the same account, so progress carries over
        var a = accounts.signIn("google", "google-sub-2", "bo@example.com", "Bo", null);
        var b = accounts.signIn("google", "google-sub-2", "bo@new.example.com", "Bo", null);
        var other = accounts.signIn("google", "google-sub-3", "cy@example.com", "Cy", null);
        assertThat(a.id()).isEqualTo(b.id()).isNotEqualTo(other.id());
    }

    @Test
    void aTamperedTokenMeansSignedOut() throws Exception {
        var cookie = signIn("google-sub-4", "di@example.com", "Di");
        var parts = cookie.getValue().split("\\.");
        var forged = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        mvc.perform(get("/api/auth/me").cookie(new Cookie(AuthTokens.COOKIE, forged)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user", nullValue()));
    }

    @Test
    void publishesThePublicKeyOnly() throws Exception {
        mvc.perform(get("/api/auth/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty", is("RSA")))
                .andExpect(jsonPath("$.keys[0].n", notNullValue()))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void logoutNeedsTheCsrfTokenAndDeletesTheCookie() throws Exception {
        var session = signIn("google-sub-5", "ed@example.com", "Ed");
        mvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isForbidden());

        var csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        var response = mvc.perform(post("/api/auth/logout").cookie(session, new Cookie("XSRF-TOKEN", csrf.getValue()))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        var cleared = response.getCookie(AuthTokens.COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    /** Runs the real sign-in success handler, as if Google had just redirected back with this identity. */
    Cookie signIn(String subject, String email, String name) throws Exception {
        var google = new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("sub", subject, "email", email, "name", name), "sub");
        var authentication = new OAuth2AuthenticationToken(google, AuthorityUtils.NO_AUTHORITIES, "google");
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        successHandler().onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        var cookie = response.getCookie(AuthTokens.COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    AuthenticationSuccessHandler successHandler() throws Exception {
        var filter = filters.getFilterChains().stream().flatMap(chain -> chain.getFilters().stream())
                .filter(f -> f instanceof OAuth2LoginAuthenticationFilter).findFirst().orElseThrow();
        var field = AbstractAuthenticationProcessingFilter.class.getDeclaredField("successHandler");
        field.setAccessible(true);
        return (AuthenticationSuccessHandler) field.get(filter);
    }
}
