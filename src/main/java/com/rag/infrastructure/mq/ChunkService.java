package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.chunking.ChunkStrategy;
import com.rag.domain.chunking.ChunkStrategyFactory;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final ObjectMapper objectMapper;

    public ChunkService(MinioStorage minioStorage,
                        DocumentEventProducer eventProducer,
                        ChunkStrategyFactory chunkStrategyFactory,
                        ObjectMapper objectMapper) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.chunkStrategyFactory = chunkStrategyFactory;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_PARSED, groupId = "${spring.kafka.consumer.group-id}-chunk")
    public void consume(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            String traceId = event.getTraceId();
            String documentId = event.getDocumentId();

            TraceLogger tracer = TraceLogger.get(ChunkService.class, traceId, documentId);

            tracer.step("4. CHUNK_START");
            tracer.info("收到 Kafka 消息: topic=document-parsed, parsedMinioPath=%s", event.getParsedMinioPath());

            // Download parsed text from MinIO
            tracer.info("下载解析后文本: parsedMinioPath=%s", event.getParsedMinioPath());
            byte[] fileContent = minioStorage.download(event.getParsedMinioPath());
            String text = new String(fileContent);
            tracer.info("文本下载完成: textLength=%d characters", text.length());

            // Get chunk strategy and params from event metadata
            String strategyName = (String) event.getMetadata().getOrDefault("chunkStrategy", "fixed");
            @SuppressWarnings("unchecked")
            Map<String, Object> strategyParams = (Map<String, Object>) event.getMetadata().get("chunkParams");

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

            // Send chunks to next stage
            tracer.step("4.2 SEND_KAFKA_MESSAGE");
            event.setEventType(DocumentEvent.EventType.CHUNKED);
            event.getMetadata().put("chunks", chunks);
            tracer.info("发送 Kafka 消息: topic=document-chunked, chunkCount=%d", chunks.size());
            eventProducer.sendChunked(event);

            tracer.stepComplete("4. CHUNK_COMPLETE", "chunkCount=" + chunks.size());
            tracer.info("分块任务完成: documentId=%s, traceId=%s", documentId, traceId);

        } catch (Exception e) {
            log.error("Failed to chunk document: {}", e.getMessage(), e);
        }
    }
}
