package com.rag.infrastructure.mq;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic documentUploadTopic() {
        return TopicBuilder.name(KafkaTopics.DOCUMENT_UPLOAD)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic documentParsedTopic() {
        return TopicBuilder.name(KafkaTopics.DOCUMENT_PARSED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic documentChunkedTopic() {
        return TopicBuilder.name(KafkaTopics.DOCUMENT_CHUNKED)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
