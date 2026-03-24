package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Chunk;
import com.rag.domain.model.Document;
import com.rag.domain.repository.DocumentRepository;
import com.rag.domain.repository.ChunkRepository;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.infrastructure.vector.MilvusVectorStore;
import com.rag.util.TraceLogger;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);
    private static final int QUEUE_CAPACITY = 5;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final MinioStorage minioStorage;
    private final EmbeddingService embeddingService;
    private final ElasticsearchSearch elasticsearchSearch;
    private final MilvusVectorStore milvusVectorStore;
    private final ObjectMapper objectMapper;

    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public IndexService(DocumentRepository documentRepository,
                        ChunkRepository chunkRepository,
                        MinioStorage minioStorage,
                        EmbeddingService embeddingService,
                        ElasticsearchSearch elasticsearchSearch,
                        MilvusVectorStore milvusVectorStore,
                        ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.minioStorage = minioStorage;
        this.embeddingService = embeddingService;
        this.elasticsearchSearch = elasticsearchSearch;
        this.milvusVectorStore = milvusVectorStore;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        executor.submit(this::processLoop);
        log.info("IndexService started with queue capacity: {}", QUEUE_CAPACITY);
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

    @KafkaListener(topics = KafkaTopics.DOCUMENT_CHUNKED, groupId = "${spring.kafka.consumer.group-id}-index")
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
        String documentId = null;
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            documentId = event.getDocumentId();
            String traceId = event.getTraceId();

            TraceLogger tracer = TraceLogger.get(IndexService.class, traceId, documentId);

            // 检查文档是否已删除
            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || docOpt.get().isDeleted()) {
                tracer.info("文档已删除，跳过处理: documentId=%s", documentId);
                return;
            }

            tracer.step("5. INDEX_START");

            // Update status to INDEXING
            docOpt.get().setStatus(Document.DocumentStatus.INDEXING);
            documentRepository.save(docOpt.get());

            tracer.info("收到 Kafka 消息: topic=document-chunked");

            // Read chunks from MinIO
            String chunksMinioPath = (String) event.getMetadata().get("chunksMinioPath");
            tracer.info("从 MinIO 读取 chunks: path=%s", chunksMinioPath);
            byte[] chunksData = minioStorage.download(chunksMinioPath);
            @SuppressWarnings("unchecked")
            List<Chunk> chunks = objectMapper.readValue(chunksData,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Chunk.class));
            tracer.info("获取到分块数据: chunkCount=%d", chunks.size());

            // Update existing document
            tracer.step("5.1 SAVE_DOCUMENT");
            final int[] successCount = {0};
            final int[] failCount = {0};
            final String docId = documentId;
            final String docFileName = event.getFileName();
            final String docKbId = event.getKbId();

            documentRepository.findById(docId).ifPresentOrElse(doc -> {
                doc.setStatus(Document.DocumentStatus.INDEXING);
                doc.setFileName(docFileName);
                doc.setKbId(docKbId);
                documentRepository.save(doc);
                tracer.info("文档状态更新为 INDEXING: documentId=%s", docId);
            }, () -> {
                // 如果文档不存在，创建一个新的
                Document doc = new Document();
                doc.setId(docId);
                doc.setFileName(docFileName);
                doc.setKbId(docKbId);
                doc.setStatus(Document.DocumentStatus.INDEXING);
                documentRepository.save(doc);
                tracer.info("新建文档: documentId=%s", docId);
            });

            // Index chunks
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

                    successCount[0]++;

                    if ((i + 1) % 10 == 0 || i == chunks.size() - 1) {
                        tracer.info("索引进度: %d/%d chunks processed", i + 1, chunks.size());
                    }

                } catch (Exception e) {
                    failCount[0]++;
                    tracer.error("索引 Chunk 失败: chunkId=%s, error=%s".formatted(chunk.getId(), e.getMessage()));
                }
            }

            tracer.stepComplete("5.2 INDEX_CHUNKS", "success=" + successCount[0] + ", fail=" + failCount[0]);

            // Update status to INDEXED
            documentRepository.findById(documentId).ifPresent(d -> {
                d.setStatus(Document.DocumentStatus.INDEXED);
                d.setChunkCount(successCount[0]);
                d.setIndexedAt(java.time.LocalDateTime.now());
                documentRepository.save(d);
            });

            tracer.stepComplete("5. INDEX_COMPLETE");
            tracer.info("文档索引完成: documentId=%s, traceId=%s, totalChunks=%d, success=%d, fail=%d",
                    documentId, traceId, chunks.size(), successCount[0], failCount[0]);

        } catch (Exception e) {
            log.error("Failed to index document: {}", e.getMessage(), e);
            // Note: If indexing fails, the document status update should be handled by error tracking
        }
    }
}
