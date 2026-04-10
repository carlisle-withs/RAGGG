package com.rag.infrastructure.mq;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.domain.model.ChunkDTO;
import com.rag.domain.model.KnowledgeChunk;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.model.KnowledgeBase;
import com.rag.domain.repository.KnowledgeDocumentRepository;
import com.rag.domain.repository.KnowledgeChunkRepository;
import com.rag.domain.repository.KnowledgeBaseRepository;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.infrastructure.vector.MilvusVectorStore;
import com.rag.util.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final MinioStorage minioStorage;
    private final EmbeddingService embeddingService;
    private final ElasticsearchSearch elasticsearchSearch;
    private final MilvusVectorStore milvusVectorStore;
    private final ObjectMapper objectMapper;

    public IndexService(KnowledgeDocumentRepository documentRepository,
                        KnowledgeChunkRepository chunkRepository,
                        KnowledgeBaseRepository kbRepository,
                        MinioStorage minioStorage,
                        EmbeddingService embeddingService,
                        ElasticsearchSearch elasticsearchSearch,
                        MilvusVectorStore milvusVectorStore,
                        ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.kbRepository = kbRepository;
        this.minioStorage = minioStorage;
        this.embeddingService = embeddingService;
        this.elasticsearchSearch = elasticsearchSearch;
        this.milvusVectorStore = milvusVectorStore;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_CHUNKED, groupId = "${spring.kafka.consumer.group-id}-index")
    public void consume(String message) {
        doProcess(message);
    }

    @Transactional
    public void doProcess(String message) {
        Long documentId = null;
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            documentId = Long.parseLong(event.getDocumentId());
            String traceId = event.getTraceId();

            TraceLogger tracer = TraceLogger.get(IndexService.class, traceId, event.getDocumentId());

            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || docOpt.get().getDeleted()) {
                tracer.info("文档已删除，跳过处理: documentId=%s", documentId);
                return;
            }

            tracer.step("5. INDEX_START");

            docOpt.get().setStatus(KnowledgeDocument.DocumentStatus.INDEXING);
            documentRepository.save(docOpt.get());

            tracer.info("收到 Kafka 消息: topic=document-chunked");

            Map<String, Object> metadata = event.getMetadata() != null ? event.getMetadata() : new HashMap<>();
            Object chunksMinioPathValue = metadata.get("chunksMinioPath");
            if (!(chunksMinioPathValue instanceof String chunksMinioPath) || chunksMinioPath.isBlank()) {
                throw new IllegalStateException("Missing chunksMinioPath in document metadata");
            }
            tracer.info("从 MinIO 读取 chunks: path=%s", chunksMinioPath);

            final int[] successCount = {0};
            final int[] failCount = {0};
            final int[] totalCount = {0};
            final Long docId = documentId;
            final Long docKbId = Long.parseLong(event.getKbId());

            documentRepository.findById(docId).ifPresentOrElse(doc -> {
                doc.setStatus(KnowledgeDocument.DocumentStatus.INDEXING);
                documentRepository.save(doc);
                tracer.info("文档状态更新为 INDEXING: documentId=%s", docId);
            }, () -> {
            });

            tracer.step("5.1 SAVE_DOCUMENT");
            try (InputStream chunksStream = minioStorage.getObjectStream(chunksMinioPath);
                 JsonParser parser = objectMapper.getFactory().createParser(chunksStream)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("Invalid chunk payload format");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    Chunk chunk = objectMapper.readValue(parser, ChunkDTO.class).toChunk();
                    totalCount[0]++;
                    try {
                        tracer.debug("正在处理 Chunk[%d]: chunkId=%s", totalCount[0], chunk.getId());

                        tracer.debug("生成向量 embedding...");
                        float[] embedding = embeddingService.embed(chunk.getContent());
                        tracer.debug("向量生成完成: dimension=%d", embedding.length);

                        KnowledgeChunk knowledgeChunk = new KnowledgeChunk();
                        knowledgeChunk.setKbId(docKbId);
                        knowledgeChunk.setDocId(docId);
                        knowledgeChunk.setChunkIndex(chunk.getChunkIndex());
                        knowledgeChunk.setContent(chunk.getContent());
                        knowledgeChunk.setTokenCount(chunk.getTokenCount());

                        tracer.debug("保存 Chunk 到 MySQL...");
                        chunkRepository.save(knowledgeChunk);

                        tracer.debug("索引 Chunk 到 Elasticsearch...");
                        elasticsearchSearch.index(chunk);

                        tracer.debug("存储向量到 Milvus...");
                        milvusVectorStore.insert(chunk, embedding);

                        successCount[0]++;
                    } catch (Exception e) {
                        failCount[0]++;
                        tracer.error("索引 Chunk 失败: chunkId=%s, error=%s".formatted(chunk.getId(), e.getMessage()));
                    }

                    if (totalCount[0] % 10 == 0) {
                        tracer.info("索引进度: %d chunks processed", totalCount[0]);
                    }
                }
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

            tracer.stepComplete("5. INDEX_COMPLETE");
            tracer.info("文档索引完成: documentId=%s, traceId=%s, totalChunks=%d, success=%d, fail=%d, finalStatus=%s",
                    documentId, traceId, totalCount[0], successCount[0], failCount[0], finalStatus);

        } catch (Exception e) {
            log.error("Failed to index document: {}", e.getMessage(), e);
            if (documentId != null) {
                documentRepository.findById(documentId).ifPresent(doc -> {
                    doc.setStatus(KnowledgeDocument.DocumentStatus.FAILED);
                    documentRepository.save(doc);
                });
            }
        }
    }
}