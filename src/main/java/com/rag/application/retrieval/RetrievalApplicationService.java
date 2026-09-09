package com.rag.application.retrieval;

import com.rag.application.retrieval.HybridRetrievalService.RetrievalResult;
import com.rag.infrastructure.llm.CrossEncoderReranker;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.llm.SiliconFlowReranker;
import com.rag.infrastructure.observability.RAGObservabilityService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索门面服务。
 *
 * master 侧演进：双路召回 + RRF 融合 + SiliconFlow/CrossEncoder 双 reranker（rerank.provider 切换）
 * + Prometheus 指标；P1 分支并入：hierarchical 检索策略（Sentence Window + Auto-Merging）路由
 * + OTel 全链路可观测性（otel.enabled 开启时生效）。
 */
@Service
public class RetrievalApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalApplicationService.class);

    private final EmbeddingService embeddingService;
    private final MilvusVectorStore milvusVectorStore;
    private final ElasticsearchSearch elasticsearchSearch;
    private final HybridRetrievalService hybridRetrievalService;
    private final CrossEncoderReranker crossEncoderReranker;
    private final SiliconFlowReranker siliconFlowReranker;
    private final HierarchicalRetrievalService hierarchicalRetrievalService;
    private final RAGObservabilityService observability;

    @Value("${retrieval.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${retrieval.rerank.provider:siliconflow}")
    private String rerankProvider;

    @Value("${retrieval.candidate-pool-size:20}")
    private int candidatePoolSize;

    @Value("${retrieval.strategy:hybrid}")
    private String retrievalStrategy;

    @Autowired
    private RetrievalMetrics retrievalMetrics;

    public RetrievalApplicationService(EmbeddingService embeddingService,
                                       MilvusVectorStore milvusVectorStore,
                                       ElasticsearchSearch elasticsearchSearch,
                                       HybridRetrievalService hybridRetrievalService,
                                       CrossEncoderReranker crossEncoderReranker,
                                       @Autowired(required = false) SiliconFlowReranker siliconFlowReranker,
                                       @Autowired(required = false) HierarchicalRetrievalService hierarchicalRetrievalService,
                                       @Autowired(required = false) RAGObservabilityService observability) {
        this.embeddingService = embeddingService;
        this.milvusVectorStore = milvusVectorStore;
        this.elasticsearchSearch = elasticsearchSearch;
        this.hybridRetrievalService = hybridRetrievalService;
        this.crossEncoderReranker = crossEncoderReranker;
        this.siliconFlowReranker = siliconFlowReranker;
        this.hierarchicalRetrievalService = hierarchicalRetrievalService;
        this.observability = observability;
    }

    /**
     * 原始单路向量检索（保持向后兼容）
     */
    public List<RetrievalResult> search(String query, String kbId, int topK) {
        try {
            log.info("=== Single-path Vector Search ===");
            float[] queryEmbedding = embeddingService.embed(query);
            List<MilvusVectorStore.SearchResult> results =
                    milvusVectorStore.search(queryEmbedding, kbId, topK);

            return results.stream()
                    .map(r -> new RetrievalResult(r.getChunkId(), r.getContent(), r.getScore(), r.getScore()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Search failed", e);
            return Collections.emptyList();
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
     * 流程: 策略路由（hybrid 双路召回 + RRF / hierarchical Sentence Window + Auto-Merging）
     *       -> CrossEncoder 精排（rerank.provider 切换 SiliconFlow API / 本地 Bi-Encoder）
     *       -> TopK；全链路 Prometheus 指标 + OTel 埋点（otel.enabled 时）
     */
    public List<RetrievalResult> hybridSearch(String query, String kbId, int topK, boolean doRerank) {
        long totalStart = System.currentTimeMillis();
        log.info("=== Hybrid Search === strategy={} topK={} rerank={}", retrievalStrategy, topK, doRerank);

        // Step 1: 策略路由获取候选
        String strategyUsed = retrievalStrategy;
        List<RetrievalResult> candidates;
        if ("hierarchical".equals(retrievalStrategy) && hierarchicalRetrievalService != null) {
            candidates = hierarchicalRetrievalService.retrieve(query, kbId, candidatePoolSize).stream()
                    .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                    .collect(Collectors.toList());
        } else {
            strategyUsed = "hybrid";
            // 双路召回 + RRF 融合（返回全部结果，不限制）
            candidates = hybridRetrievalService.hybridSearch(query, kbId, candidatePoolSize).stream()
                    .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            log.info("No results from retrieval");
            recordRetrievalMetrics(query, kbId, strategyUsed, 0, 0, false, 0, totalStart);
            return Collections.emptyList();
        }

        // Step 2: CrossEncoder 重排（RRF 只是粗排，全部候选送入，不二次截断）
        long rerankStart = System.currentTimeMillis();
        boolean rerankApplied = false;
        if (doRerank && rerankEnabled) {
            candidates = rerankCandidates(query, candidates);
            rerankApplied = true;
        }
        long rerankLatency = System.currentTimeMillis() - rerankStart;

        List<RetrievalResult> topResults = candidates.stream()
                .limit(topK)
                .collect(Collectors.toList());

        recordRetrievalMetrics(query, kbId, strategyUsed, candidates.size(), topResults.size(),
                rerankApplied, rerankLatency, totalStart);

        return topResults;
    }

    /**
     * 对候选执行精排，provider 由 rerank.provider 决定（siliconflow / 本地 Bi-Encoder 回退）
     */
    private List<RetrievalResult> rerankCandidates(String query, List<RetrievalResult> candidates) {
        long rerankStart = System.currentTimeMillis();
        boolean success = false;
        List<HybridRetrievalService.RetrievalResult> asHybrid = candidates.stream()
                .map(r -> new HybridRetrievalService.RetrievalResult(
                        r.chunkId(), r.content(), r.score(), r.relevance()))
                .collect(Collectors.toList());

        List<HybridRetrievalService.RetrievalResult> reranked;
        try {
            if ("siliconflow".equals(rerankProvider) && siliconFlowReranker != null) {
                reranked = siliconFlowReranker.rerank(query, asHybrid, candidates.size());
            } else {
                reranked = crossEncoderReranker.rerank(asHybrid, query, candidates.size());
            }
            success = true;
        } finally {
            long latency = System.currentTimeMillis() - rerankStart;
            retrievalMetrics.recordRerankLatency("hybrid+rerank", latency);
            if (observability != null) {
                observability.recordRerankLatency(latency, candidates.size(), success);
            }
        }

        return reranked.stream()
                .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                .collect(Collectors.toList());
    }

    private void recordRetrievalMetrics(String query, String kbId, String strategy,
            int candidatesCount, int finalCount, boolean rerankApplied, long rerankLatencyMs, long totalStart) {
        if (observability == null) {
            return;
        }
        RAGObservabilityService.RetrievalContext ctx =
                RAGObservabilityService.RetrievalContext.builder()
                        .queryLength(query.length())
                        .kbId(kbId)
                        .conversationId("N/A")
                        .intent("UNKNOWN")
                        .retrievalStrategy(strategy)
                        .candidatesCount(candidatesCount)
                        .finalCount(finalCount)
                        .rerankEnabled(rerankApplied)
                        .totalLatencyMs(System.currentTimeMillis() - totalStart)
                        .milvusLatencyMs(0)
                        .esLatencyMs(0)
                        .rerankLatencyMs(rerankLatencyMs)
                        .build();
        observability.recordRetrieval(ctx);
    }

    /**
     * 检索结果 record
     */
    public record RetrievalResult(String chunkId, String content, double score, double relevance) {}
}
