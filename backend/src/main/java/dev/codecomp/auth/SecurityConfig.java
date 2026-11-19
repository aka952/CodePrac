package dev.codecomp.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Sign-in is required for every API call. People sign in with Google (OpenID Connect); the session lives in an
 * HTTP-only cookie, and state-changing requests must echo the CSRF token cookie in a header.
 */
@Configuration
@ConditionalOnWebApplication
class SecurityConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Where the signed-in {@link AppUser} is kept: in the session after Google sign-in, on the request in dev-header mode. */
    static final String USER_ATTRIBUTE = "appUser";
    static final String DEV_HEADER = "X-User-Id";

    private final boolean devHeader;
    private final String googleClientId;
    private final String googleClientSecret;

    SecurityConfig(@Value("${app.auth.dev-header}") boolean devHeader,
                   @Value("${app.auth.google.client-id}") String googleClientId,
                   @Value("${app.auth.google.client-secret}") String googleClientSecret) {
        this.devHeader = devHeader;
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        if (devHeader) log.warn("AUTH_DEV_HEADER is on: any caller can act as any user through the {} header. Never use this in production.", DEV_HEADER);
        if (googleClientId.isBlank()) log.warn("GOOGLE_CLIENT_ID is not set: Google sign-in is disabled");
    }

    /** Only present when Google credentials are configured, so the app still starts without them. */
    @Bean
    @ConditionalOnExpression("!'${app.auth.google.client-id:}'.isEmpty()")
    ClientRegistrationRepository clientRegistrations() {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(googleClientId)
                .clientSecret(googleClientSecret)
                .redirectUri("{baseUrl}/api/auth/oauth2/code/{registrationId}")
                .build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> clients, UserAccounts accounts) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        // async dispatches finish requests that were already authorized (Server-Sent Events); error dispatches render errors
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                // an API: answer 401 and let the page show its sign-in screen, instead of redirecting to a login page
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // a request identified by a custom header cannot be forged by another site, so it needs no token
                        .ignoringRequestMatchers(request -> devHeader && request.getHeader(DEV_HEADER) != null))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        if (clients.getIfAvailable() != null) {
            // everything stays under /api so the existing reverse proxies need no extra routes
            http.oauth2Login(login -> login
                    .authorizationEndpoint(a -> a.baseUri("/api/auth/oauth2/authorization"))
                    .redirectionEndpoint(r -> r.baseUri("/api/auth/oauth2/code/*"))
                    .successHandler((request, response, authentication) -> {
                        var google = (OAuth2User) authentication.getPrincipal();
                        var user = accounts.signIn("google", google.getName(), google.getAttribute("email"),
                                google.getAttribute("name"), google.getAttribute("picture"));
                        request.getSession().setAttribute(USER_ATTRIBUTE, user);
                        response.sendRedirect("/");
                    })
                    .failureHandler((request, response, exception) -> {
                        log.warn("Google sign-in failed: {}", exception.getMessage());
                        response.sendRedirect("/?signin=failed");
                    }));
        }
        if (devHeader) http.addFilterBefore(new DevHeaderFilter(), AuthorizationFilter.class);
        return http.build();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserResolver());
    }

    /** Dev and test only: treats the UUID in the X-User-Id header as the signed-in user. */
    private static final class DevHeaderFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            var header = request.getHeader(DEV_HEADER);
            if (header != null) {
                try {
                    var user = new AppUser(UUID.fromString(header), "Dev user", null, null);
                    request.setAttribute(USER_ATTRIBUTE, user);
                    SecurityContextHolder.getContext().setAuthentication(new PreAuthenticatedAuthenticationToken(user, "n/a", List.of()));
                } catch (IllegalArgumentException ignored) {
                    // not a UUID: stay unauthenticated
                }
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * CSRF handling for a single-page app, as recommended in the Spring Security reference: the token is readable from
     * a cookie, and the page sends it back as a plain header value on every state-changing request.
     */
    private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            boolean fromHeader = StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()));
            return (fromHeader ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
        }
    }

    /** Loads the token on every request so that its cookie is always sent to the page. */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) token.getToken();
            chain.doFilter(request, response);
        }
    }
}
