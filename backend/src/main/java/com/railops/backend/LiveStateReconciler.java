package com.railops.backend;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the Redis live state from PostgreSQL: once at startup when it looks empty, whenever the "redis" circuit
 * breaker closes after having tripped open while {@link ReconcileState} is marked needed, and every 30 seconds
 * while the flag is still set with the breaker closed ({@link #retryIfStillNeeded}). Computes
 * the whole snapshot from Postgres aggregates and writes it into Redis as one pipelined batch of absolute values,
 * rather than replaying the Lua scripts once per stored event: their {@code processed:{id}} apply-once guard would
 * make them a no-op for any event Redis already has a guard key for, including one whose counted state is stale
 * because its status changed during the outage the guard predates.
 */
@Component
class LiveStateReconciler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LiveStateReconciler.class);
    // A bucket lives at most 2h (+1 min) past its minute start, matching apply-event.lua's own TTL rule.
    private static final long TIMELINE_TTL_SECONDS = 7260;
    private static final int RECENT_EVENTS_LIMIT = 50;

    private final StringRedisTemplate redis;
    private final IncidentEventRepository repository;
    private final ReconcileState reconcileState;
    private final LiveStateLock liveStateLock;
    private final KafkaListenerEndpointRegistry kafkaListeners;
    private final CircuitBreaker redisCircuitBreaker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "live-state-reconciler");
        thread.setDaemon(true);
        return thread;
    });

    LiveStateReconciler(StringRedisTemplate redis, IncidentEventRepository repository, ReconcileState reconcileState,
                        LiveStateLock liveStateLock, KafkaListenerEndpointRegistry kafkaListeners,
                        CircuitBreaker redisCircuitBreaker) {
        this.redis = redis;
        this.repository = repository;
        this.reconcileState = reconcileState;
        this.liveStateLock = liveStateLock;
        this.kafkaListeners = kafkaListeners;
        this.redisCircuitBreaker = redisCircuitBreaker;
        redisCircuitBreaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition() == CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED
                    && reconcileState.isNeeded()) {
                executor.execute(this::reconcile);
            }
        });
    }

    /**
     * Retries a needed reconcile that the breaker's state-transition listener alone would miss: too few Redis
     * failures to trip the breaker, a failed startup check (which runs before any breaker-guarded call), or a
     * failed rebuild (whose own Redis pipeline is not breaker-guarded either). Without this, the flag can stay set
     * with Redis reachable and closed, silently serving drifted counters as current until the breaker happens to
     * cycle again.
     */
    @Scheduled(fixedDelay = 30_000)
    void retryIfStillNeeded() {
        if (reconcileState.isNeeded() && redisCircuitBreaker.getState() == CircuitBreaker.State.CLOSED) {
            executor.execute(this::reconcile);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void reconcileAtStartupIfEmpty() {
        executor.execute(() -> {
            try {
                if (!Boolean.TRUE.equals(redis.hasKey("services"))) {
                    reconcile();
                }
            } catch (RuntimeException e) {
                // This call is not breaker-guarded, so the failure never trips the breaker; the scheduled retry recovers it.
                log.warn("Startup reconciliation check failed; will retry while the reconcile flag is set", e);
                reconcileState.markNeeded();
            }
        });
    }

    private void reconcile() {
        MessageListenerContainer listener = kafkaListeners.getListenerContainer(IncidentEventListener.LISTENER_ID);
        if (listener != null) {
            listener.pause();
        }
        liveStateLock.forRebuild().lock();
        try {
            rebuild();
            reconcileState.clear();
            log.info("Live state reconciled from Postgres");
        } catch (RuntimeException e) {
            // The rebuild's own Redis pipeline is not breaker-guarded either, so the scheduled retry picks this up.
            log.warn("Live state reconciliation failed; will retry while the reconcile flag is set", e);
            reconcileState.markNeeded();
        } finally {
            liveStateLock.forRebuild().unlock();
            if (listener != null) {
                listener.resume();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rebuild() {
        Instant now = Instant.now();

        long total = repository.count();
        Map<EventStatus, Long> statusCounts = new EnumMap<>(EventStatus.class);
        for (EventStatus status : EventStatus.values()) {
            statusCounts.put(status, repository.countByStatus(status));
        }
        Map<Severity, Long> severityCounts = new EnumMap<>(Severity.class);
        Map<Severity, Long> activeSeverityCounts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            severityCounts.put(severity, repository.countBySeverity(severity));
            // "Active" is status <> RESOLVED; OPEN and ACKNOWLEDGED are the only other statuses.
            activeSeverityCounts.put(severity, repository.countBySeverityAndStatusNot(severity, EventStatus.RESOLVED));
        }

        Map<String, ServiceCounts> serviceCounts = repository.aggregateServiceCounts().stream()
                .collect(Collectors.toMap(ServiceCounts::service, Function.identity()));
        Map<String, IncidentEvent> latestByService = repository.findLatestPerService().stream()
                .collect(Collectors.toMap(IncidentEvent::getService, Function.identity()));

        Map<Instant, Map<Severity, Long>> timelineByMinute = DashboardQueryService.groupTimelineRows(
                repository.timelineCounts(now.minusSeconds(TIMELINE_TTL_SECONDS)));

        List<String> recentEventIds = repository
                .findByOrderByReceivedAtDesc(PageRequest.of(0, RECENT_EVENTS_LIMIT)).stream()
                .map(IncidentEvent::getEventId)
                .toList();

        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                // MULTI/EXEC around the pipelined batch: still one round trip, but readers never see a
                // partially-rebuilt state (for example "services" deleted but not yet re-added).
                operations.multi();
                operations.opsForValue().set("events:count", Long.toString(total));
                for (EventStatus status : EventStatus.values()) {
                    operations.opsForValue().set("status:" + status + ":count",
                            Long.toString(statusCounts.get(status)));
                }
                for (Severity severity : Severity.values()) {
                    operations.opsForValue().set("severity:" + severity + ":count",
                            Long.toString(severityCounts.get(severity)));
                    operations.opsForValue().set("active:" + severity + ":count",
                            Long.toString(activeSeverityCounts.get(severity)));
                }

                operations.delete("services");
                if (!serviceCounts.isEmpty()) {
                    operations.opsForSet().add("services", serviceCounts.keySet().toArray(new String[0]));
                }
                for (ServiceCounts counts : serviceCounts.values()) {
                    long activeInfo = counts.activeCount() - counts.activeCritical() - counts.activeMajor()
                            - counts.activeWarning();
                    Map<String, String> hash = new HashMap<>();
                    hash.put("active:INFO", Long.toString(activeInfo));
                    hash.put("active:WARNING", Long.toString(counts.activeWarning()));
                    hash.put("active:MAJOR", Long.toString(counts.activeMajor()));
                    hash.put("active:CRITICAL", Long.toString(counts.activeCritical()));
                    hash.put("openCount", Long.toString(counts.openCount()));
                    hash.put("activeCount", Long.toString(counts.activeCount()));
                    hash.put("status", healthOf(counts).name());
                    IncidentEvent latest = latestByService.get(counts.service());
                    if (latest != null) {
                        hash.put("lastEventTime", LiveStateUpdater.EVENT_TIME.format(latest.getTimestamp()));
                        hash.put("latestSeverity", latest.getSeverity().name());
                    }
                    operations.opsForHash().putAll("service:" + counts.service(), hash);
                }

                for (Map.Entry<Instant, Map<Severity, Long>> bucket : timelineByMinute.entrySet()) {
                    long secondsRemaining = bucket.getKey().getEpochSecond() + TIMELINE_TTL_SECONDS
                            - now.getEpochSecond();
                    if (secondsRemaining <= 0) {
                        continue;
                    }
                    String key = LiveStateUpdater.timelineKey(bucket.getKey());
                    Map<String, String> fields = new HashMap<>();
                    for (Severity severity : Severity.values()) {
                        fields.put(severity.name(), Long.toString(bucket.getValue().getOrDefault(severity, 0L)));
                    }
                    operations.opsForHash().putAll(key, fields);
                    operations.expire(key, secondsRemaining, TimeUnit.SECONDS);
                }

                operations.delete("recent:events");
                if (!recentEventIds.isEmpty()) {
                    operations.opsForList().rightPushAll("recent:events", new ArrayList<>(recentEventIds));
                }

                operations.delete(LiveStateUpdater.SUMMARY_CACHE_KEY);
                operations.delete(LiveStateUpdater.SUMMARY_VERSION_KEY);
                operations.exec();
                return null;
            }
        });
    }

    private static ServiceHealth healthOf(ServiceCounts counts) {
        if (counts.activeCritical() > 0) {
            return ServiceHealth.DOWN;
        }
        if (counts.activeMajor() > 0 || counts.activeWarning() > 0) {
            return ServiceHealth.DEGRADED;
        }
        return ServiceHealth.HEALTHY;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
