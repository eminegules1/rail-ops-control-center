package com.railops.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over native WebSocket at {@code /ws}; the server pushes to {@code /topic/*} and clients only subscribe.
 * The handshake keeps Spring's default same-origin check, and a CONNECT frame must carry a valid login token.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Keeps idle sockets alive through nginx's 60 s proxy timeout and lets clients detect dead connections.
    private static final long HEARTBEAT_MS = 10_000;

    private final JwtDecoder jwtDecoder;
    private TaskScheduler brokerScheduler;

    WebSocketConfig(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Autowired
    void setBrokerScheduler(@Lazy TaskScheduler messageBrokerTaskScheduler) {
        this.brokerScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(brokerScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new AuthenticatedConnect(jwtDecoder), new SubscribeOnly());
    }

    /**
     * Rejects a CONNECT without a valid {@code Authorization: Bearer <token>} header. The exception carries no
     * message, so the rejected frame (and the token in it) never reaches the logs. A token that expires while the
     * socket stays open is not re-checked; the next reconnect needs a valid one.
     */
    static class AuthenticatedConnect implements ChannelInterceptor {

        private static final String BEARER_PREFIX = "Bearer ";

        private final JwtDecoder decoder;

        AuthenticatedConnect(JwtDecoder decoder) {
            this.decoder = decoder;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null
                    && (accessor.getCommand() == StompCommand.CONNECT || accessor.getCommand() == StompCommand.STOMP)) {
                String header = accessor.getFirstNativeHeader("Authorization");
                if (header == null || !header.startsWith(BEARER_PREFIX)) {
                    throw new MessageDeliveryException("Sign in to connect");
                }
                try {
                    decoder.decode(header.substring(BEARER_PREFIX.length()));
                } catch (JwtException e) {
                    throw new MessageDeliveryException("Sign in to connect");
                }
            }
            return message;
        }
    }

    /** Rejects client SEND frames: the simple broker would otherwise relay a forged event to every subscriber. */
    static class SubscribeOnly implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null && accessor.getCommand() == StompCommand.SEND) {
                throw new MessageDeliveryException(message, "Clients can only subscribe");
            }
            return message;
        }
    }
}
