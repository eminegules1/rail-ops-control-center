package com.railops.producer;

import com.railops.producer.ServiceCatalog.RailService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds realistic incident events. Shared by the scheduler and HTTP threads,
 * so every public method is synchronized.
 */
@Component
public class EventGenerator {

    static final int BUFFER_SIZE = 100;
    static final Duration SEED_WINDOW = Duration.ofMinutes(60);

    private static final Severity[] SEVERITIES = Severity.values();
    private static final int[] SEVERITY_WEIGHTS = {50, 30, 15, 5};
    private static final EventStatus[] STATUSES = EventStatus.values();
    private static final int[] STATUS_WEIGHTS = {70, 20, 10};

    public record Generated(IncidentEvent event, boolean duplicate) {
    }

    private final RandomGenerator random;
    private final Clock clock;
    private final double duplicateRatio;
    private final Deque<IncidentEvent> recent = new ArrayDeque<>(BUFFER_SIZE);

    @Autowired
    public EventGenerator(RandomGenerator random, Clock clock, ProducerProperties properties) {
        this(random, clock, properties.duplicateRatio());
    }

    EventGenerator(RandomGenerator random, Clock clock, double duplicateRatio) {
        this.random = random;
        this.clock = clock;
        this.duplicateRatio = duplicateRatio;
    }

    public synchronized Generated next() {
        return next(clock.instant());
    }

    /** Seed events get random timestamps in the last hour, returned oldest first. */
    public synchronized List<Generated> seed(int count) {
        Instant now = clock.instant();
        List<Instant> timestamps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            timestamps.add(now.minusMillis(random.nextLong(SEED_WINDOW.toMillis())));
        }
        timestamps.sort(null);
        List<Generated> batch = new ArrayList<>(count);
        for (Instant timestamp : timestamps) {
            batch.add(next(timestamp));
        }
        return batch;
    }

    private Generated next(Instant timestamp) {
        if (!recent.isEmpty() && random.nextDouble() < duplicateRatio) {
            return new Generated(pickRecent(), true);
        }
        IncidentEvent event = fresh(timestamp);
        remember(event);
        return new Generated(event, false);
    }

    private IncidentEvent fresh(Instant timestamp) {
        RailService service = ServiceCatalog.SERVICES.get(random.nextInt(ServiceCatalog.SERVICES.size()));
        String message = service.messages().get(random.nextInt(service.messages().size()));
        return new IncidentEvent(
                "EVT-" + UUID.randomUUID(),
                service.source(),
                service.name(),
                weighted(SEVERITIES, SEVERITY_WEIGHTS),
                message,
                weighted(STATUSES, STATUS_WEIGHTS),
                timestamp.truncatedTo(ChronoUnit.MILLIS));
    }

    private IncidentEvent pickRecent() {
        int index = random.nextInt(recent.size());
        return recent.stream().skip(index).findFirst().orElseThrow();
    }

    private void remember(IncidentEvent event) {
        if (recent.size() == BUFFER_SIZE) {
            recent.removeFirst();
        }
        recent.addLast(event);
    }

    synchronized int bufferedCount() {
        return recent.size();
    }

    private <T> T weighted(T[] values, int[] weights) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < values.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return values[i];
            }
        }
        throw new IllegalStateException("weights do not cover the roll");
    }
}
