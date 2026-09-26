package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IncidentEventMessageValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsProducerShapedPayload() {
        assertThat(violations(valid())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVT-10001", "any non-blank id"})
    void acceptsAnyNonBlankEventId(String eventId) {
        assertThat(violations(withEventId(eventId))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATS", "CBTC", "SCADA", "TMS", "PIS"})
    void acceptsEveryKnownSource(String source) {
        assertThat(violations(withSource(source))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"XYZ", "cbtc", "ATS ", " ", "ATSX"})
    void rejectsUnknownSource(String source) {
        assertThat(violations(withSource(source))).containsExactly("source");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsBlankEventId(String eventId) {
        assertThat(violations(withEventId(eventId))).containsExactly("eventId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsBlankService(String service) {
        assertThat(violations(withService(service))).containsExactly("service");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsBlankMessage(String message) {
        IncidentEventMessage m = valid();
        assertThat(violations(new IncidentEventMessage(m.eventId(), m.source(), m.service(), m.severity(),
                message, m.status(), m.timestamp()))).containsExactly("message");
    }

    @Test
    void rejectsOverLengthEventIdAndService() {
        assertThat(violations(withEventId("E".repeat(64)))).isEmpty();
        assertThat(violations(withEventId("E".repeat(65)))).containsExactly("eventId");
        assertThat(violations(withService("s".repeat(64)))).isEmpty();
        assertThat(violations(withService("s".repeat(65)))).containsExactly("service");
    }

    @Test
    void rejectsMissingSeverityStatusAndTimestamp() {
        IncidentEventMessage m = valid();
        assertThat(violations(new IncidentEventMessage(m.eventId(), m.source(), m.service(), null,
                m.message(), null, null))).containsExactlyInAnyOrder("severity", "status", "timestamp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2000-01-01T00:00:00Z", "9999-12-31T23:59:59.999Z"})
    void acceptsTimestampsInRange(String timestamp) {
        assertThat(violations(withTimestamp(Instant.parse(timestamp)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1999-12-31T23:59:59.999Z", "-5000-01-01T00:00:00Z", "+10000-01-01T00:00:00Z"})
    void rejectsTimestampsOutOfRange(String timestamp) {
        assertThat(violations(withTimestamp(Instant.parse(timestamp)))).containsExactly("timestampInRange");
    }

    private Set<String> violations(IncidentEventMessage message) {
        return validator.validate(message).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    static IncidentEventMessage valid() {
        return new IncidentEventMessage("EVT-3f1c2a9e-8b7d-4e21-9c55-0a6b1d2e3f40", "CBTC", "signal-service",
                Severity.CRITICAL, "Signal SG-14 failed to clear", EventStatus.OPEN,
                Instant.parse("2026-09-26T14:30:05.123Z"));
    }

    private static IncidentEventMessage withEventId(String eventId) {
        IncidentEventMessage m = valid();
        return new IncidentEventMessage(eventId, m.source(), m.service(), m.severity(), m.message(), m.status(),
                m.timestamp());
    }

    private static IncidentEventMessage withSource(String source) {
        IncidentEventMessage m = valid();
        return new IncidentEventMessage(m.eventId(), source, m.service(), m.severity(), m.message(), m.status(),
                m.timestamp());
    }

    private static IncidentEventMessage withTimestamp(Instant timestamp) {
        IncidentEventMessage m = valid();
        return new IncidentEventMessage(m.eventId(), m.source(), m.service(), m.severity(), m.message(), m.status(),
                timestamp);
    }

    private static IncidentEventMessage withService(String service) {
        IncidentEventMessage m = valid();
        return new IncidentEventMessage(m.eventId(), m.source(), service, m.severity(), m.message(), m.status(),
                m.timestamp());
    }
}
