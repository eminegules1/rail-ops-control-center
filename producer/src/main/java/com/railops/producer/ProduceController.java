package com.railops.producer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProduceController {

    private final EventPublisher publisher;

    ProduceController(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/produce")
    ProduceResult produce(@RequestParam(defaultValue = "1") @Min(1) @Max(1000) int count) {
        return publisher.produce(count);
    }
}
