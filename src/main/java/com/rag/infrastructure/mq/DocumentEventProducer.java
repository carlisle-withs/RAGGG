package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.kafka.core.KafkaTemplate;

@Component
public class DocumentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public DocumentEventProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
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
                            // 发送失败目前无 outbox 补偿，必须可观测：指标 + error 级日志
                            Counter.builder("kafka_produce_fail_total")
                                    .description("Kafka 事件发送失败次数（无补偿，需人工关注）")
                                    .tag("topic", topic)
                                    .register(meterRegistry)
                                    .increment();
                            log.error("Failed to send event to {}: {}", topic, ex.getMessage());
                        } else {
                            log.debug("Event sent to {}: {}", topic, event.getDocumentId());
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to serialize event", e);
        }
    }
}
