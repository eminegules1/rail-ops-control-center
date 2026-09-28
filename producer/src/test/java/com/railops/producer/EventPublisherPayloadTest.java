package com.railops.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railops.producer.EventGenerator.Defect;
import com.railops.producer.EventGenerator.Generated;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class EventPublisherPayloadTest {

    private static final IncidentEvent EVENT = new IncidentEvent("EVT-1", "CBTC", "signal-service",
            Severity.MAJOR, "Signal failure", EventStatus.OPEN, Instant.parse("2026-09-26T14:30:05.123Z"));

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validEventIsItsJson() throws Exception {
        assertThat(payload(null)).isEqualTo(objectMapper.writeValueAsString(EVENT));
    }

    @Test
    void notJsonCannotBeParsed() {
        assertThatThrownBy(() -> objectMapper.readTree(payload(Defect.NOT_JSON)))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void unknownSeverityIsNotASeverityName() throws Exception {
        JsonNode node = objectMapper.readTree(payload(Defect.UNKNOWN_SEVERITY));

        assertThatThrownBy(() -> Severity.valueOf(node.get("severity").asText()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(node.get("eventId").asText()).isEqualTo("EVT-1");
    }

    @Test
    void blankServiceKeepsTheOtherFields() throws Exception {
        JsonNode node = objectMapper.readTree(payload(Defect.BLANK_SERVICE));

        assertThat(node.get("service").asText()).isEmpty();
        assertThat(node.get("severity").asText()).isEqualTo("MAJOR");
    }

    @Test
    void missingEventIdHasNoEventIdField() throws Exception {
        JsonNode node = objectMapper.readTree(payload(Defect.MISSING_EVENT_ID));

        assertThat(node.has("eventId")).isFalse();
        assertThat(node.get("timestamp").asText()).isEqualTo("2026-09-26T14:30:05.123Z");
    }

    private String payload(Defect defect) {
        return EventPublisher.payload(objectMapper, new Generated(EVENT, false, defect));
    }
}
