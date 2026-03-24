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
            log.info("Index service received: {}", event.getDocumentId());

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = objectMapper.convertValue(event.getMetadata().get("chunks"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Chunk.class));

            // Save document metadata
            Document doc = new Document();
            doc.setId(event.getDocumentId());
            doc.setFileName(event.getFileName());
            doc.setKbId(event.getKbId());
            doc.setStatus(Document.DocumentStatus.INDEXED);
            documentRepository.save(doc);

            // Index chunks
            for (Chunk chunk : chunks) {
                // Generate embedding
                float[] embedding = embeddingService.embed(chunk.getContent());

                // Save chunk to MySQL
                chunkRepository.save(chunk);

                // Index raw text in Elasticsearch for keyword search
                elasticsearchSearch.index(chunk);

                // Store vector in Milvus
                milvusVectorStore.insert(chunk, embedding);
            }

            log.info("Document indexed successfully: {} ({} chunks)", event.getDocumentId(), chunks.size());

        } catch (Exception e) {
            log.error("Failed to index document", e);
        }
    }
}
