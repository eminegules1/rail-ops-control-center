package com.railops.producer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Seeds the topic once at startup, then sends one event per interval. */
@Component
class ProducerRunner {

    private static final Logger log = LoggerFactory.getLogger(ProducerRunner.class);

    private final EventPublisher publisher;
    private final ProducerProperties properties;

    ProducerRunner(EventPublisher publisher, ProducerProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        if (properties.seedCount() == 0) {
            return;
        }
        try {
            ProduceResult result = publisher.seed(properties.seedCount());
            log.info("Seed burst sent {} events ({} duplicates) to {}",
                    result.sent(), result.duplicates(), properties.topic().name());
        } catch (PublishException e) {
            log.warn("Seed burst failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${producer.interval-ms}", initialDelayString = "${producer.interval-ms}")
    void sendNext() {
        try {
            publisher.produce(1);
        } catch (PublishException e) {
            log.warn("Auto send failed: {}", e.getMessage());
        }
    }
}
