package com.rag.infrastructure.mq;

import com.rag.config.AppConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    private final AppConfig appConfig;

    public KafkaConfig(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @Bean
    public NewTopic documentRawTopic() {
        return TopicBuilder.name(appConfig.getKafkaTopics().getDocumentRaw())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic documentChunkedTopic() {
        return TopicBuilder.name(appConfig.getKafkaTopics().getDocumentChunked())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
