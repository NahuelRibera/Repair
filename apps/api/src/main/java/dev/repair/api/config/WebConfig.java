package dev.repair.api.config;

import dev.repair.api.auth.AuthenticatedUserInterceptor;
import dev.repair.api.common.VisitorCookieInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final VisitorCookieInterceptor visitorCookieInterceptor;
    private final AuthenticatedUserInterceptor authenticatedUserInterceptor;

    public WebConfig(VisitorCookieInterceptor visitorCookieInterceptor, AuthenticatedUserInterceptor authenticatedUserInterceptor) {
        this.visitorCookieInterceptor = visitorCookieInterceptor;
        this.authenticatedUserInterceptor = authenticatedUserInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Preserved unchanged for the car prototype's anonymous-cookie
        // endpoints (see docs/repair-v2-current-state.md).
        registry.addInterceptor(visitorCookieInterceptor).addPathPatterns("/api/**");
        // Resolves the authenticated Repair user for the motorcycle-domain
        // endpoints — see docs/authentication.md.
        registry.addInterceptor(authenticatedUserInterceptor).addPathPatterns("/api/**");
    }
}
