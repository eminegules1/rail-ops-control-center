package com.railops.producer;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GeneratorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RandomGenerator randomGenerator() {
        // java.util.Random lives in java.base; the default algorithm needs jdk.random, which the JRE image omits.
        return new Random();
    }
}
