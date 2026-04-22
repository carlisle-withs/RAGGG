package com.rag.application.retrieval;

import com.rag.infrastructure.llm.EmbeddingService;
import com.rag.infrastructure.search.ElasticsearchSearch;
import com.rag.infrastructure.vector.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 混合检索服务 - 双路召回 + RRF 融合
 *
 * 同时执行:
 * 1. Milvus 向量检索 (语义相似度)
 * 2. Elasticsearch 全文检索 (BM25)
 *
 * 使用 RRF (Reciprocal Rank Fusion) 融合两路结果
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    /**
     * RRF 融合参数
     * k 值通常在 40-100 之间，值越大，各路结果的影响越均衡
     */
    private static final int RRF_K = 60;

    private final MilvusVectorStore milvusVectorStore;
    private final ElasticsearchSearch elasticsearchSearch;
    private final EmbeddingService embeddingService;
    private final Executor retrievalExecutor;
    private final int candidatePoolSize;

    public HybridRetrievalService(MilvusVectorStore milvusVectorStore,
                                  ElasticsearchSearch elasticsearchSearch,
                                  EmbeddingService embeddingService,
                                  @Qualifier("retrievalThreadPoolExecutor") Executor retrievalExecutor,
                                  @Value("${retrieval.candidate-pool-size:20}") int candidatePoolSize) {
        this.milvusVectorStore = milvusVectorStore;
        this.elasticsearchSearch = elasticsearchSearch;
        this.embeddingService = embeddingService;
        this.retrievalExecutor = retrievalExecutor;
        this.candidatePoolSize = candidatePoolSize;
    }

    /**
     * 混合检索入口
     *
     * @param query 查询文本
     * @param kbId 知识库 ID
     * @param topK 返回的最终结果数
     * @return 融合后的检索结果
     */
    public List<RetrievalResult> hybridSearch(String query, String kbId, int topK) {
        log.info("=== Hybrid Search Start ===");
        log.info("Query: {}, KB: {}, TopK: {}", query, kbId, topK);

        try {
            // 1. 并行执行双路检索
            long startTime = System.currentTimeMillis();
            CompletableFuture<List<MilvusVectorStore.SearchResult>> milvusFuture =
                    CompletableFuture.supplyAsync(() -> {
                        log.info("Milvus vector search starting...");
                        float[] queryEmbedding = embeddingService.embed(query);
                        List<MilvusVectorStore.SearchResult> results =
                                milvusVectorStore.search(queryEmbedding, kbId, candidatePoolSize);
                        log.info("Milvus returned {} results", results.size());
                        return results;
                    }, retrievalExecutor);

            CompletableFuture<List<ElasticsearchSearch.SearchResult>> esFuture =
                    CompletableFuture.supplyAsync(() -> {
                        log.info("Elasticsearch text search starting...");
                        List<ElasticsearchSearch.SearchResult> results =
                                elasticsearchSearch.search(query, kbId, candidatePoolSize);
                        log.info("ES returned {} results", results.size());
                        return results;
                    }, retrievalExecutor);

            // 等待两路完成
            CompletableFuture.allOf(milvusFuture, esFuture).join();
            long retrievalTime = System.currentTimeMillis() - startTime;

            List<MilvusVectorStore.SearchResult> milvusResults = milvusFuture.get();
            List<ElasticsearchSearch.SearchResult> esResults = esFuture.get();

            log.info("Dual retrieval completed in {}ms", retrievalTime);

            // 2. RRF 融合
            List<RetrievalResult> fusedResults = rrfFusion(milvusResults, esResults, topK);

            log.info("RRF fusion completed, final results: {}", fusedResults.size());
            log.info("=== Hybrid Search End ===");

            return fusedResults;

        } catch (Exception e) {
            log.error("Hybrid search failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合算法
     *
     * Score(d) = Σ 1/(k + rank_i(d))
     *
     * 其中:
     * - k = 融合参数 (默认 60)
     * - rank_i(d) = 文档 d 在第 i 路检索中的排名
     */
    private List<RetrievalResult> rrfFusion(
            List<MilvusVectorStore.SearchResult> milvusResults,
            List<ElasticsearchSearch.SearchResult> esResults,
            int topK) {

        Map<String, FusionDoc> fusionMap = new HashMap<>();

        // 处理 Milvus 结果
        for (int rank = 0; rank < milvusResults.size(); rank++) {
            MilvusVectorStore.SearchResult r = milvusResults.get(rank);
            String chunkId = r.getChunkId();
            double rrfScore = 1.0 / (RRF_K + rank + 1); // rank 从 0 开始
            double vectorScore = r.getScore();

            FusionDoc doc = fusionMap.computeIfAbsent(chunkId, id -> new FusionDoc(chunkId, r.getContent()));
            doc.addScore("milvus", rrfScore, vectorScore);
        }

        // 处理 ES 结果
        for (int rank = 0; rank < esResults.size(); rank++) {
            ElasticsearchSearch.SearchResult r = esResults.get(rank);
            String chunkId = r.getId();
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            double textScore = r.getScore();

            FusionDoc doc = fusionMap.computeIfAbsent(chunkId, id -> new FusionDoc(chunkId, r.getContent()));
            doc.addScore("es", rrfScore, textScore);
        }

        // 按 RRF 总分排序，返回全部（由调用方限制）
        return fusionMap.values().stream()
                .sorted(Comparator.comparingDouble(FusionDoc::getRrfScore).reversed())
                .map(doc -> new RetrievalResult(
                        doc.chunkId,
                        doc.content,
                        doc.getRrfScore(),
                        doc.getRrfScore()  // relevance 与 score 相同
                ))
                .collect(Collectors.toList());
    }

    /**
     * 融合文档结构
     */
    private static class FusionDoc {
        String chunkId;
        String content;
        double milvusRrfScore = 0;
        double esRrfScore = 0;
        double milvusVectorScore = 0;
        double esTextScore = 0;

        FusionDoc(String chunkId, String content) {
            this.chunkId = chunkId;
            this.content = content;
        }

        void addScore(String source, double rrfScore, double rawScore) {
            if ("milvus".equals(source)) {
                this.milvusRrfScore = rrfScore;
                this.milvusVectorScore = rawScore;
            } else {
                this.esRrfScore = rrfScore;
                this.esTextScore = rawScore;
            }
        }

        double getRrfScore() {
            return milvusRrfScore + esRrfScore;
        }
    }

    /**
     * 检索结果 record
     */
    public record RetrievalResult(String chunkId, String content, double score, double relevance) {}
}
