package com.rag.application.retrieval;

import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RetrievalApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalApplicationService.class);

    private final EmbeddingService embeddingService;
    private final MilvusVectorStore milvusVectorStore;
    private final ElasticsearchSearch elasticsearchSearch;

    public RetrievalApplicationService(EmbeddingService embeddingService,
                                       MilvusVectorStore milvusVectorStore,
                                       ElasticsearchSearch elasticsearchSearch) {
        this.embeddingService = embeddingService;
        this.milvusVectorStore = milvusVectorStore;
        this.elasticsearchSearch = elasticsearchSearch;
    }

    public List<RetrievalResult> search(String query, String kbId, int topK) {
        try {
            // Generate query embedding
            float[] queryEmbedding = embeddingService.embed(query);

            // Search in Milvus
            List<MilvusVectorStore.SearchResult> results = milvusVectorStore.search(queryEmbedding, kbId, topK);

            // Convert to retrieval results
            return results.stream()
                    .map(r -> new RetrievalResult(r.getChunkId(), r.getContent(), r.getScore(), r.getScore()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Search failed", e);
            return List.of();
        }
    }

    public record RetrievalResult(String chunkId, String content, double score, double relevance) {}
}
