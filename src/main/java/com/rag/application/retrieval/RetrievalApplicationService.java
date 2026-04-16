package com.rag.application.retrieval;

import com.rag.application.retrieval.HybridRetrievalService.RetrievalResult;
import com.rag.infrastructure.llm.CrossEncoderReranker;
import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.llm.SiliconFlowReranker;
import com.rag.infrastructure.observability.RAGObservabilityService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import io.opentelemetry.api.trace.SpanKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RetrievalApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalApplicationService.class);

    private final EmbeddingService embeddingService;
    private final MilvusVectorStore milvusVectorStore;
    private final ElasticsearchSearch elasticsearchSearch;
    private final HybridRetrievalService hybridRetrievalService;
    private final HierarchicalRetrievalService hierarchicalRetrievalService;
    private final CrossEncoderReranker reranker;
    private final SiliconFlowReranker siliconFlowReranker;
    private final RAGObservabilityService observability;

    @Value("${retrieval.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${retrieval.rerank.provider:siliconflow}")
    private String rerankProvider;

    @Value("${retrieval.strategy:hybrid}")
    private String retrievalStrategy;

    @Value("${retrieval.initial-topk:20}")
    private int initialTopK;

    @Value("${retrieval.final-topk:5}")
    private int finalTopK;

    public RetrievalApplicationService(
            EmbeddingService embeddingService,
            MilvusVectorStore milvusVectorStore,
            ElasticsearchSearch elasticsearchSearch,
            HybridRetrievalService hybridRetrievalService,
            @Autowired(required = false) HierarchicalRetrievalService hierarchicalRetrievalService,
            CrossEncoderReranker reranker,
            @Autowired(required = false) SiliconFlowReranker siliconFlowReranker,
            @Autowired(required = false) RAGObservabilityService observability) {
        this.embeddingService = embeddingService;
        this.milvusVectorStore = milvusVectorStore;
        this.elasticsearchSearch = elasticsearchSearch;
        this.hybridRetrievalService = hybridRetrievalService;
        this.hierarchicalRetrievalService = hierarchicalRetrievalService;
        this.reranker = reranker;
        this.siliconFlowReranker = siliconFlowReranker;
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
     * 混合检索 + 重排（主要检索方法）
     *
     * P1 增强：
     * 1. 支持 hierarchical 检索策略（Sentence Window + Auto-Merging）
     * 2. 支持 True CrossEncoder 重排（SiliconFlow API）
     * 3. 全链路 Span + Metrics 可观测性
     */
    public List<RetrievalResult> hybridSearch(
            String query, String kbId, String conversationId, String intent) {

        long totalStart = System.currentTimeMillis();
        log.info("=== {} Search with Reranking ===", retrievalStrategy);

        // Step 1: 选择检索策略并获取候选
        List<RetrievalResult> candidates;
        String strategyUsed = retrievalStrategy;

        if ("hierarchical".equals(retrievalStrategy) && hierarchicalRetrievalService != null) {
            List<HierarchicalRetrievalService.RetrievalResult> rawCandidates =
                    hierarchicalRetrievalService.retrieve(query, kbId, initialTopK);
            candidates = rawCandidates.stream()
                    .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                    .collect(Collectors.toList());
        } else {
            // 默认: 双路召回 + RRF 融合
            List<HybridRetrievalService.RetrievalResult> rawCandidates =
                    hybridRetrievalService.hybridSearch(query, kbId, initialTopK);
            candidates = rawCandidates.stream()
                    .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            log.info("No results from retrieval");
            recordRetrievalMetrics(query, kbId, conversationId, intent,
                    strategyUsed, 0, 0, totalStart);
            return Collections.emptyList();
        }

        // Step 2: CrossEncoder 重排
        long rerankStart = System.currentTimeMillis();
        boolean rerankApplied = false;
        if (rerankEnabled && candidates.size() > finalTopK) {
            rerankApplied = true;
            candidates = doRerank(query, candidates);
        }
        long rerankLatency = System.currentTimeMillis() - rerankStart;

        List<RetrievalResult> topResults = candidates.stream()
                .limit(finalTopK)
                .collect(Collectors.toList());

        // 记录可观测性数据
        long totalLatency = System.currentTimeMillis() - totalStart;
        if (observability != null) {
            RAGObservabilityService.RetrievalContext ctx =
                    RAGObservabilityService.RetrievalContext.builder()
                            .queryLength(query.length())
                            .kbId(kbId)
                            .conversationId(conversationId != null ? conversationId : "N/A")
                            .intent(intent != null ? intent : "UNKNOWN")
                            .retrievalStrategy(strategyUsed)
                            .candidatesCount(candidates.size())
                            .finalCount(topResults.size())
                            .rerankEnabled(rerankApplied)
                            .totalLatencyMs(totalLatency)
                            .milvusLatencyMs(0)
                            .esLatencyMs(0)
                            .rerankLatencyMs(rerankLatency)
                            .build();
            observability.recordRetrieval(ctx);
        }

        log.info("=== Retrieval Complete: {} results in {}ms ===", topResults.size(), totalLatency);
        return topResults;
    }

    /**
     * 重载方法（兼容旧调用）
     */
    public List<RetrievalResult> hybridSearch(String query, String kbId, int topK) {
        return hybridSearch(query, kbId, null, null);
    }

    /**
     * 执行重排
     */
    @SuppressWarnings("unchecked")
    private List<RetrievalResult> doRerank(String query, List<RetrievalResult> candidates) {
        long rerankStart = System.currentTimeMillis();
        boolean success = false;

        try {
            if ("siliconflow".equals(rerankProvider) && siliconFlowReranker != null) {
                // P1: True CrossEncoder（SiliconFlow API）
                List<HybridRetrievalService.RetrievalResult> asHybrid = candidates.stream()
                        .map(r -> new HybridRetrievalService.RetrievalResult(
                                r.chunkId(), r.content(), r.score(), r.relevance()))
                        .collect(Collectors.toList());

                List<HybridRetrievalService.RetrievalResult> reranked =
                        siliconFlowReranker.rerank(query, asHybrid, candidates.size());

                success = true;
                return reranked.stream()
                        .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                        .collect(Collectors.toList());
            } else {
                // 回退: Bi-Encoder 近似
                List<HybridRetrievalService.RetrievalResult> asHybrid = candidates.stream()
                        .map(r -> new HybridRetrievalService.RetrievalResult(
                                r.chunkId(), r.content(), r.score(), r.relevance()))
                        .collect(Collectors.toList());

                List<HybridRetrievalService.RetrievalResult> reranked =
                        reranker.rerank(asHybrid, query, candidates.size());

                success = true;
                return reranked.stream()
                        .map(r -> new RetrievalResult(r.chunkId(), r.content(), r.score(), r.relevance()))
                        .collect(Collectors.toList());
            }
        } finally {
            long latency = System.currentTimeMillis() - rerankStart;
            if (observability != null) {
                observability.recordRerankLatency(latency, candidates.size(), success);
            }
        }
    }

    private void recordRetrievalMetrics(String query, String kbId, String conversationId,
            String intent, String strategy, int candidatesCount, int finalCount, long totalStart) {
        if (observability != null) {
            RAGObservabilityService.RetrievalContext ctx =
                    RAGObservabilityService.RetrievalContext.builder()
                            .queryLength(query.length())
                            .kbId(kbId)
                            .conversationId(conversationId != null ? conversationId : "N/A")
                            .intent(intent != null ? intent : "UNKNOWN")
                            .retrievalStrategy(strategy)
                            .candidatesCount(candidatesCount)
                            .finalCount(finalCount)
                            .rerankEnabled(false)
                            .totalLatencyMs(System.currentTimeMillis() - totalStart)
                            .milvusLatencyMs(0)
                            .esLatencyMs(0)
                            .rerankLatencyMs(0)
                            .build();
            observability.recordRetrieval(ctx);
        }
    }

    /**
     * 检索结果 record
     */
    public record RetrievalResult(String chunkId, String content, double score, double relevance) {}
}
