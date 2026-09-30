package com.railops.producer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TracingConfigTest {

    private final ObservationPredicate predicate = new TracingConfig().skipActuatorRequests();

    @Test
    void skipsHealthAndPrometheusRequests() {
        assertThat(observes(request("/actuator/health"))).isFalse();
        assertThat(observes(request("/actuator/prometheus"))).isFalse();
    }

    @Test
    void observesOtherRequests() {
        assertThat(observes(request("/api/events"))).isTrue();
        assertThat(observes(request("/produce"))).isTrue();
    }

    @Test
    void observesNonHttpWork() {
        assertThat(predicate.test("spring.kafka.listener", new Observation.Context())).isTrue();
    }

    private boolean observes(ServerRequestObservationContext context) {
        return predicate.test("http.server.requests", context);
    }

    private static ServerRequestObservationContext request(String uri) {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri),
                new MockHttpServletResponse());
    }
}
