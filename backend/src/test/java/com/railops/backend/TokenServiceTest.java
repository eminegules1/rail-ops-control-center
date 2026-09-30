package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {

    // Relative to the real clock: the decoder checks expiry against it, so a fixed date would eventually expire.
    // The 789 ms checks that issuing truncates to whole seconds.
    private static final Instant WHOLE_SECOND = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant NOW = WHOLE_SECOND.plusMillis(789);

    private final SecretKey key = SigningKey.resolve("k".repeat(32));
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

    @Test
    void issuesAnEightHourTokenWithSubjectAndRole() {
        TokenService.IssuedToken issued = service(key, NOW).issue("admin", Role.ADMIN);

        Jwt token = decoder.decode(issued.value());
        assertThat(token.getSubject()).isEqualTo("admin");
        assertThat(token.<String>getClaim("role")).isEqualTo("ADMIN");
        assertThat(token.getIssuedAt()).isEqualTo(WHOLE_SECOND);
        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt())).isEqualTo(TokenService.LIFETIME);
        assertThat(issued.expiresAt()).isEqualTo(token.getExpiresAt());
    }

    @Test
    void rejectsAnExpiredToken() {
        String expired = service(key, Instant.now().minus(Duration.ofHours(9))).issue("viewer", Role.VIEWER).value();

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        SecretKey other = SigningKey.resolve("o".repeat(32));
        String forged = service(other, Instant.now()).issue("admin", Role.ADMIN).value();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    private static TokenService service(SecretKey signingKey, Instant now) {
        return new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(signingKey)),
                Clock.fixed(now, ZoneOffset.UTC));
    }
}
