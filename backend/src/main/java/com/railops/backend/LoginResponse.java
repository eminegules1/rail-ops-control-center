package com.railops.backend;

import java.time.Instant;

/** A signed-in user's bearer token; {@code expiresAt} is when it stops being accepted. */
public record LoginResponse(String token, String tokenType, String username, Role role, Instant expiresAt) {
}
