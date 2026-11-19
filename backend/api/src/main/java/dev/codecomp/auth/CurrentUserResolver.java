package dev.codecomp.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Supplies {@code @CurrentUser UUID} controller parameters. */
class CurrentUserResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest web, WebDataBinderFactory binder) {
        var user = find(web.getNativeRequest(HttpServletRequest.class));
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue");
        return user.id();
    }

    /** The user set by the dev-header filter for this request, otherwise the one stored in the session at sign-in. */
    static AppUser find(HttpServletRequest request) {
        if (request.getAttribute(SecurityConfig.USER_ATTRIBUTE) instanceof AppUser user) return user;
        var session = request.getSession(false);
        return session != null && session.getAttribute(SecurityConfig.USER_ATTRIBUTE) instanceof AppUser user ? user : null;
    }
}
