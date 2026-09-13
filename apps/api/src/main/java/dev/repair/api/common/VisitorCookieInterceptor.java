package dev.repair.api.common;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Assigns every visitor an opaque random session cookie on first contact
 * and resolves it into {@link VisitorContext} on every request. This is
 * deliberately not a shared "demo user" id: ownership checks throughout the
 * conversation endpoints compare against this per-browser identity, so one
 * anonymous visitor can never read or delete another's sessions.
 */
@Component
public class VisitorCookieInterceptor implements HandlerInterceptor {

    public static final String COOKIE_NAME = "repair_visitor";
    private static final int MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

    private final VisitorContext visitorContext;

    public VisitorCookieInterceptor(VisitorContext visitorContext) {
        this.visitorContext = visitorContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID visitorId = readCookie(request);
        if (visitorId == null) {
            visitorId = UUID.randomUUID();
            Cookie cookie = new Cookie(COOKIE_NAME, visitorId.toString());
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(MAX_AGE_SECONDS);
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        }
        visitorContext.setVisitorId(visitorId);
        return true;
    }

    private UUID readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                try {
                    return UUID.fromString(cookie.getValue());
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        }
        return null;
    }
}
