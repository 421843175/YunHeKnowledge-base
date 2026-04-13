package com.company.aiservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allow-origins:*}")
    private String allowOrigins;

    @Value("${cors.allow-methods:GET,POST,OPTIONS}")
    private String allowMethods;

    @Value("${cors.allow-headers:*}")
    private String allowHeaders;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowOrigins.split(","))
                .allowedMethods(allowMethods.split(","))
                .allowedHeaders(allowHeaders.split(","))
                .allowCredentials(false);
    }
}
