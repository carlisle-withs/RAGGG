package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import com.rag.domain.event.DocumentEvent;
import com.rag.infrastructure.storage.MinioStorage;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Tika tika;

    public ParseService(MinioStorage minioStorage,
                       DocumentEventProducer eventProducer,
                       ObjectMapper objectMapper) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.tika = new Tika();
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_UPLOAD, groupId = "${spring.kafka.consumer.group-id}-parse")
    public void consume(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            log.info("Parse service received: {}", event.getDocumentId());

            // Download from MinIO
            byte[] fileContent = minioStorage.download(event.getMinioPath());

            // Extract text using Tika
            String parsedText = tika.parseToString(new java.io.ByteArrayInputStream(fileContent));

            // Upload parsed text to MinIO
            String parsedPath = event.getKbId() + "/" + event.getDocumentId() + "/parsed.txt";
            minioStorage.upload(parsedPath, parsedText.getBytes(), "text/plain");

            // Update event and send to next topic
            event.setEventType(DocumentEvent.EventType.PARSED);
            event.setParsedMinioPath(parsedPath);
            eventProducer.sendParsed(event);

            log.info("Document parsed: {}", event.getDocumentId());

        } catch (Exception e) {
            log.error("Failed to parse document", e);
        }
    }
}
