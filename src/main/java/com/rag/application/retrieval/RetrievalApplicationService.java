package com.rag.application.retrieval;

import com.rag.infrastructure.llm.CrossEncoderReranker;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RetrievalApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalApplicationService.class);

    private final EmbeddingService embeddingService;
    private final MilvusVectorStore milvusVectorStore;
    private final ElasticsearchSearch elasticsearchSearch;
    private final HybridRetrievalService hybridRetrievalService;
    private final CrossEncoderReranker reranker;

    @Value("${retrieval.rerank.enabled:true}")
    private boolean rerankEnabled;

    public RetrievalApplicationService(EmbeddingService embeddingService,
                                       MilvusVectorStore milvusVectorStore,
                                       ElasticsearchSearch elasticsearchSearch,
                                       HybridRetrievalService hybridRetrievalService,
                                       CrossEncoderReranker reranker) {
        this.embeddingService = embeddingService;
        this.milvusVectorStore = milvusVectorStore;
        this.elasticsearchSearch = elasticsearchSearch;
        this.hybridRetrievalService = hybridRetrievalService;
        this.reranker = reranker;
    }

    /**
     * 原始单路向量检索（保持向后兼容）
     */
    public List<RetrievalResult> search(String query, String kbId, int topK) {
        try {
            log.info("=== Single-path Vector Search ===");
            float[] queryEmbedding = embeddingService.embed(query);
            List<MilvusVectorStore.SearchResult> results = milvusVectorStore.search(queryEmbedding, kbId, topK);

            return results.stream()
                    .map(r -> new RetrievalResult(r.getChunkId(), r.getContent(), r.getScore(), r.getScore()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Search failed", e);
            return List.of();
        }
    }

    /**
     * 混合检索 + 重排（主要检索方法）
     *
     * 流程: 双路召回 -> RRF 融合 -> CrossEncoder 重排
     *
     * @param query 查询文本
     * @param kbId 知识库 ID
     * @param topK 返回结果数
     * @return 检索结果
     */
    public List<RetrievalResult> hybridSearch(String query, String kbId, int topK) {
        log.info("=== Hybrid Search with Reranking ===");

        // 1. 双路召回 + RRF 融合
        List<HybridRetrievalService.RetrievalResult> fusedResults =
                hybridRetrievalService.hybridSearch(query, kbId, topK * 2);

        if (fusedResults.isEmpty()) {
            log.info("No results from hybrid search");
            return List.of();
        }

        // 2. 如果启用重排，则进行 CrossEncoder 重排
        if (rerankEnabled) {
            log.info("Reranking enabled, applying CrossEncoder reranking");
            List<HybridRetrievalService.RetrievalResult> rerankedResults =
                    reranker.rerank(fusedResults, query, topK);

            return rerankedResults.stream()
                    .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                    .collect(Collectors.toList());
        }

        // 3. 直接返回 RRF 融合结果
        return fusedResults.stream()
                .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                .collect(Collectors.toList());
    }

    /**
     * 检索结果 record
     */
    public record RetrievalResult(String chunkId, String content, double score, double relevance) {}
}
