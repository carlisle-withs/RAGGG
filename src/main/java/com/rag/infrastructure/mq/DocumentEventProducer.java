package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import com.rag.domain.event.DocumentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.kafka.core.KafkaTemplate;

@Component
public class DocumentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String rawTopic;
    private final String chunkedTopic;

    public DocumentEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 AppConfig appConfig) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.rawTopic = appConfig.getKafkaTopics().getDocumentRaw();
        this.chunkedTopic = appConfig.getKafkaTopics().getDocumentChunked();
    }

    public void sendRawDocument(DocumentEvent event) {
        send(rawTopic, event);
    }

    public void sendChunkedDocument(DocumentEvent event) {
        send(chunkedTopic, event);
    }

    private void send(String topic, DocumentEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, event.getDocumentId(), message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send event to {}: {}", topic, ex.getMessage());
                        } else {
                            log.debug("Event sent to {}: {}", topic, event.getDocumentId());
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to serialize event", e);
            throw new RuntimeException("Send failed", e);
        }
    }
}
