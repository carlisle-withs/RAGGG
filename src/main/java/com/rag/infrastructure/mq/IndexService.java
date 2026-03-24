package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.domain.model.Document;
import com.rag.domain.repository.DocumentRepository;
import com.rag.domain.repository.ChunkRepository;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import com.rag.util.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ElasticsearchSearch elasticsearchSearch;
    private final MilvusVectorStore milvusVectorStore;
    private final ObjectMapper objectMapper;

    public IndexService(DocumentRepository documentRepository,
                        ChunkRepository chunkRepository,
                        EmbeddingService embeddingService,
                        ElasticsearchSearch elasticsearchSearch,
                        MilvusVectorStore milvusVectorStore,
                        ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.elasticsearchSearch = elasticsearchSearch;
        this.milvusVectorStore = milvusVectorStore;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_CHUNKED, groupId = "${spring.kafka.consumer.group-id}-index")
    @Transactional
    public void consume(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            String traceId = event.getTraceId();
            String documentId = event.getDocumentId();

            TraceLogger tracer = TraceLogger.get(IndexService.class, traceId, documentId);

            tracer.step("5. INDEX_START");
            tracer.info("收到 Kafka 消息: topic=document-chunked");

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = objectMapper.convertValue(event.getMetadata().get("chunks"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Chunk.class));

            tracer.info("获取到分块数据: chunkCount=%d", chunks.size());

            // Save document metadata
            tracer.step("5.1 SAVE_DOCUMENT");
            Document doc = new Document();
            doc.setId(event.getDocumentId());
            doc.setFileName(event.getFileName());
            doc.setKbId(event.getKbId());
            doc.setStatus(Document.DocumentStatus.INDEXED);
            documentRepository.save(doc);
            tracer.info("文档元数据保存到 MySQL: documentId=%s, fileName=%s", doc.getId(), doc.getFileName());

            // Index chunks
            int successCount = 0;
            int failCount = 0;

            tracer.step("5.2 INDEX_CHUNKS");

            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                try {
                    tracer.debug("正在处理 Chunk[%d/%d]: chunkId=%s", i + 1, chunks.size(), chunk.getId());

                    // Generate embedding
                    tracer.debug("生成向量 embedding...");
                    float[] embedding = embeddingService.embed(chunk.getContent());
                    tracer.debug("向量生成完成: dimension=%d", embedding.length);

                    // Save chunk to MySQL
                    tracer.debug("保存 Chunk 到 MySQL...");
                    chunkRepository.save(chunk);

                    // Index raw text in Elasticsearch for keyword search
                    tracer.debug("索引 Chunk 到 Elasticsearch...");
                    elasticsearchSearch.index(chunk);

                    // Store vector in Milvus
                    tracer.debug("存储向量到 Milvus...");
                    milvusVectorStore.insert(chunk, embedding);

                    successCount++;

                    if ((i + 1) % 10 == 0 || i == chunks.size() - 1) {
                        tracer.info("索引进度: %d/%d chunks processed", i + 1, chunks.size());
                    }

                } catch (Exception e) {
                    failCount++;
                    tracer.error("索引 Chunk 失败: chunkId=%s, error=%s".formatted(chunk.getId(), e.getMessage()));
                }
            }

            tracer.stepComplete("5.2 INDEX_CHUNKS", "success=" + successCount + ", fail=" + failCount);

            tracer.stepComplete("5. INDEX_COMPLETE");
            tracer.info("文档索引完成: documentId=%s, traceId=%s, totalChunks=%d, success=%d, fail=%d",
                    documentId, traceId, chunks.size(), successCount, failCount);

        } catch (Exception e) {
            log.error("Failed to index document: {}", e.getMessage(), e);
        }
    }
}
