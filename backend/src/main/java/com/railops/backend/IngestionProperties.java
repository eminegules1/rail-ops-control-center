package com.railops.backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ingestion")
public record IngestionProperties(@Valid @NotNull Topic topic, @Valid @NotNull DeadLetter deadLetter,
        @Valid @NotNull Retry retry) {

    public record Topic(@NotBlank String name, @Min(1) int partitions, @NotNull Duration retention) {
    }

    /** Invalid and retry-exhausted records; it has the source topic's partitions so each keeps its partition. */
    public record DeadLetter(@NotBlank String name, @NotNull Duration retention) {
    }

    /** Exponential back-off for failures that may pass on a later attempt, such as PostgreSQL being down. */
    public record Retry(@NotNull Duration initialInterval, @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxInterval, @Min(1) int maxRetries) {
    }
}
