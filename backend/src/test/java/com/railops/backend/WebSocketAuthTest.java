package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.ZoneOffset;

class WebSocketAuthTest {

    private final SecretKey key = SigningKey.resolve("k".repeat(32));
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    private final WebSocketConfig.AuthenticatedConnect interceptor = new WebSocketConfig.AuthenticatedConnect(decoder);
    private final MessageChannel channel = mock(MessageChannel.class);

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void acceptsAConnectWithAValidToken(StompCommand command) {
        Message<byte[]> message = frame(command, "Bearer " + token(key, Instant.now()));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void rejectsAConnectWithoutAToken(StompCommand command) {
        assertThatThrownBy(() -> interceptor.preSend(frame(command, null), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void rejectsAWrongSchemeGarbageExpiredAndForeignTokens() {
        String valid = token(key, Instant.now());
        String expired = token(key, Instant.now().minus(Duration.ofHours(9)));
        String foreign = token(SigningKey.resolve("o".repeat(32)), Instant.now());

        for (String header : new String[] {"Basic " + valid, valid, "Bearer ", "Bearer not.a.jwt",
                "Bearer " + expired, "Bearer " + foreign}) {
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, header), channel))
                    .as(header)
                    .isInstanceOf(MessageDeliveryException.class);
        }
    }

    @Test
    void theRejectionDoesNotCarryTheFrameOrItsToken() {
        String header = "Bearer " + token(SigningKey.resolve("o".repeat(32)), Instant.now());

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, header), channel))
                .isInstanceOfSatisfying(MessageDeliveryException.class, e -> {
                    assertThat(e.getFailedMessage()).isNull();
                    assertThat(e.toString()).doesNotContain("Bearer");
                });
    }

    @Test
    void leavesOtherFramesToTheirOwnRules() {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, null);

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
    }

    private static String token(SecretKey signingKey, Instant issuedAt) {
        return new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(signingKey)),
                Clock.fixed(issuedAt, ZoneOffset.UTC)).issue("admin", Role.ADMIN).value();
    }

    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
