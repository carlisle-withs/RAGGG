package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.chunking.ChunkStrategy;
import com.rag.domain.chunking.ChunkStrategyFactory;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.domain.model.Document;
import com.rag.domain.repository.DocumentRepository;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);
    private static final int QUEUE_CAPACITY = 10;

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final ObjectMapper objectMapper;
    private final DocumentRepository documentRepository;

    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

    @PostConstruct
    public void init() {
        executor.submit(this::processLoop);
        log.info("ChunkService started with queue capacity: {}", QUEUE_CAPACITY);
    }

    private void processLoop() {
        while (true) {
            try {
                String message = messageQueue.take();
                doProcess(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing message", e);
            }
        }
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_PARSED, groupId = "${spring.kafka.consumer.group-id}-chunk")
    public void consume(String message) {
        try {
            messageQueue.put(message);
            log.debug("Message queued, queue size: {}", messageQueue.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to queue message", e);
        }
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

            // Update status to CHUNKED
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.DocumentStatus.CHUNKED);
                doc.setChunkCount(chunks.size());
                documentRepository.save(doc);
            });

            // Save chunks to MinIO
            String chunksPath = event.getKbId() + "/" + event.getDocumentId() + "/chunks.json";
            byte[] chunksJson = objectMapper.writeValueAsBytes(chunks);
            minioStorage.upload(chunksPath, chunksJson, "application/json");
            tracer.info("Chunks 已保存到 MinIO: path=%s, size=%d bytes", chunksPath, chunksJson.length);

            // Send chunks to next stage
            tracer.step("4.2 SEND_KAFKA_MESSAGE");
            event.setEventType(DocumentEvent.EventType.CHUNKED);
            event.getMetadata().put("chunksMinioPath", chunksPath);
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
