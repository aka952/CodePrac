package dev.codecomp.auth;

import dev.codecomp.platform.AuthTokens;
import dev.codecomp.platform.CurrentUserResolver;
import dev.codecomp.platform.SignedInUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** {@code user} is null when nobody is signed in. The two flags tell the page which sign-in options exist. */
    record Me(Profile user, boolean devHeader, boolean googleConfigured) {}

    record Profile(String name, String email, String picture) {}

    private final boolean devHeader;
    private final boolean googleConfigured;
    private final JwtDecoder decoder;
    private final SignInTokens tokens;

    AuthController(@Value("${app.auth.dev-header}") boolean devHeader, ObjectProvider<ClientRegistrationRepository> clients,
                   JwtDecoder decoder, SignInTokens tokens) {
        this.devHeader = devHeader;
        this.googleConfigured = clients.getIfAvailable() != null;
        this.decoder = decoder;
        this.tokens = tokens;
    }

    /** Public, so it reads the cookie itself: an expired or foreign token means "signed out", not an error. */
    @GetMapping("/me")
    Me me(HttpServletRequest request) {
        var user = CurrentUserResolver.find(SecurityContextHolder.getContext().getAuthentication());
        if (user == null) user = fromCookie(request);
        return new Me(user == null ? null : new Profile(user.name(), user.email(), user.picture()), devHeader, googleConfigured);
    }

    /** Deletes the cookie. The token itself stays valid until it expires (see {@link SignInTokens}). */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        IdentitySecurity.setCookie(request, response, "", Duration.ZERO);
        return ResponseEntity.noContent().build();
    }

    /** The public keys other services check sign-in tokens with. */
    @GetMapping("/jwks")
    Map<String, Object> jwks() {
        return tokens.publicKeys();
    }

    private SignedInUser fromCookie(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) return null;
        var token = Arrays.stream(cookies).filter(c -> AuthTokens.COOKIE.equals(c.getName()) && !c.getValue().isBlank())
                .map(jakarta.servlet.http.Cookie::getValue).findFirst();
        if (token.isEmpty()) return null;
        try {
            return CurrentUserResolver.fromToken(decoder.decode(token.get()));
        } catch (JwtException e) {
            return null;
        }
    }
}
