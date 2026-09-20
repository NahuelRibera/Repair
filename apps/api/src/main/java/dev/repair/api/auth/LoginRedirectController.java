package dev.repair.api.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point the frontend navigates to (a real browser navigation, not
 * a fetch) to start a Google sign-in: "Continue with Google" links here
 * with a `next` query param naming where to land afterward (e.g. the
 * bike the visitor picked before signing in — see
 * docs/authentication.md "landing bike selector before login"). This
 * stashes that destination in a short-lived cookie and hands off to
 * Spring Security's own OAuth2 authorization endpoint; the destination is
 * read back, re-validated, and actually redirected to by
 * OAuth2LoginSuccessHandler once Google's login completes.
 */
@RestController
public class LoginRedirectController {

    static final String PENDING_REDIRECT_COOKIE = "repair_post_login_redirect";
    private static final int COOKIE_MAX_AGE_SECONDS = 60 * 10;
    static final String DEFAULT_NEXT = "/chat";

    @GetMapping("/api/auth/login")
    public void login(@RequestParam(required = false) String next, HttpServletResponse response) throws java.io.IOException {
        String safeNext = RedirectValidator.sanitizeOrDefault(next, DEFAULT_NEXT);
        Cookie cookie = new Cookie(PENDING_REDIRECT_COOKIE, encode(safeNext));
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        response.sendRedirect("/api/oauth2/authorization/google");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
