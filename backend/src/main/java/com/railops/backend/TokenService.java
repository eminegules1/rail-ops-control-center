package com.railops.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues signed login tokens with claims {@code sub}, {@code role}, {@code iat} and {@code exp}. */
@Service
class TokenService {

    static final Duration LIFETIME = Duration.ofHours(8);

    private final JwtEncoder encoder;
    private final Clock clock;

    @Autowired
    TokenService(JwtEncoder encoder) {
        this(encoder, Clock.systemUTC());
    }

    TokenService(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    IssuedToken issue(String username, Role role) {
        Instant issuedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(LIFETIME);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(username)
                .claim("role", role.name())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }

    record IssuedToken(String value, Instant expiresAt) {
    }
}
