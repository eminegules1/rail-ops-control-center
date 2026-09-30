package com.railops.backend;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The two demo accounts and the token signing secret; see the {@code auth} block in application.yml. */
@ConfigurationProperties("auth")
public record AuthProperties(String adminPassword, String viewerPassword, String jwtSecret) {
}
