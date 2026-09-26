package com.railops.backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ingestion")
public record IngestionProperties(@Valid @NotNull Topic topic) {

    public record Topic(@NotBlank String name, @Min(1) int partitions, @NotNull Duration retention) {
    }
}
