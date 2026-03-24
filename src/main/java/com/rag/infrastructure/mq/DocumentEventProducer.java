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

    public DocumentEventProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void sendUploaded(DocumentEvent event) {
        send(KafkaTopics.DOCUMENT_UPLOAD, event);
    }

    public void sendParsed(DocumentEvent event) {
        send(KafkaTopics.DOCUMENT_PARSED, event);
    }

    public void sendChunked(DocumentEvent event) {
        send(KafkaTopics.DOCUMENT_CHUNKED, event);
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
