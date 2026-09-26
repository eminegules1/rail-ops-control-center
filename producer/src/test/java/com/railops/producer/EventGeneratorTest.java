package com.railops.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.railops.producer.EventGenerator.Generated;
import com.railops.producer.ServiceCatalog.RailService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EventGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-09-26T14:30:05.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int DRAWS = 20_000;

    private static EventGenerator generator(double duplicateRatio) {
        return new EventGenerator(new Random(42), CLOCK, duplicateRatio);
    }

    @Test
    void eventIdIsEvtPrefixedLowercaseUuidAndUnique() {
        EventGenerator generator = generator(0);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String id = generator.next().event().eventId();
            assertThat(id).matches("EVT-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
            ids.add(id);
        }
        assertThat(ids).hasSize(1_000);
    }

    @Test
    void everyEventMatchesItsCatalogSourceAndMessages() {
        Map<String, RailService> byName = ServiceCatalog.SERVICES.stream()
                .collect(Collectors.toMap(RailService::name, Function.identity()));
        EventGenerator generator = generator(0);
        Set<String> seenServices = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            IncidentEvent event = generator.next().event();
            RailService service = byName.get(event.service());
            assertThat(service).isNotNull();
            assertThat(event.source()).isEqualTo(service.source());
            assertThat(service.messages()).contains(event.message());
            seenServices.add(event.service());
        }
        assertThat(seenServices).isEqualTo(byName.keySet());
    }

    @Test
    void severityFollowsWeights() {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        EventGenerator generator = generator(0);
        for (int i = 0; i < DRAWS; i++) {
            counts.merge(generator.next().event().severity(), 1, Integer::sum);
        }
        assertShare(counts.get(Severity.INFO), 0.50);
        assertShare(counts.get(Severity.WARNING), 0.30);
        assertShare(counts.get(Severity.MAJOR), 0.15);
        assertShare(counts.get(Severity.CRITICAL), 0.05);
    }

    @Test
    void statusFollowsWeights() {
        Map<EventStatus, Integer> counts = new EnumMap<>(EventStatus.class);
        EventGenerator generator = generator(0);
        for (int i = 0; i < DRAWS; i++) {
            counts.merge(generator.next().event().status(), 1, Integer::sum);
        }
        assertShare(counts.get(EventStatus.OPEN), 0.70);
        assertShare(counts.get(EventStatus.ACKNOWLEDGED), 0.20);
        assertShare(counts.get(EventStatus.RESOLVED), 0.10);
    }

    @Test
    void liveTimestampIsClockNowTruncatedToMillis() {
        Instant timestamp = generator(0).next().event().timestamp();
        assertThat(timestamp).isEqualTo(NOW.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void seedTimestampsFallInTheLastHourInAscendingOrder() {
        List<Generated> seed = generator(0).seed(200);
        assertThat(seed).hasSize(200);
        List<Instant> timestamps = seed.stream().map(g -> g.event().timestamp()).toList();
        assertThat(timestamps).isSorted();
        assertThat(timestamps).allSatisfy(t -> {
            assertThat(t).isAfter(NOW.minus(EventGenerator.SEED_WINDOW));
            assertThat(t).isBeforeOrEqualTo(NOW);
            assertThat(t).isEqualTo(t.truncatedTo(ChronoUnit.MILLIS));
        });
    }

    @Test
    void ratioZeroNeverDuplicates() {
        EventGenerator generator = generator(0);
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.next().duplicate()).isFalse();
        }
    }

    @Test
    void ratioOneResendsABufferedEventOnceOneExists() {
        EventGenerator generator = generator(1);
        Generated first = generator.next();
        assertThat(first.duplicate()).as("empty buffer falls back to a fresh event").isFalse();
        for (int i = 0; i < 100; i++) {
            Generated next = generator.next();
            assertThat(next.duplicate()).isTrue();
            assertThat(next.event()).isSameAs(first.event());
        }
    }

    @Test
    void duplicateRatioIsRoughlyHonoured() {
        EventGenerator generator = generator(0.25);
        long duplicates = 0;
        for (int i = 0; i < DRAWS; i++) {
            if (generator.next().duplicate()) {
                duplicates++;
            }
        }
        assertShare((int) duplicates, 0.25);
    }

    @Test
    void bufferStaysBounded() {
        EventGenerator generator = generator(0);
        for (int i = 0; i < 500; i++) {
            generator.next();
        }
        assertThat(generator.bufferedCount()).isEqualTo(EventGenerator.BUFFER_SIZE);
    }

    private static void assertShare(Integer count, double expected) {
        assertThat(count).isNotNull();
        assertThat(count / (double) DRAWS).isCloseTo(expected, within(0.02));
    }
}
