package dev.repair.api.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Runs exactly once per successful Google login (never per request — see
 * AuthenticatedUserInterceptor for the per-request path). Finds or
 * creates the Repair `app_users` row keyed by the OIDC `sub`, then
 * redirects the browser to wherever LoginRedirectController stashed as
 * the intended destination — re-validated here too, since a cookie is
 * client-observable (though HttpOnly) and this is the one place that
 * actually issues the redirect.
 *
 * The redirect is always an ABSOLUTE URL against {@link #frontendBaseUrl},
 * never a bare relative path — see that field's own doc comment for why
 * this specific handler (unlike every other redirect in the login flow)
 * cannot rely on relative-URL resolution.
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AppUserService appUserService;

    /**
     * Google's own OAuth callback ({@code /api/login/oauth2/code/google})
     * is the one request in the entire login flow that the browser sends
     * directly to this backend's own origin, bypassing the Next.js dev
     * proxy entirely (see docs/authentication.md "Google Cloud OAuth
     * config" — the registered redirect URI is this backend's absolute
     * port, not the frontend's). Embedded Tomcat emits a relative
     * {@code Location} header as-is (RFC 7231 relative redirects, the
     * modern default), and the browser then resolves it against whatever
     * origin it most recently talked to — for this one request, that's
     * this backend, not the frontend. A bare {@code response.sendRedirect("/chat")}
     * here therefore lands the browser on this backend's own origin
     * (e.g. http://localhost:8082/chat), which has no such route and
     * produces Spring Boot's default Whitelabel 404 — this was a real,
     * reproduced bug, not a hypothetical. The fix is to always redirect
     * to an absolute URL built from this server-configured, trusted
     * frontend origin; RedirectValidator still constrains the *path*
     * portion to a safe same-app relative path first, so this never
     * becomes an open redirect — only the already-validated path is
     * appended to a value this server controls, never one a client can
     * influence.
     */
    private final String frontendBaseUrl;

    public OAuth2LoginSuccessHandler(
            AppUserService appUserService, @Value("${repair.frontend.base-url}") String frontendBaseUrl
    ) {
        this.appUserService = appUserService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            appUserService.findOrCreate(
                    oidcUser.getSubject(), oidcUser.getEmail(), oidcUser.getFullName(), oidcUser.getPicture()
            );
        }

        String next = readAndClearPendingRedirect(request, response);
        String safeNext = RedirectValidator.sanitizeOrDefault(next, LoginRedirectController.DEFAULT_NEXT);
        response.sendRedirect(frontendBaseUrl + safeNext);
    }

    private String readAndClearPendingRedirect(HttpServletRequest request, HttpServletResponse response) {
        String value = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (LoginRedirectController.PENDING_REDIRECT_COOKIE.equals(cookie.getName())) {
                    value = java.net.URLDecoder.decode(cookie.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        Cookie expired = new Cookie(LoginRedirectController.PENDING_REDIRECT_COOKIE, "");
        expired.setHttpOnly(true);
        expired.setPath("/");
        expired.setMaxAge(0);
        response.addCookie(expired);
        return value;
    }
}
