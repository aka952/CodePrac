package dev.codecomp.platform;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Supplies {@code @CurrentUser UUID} controller parameters. */
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest web, WebDataBinderFactory binder) {
        var user = find(SecurityContextHolder.getContext().getAuthentication());
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue");
        return user.id();
    }

    /** The user from a verified sign-in token, or the one the dev-header filter set; null when nobody is signed in. */
    public static SignedInUser find(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) return fromToken(token.getToken());
        if (authentication != null && authentication.getPrincipal() instanceof SignedInUser user) return user;
        return null;
    }

    /** The token's subject is the user id; anything else is not one of our tokens. */
    public static SignedInUser fromToken(Jwt jwt) {
        try {
            return new SignedInUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("name"),
                    jwt.getClaimAsString("email"), jwt.getClaimAsString("picture"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
