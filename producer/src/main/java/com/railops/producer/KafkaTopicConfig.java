package com.railops.producer;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class KafkaTopicConfig {

    // Broker auto-create is off. The backend declares the same topic too; KafkaAdmin only creates missing topics.
    @Bean
    NewTopic incidentEventsTopic(ProducerProperties properties) {
        ProducerProperties.Topic topic = properties.topic();
        return TopicBuilder.name(topic.name())
                .partitions(topic.partitions())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(topic.retention().toMillis()))
                .build();
    }
}
