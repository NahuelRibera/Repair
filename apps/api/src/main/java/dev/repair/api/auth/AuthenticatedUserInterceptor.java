package dev.repair.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves {@link AuthenticatedUserContext} from Spring Security's
 * already-authenticated session on every request — one indexed lookup by
 * google_sub, not a network call (the OAuth2/OIDC token exchange itself
 * already happened once, at login; see OAuth2LoginSuccessHandler). A
 * request with no authenticated principal simply leaves the context
 * unpopulated; SecurityConfig's ".authenticated()" rule is what actually
 * blocks unauthenticated access to protected endpoints (with a 401 JSON
 * response — see ApiAuthenticationEntryPoint), not this interceptor.
 */
@Component
public class AuthenticatedUserInterceptor implements HandlerInterceptor {

    private final AppUserRepository appUserRepository;
    private final AuthenticatedUserContext context;

    public AuthenticatedUserInterceptor(AppUserRepository appUserRepository, AuthenticatedUserContext context) {
        this.appUserRepository = appUserRepository;
        this.context = context;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof OidcUser oidcUser) {
            appUserRepository.findByGoogleSub(oidcUser.getSubject()).ifPresent(context::setUser);
        }
        return true;
    }
}
