package com.company.aiservice.config;

import com.company.aiservice.security.JwtUploadInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {

    private final JwtUploadInterceptor jwtUploadInterceptor;

    public WebMvcSecurityConfig(JwtUploadInterceptor jwtUploadInterceptor) {
        this.jwtUploadInterceptor = jwtUploadInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtUploadInterceptor)
                .addPathPatterns("/api/docs/**", "/api/chat/history", "/api/chat/history/**");
    }
}

