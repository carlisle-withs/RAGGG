package com.rag.infrastructure.llm;

import com.rag.application.retrieval.HybridRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 轻量级重排服务
 *
 * 使用 Embedding 模型计算 query 与候选文档的语义相似度，
 * 对候选文档进行精排。
 *
 * 注意：标准的 CrossEncoder 使用交叉编码器同时处理 (query, document) 对，
 * 这里使用 Bi-Encoder 方式近似实现：通过计算余弦相似度进行重排。
 * 如果有条件，可以使用专门的 CrossEncoder 模型（如 BAAI/bge-reranker-v2-m3）
 */
@Component
public class CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    private final EmbeddingService embeddingService;

    public CrossEncoderReranker(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * 对候选文档进行重排
     *
     * @param candidates 候选检索结果
     * @param query 查询文本
     * @param topK 返回的结果数
     * @return 重排后的结果
     */
    public List<HybridRetrievalService.RetrievalResult> rerank(
            List<HybridRetrievalService.RetrievalResult> candidates,
            String query,
            int topK) {

        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        log.info("=== Reranking Start ===");
        log.info("Candidates: {}, Query: {}, TopK: {}", candidates.size(), query, topK);

        try {
            // 1. 生成查询向量
            float[] queryEmbedding = embeddingService.embed(query);

            // 2. 计算每个候选与 query 的相似度
            List<ScoredResult> scored = new ArrayList<>();
            for (HybridRetrievalService.RetrievalResult candidate : candidates) {
                float[] docEmbedding = embeddingService.embed(candidate.content());
                double similarity = cosineSimilarity(queryEmbedding, docEmbedding);
                scored.add(new ScoredResult(candidate, similarity));
                log.debug("Chunk {} similarity: {}", candidate.chunkId(), similarity);
            }

            // 3. 按相似度降序排序
            scored.sort(Comparator.comparingDouble(ScoredResult::similarity).reversed());

            // 4. 返回 topK
            List<HybridRetrievalService.RetrievalResult> reranked = scored.stream()
                    .limit(topK)
                    .map(sr -> new HybridRetrievalService.RetrievalResult(
                            sr.result().chunkId(),
                            sr.result().content(),
                            sr.result().score(),  // 保留原始 RRF score
                            sr.similarity()       // 使用新的相似度作为 relevance
                    ))
                    .toList();

            log.info("=== Reranking End ===");
            return reranked;

        } catch (Exception e) {
            log.error("Reranking failed, returning original candidates", e);
            return candidates;
        }
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (normA * normB);
    }

    /**
     * 带分数的结果
     */
    private record ScoredResult(HybridRetrievalService.RetrievalResult result, double similarity) {}
}
