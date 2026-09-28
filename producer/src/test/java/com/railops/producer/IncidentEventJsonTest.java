package com.railops.producer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class IncidentEventJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    private final IncidentEvent event = new IncidentEvent(
            "EVT-3f1c2a9e-8b7d-4e21-9c55-0a6b1d2e3f40",
            "CBTC",
            "signal-service",
            Severity.CRITICAL,
            "Signal SG-14 failed to clear",
            EventStatus.OPEN,
            Instant.parse("2026-09-26T14:30:05.123Z"));

    @Test
    void serializesExactlyTheSevenContractFields() throws Exception {
        String json = objectMapper.writeValueAsString(event);

        assertThat(json).isEqualTo("{\"eventId\":\"EVT-3f1c2a9e-8b7d-4e21-9c55-0a6b1d2e3f40\","
                + "\"source\":\"CBTC\",\"service\":\"signal-service\",\"severity\":\"CRITICAL\","
                + "\"message\":\"Signal SG-14 failed to clear\",\"status\":\"OPEN\","
                + "\"timestamp\":\"2026-09-26T14:30:05.123Z\"}");
    }

    @Test
    void outputDoesNotDependOnTheDefaultLocale() throws Exception {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ROOT);
            List<String> rootJson = sample();
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            List<String> turkishJson = sample();

            assertThat(turkishJson).isEqualTo(rootJson);
            for (String json : turkishJson) {
                JsonNode node = objectMapper.readTree(json);
                assertThat(Severity.valueOf(node.get("severity").asText())).isNotNull();
                assertThat(EventStatus.valueOf(node.get("status").asText())).isNotNull();
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    private List<String> sample() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-26T14:30:05Z"), ZoneOffset.UTC);
        EventGenerator generator = new EventGenerator(new Random(7), clock, 0, 0);
        List<String> json = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            IncidentEvent generated = generator.next().event();
            // eventId is random by design; blank it so the rest can be compared across runs.
            json.add(objectMapper.writeValueAsString(new IncidentEvent("EVT-x", generated.source(),
                    generated.service(), generated.severity(), generated.message(), generated.status(),
                    generated.timestamp())));
        }
        return json;
    }
}
