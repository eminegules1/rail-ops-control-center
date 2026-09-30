package com.railops.backend;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chooses the HS256 key that signs and verifies login tokens. */
final class SigningKey {

    static final int MIN_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(SigningKey.class);
    private static final String ALGORITHM = "HmacSHA256";

    private SigningKey() {
    }

    /**
     * The configured secret's UTF-8 bytes; a random key when it is blank.
     *
     * @throws IllegalStateException when a secret is set but shorter than 32 bytes
     */
    static SecretKey resolve(String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("auth.jwt-secret is not set: using a random signing key, so sessions end when the backend restarts");
            byte[] random = new byte[MIN_BYTES];
            new SecureRandom().nextBytes(random);
            return new SecretKeySpec(random, ALGORITHM);
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException("auth.jwt-secret must be at least " + MIN_BYTES + " bytes");
        }
        return new SecretKeySpec(bytes, ALGORITHM);
    }
}
