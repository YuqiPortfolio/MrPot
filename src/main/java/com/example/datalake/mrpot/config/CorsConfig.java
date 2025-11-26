package com.example.datalake.mrpot.config;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final WebProperties webProperties;

    public CorsConfig(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var cors = webProperties.getCors();
        if (cors == null) {
            return;
        }

        var mapping = registry.addMapping("/**");

        if (!cors.getAllowedOriginPatterns().isEmpty()) {
            mapping.allowedOriginPatterns(cors.getAllowedOriginPatterns().toArray(String[]::new));
        } else if (!cors.getAllowedOrigins().isEmpty()) {
            mapping.allowedOrigins(cors.getAllowedOrigins().toArray(String[]::new));
        }

        if (!cors.getAllowedMethods().isEmpty()) {
            mapping.allowedMethods(cors.getAllowedMethods().toArray(String[]::new));
        }

        if (!cors.getAllowedHeaders().isEmpty()) {
            mapping.allowedHeaders(cors.getAllowedHeaders().toArray(String[]::new));
        }

        if (!cors.getExposedHeaders().isEmpty()) {
            mapping.exposedHeaders(cors.getExposedHeaders().toArray(String[]::new));
        }

        if (cors.getAllowCredentials() != null) {
            mapping.allowCredentials(cors.getAllowCredentials());
        }

        if (cors.getMaxAge() != null) {
            mapping.maxAge(cors.getMaxAge());
        }
    }
}
