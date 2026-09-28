package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class WebSocketConfigTest {

    private final WebSocketConfig.SubscribeOnly interceptor = new WebSocketConfig.SubscribeOnly();
    private final MessageChannel channel = mock(MessageChannel.class);

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP", "SUBSCRIBE", "UNSUBSCRIBE", "DISCONNECT"})
    void passesSubscriberFrames(StompCommand command) {
        Message<byte[]> message = frame(command);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void rejectsClientSend() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    private static Message<byte[]> frame(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination("/topic/events");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
