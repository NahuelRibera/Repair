package dev.repair.api.config;

import dev.repair.api.common.VisitorCookieInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final VisitorCookieInterceptor visitorCookieInterceptor;

    public WebConfig(VisitorCookieInterceptor visitorCookieInterceptor) {
        this.visitorCookieInterceptor = visitorCookieInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitorCookieInterceptor).addPathPatterns("/api/**");
    }
}
