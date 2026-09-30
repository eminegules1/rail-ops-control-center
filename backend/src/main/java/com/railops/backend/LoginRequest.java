package com.railops.backend;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/auth/login}; the password limit is BCrypt's 72 bytes. */
public record LoginRequest(
        @NotBlank(message = "is required") @Size(max = 50, message = "must be at most 50 characters") String username,
        @NotBlank(message = "is required") @Size(max = 72, message = "must be at most 72 characters") String password) {
}
