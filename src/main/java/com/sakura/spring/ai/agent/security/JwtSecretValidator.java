package com.sakura.spring.ai.agent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class JwtSecretValidator {

    @Value("${mimo.jwt.secret:}")
    private String jwtSecret;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET environment variable must be set (at least 32 bytes)");
        }
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes, got " + jwtSecret.length());
        }
    }
}
