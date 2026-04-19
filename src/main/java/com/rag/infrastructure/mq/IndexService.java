package com.rag.infrastructure.mq;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.domain.model.ChunkDTO;
import com.rag.domain.model.KnowledgeChunk;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.repository.KnowledgeDocumentRepository;
import com.rag.domain.repository.KnowledgeChunkRepository;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.infrastructure.vector.MilvusVectorStore;
import com.rag.util.TraceLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final MinioStorage minioStorage;
    private final EmbeddingService embeddingService;
    private final ElasticsearchSearch elasticsearchSearch;
    private final MilvusVectorStore milvusVectorStore;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** 每次批量 embedding 的 chunk 数量（从配置读取） */
    private final int embeddingBatchSize;

    // Timers
    private final Timer fullIndexTimer;
    /** 批量 embedding 耗时（一次 API 调用处理 batchSize 个 chunks） */
    private final Timer batchEmbedTimer;
    /** 单 chunk MySQL+ES+Milvus 持久化耗时 */
    private final Timer chunkPersistTimer;
    // Counters
    private final Counter indexSuccessCounter;
    private final Counter indexFailureCounter;
    private final Counter indexSkipCounter;
    private final Counter chunkSuccessCounter;
    private final Counter chunkFailureCounter;

    public IndexService(KnowledgeDocumentRepository documentRepository,
                        KnowledgeChunkRepository chunkRepository,
                        MinioStorage minioStorage,
                        EmbeddingService embeddingService,
                        ElasticsearchSearch elasticsearchSearch,
                        MilvusVectorStore milvusVectorStore,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry,
                        @Value("${embedding.batch-size:32}") int embeddingBatchSize) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.minioStorage = minioStorage;
        this.embeddingService = embeddingService;
        this.elasticsearchSearch = elasticsearchSearch;
        this.milvusVectorStore = milvusVectorStore;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.embeddingBatchSize = embeddingBatchSize;

        this.fullIndexTimer = Timer.builder("doc.pipeline").tag("stage", "index").description("文档索引总耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.batchEmbedTimer = Timer.builder("doc.pipeline.chunk_ops").tag("op", "embed_batch").description("批量 Embedding 耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.chunkPersistTimer = Timer.builder("doc.pipeline.chunk_ops").tag("op", "persist").description("单 Chunk MySQL+ES+Milvus 耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.indexSuccessCounter = Counter.builder("doc.pipeline.count").tag("stage", "index").tag("status", "success").description("索引成功次数").register(meterRegistry);
        this.indexFailureCounter = Counter.builder("doc.pipeline.count").tag("stage", "index").tag("status", "failure").description("索引失败次数").register(meterRegistry);
        this.indexSkipCounter = Counter.builder("doc.pipeline.count").tag("stage", "index").tag("status", "skip").description("索引跳过次数").register(meterRegistry);
        this.chunkSuccessCounter = Counter.builder("doc.pipeline.chunk_count").tag("status", "success").description("Chunk 索引成功次数").register(meterRegistry);
        this.chunkFailureCounter = Counter.builder("doc.pipeline.chunk_count").tag("status", "failure").description("Chunk 索引失败次数").register(meterRegistry);

        log.info("IndexService initialized: embeddingBatchSize={}", embeddingBatchSize);
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_CHUNKED, groupId = "${spring.kafka.consumer.group-id}-index")
    public void consume(String message) {
        doProcess(message);
    }

    @Transactional
    public void doProcess(String message) {
        long t0 = System.nanoTime();
        Long documentId = null;
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            documentId = Long.parseLong(event.getDocumentId());
            String traceId = event.getTraceId();

            TraceLogger tracer = TraceLogger.get(IndexService.class, traceId, event.getDocumentId());

            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || docOpt.get().getDeleted()) {
                tracer.info("文档已删除，跳过处理: documentId=%s", documentId);
                indexSkipCounter.increment();
                return;
            }

            tracer.step("5. INDEX_START");
            docOpt.get().setStatus(KnowledgeDocument.DocumentStatus.INDEXING);
            documentRepository.save(docOpt.get());

            Map<String, Object> metadata = event.getMetadata() != null ? event.getMetadata() : new HashMap<>();
            Object chunksMinioPathValue = metadata.get("chunksMinioPath");
            if (!(chunksMinioPathValue instanceof String chunksMinioPath) || chunksMinioPath.isBlank()) {
                throw new IllegalStateException("Missing chunksMinioPath in document metadata");
            }

            final int[] successCount = {0};
            final int[] failCount = {0};
            final Long docId = documentId;
            final Long docKbId = Long.parseLong(event.getKbId());

            // ---- 批量缓冲：累积 chunk，直到凑满一批才调用 LLM API ----
            List<Chunk> batch = new ArrayList<>(embeddingBatchSize);

            try (InputStream chunksStream = minioStorage.getObjectStream(chunksMinioPath);
                 JsonParser parser = objectMapper.getFactory().createParser(chunksStream)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("Invalid chunk payload format");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    Chunk chunk = objectMapper.readValue(parser, ChunkDTO.class).toChunk();
                    // 跳过空内容 chunk，避免 SiliconFlow 批量 embedding 报 20015（不接受空字符串）
                    if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                        continue;
                    }
                    batch.add(chunk);

                    // 凑满一批 → 批量 embedding，然后处理整批
                    if (batch.size() == embeddingBatchSize) {
                        processBatch(batch, docId, docKbId, tracer, successCount, failCount);
                        tracer.info("索引进度: %d chunks processed", successCount[0]);
                        batch.clear();
                    }
                }
            }

            // ---- Flush 剩余不足一批的 chunks ----
            if (!batch.isEmpty()) {
                processBatch(batch, docId, docKbId, tracer, successCount, failCount);
            }

            // ---- 无 chunks 时（MinIO 不可用或文档为空） ----
            if (successCount[0] == 0 && failCount[0] == 0) {
                tracer.warn("No chunks found for document: {} (chunksMinioPath={})", documentId, chunksMinioPath);
                indexSkipCounter.increment();
                fullIndexTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
                return;
            }

            tracer.stepComplete("5.2 INDEX_CHUNKS", "success=" + successCount[0] + ", fail=" + failCount[0]);

            KnowledgeDocument.DocumentStatus finalStatus = failCount[0] > 0
                    ? KnowledgeDocument.DocumentStatus.FAILED
                    : KnowledgeDocument.DocumentStatus.COMPLETED;
            documentRepository.findById(documentId).ifPresent(d -> {
                d.setStatus(finalStatus);
                d.setChunkCount(successCount[0]);
                documentRepository.save(d);
            });

            fullIndexTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            if (finalStatus == KnowledgeDocument.DocumentStatus.COMPLETED) {
                indexSuccessCounter.increment();
            } else {
                indexFailureCounter.increment();
            }

            tracer.stepComplete("5. INDEX_COMPLETE");
            tracer.info("文档索引完成: documentId={}, total={}, success={}, fail={}, batchSize={}",
                    documentId, successCount[0] + failCount[0], successCount[0], failCount[0], embeddingBatchSize);

        } catch (Exception e) {
            log.error("Failed to index document: {}", e.getMessage(), e);
            indexFailureCounter.increment();
            fullIndexTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            if (documentId != null) {
                documentRepository.findById(documentId).ifPresent(doc -> {
                    doc.setStatus(KnowledgeDocument.DocumentStatus.FAILED);
                    documentRepository.save(doc);
                });
            }
        }
    }

    /**
     * 批量处理一组 chunks：
     * 1. 一次性调用 LLM API 获取所有向量（1 次网络往返替代 N 次）
     * 2. 整批一次写入 MySQL + ES + Milvus（各 1 次 RPC/DB 请求替代 N 次）
     */
    private void processBatch(List<Chunk> batch, Long docId, Long docKbId,
                              TraceLogger tracer,
                              int[] successCount, int[] failCount) {
        if (batch.isEmpty()) return;

        // ---- Step 1: 批量 embedding（一次 API 调用） ----
        List<String> texts = new ArrayList<>(batch.size());
        for (Chunk c : batch) {
            texts.add(c.getContent());
        }

        long tEmbed = System.nanoTime();
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        batchEmbedTimer.record(System.nanoTime() - tEmbed, TimeUnit.NANOSECONDS);
        tracer.debug("批量 embedding 完成: batchSize={}, vectors={}, tookMs={}",
                batch.size(), embeddings.size(), (System.nanoTime() - tEmbed) / 1_000_000);

        // ---- Step 2: 整批持久化（MySQL + ES + Milvus 各 1 次请求） ----
        long tPersist = System.nanoTime();
        try {
            // 2a. MySQL: 整批 saveAll（由 JPA/Hibernate 批量插入）
            List<KnowledgeChunk> kcs = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Chunk chunk = batch.get(i);
                KnowledgeChunk kc = new KnowledgeChunk();
                kc.setKbId(docKbId);
                kc.setDocId(docId);
                kc.setChunkIndex(chunk.getChunkIndex());
                kc.setContent(cleanForMilvus(chunk.getContent()));
                kc.setTokenCount(chunk.getTokenCount());
                kc.setCreatedBy("guest");
                kcs.add(kc);
            }
            chunkRepository.saveAll(kcs);

            // 2b. Elasticsearch: 一次 Bulk 请求写入整批 chunks
            elasticsearchSearch.indexBatch(batch);

            // 2c. Milvus: 一次 RPC 请求写入整批向量
            List<Map.Entry<Chunk, float[]>> chunkEmbedPairs = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Chunk cleanChunk = batch.get(i);
                // 清洗 Milvus 不接受的孤立代理字符
                cleanChunk.setContent(cleanForMilvus(cleanChunk.getContent()));
                chunkEmbedPairs.add(Map.entry(cleanChunk, embeddings.get(i)));
            }
            milvusVectorStore.insertBatch(chunkEmbedPairs);

            chunkPersistTimer.record(System.nanoTime() - tPersist, TimeUnit.NANOSECONDS);
            successCount[0] += batch.size();
            chunkSuccessCounter.increment(batch.size());
            tracer.debug("批量持久化完成: MySQL+ES+Milvus 各 1 次请求, chunks={}", batch.size());

        } catch (Exception e) {
            failCount[0] += batch.size();
            chunkFailureCounter.increment(batch.size());
            tracer.error("批量持久化失败: batchSize=" + batch.size() + ", error=" + e.getMessage());
        }
    }

    /**
     * 清洗字符串中的孤立 UTF-16 代理字符，
     * 防止 Milvus protobuf 序列化时抛出 UnpairedSurrogateException。
     * 孤立的高代理（0xD800-0xDBFF）或低代理（0xDC00-0xDFFF）
     * 会被替换为 Unicode 替换字符 '\uFFFD'。
     */
    private String cleanForMilvus(String content) {
        if (content == null) return null;
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isHighSurrogate(c)) {
                // 高代理：检查下一个是否是低代理
                if (i + 1 < content.length() && Character.isLowSurrogate(content.charAt(i + 1))) {
                    sb.append(c);  // 正常的高-低代理对，保留
                } else {
                    sb.append('\uFFFD');  // 孤立高代理，替换
                }
            } else if (Character.isLowSurrogate(c)) {
                // 孤立低代理（不应该出现在这里，因为高代理已在上面处理），替换
                sb.append('\uFFFD');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
