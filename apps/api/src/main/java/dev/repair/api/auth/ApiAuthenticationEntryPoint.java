package dev.repair.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Every protected endpoint under this API is called by the frontend as a
 * JSON fetch, never as a browser page load — so an unauthenticated
 * request must get a plain 401 the SPA can branch on, never Spring
 * Security's default behavior of redirecting to the login page (that
 * default is correct for a traditional server-rendered app, wrong here).
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthenticated\"}");
    }
}
