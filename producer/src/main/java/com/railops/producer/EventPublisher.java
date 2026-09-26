package com.railops.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railops.producer.EventGenerator.Generated;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Sends generated events to Kafka as plain JSON strings keyed by service, and waits for confirmation. */
@Component
public class EventPublisher {

    // Above delivery.timeout.ms plus max.block.ms, so the client reports the failure before we give up.
    private static final long CONFIRM_TIMEOUT_SECONDS = 20;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final EventGenerator generator;
    private final String topic;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
            EventGenerator generator, ProducerProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.generator = generator;
        this.topic = properties.topic().name();
    }

    public ProduceResult produce(int count) {
        return send(Stream.generate(generator::next).limit(count).toList());
    }

    public ProduceResult seed(int count) {
        return send(generator.seed(count));
    }

    private ProduceResult send(List<Generated> batch) {
        List<CompletableFuture<?>> futures = new ArrayList<>(batch.size());
        try {
            for (Generated generated : batch) {
                IncidentEvent event = generated.event();
                CompletableFuture<?> future = kafka.send(topic, event.service(), toJson(event));
                futures.add(future);
                // A future that failed immediately means the broker is unreachable; don't wait out the rest.
                if (future.isCompletedExceptionally()) {
                    break;
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new PublishException(batch.size(), confirmed(futures), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException(batch.size(), confirmed(futures), e);
        }
        int duplicates = (int) batch.stream().filter(Generated::duplicate).count();
        return new ProduceResult(batch.size(), duplicates);
    }

    private String toJson(IncidentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long confirmed(List<CompletableFuture<?>> futures) {
        return futures.stream().filter(f -> f.isDone() && !f.isCompletedExceptionally()).count();
    }
}
