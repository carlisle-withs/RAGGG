package com.rag.infrastructure.mq;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.chunking.ChunkStrategy;
import com.rag.domain.chunking.ChunkStrategyFactory;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.domain.model.ChunkDTO;
import com.rag.domain.model.Document;
import com.rag.domain.repository.DocumentRepository;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final ObjectMapper objectMapper;
    private final DocumentRepository documentRepository;

    public ChunkService(MinioStorage minioStorage,
                        DocumentEventProducer eventProducer,
                        ChunkStrategyFactory chunkStrategyFactory,
                        ObjectMapper objectMapper,
                        DocumentRepository documentRepository) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.chunkStrategyFactory = chunkStrategyFactory;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_PARSED, groupId = "${spring.kafka.consumer.group-id}-chunk")
    public void consume(String message) {
        doProcess(message);
    }

    @Transactional
    public void doProcess(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            String traceId = event.getTraceId();
            String documentId = event.getDocumentId();

            TraceLogger tracer = TraceLogger.get(ChunkService.class, traceId, documentId);

            // 检查文档是否已删除
            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || docOpt.get().isDeleted()) {
                tracer.info("文档已删除，跳过处理: documentId=%s", documentId);
                return;
            }

            tracer.step("4. CHUNK_START");

            // Update status to CHUNKING
            docOpt.get().setStatus(Document.DocumentStatus.CHUNKING);
            documentRepository.save(docOpt.get());

            tracer.info("收到 Kafka 消息: topic=document-parsed, parsedMinioPath=%s", event.getParsedMinioPath());

            // Download parsed text from MinIO
            tracer.info("下载解析后文本: parsedMinioPath=%s", event.getParsedMinioPath());
            String text = minioStorage.downloadAsString(event.getParsedMinioPath(), StandardCharsets.UTF_8);
            tracer.info("文本下载完成: textLength=%d characters", text.length());

            // Get chunk strategy and params from event metadata
            Map<String, Object> metadata = event.getMetadata() != null ? event.getMetadata() : new HashMap<>();
            String strategyName = String.valueOf(metadata.getOrDefault("chunkStrategy", "fixed"));
            @SuppressWarnings("unchecked")
            Map<String, Object> strategyParams = metadata.get("chunkParams") instanceof Map<?, ?> rawParams
                    ? (Map<String, Object>) rawParams
                    : Map.of();

            tracer.step("4.1 CHUNK_TEXT");
            tracer.info("开始分块: strategy=%s, params=%s", strategyName, strategyParams);

            // Get strategy and chunk
            ChunkStrategy strategy = chunkStrategyFactory.getStrategy(strategyName, strategyParams);
            List<Chunk> chunks = strategy.chunk(text, event.getDocumentId(), event.getKbId());

            tracer.info("分块完成: chunkCount=%d, strategy=%s", chunks.size(), strategyName);

            // Log each chunk info
            for (int i = 0; i < Math.min(chunks.size(), 3); i++) {
                Chunk c = chunks.get(i);
                tracer.debug("Chunk[%d]: id=%s, length=%d, tokenCount=%d",
                        i, c.getId(), c.getContent().length(), c.getTokenCount());
            }
            if (chunks.size() > 3) {
                tracer.debug("... and %d more chunks", chunks.size() - 3);
            }

            tracer.stepComplete("4.1 CHUNK_TEXT", "chunkCount=" + chunks.size());
            text = null;

            // Update status to CHUNKED
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.DocumentStatus.CHUNKED);
                doc.setChunkCount(chunks.size());
                documentRepository.save(doc);
            });

            // Save chunks to MinIO (convert to DTOs to avoid JPA serialization issues)
            String chunksPath = event.getKbId() + "/" + event.getDocumentId() + "/chunks.json";
            Path tempChunksFile = Files.createTempFile("rag-chunks-", ".json");
            try {
                try (JsonGenerator generator = objectMapper.getFactory().createGenerator(Files.newOutputStream(tempChunksFile))) {
                    generator.writeStartArray();
                    for (Chunk chunk : chunks) {
                        objectMapper.writeValue(generator, ChunkDTO.fromChunk(chunk));
                    }
                    generator.writeEndArray();
                }
                try (InputStream chunksStream = Files.newInputStream(tempChunksFile)) {
                    minioStorage.upload(chunksPath, chunksStream, Files.size(tempChunksFile), "application/json");
                }
                tracer.info("Chunks 已保存到 MinIO: path=%s, size=%d bytes", chunksPath, Files.size(tempChunksFile));
            } finally {
                Files.deleteIfExists(tempChunksFile);
            }

            // Send chunks to next stage
            tracer.step("4.2 SEND_KAFKA_MESSAGE");
            event.setEventType(DocumentEvent.EventType.CHUNKED);
            metadata.put("chunksMinioPath", chunksPath);
            event.setMetadata(metadata);
            tracer.info("发送 Kafka 消息: topic=document-chunked, chunkCount=%d", chunks.size());
            eventProducer.sendChunked(event);

            tracer.stepComplete("4. CHUNK_COMPLETE", "chunkCount=" + chunks.size());
            tracer.info("分块任务完成: documentId=%s, traceId=%s", documentId, traceId);

        } catch (Exception e) {
            log.error("Failed to chunk document: {}", e.getMessage(), e);
            // Update status to FAILED
            try {
                DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
                documentRepository.findById(event.getDocumentId()).ifPresent(doc -> {
                    doc.setStatus(Document.DocumentStatus.FAILED);
                    documentRepository.save(doc);
                });
            } catch (Exception ex) {
                log.error("Failed to update document status to FAILED", ex);
            }
        }
    }
}
