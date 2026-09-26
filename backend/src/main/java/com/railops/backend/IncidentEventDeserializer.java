package com.railops.backend;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Reads the event JSON strictly: enums only by name and {@code timestamp} only as an ISO-8601 string. The default
 * mapper would turn {@code "severity":3} into {@code CRITICAL} and an epoch number into an instant.
 */
public class IncidentEventDeserializer extends JsonDeserializer<IncidentEventMessage> {

    public IncidentEventDeserializer() {
        super(IncidentEventMessage.class, strictMapper(), false);
    }

    private static ObjectMapper strictMapper() {
        return JacksonUtils.enhancedObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .registerModule(new SimpleModule().addDeserializer(Instant.class, new IsoStringInstantDeserializer()));
    }

    /**
     * JSR-310's deserializer reads epoch numbers and digit strings regardless of leniency or coercion settings,
     * so the text is parsed here.
     */
    private static final class IsoStringInstantDeserializer extends StdDeserializer<Instant> {

        IsoStringInstantDeserializer() {
            super(Instant.class);
        }

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (Instant) context.handleUnexpectedToken(Instant.class, parser);
            }
            String text = parser.getText();
            try {
                return DateTimeFormatter.ISO_INSTANT.parse(text, Instant::from);
            } catch (DateTimeException e) {
                return (Instant) context.handleWeirdStringValue(Instant.class, text, "expected an ISO-8601 instant");
            }
        }
    }
}
