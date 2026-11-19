package dev.codecomp.platform;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The filter chain every web service uses. Sign-in is required for every API call: the browser holds a JWT, signed by
 * identity-service, in an HTTP-only cookie, and each service checks its signature against identity-service's public
 * keys (JWKS) locally, so no request needs a call to identity-service. No server keeps a session.
 * State-changing requests must echo the CSRF token cookie in a header (stateless double-submit, the same in every service).
 *
 * <p>Per service: {@code app.security.public-paths} need no sign-in; {@code app.security.internal-paths} need no
 * sign-in and no CSRF token, because only other services can reach them (the load balancer has no route to them).
 */
@Configuration
@ConditionalOnWebApplication
public class SecurityConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    /** Lets a service add to the shared chain; identity-service adds Google sign-in and logout. */
    @FunctionalInterface
    public interface Customizer {
        void customize(HttpSecurity http) throws Exception;
    }

    private final boolean devHeader;
    private final String[] publicPaths;
    private final String[] internalPaths;

    SecurityConfig(@Value("${app.auth.dev-header:false}") boolean devHeader,
                   @Value("${app.security.public-paths:}") String[] publicPaths,
                   @Value("${app.security.internal-paths:}") String[] internalPaths) {
        this.devHeader = devHeader;
        this.publicPaths = nonBlank(publicPaths);
        this.internalPaths = nonBlank(internalPaths);
        if (devHeader) log.warn("AUTH_DEV_HEADER is on: any caller can act as any user through the {} header. Never use this in production.", AuthTokens.DEV_HEADER);
    }

    /** Checks tokens against identity-service's published keys. identity-service itself defines its own decoder instead. */
    @Bean
    @ConditionalOnProperty("app.auth.jwks-uri")
    JwtDecoder jwtDecoder(@Value("${app.auth.jwks-uri}") String jwksUri) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AuthTokens.ISSUER));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder, List<Customizer> customizers) throws Exception {
        var unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http.authorizeHttpRequests(auth -> {
                    // async dispatches finish requests that were already authorized (Server-Sent Events); error dispatches render errors
                    auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                            .requestMatchers("/actuator/health").permitAll();
                    if (publicPaths.length > 0) auth.requestMatchers(publicPaths).permitAll();
                    if (internalPaths.length > 0) auth.requestMatchers(internalPaths).permitAll();
                    auth.anyRequest().authenticated();
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // an API: answer 401 and let the page show its sign-in screen, instead of redirecting to a login page
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(headerTokenResolver())
                        .authenticationEntryPoint(unauthorized)
                        .jwt(jwt -> jwt.decoder(decoder)))
                // before the anonymous filter, which would otherwise mark the request "anonymous" first
                .addFilterAfter(new CookieTokenFilter(decoder), BearerTokenAuthenticationFilter.class)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(request -> matches(internalPaths, request)
                                // a request identified by a custom header cannot be forged by another site, so it needs no token
                                || devHeader && request.getHeader(AuthTokens.DEV_HEADER) != null))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .logout(logout -> logout.disable());
        if (devHeader) http.addFilterBefore(new DevHeaderFilter(), AuthorizationFilter.class);
        for (var customizer : customizers) customizer.customize(http);
        return http.build();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserResolver());
    }

    /**
     * The token from the {@code Authorization: Bearer} header only. Spring skips the CSRF check for requests carrying a
     * bearer token, which is safe for a header (another site cannot make the browser send one) but not for a cookie,
     * so the cookie is handled by {@link CookieTokenFilter} and still needs the CSRF token.
     * Ignored on public paths, so a stale token cannot make the sign-in endpoints answer 401.
     */
    private BearerTokenResolver headerTokenResolver() {
        var header = new DefaultBearerTokenResolver();
        return request -> matches(publicPaths, request) ? null : header.resolve(request);
    }

    /** Signs the browser in from the sign-in cookie. An invalid or expired token leaves it signed out (so: 401). */
    private final class CookieTokenFilter extends OncePerRequestFilter {
        private final JwtDecoder decoder;

        CookieTokenFilter(JwtDecoder decoder) { this.decoder = decoder; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            var context = SecurityContextHolder.getContext();
            var cookies = request.getCookies();
            if (context.getAuthentication() == null && cookies != null && !matches(publicPaths, request)) {
                Arrays.stream(cookies).filter(c -> AuthTokens.COOKIE.equals(c.getName()) && !c.getValue().isBlank()).findFirst().ifPresent(c -> {
                    try {
                        // this constructor marks the token authenticated; the one without authorities does not
                        context.setAuthentication(new JwtAuthenticationToken(decoder.decode(c.getValue()), List.of()));
                    } catch (JwtException ignored) {
                        // expired, tampered with, or signed by someone else: not signed in
                    }
                });
            }
            chain.doFilter(request, response);
        }
    }

    private static boolean matches(String[] patterns, HttpServletRequest request) {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        return Arrays.stream(patterns).anyMatch(p -> PATHS.match(p, path));
    }

    private static String[] nonBlank(String[] values) {
        return Arrays.stream(values).map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    /** Dev and test only: treats the UUID in the X-User-Id header as the signed-in user. */
    private static final class DevHeaderFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            var header = request.getHeader(AuthTokens.DEV_HEADER);
            if (header != null) {
                try {
                    var user = new SignedInUser(UUID.fromString(header), "Dev user", null, null);
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
