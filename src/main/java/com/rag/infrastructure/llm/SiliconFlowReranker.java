package com.rag.infrastructure.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.application.retrieval.HybridRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * True CrossEncoder Reranker
 *
 * 通过 SiliconFlow API 调用 BAAI/bge-reranker-v2-m3 模型，
 * 对候选文档进行精确的 query-document 交互式相关性评分。
 *
 * 与 Bi-Encoder 的本质区别：
 * - Bi-Encoder: Query 和 Doc 分别独立编码 → cosine_similarity
 * - True CrossEncoder: [CLS] Query [SEP] Doc [SEP] → Transformer → Relevance Score
 *   能够捕捉细粒度的 token 级别交互
 */
@Component
public class SiliconFlowReranker {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowReranker.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final boolean enabled;
    private final String model;
    private final String baseUrl;

    public SiliconFlowReranker(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            EmbeddingService embeddingService,
            @Value("${reranker.siliconflow.enabled:false}") boolean enabled,
            @Value("${reranker.siliconflow.model:BAAI/bge-reranker-v2-m3}") String model,
            @Value("${reranker.siliconflow.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
            @Value("${reranker.siliconflow.api-key:}") String apiKey) {

        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.enabled = enabled;
        this.model = model;
        this.baseUrl = baseUrl;

        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 对候选文档进行 True CrossEncoder 重排
     *
     * @param query 用户查询
     * @param candidates RRF 融合后的候选结果
     * @param topK 返回前 K 个
     * @return 重排后的结果列表（按 CrossEncoder 分数降序）
     */
    public List<HybridRetrievalService.RetrievalResult> rerank(
            String query,
            List<HybridRetrievalService.RetrievalResult> candidates,
            int topK) {

        if (!enabled) {
            log.debug("SiliconFlow Reranker is disabled, skipping");
            return candidates;
        }

        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        if (candidates.size() <= 2) {
            log.debug("Candidate count ({}) <= 2, skipping rerank", candidates.size());
            return candidates;
        }

        long startTime = System.currentTimeMillis();
        log.info("[CrossEncoder] Starting rerank for {} candidates, query: {}",
            candidates.size(), truncate(query, 50));

        try {
            // 提取文档内容列表
            List<String> documents = candidates.stream()
                    .map(HybridRetrievalService.RetrievalResult::content)
                    .collect(Collectors.toList());

            // 调用 SiliconFlow Rerank API
            List<RerankScore> scores = callRerankAPI(query, documents);

            if (scores.isEmpty()) {
                log.warn("[CrossEncoder] API returned empty scores, fallback to candidates");
                return candidates;
            }

            // 按 CrossEncoder 分数降序排列
            final Map<Integer, RerankScore> scoreMap = new HashMap<>();
            for (int i = 0; i < scores.size(); i++) {
                scoreMap.put(i, scores.get(i));
            }

            // 构建原始索引到分数的映射（按原始顺序保留 chunkId）
            List<HybridRetrievalService.RetrievalResult> reranked = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                RerankScore score = scoreMap.get(i);
                if (score != null) {
                    reranked.add(new HybridRetrievalService.RetrievalResult(
                            candidates.get(i).chunkId(),
                            candidates.get(i).content(),
                            score.score(),      // 使用 CrossEncoder 分数替换
                            score.score()       // relevance 也用 CrossEncoder 分数
                    ));
                }
            }

            // 按 CrossEncoder 分数降序，取 topK
            reranked.sort(Comparator.comparingDouble(
                    r -> ((HybridRetrievalService.RetrievalResult) r).score()
            ).reversed());

            List<HybridRetrievalService.RetrievalResult> topResults =
                    reranked.stream().limit(topK).collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[CrossEncoder] Rerank completed in {}ms, returning {} results",
                elapsed, topResults.size());

            // 记录 top3 分数供调试
            for (int i = 0; i < Math.min(3, topResults.size()); i++) {
                HybridRetrievalService.RetrievalResult r = topResults.get(i);
                log.debug("[CrossEncoder] Top[{}] chunkId={}, score={:.4f}",
                    i, r.chunkId(), r.score());
            }

            return topResults;

        } catch (Exception e) {
            log.error("[CrossEncoder] Rerank API call failed: {}, fallback to candidates", e.getMessage());
            return candidates;
        }
    }

    /**
     * 调用 SiliconFlow Rerank API
     */
    private List<RerankScore> callRerankAPI(String query, List<String> documents)
            throws JsonProcessingException {

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "query", query,
                "documents", documents,
                "top_n", documents.size()
        );

        String response = webClient.post()
                .uri("/rerank")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        if (response == null || response.isEmpty()) {
            log.warn("[CrossEncoder] Empty response from API");
            return Collections.emptyList();
        }

        JsonNode root = objectMapper.readTree(response);
        JsonNode results = root.path("results");

        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt();
            double score = item.path("relevance_score").asDouble();
            scores.add(new RerankScore(index, score));
        }

        return scores;
    }

    /**
     * 回退方案：使用 Bi-Encoder 余弦相似度（复用 EmbeddingService）
     */
    public List<HybridRetrievalService.RetrievalResult> fallbackBiEncoder(
            String query,
            List<HybridRetrievalService.RetrievalResult> candidates,
            int topK) {

        log.info("[CrossEncoder] Falling back to Bi-Encoder approximation");

        try {
            float[] queryEmbedding = embeddingService.embed(query);
            List<HybridRetrievalService.RetrievalResult> scored = new ArrayList<>();

            for (HybridRetrievalService.RetrievalResult candidate : candidates) {
                float[] docEmbedding = embeddingService.embed(candidate.content());
                double similarity = cosineSimilarity(queryEmbedding, docEmbedding);
                scored.add(new HybridRetrievalService.RetrievalResult(
                        candidate.chunkId(),
                        candidate.content(),
                        similarity,
                        similarity
                ));
            }

            scored.sort(Comparator.comparingDouble(
                    HybridRetrievalService.RetrievalResult::score
            ).reversed());

            return scored.stream().limit(topK).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("[CrossEncoder] Bi-Encoder fallback also failed", e);
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (normA * normB);
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    // ===== DTO =====
    private record RerankScore(int index, double score) {}
}
