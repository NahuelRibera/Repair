package dev.repair.api.auth;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * The authenticated Repair user for the current request, resolved by
 * {@link AuthenticatedUserInterceptor} from Spring Security's session.
 * Every motorcycle-domain endpoint must check ownership against this id
 * instead of trusting any id supplied in the request body/path — the
 * authenticated counterpart of {@code dev.repair.api.common.VisitorContext}
 * (which remains in place, unchanged, for the preserved car-prototype
 * endpoints). getUserId() is only ever called from a controller/repository
 * reachable behind Spring Security's ".authenticated()" rule, so the
 * interceptor is guaranteed to have populated it — see SecurityConfig.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class AuthenticatedUserContext {

    private AppUserDto user;

    public AppUserDto getUser() {
        return user;
    }

    public long getUserId() {
        if (user == null) {
            throw new IllegalStateException(
                    "AuthenticatedUserContext was not populated for this request — this endpoint must be behind " +
                            "SecurityConfig's .authenticated() rule."
            );
        }
        return user.id();
    }

    public void setUser(AppUserDto user) {
        this.user = user;
    }
}
