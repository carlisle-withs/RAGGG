package com.rag.infrastructure.llm;

import com.rag.application.retrieval.HybridRetrievalService;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * True CrossEncoder 重排器
 *
 * 使用 SiliconFlow /rerank API 实现真正的交叉编码器重排:
 * - Bi-Encoder: query 和 doc 分别独立编码 → cosine similarity (近似)
 * - CrossEncoder: [CLS] query [SEP] doc [SEP] 联合编码 → 直接输出 relevance score (准确)
 *
 * 重排模型: BAAI/bge-reranker-v2-m3
 * API: POST https://api.siliconflow.cn/v1/rerank
 */
@Component
public class SiliconFlowReranker {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowReranker.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean enabled;

    public SiliconFlowReranker(AppConfig appConfig, RestTemplateBuilder builder) {
        AppConfig.Reranker cfg = appConfig.getReranker();
        this.enabled = cfg != null && cfg.isEnabled();
        this.baseUrl = cfg != null ? cfg.getBaseUrl() : "https://api.siliconflow.cn/v1";
        this.apiKey = cfg != null ? cfg.getApiKey() : "";
        this.model = cfg != null ? cfg.getModel() : "BAAI/bge-reranker-v2-m3";
        this.restTemplate = builder.build();

        log.info("[CrossEncoder] SiliconFlowReranker initialized: enabled={}, model={}, baseUrl={}",
                enabled, model, baseUrl);
    }

    /**
     * 对候选文档进行 True CrossEncoder 重排
     *
     * @param query     查询文本
     * @param candidates RRF 融合后的候选结果 (通常 10~30 个)
     * @param topK      返回的重排结果数
     * @return 重排后的结果列表，按 relevance 降序
     */
    public List<HybridRetrievalService.RetrievalResult> rerank(
            String query,
            List<HybridRetrievalService.RetrievalResult> candidates,
            int topK) {

        if (!enabled) {
            log.debug("[CrossEncoder] SiliconFlow reranker disabled, skipping");
            return candidates;
        }

        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        // 重排候选 <= 2 时，跳过重排（收益不明显且浪费 API 调用）
        if (candidates.size() <= 2) {
            log.debug("[CrossEncoder] Candidates <= 2, skip reranking");
            return candidates;
        }

        long start = System.currentTimeMillis();

        try {
            // 构建请求体
            List<String> documents = candidates.stream()
                    .map(HybridRetrievalService.RetrievalResult::content)
                    .toList();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", Math.min(topK, candidates.size()));

            // 发送请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String url = baseUrl + "/rerank";
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Map.class);

            long latency = System.currentTimeMillis() - start;

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<?, ?> body = response.getBody();
                List<?> results = (List<?>) body.get("results");

                if (results == null || results.isEmpty()) {
                    log.warn("[CrossEncoder] Empty results from SiliconFlow API");
                    return candidates;
                }

                // 按 SiliconFlow 返回的 index 映射回原始 candidates，并替换 score
                Map<Integer, Double> scoreMap = new HashMap<>();
                for (Object item : results) {
                    Map<?, ?> r = (Map<?, ?>) item;
                    int idx = ((Number) r.get("index")).intValue();
                    double score = ((Number) r.get("relevance_score")).doubleValue();
                    scoreMap.put(idx, score);
                }

                List<HybridRetrievalService.RetrievalResult> reranked = new ArrayList<>();
                for (int i = 0; i < candidates.size(); i++) {
                    HybridRetrievalService.RetrievalResult c = candidates.get(i);
                    double newScore = scoreMap.getOrDefault(i, c.score());
                    reranked.add(new HybridRetrievalService.RetrievalResult(
                            c.chunkId(), c.content(), c.score(), newScore));
                }

                // 按 relevance_score 降序
                reranked.sort(Comparator.comparingDouble(HybridRetrievalService.RetrievalResult::relevance).reversed());

                log.info("[CrossEncoder] Reranked {} candidates in {}ms, top relevance={}",
                        candidates.size(), latency,
                        reranked.isEmpty() ? 0 : reranked.get(0).relevance());

                return reranked;
            } else {
                log.warn("[CrossEncoder] SiliconFlow API returned status={}, falling back to candidates",
                        response.getStatusCode());
                return candidates;
            }

        } catch (RestClientException e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[CrossEncoder] API call failed after {}ms, falling back to candidates: {}",
                    latency, e.getMessage());
            return candidates;
        } catch (Exception e) {
            log.error("[CrossEncoder] Unexpected error during reranking", e);
            return candidates;
        }
    }
}