package com.rag.application.retrieval;

import com.rag.infrastructure.llm.CrossEncoderReranker;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.llm.SiliconFlowReranker;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final CrossEncoderReranker crossEncoderReranker;
    private final SiliconFlowReranker siliconFlowReranker;

    @Value("${rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rerank.provider:siliconflow}")
    private String rerankProvider;

    @Value("${retrieval.candidate-pool-size:20}")
    private int candidatePoolSize;

    @Autowired
    private RetrievalMetrics retrievalMetrics;

    public RetrievalApplicationService(EmbeddingService embeddingService,
                                       MilvusVectorStore milvusVectorStore,
                                       ElasticsearchSearch elasticsearchSearch,
                                       HybridRetrievalService hybridRetrievalService,
                                       CrossEncoderReranker crossEncoderReranker,
                                       @Autowired(required = false) SiliconFlowReranker siliconFlowReranker) {
        this.embeddingService = embeddingService;
        this.milvusVectorStore = milvusVectorStore;
        this.elasticsearchSearch = elasticsearchSearch;
        this.hybridRetrievalService = hybridRetrievalService;
        this.crossEncoderReranker = crossEncoderReranker;
        this.siliconFlowReranker = siliconFlowReranker;
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
     * 向量检索 + CrossEncoder 精排
     *
     * 流程: Milvus TopN (默认 20) -> CrossEncoder rerank -> TopK
     */
    public List<RetrievalResult> searchWithRerank(String query, String kbId, int topK) {
        log.info("=== Vector Search + CrossEncoder Rerank ===");
        int fetchSize = candidatePoolSize;  // configurable candidate pool size

        try {
            float[] queryEmbedding = embeddingService.embed(query);
            List<MilvusVectorStore.SearchResult> rawResults =
                    milvusVectorStore.search(queryEmbedding, kbId, fetchSize);

            if (rawResults.isEmpty()) {
                return List.of();
            }

            List<HybridRetrievalService.RetrievalResult> candidates = rawResults.stream()
                    .map(r -> new HybridRetrievalService.RetrievalResult(
                            r.getChunkId(), r.getContent(), r.getScore(), r.getScore()))
                    .toList();

            // CrossEncoder 精排
            List<HybridRetrievalService.RetrievalResult> reranked;
            long rerankStart = System.currentTimeMillis();
            if ("siliconflow".equals(rerankProvider) && siliconFlowReranker != null) {
                reranked = siliconFlowReranker.rerank(query, candidates, topK);
            } else {
                reranked = crossEncoderReranker.rerank(candidates, query, topK);
            }
            retrievalMetrics.recordRerankLatency("vector+rerank", System.currentTimeMillis() - rerankStart);

            return reranked.stream()
                    .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Vector + Rerank failed", e);
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
    public List<RetrievalResult> hybridSearch(String query, String kbId, int topK, boolean doRerank) {
        log.info("=== Hybrid Search with Reranking ===");

        // 1. 双路召回 + RRF 融合（返回全部结果，不限制）
        List<HybridRetrievalService.RetrievalResult> fusedResults =
                hybridRetrievalService.hybridSearch(query, kbId, topK);

        if (fusedResults.isEmpty()) {
            log.info("No results from hybrid search");
            return List.of();
        }

        // 2. 如果启用重排，则进行 CrossEncoder 重排
        if (doRerank) {
            log.info("Reranking enabled, fused={} candidates, topK={}", fusedResults.size(), topK);

            // 全部 RRF 结果送入 reranker（RRF 只是粗排，不应二次截断）
            List<HybridRetrievalService.RetrievalResult> rerankedResults;
            long rerankStart = System.currentTimeMillis();
            if ("siliconflow".equals(rerankProvider) && siliconFlowReranker != null) {
                rerankedResults = siliconFlowReranker.rerank(query, fusedResults, topK);
            } else {
                rerankedResults = crossEncoderReranker.rerank(fusedResults, query, topK);
            }
            retrievalMetrics.recordRerankLatency("hybrid+rerank", System.currentTimeMillis() - rerankStart);

            return rerankedResults.stream()
                    .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                    .collect(Collectors.toList());
        }

        // 3. 直接返回 RRF 融合结果（取 topK）
        return fusedResults.stream()
                .limit(topK)
                .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                .collect(Collectors.toList());
    }

    /**
     * 检索结果 record
     */
    public record RetrievalResult(String chunkId, String content, double score, double relevance) {}
}
