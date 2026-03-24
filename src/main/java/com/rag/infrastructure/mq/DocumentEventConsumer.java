package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class DocumentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final MinioStorage minioStorage;
    private final EmbeddingService embeddingService;
    private final ElasticsearchSearch elasticsearchSearch;
    private final MilvusVectorStore milvusVectorStore;
    private final DocumentParser documentParser;

    public DocumentEventConsumer(ObjectMapper objectMapper,
                                  MinioStorage minioStorage,
                                  EmbeddingService embeddingService,
                                  ElasticsearchSearch elasticsearchSearch,
                                  MilvusVectorStore milvusVectorStore,
                                  AppConfig appConfig) {
        this.objectMapper = objectMapper;
        this.minioStorage = minioStorage;
        this.embeddingService = embeddingService;
        this.elasticsearchSearch = elasticsearchSearch;
        this.milvusVectorStore = milvusVectorStore;
        this.documentParser = new DocumentParser(appConfig);
    }

    @KafkaListener(topics = "#{@appConfig.kafkaTopics.documentRaw}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeRawDocument(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            log.info("Processing document: {}", event.getDocumentId());

            // Download from MinIO
            byte[] fileContent = minioStorage.download(event.getMinioPath());

            // Extract text
            String text = documentParser.extractText(fileContent);

            // Chunk text
            List<Chunk> chunks = documentParser.chunkText(text, event.getDocumentId());

            log.info("Document chunked: {} chunks", chunks.size());

            // Send to next stage
            event.setEventType(DocumentEvent.EventType.CHUNKED);
            event.getMetadata().put("chunks", chunks);
            // Note: In a full implementation, would send to chunked topic

            // Directly process chunks
            for (Chunk chunk : chunks) {
                indexChunk(chunk, event.getKbId());
            }

            log.info("Document indexed: {}", event.getDocumentId());

        } catch (Exception e) {
            log.error("Failed to process document", e);
        }
    }

    private void indexChunk(Chunk chunk, String kbId) {
        try {
            // Generate embedding
            float[] embedding = embeddingService.embed(chunk.getContent());

            // Store in both ES and Milvus
            elasticsearchSearch.index(chunk);
            milvusVectorStore.insert(chunk, embedding);

        } catch (Exception e) {
            log.error("Failed to index chunk: {}", chunk.getId(), e);
        }
    }

    // Inner class for document parsing
    private static class DocumentParser {
        private final int chunkSize;
        private final int chunkOverlap;

        DocumentParser(AppConfig appConfig) {
            this.chunkSize = appConfig.getChunking().getChunkSize();
            this.chunkOverlap = appConfig.getChunking().getChunkOverlap();
        }

        String extractText(byte[] content) {
            // Simple text extraction - in production use Apache Tika
            return new String(content);
        }

        List<Chunk> chunkText(String text, String documentId) {
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
                chunk.getMetadata().put("kbId", "default");

                chunks.add(chunk);

                index = end - chunkOverlap;
                if (index <= 0) index = end;
                chunkIndex++;
            }

            return chunks;
        }
    }
}
