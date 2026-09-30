package com.railops.producer;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
class TracingConfig {

    /**
     * The compose healthchecks and Prometheus scrapes call {@code /actuator} every few seconds; observing them would
     * bury the real traces in Jaeger.
     */
    @Bean
    ObservationPredicate skipActuatorRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
