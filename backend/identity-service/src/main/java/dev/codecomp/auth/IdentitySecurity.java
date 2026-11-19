package dev.codecomp.auth;

import dev.codecomp.platform.AuthTokens;
import dev.codecomp.platform.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Duration;

/**
 * Google sign-in (OpenID Connect). The only session in the whole system lives here, and only for the few seconds of
 * the redirect to Google and back; after that the browser holds a signed token in a cookie and no server remembers it.
 * With more than one instance of this service, the load balancer must keep a browser on one instance during sign-in
 * (sticky sessions on this target group).
 */
@Configuration
class IdentitySecurity {

    private static final Logger log = LoggerFactory.getLogger(IdentitySecurity.class);

    private final String googleClientId;
    private final String googleClientSecret;

    IdentitySecurity(@Value("${app.auth.google.client-id}") String googleClientId,
                     @Value("${app.auth.google.client-secret}") String googleClientSecret) {
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        if (googleClientId.isBlank()) log.warn("GOOGLE_CLIENT_ID is not set: Google sign-in is disabled");
    }

    /** Only present when Google credentials are configured, so the service still starts without them. */
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
    SecurityConfig.Customizer googleSignIn(ObjectProvider<ClientRegistrationRepository> clients, UserAccounts accounts, SignInTokens tokens) {
        return http -> {
            if (clients.getIfAvailable() == null) return;
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
            // everything stays under /api/auth so the load balancer needs one rule for this service
            http.oauth2Login(login -> login
                    .authorizationEndpoint(a -> a.baseUri("/api/auth/oauth2/authorization"))
                    .redirectionEndpoint(r -> r.baseUri("/api/auth/oauth2/code/*"))
                    .successHandler((request, response, authentication) -> {
                        var google = (OAuth2User) authentication.getPrincipal();
                        var user = accounts.signIn("google", google.getName(), google.getAttribute("email"),
                                google.getAttribute("name"), google.getAttribute("picture"));
                        setCookie(request, response, tokens.issue(user), tokens.lifetime());
                        var session = request.getSession(false);
                        if (session != null) session.invalidate();
                        response.sendRedirect("/");
                    })
                    .failureHandler((request, response, exception) -> {
                        log.warn("Google sign-in failed: {}", exception.getMessage());
                        response.sendRedirect("/?signin=failed");
                    }));
        };
    }

    /** Secure when the browser came in over HTTPS (the load balancer says so in X-Forwarded-Proto). */
    static void setCookie(HttpServletRequest request, HttpServletResponse response, String value, Duration maxAge) {
        var cookie = ResponseCookie.from(AuthTokens.COOKIE, value)
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/").maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
