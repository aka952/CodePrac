package dev.codecomp.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** {@code user} is null when nobody is signed in. The two flags tell the page which sign-in options exist. */
    record Me(Profile user, boolean devHeader, boolean googleConfigured) {}

    record Profile(String name, String email, String picture) {}

    private final boolean devHeader;
    private final boolean googleConfigured;

    AuthController(@Value("${app.auth.dev-header}") boolean devHeader, ObjectProvider<ClientRegistrationRepository> clients) {
        this.devHeader = devHeader;
        this.googleConfigured = clients.getIfAvailable() != null;
    }

    @GetMapping("/me")
    Me me(HttpServletRequest request) {
        var user = CurrentUserResolver.find(request);
        return new Me(user == null ? null : new Profile(user.name(), user.email(), user.picture()), devHeader, googleConfigured);
    }
}
