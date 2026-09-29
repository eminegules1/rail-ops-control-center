package com.railops.backend;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The circuit breaker protecting every Redis call; its thresholds live in {@code application.yml}. */
@Configuration
class RedisResilienceConfig {

    static final String REDIS_CIRCUIT_BREAKER = "redis";

    @Bean
    CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(REDIS_CIRCUIT_BREAKER);
    }
}
