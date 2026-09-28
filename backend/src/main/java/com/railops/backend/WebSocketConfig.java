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
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over native WebSocket at {@code /ws}; the server pushes to {@code /topic/*} and clients only subscribe.
 * The handshake keeps Spring's default same-origin check.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Keeps idle sockets alive through nginx's 60 s proxy timeout and lets clients detect dead connections.
    private static final long HEARTBEAT_MS = 10_000;

    private TaskScheduler brokerScheduler;

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
        registration.interceptors(new SubscribeOnly());
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
