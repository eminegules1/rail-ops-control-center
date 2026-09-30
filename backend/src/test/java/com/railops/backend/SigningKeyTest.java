package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SigningKeyTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankSecretGivesARandomKeyOfFullLength(String blank) {
        SecretKey first = SigningKey.resolve(blank);

        assertThat(first.getEncoded()).hasSize(SigningKey.MIN_BYTES);
        assertThat(SigningKey.resolve(blank).getEncoded()).isNotEqualTo(first.getEncoded());
    }

    @Test
    void configuredSecretIsUsedAsItsUtf8Bytes() {
        String secret = "0123456789abcdef0123456789abcdef";

        assertThat(SigningKey.resolve(secret).getEncoded()).isEqualTo(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void secretOfExactlyThirtyTwoBytesIsAccepted() {
        assertThat(SigningKey.resolve("x".repeat(32)).getEncoded()).hasSize(32);
    }

    @Test
    void shortSecretFailsStartupWithoutEchoingIt() {
        assertThatThrownBy(() -> SigningKey.resolve("too-short-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auth.jwt-secret must be at least 32 bytes");
    }

    @Test
    void lengthIsCountedInBytesNotCharacters() {
        // 16 two-byte characters are exactly 32 bytes; 15 are 30.
        assertThat(SigningKey.resolve("é".repeat(16)).getEncoded()).hasSize(32);
        assertThatThrownBy(() -> SigningKey.resolve("é".repeat(15))).isInstanceOf(IllegalStateException.class);
    }
}
