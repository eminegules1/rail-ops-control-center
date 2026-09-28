package com.railops.backend;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class KafkaTopicConfig {

    // Same declaration as the producer, so the backend does not depend on the producer starting first.
    // KafkaAdmin only creates missing topics.
    @Bean
    NewTopic incidentEventsTopic(IngestionProperties properties) {
        IngestionProperties.Topic topic = properties.topic();
        return TopicBuilder.name(topic.name())
                .partitions(topic.partitions())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(topic.retention().toMillis()))
                .build();
    }

    // The dead-letter recoverer keeps the source partition, so this topic needs at least as many partitions.
    @Bean
    NewTopic deadLetterTopic(IngestionProperties properties) {
        IngestionProperties.DeadLetter deadLetter = properties.deadLetter();
        return TopicBuilder.name(deadLetter.name())
                .partitions(properties.topic().partitions())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(deadLetter.retention().toMillis()))
                .build();
    }
}
