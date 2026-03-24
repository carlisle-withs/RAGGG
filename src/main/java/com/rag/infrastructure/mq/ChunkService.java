package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.infrastructure.storage.MinioStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final int chunkSize;
    private final int chunkOverlap;

    public ChunkService(MinioStorage minioStorage,
                        DocumentEventProducer eventProducer,
                        ObjectMapper objectMapper,
                        AppConfig appConfig) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.chunkSize = appConfig.getChunking().getChunkSize();
        this.chunkOverlap = appConfig.getChunking().getChunkOverlap();
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_PARSED, groupId = "${spring.kafka.consumer.group-id}-chunk")
    public void consume(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            log.info("Chunk service received: {}", event.getDocumentId());

            // Download parsed text from MinIO
            byte[] fileContent = minioStorage.download(event.getParsedMinioPath());
            String text = new String(fileContent);

            // Chunk text
            List<Chunk> chunks = chunkText(text, event.getDocumentId(), event.getKbId());

            log.info("Document chunked into {} chunks", chunks.size());

            // Send chunks to next stage
            event.setEventType(DocumentEvent.EventType.CHUNKED);
            event.getMetadata().put("chunks", chunks);
            eventProducer.sendChunked(event);

            log.info("Chunks sent for indexing: {}", event.getDocumentId());

        } catch (Exception e) {
            log.error("Failed to chunk document", e);
        }
    }

    private List<Chunk> chunkText(String text, String documentId, String kbId) {
        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        int chunkIndex = 0;

        while (index < text.length()) {
            int end = Math.min(index + chunkSize, text.length());
            String chunkText = text.substring(index, end);

            Chunk chunk = new Chunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setDocumentId(documentId);
            chunk.setContent(chunkText);
            chunk.setChunkIndex(chunkIndex);
            chunk.setTokenCount(chunkText.length() / 4);
            chunk.getMetadata().put("kbId", kbId);

            chunks.add(chunk);

            index = end - chunkOverlap;
            if (index <= 0) index = end;
            chunkIndex++;
        }

        return chunks;
    }
}
