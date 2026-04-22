package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.chunking.ChunkStrategy;
import com.rag.domain.chunking.ChunkStrategyFactory;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.domain.model.ChunkDTO;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.repository.KnowledgeDocumentRepository;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.TimeUnit;

@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentRepository documentRepository;
    private final MeterRegistry meterRegistry;

    private final Timer chunkTimer;
    private final Timer chunkingAlgoTimer;
    private final Timer minioDownloadTimer;
    private final Timer minioUploadTimer;
    private final Counter chunkSuccessCounter;
    private final Counter chunkFailureCounter;
    private final Counter chunkSkipCounter;
    private final DistributionSummary chunkCountSummary;

    public ChunkService(MinioStorage minioStorage,
                        DocumentEventProducer eventProducer,
                        ChunkStrategyFactory chunkStrategyFactory,
                        ObjectMapper objectMapper,
                        KnowledgeDocumentRepository documentRepository,
                        MeterRegistry meterRegistry) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.chunkStrategyFactory = chunkStrategyFactory;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.meterRegistry = meterRegistry;

        this.chunkTimer = Timer.builder("doc.pipeline").tag("stage", "chunk").description("文档分块总耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.chunkingAlgoTimer = Timer.builder("doc.pipeline.chunking").tag("op", "algorithm").description("分块算法执行耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.minioDownloadTimer = Timer.builder("doc.pipeline.io").tag("op", "minio_download").description("MinIO 下载耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.minioUploadTimer = Timer.builder("doc.pipeline.io").tag("op", "minio_upload").description("MinIO 上传耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.chunkSuccessCounter = Counter.builder("doc.pipeline.count").tag("stage", "chunk").tag("status", "success").description("分块成功次数").register(meterRegistry);
        this.chunkFailureCounter = Counter.builder("doc.pipeline.count").tag("stage", "chunk").tag("status", "failure").description("分块失败次数").register(meterRegistry);
        this.chunkSkipCounter = Counter.builder("doc.pipeline.count").tag("stage", "chunk").tag("status", "skip").description("分块跳过次数").register(meterRegistry);
        this.chunkCountSummary = DistributionSummary.builder("doc.pipeline.chunk_count").description("每个文档的分块数量分布").baseUnit("chunks").register(meterRegistry);
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_PARSED, groupId = "${spring.kafka.consumer.group-id}-chunk")
    public void consume(String message) {
        doProcess(message);
    }

    @Transactional
    public void doProcess(String message) {
        long t0 = System.nanoTime();
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            String traceId = event.getTraceId();
            Long documentId = Long.parseLong(event.getDocumentId());

            TraceLogger tracer = TraceLogger.get(ChunkService.class, traceId, event.getDocumentId());

            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || docOpt.get().getDeleted()) {
                tracer.info("文档已删除，跳过处理: documentId=%s", documentId);
                chunkSkipCounter.increment();
                return;
            }

            tracer.step("4. CHUNK_START");

            docOpt.get().setStatus(KnowledgeDocument.DocumentStatus.CHUNKING);
            documentRepository.save(docOpt.get());

            // ---- MinIO 下载解析后文本 ----
            long t1 = System.nanoTime();
            tracer.info("下载解析后文本: parsedMinioPath=%s", event.getParsedMinioPath());
            String text = minioStorage.downloadAsString(event.getParsedMinioPath(), StandardCharsets.UTF_8);
            minioDownloadTimer.record(System.nanoTime() - t1, TimeUnit.NANOSECONDS);
            tracer.info("文本下载完成: textLength=%d characters", text.length());

            Map<String, Object> metadata = event.getMetadata() != null ? event.getMetadata() : new HashMap<>();
            String strategyName = String.valueOf(metadata.getOrDefault("chunkStrategy", "fixed"));

            tracer.step("4.1 CHUNK_TEXT");
            tracer.info("开始分块: strategy=%s", strategyName);

            String mimeType = (String) metadata.get("mimeType");
            tracer.info("MIME 类型: %s", mimeType != null ? mimeType : "未检测到");

            // ---- 分块算法 ----
            long t2 = System.nanoTime();
            List<Chunk> chunks;
            if ("intelligent".equalsIgnoreCase(strategyName) && mimeType != null) {
                tracer.info("使用智能分块（基于 MIME 类型: %s）", mimeType);
                chunks = chunkStrategyFactory.getIntelligentChunks(text, event.getDocumentId(), event.getKbId(), mimeType);
            } else {
                ChunkStrategy strategy = chunkStrategyFactory.getStrategy(strategyName, new HashMap<>());
                chunks = strategy.chunk(text, event.getDocumentId(), event.getKbId());
            }
            chunkingAlgoTimer.record(System.nanoTime() - t2, TimeUnit.NANOSECONDS);

            tracer.info("分块完成: chunkCount=%d, strategy=%s", chunks.size(), strategyName);
            for (int i = 0; i < Math.min(chunks.size(), 3); i++) {
                Chunk c = chunks.get(i);
                tracer.debug("Chunk[%d]: length=%d, tokenCount=%d",
                        i, c.getContent().length(), c.getTokenCount());
            }
            if (chunks.size() > 3) {
                tracer.debug("... and %d more chunks", chunks.size() - 3);
            }

            chunkCountSummary.record(chunks.size());
            tracer.stepComplete("4.1 CHUNK_TEXT", "chunkCount=" + chunks.size());
            text = null; // help GC

            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(KnowledgeDocument.DocumentStatus.CHUNKED);
                doc.setChunkCount(chunks.size());
                documentRepository.save(doc);
            });

            // ---- MinIO 上传 chunks.json ----
            long t3 = System.nanoTime();
            String chunksPath = event.getKbId() + "/" + event.getDocumentId() + "/chunks.json";
            Path tempChunksFile = Files.createTempFile("rag-chunks-", ".json");
            try {
                try (com.fasterxml.jackson.core.JsonGenerator generator = objectMapper.getFactory().createGenerator(Files.newOutputStream(tempChunksFile))) {
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
            minioUploadTimer.record(System.nanoTime() - t3, TimeUnit.NANOSECONDS);

            tracer.step("4.2 SEND_KAFKA_MESSAGE");
            event.setEventType(DocumentEvent.EventType.CHUNKED);
            metadata.put("chunksMinioPath", chunksPath);
            event.setMetadata(metadata);
            tracer.info("发送 Kafka 消息: topic=document-chunked, chunkCount=%d", chunks.size());
            eventProducer.sendChunked(event);

            chunkTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            chunkSuccessCounter.increment();
            tracer.stepComplete("4. CHUNK_COMPLETE", "chunkCount=" + chunks.size());
            tracer.info("分块任务完成: documentId=%s, traceId=%s", documentId, traceId);

        } catch (Exception e) {
            log.error("Failed to chunk document: {}", e.getMessage(), e);
            chunkFailureCounter.increment();
            chunkTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            try {
                DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
                documentRepository.findById(Long.parseLong(event.getDocumentId())).ifPresent(doc -> {
                    doc.setStatus(KnowledgeDocument.DocumentStatus.FAILED);
                    documentRepository.save(doc);
                });
            } catch (Exception ex) {
                log.error("Failed to update document status to FAILED", ex);
            }
        }
    }
}
