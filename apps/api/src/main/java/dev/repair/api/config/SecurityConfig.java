package dev.repair.api.config;

import dev.repair.api.auth.ApiAuthenticationEntryPoint;
import dev.repair.api.auth.OAuth2LoginSuccessHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Backend-owned Spring Security OAuth2/OIDC login (Google only) — see
 * docs/authentication.md for the full architecture decision and why this
 * was chosen over a separate frontend auth library. Ownership for the
 * motorcycle domain (Garage/maintenance/preferences/conversations) is
 * gated here; the preserved car-prototype endpoints keep their original
 * anonymous-cookie behavior (see VisitorCookieInterceptor, untouched).
 *
 * All OAuth2 endpoints are deliberately kept under /api/** (custom
 * authorization/redirection base URIs below) so the existing single-
 * origin Next.js rewrite proxy (next.config.ts, "/api/:path*") covers the
 * entire login dance with no separate proxy rule needed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    public SecurityConfig(ApiAuthenticationEntryPoint apiAuthenticationEntryPoint, OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler) {
        this.apiAuthenticationEntryPoint = apiAuthenticationEntryPoint;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // Spring Security 6's default XorCsrfTokenRequestAttributeHandler
                        // BREACH-masks the token exposed for server-rendered form
                        // fields, and expects whatever comes back in the header to
                        // be masked the same way. This is a pure-JSON SPA: the
                        // frontend (apps/web/src/lib/api.ts) reads the raw
                        // XSRF-TOKEN cookie value and echoes it verbatim as
                        // X-XSRF-TOKEN — the plain (non-XOR) handler is the one
                        // documented for exactly this cookie-to-header pattern.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                )
                .addFilterAfter(new CsrfCookieEnsuringFilter(), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Motorcycle-domain data (Garage/maintenance/preferences/
                        // conversations) requires a real authenticated Repair user.
                        .requestMatchers("/api/garage/**", "/api/moto-sessions/**", "/api/moto-rag-runs/**").authenticated()
                        .requestMatchers("/api/me", "/api/me/**").authenticated()
                        // The dynamic motorcycle catalog stays public (the landing
                        // bike picker must work before sign-in), as does the car
                        // prototype (preserved, unchanged, anonymous-cookie-based)
                        // and the login/logout endpoints themselves.
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
                        .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
                        .successHandler(oAuth2LoginSuccessHandler)
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(apiAuthenticationEntryPoint));
        return http.build();
    }

    /**
     * Forces the CSRF token to actually be resolved (and therefore its
     * cookie written) on every request, not only ones that happen to read
     * it — this is a pure API backend with no server-rendered page that
     * would otherwise trigger that resolution. Standard, documented
     * Spring Security pattern for SPA + CookieCsrfTokenRepository.
     */
    private static final class CsrfCookieEnsuringFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
