package com.railops.producer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("producer")
public record ProducerProperties(
        @Min(100) long intervalMs,
        @DecimalMin("0.0") @DecimalMax("1.0") double duplicateRatio,
        @Min(0) @Max(1000) int seedCount,
        @Valid @NotNull Topic topic) {

    public record Topic(@NotBlank String name, @Min(1) int partitions, @NotNull Duration retention) {
    }
}
