package com.rag.application.chat.react;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.application.chat.react.model.Action;
import com.rag.application.chat.react.model.ActionResult;
import com.rag.config.AppConfig;
import com.rag.infrastructure.llm.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ActionCache {

    private static final Logger log = LoggerFactory.getLogger(ActionCache.class);

    private final AppConfig.React reactConfig;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final LocalLlmClient localLlmClient;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ActionCache(AppConfig appConfig,
                      EmbeddingService embeddingService,
                      ObjectMapper objectMapper,
                      LocalLlmClient localLlmClient) {
        this.reactConfig = appConfig.getReact();
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.localLlmClient = localLlmClient;
        log.info("ActionCache initialized: enabled={}, directHit={}, review={}",
                reactConfig.getCache().isEnabled(),
                reactConfig.getCache().getSimilarityThreshold().getDirectHit(),
                reactConfig.getCache().getSimilarityThreshold().getReview());
    }

    public ActionResult getOrCompute(Action action, ActionResult result) {
        if (!reactConfig.getCache().isEnabled()) {
            return result;
        }

        String cacheKey = action.getCacheKey();
        CacheEntry existing = cache.get(cacheKey);

        if (existing != null) {
            log.debug("Cache hit for key: {}", cacheKey);
            return existing.result;
        }

        cache.put(cacheKey, new CacheEntry(action, result));
        return result;
    }

    public ActionResult checkCache(Action action) {
        if (!reactConfig.getCache().isEnabled()) {
            return null;
        }

        String cacheKey = action.getCacheKey();
        CacheEntry existing = cache.get(cacheKey);

        if (existing != null) {
            log.info("Cache hit for action: type={}", action.getType());
            return existing.result;
        }

        float similarity = findMostSimilarAction(action);
        if (similarity > reactConfig.getCache().getSimilarityThreshold().getDirectHit()) {
            log.info("High similarity match found: similarity={}", similarity);
            return findSimilarResult(action);
        }

        return null;
    }

    private float findMostSimilarAction(Action action) {
        if (cache.isEmpty()) {
            return 0f;
        }

        try {
            float[] queryEmbedding = embeddingService.embed(action.getCacheKey());

            float maxSimilarity = 0f;
            for (CacheEntry entry : cache.values()) {
                float[] cachedEmbedding = entry.embedding;
                if (cachedEmbedding == null) {
                    cachedEmbedding = embeddingService.embed(entry.action.getCacheKey());
                    entry.embedding = cachedEmbedding;
                }

                float similarity = cosineSimilarity(queryEmbedding, cachedEmbedding);
                if (similarity > maxSimilarity) {
                    maxSimilarity = similarity;
                }
            }

            return maxSimilarity;
        } catch (Exception e) {
            log.error("Failed to compute similarity: {}", e.getMessage());
            return 0f;
        }
    }

    private ActionResult findSimilarResult(Action action) {
        if (cache.isEmpty()) {
            return null;
        }

        try {
            float[] queryEmbedding = embeddingService.embed(action.getCacheKey());

            float maxSimilarity = 0f;
            ActionResult bestResult = null;

            for (CacheEntry entry : cache.values()) {
                float[] cachedEmbedding = entry.embedding;
                if (cachedEmbedding == null) {
                    cachedEmbedding = embeddingService.embed(entry.action.getCacheKey());
                    entry.embedding = cachedEmbedding;
                }

                float similarity = cosineSimilarity(queryEmbedding, cachedEmbedding);
                if (similarity > maxSimilarity && similarity >= reactConfig.getCache().getSimilarityThreshold().getDirectHit()) {
                    maxSimilarity = similarity;
                    bestResult = entry.result;
                }
            }

            return bestResult;
        } catch (Exception e) {
            log.error("Failed to find similar result: {}", e.getMessage());
            return null;
        }
    }

    public boolean needsReview(float similarity) {
        return similarity > reactConfig.getCache().getSimilarityThreshold().getReview() &&
               similarity <= reactConfig.getCache().getSimilarityThreshold().getDirectHit();
    }

    public boolean shouldUseCache(float similarity) {
        return similarity >= reactConfig.getCache().getSimilarityThreshold().getDirectHit();
    }

    public boolean isDuplicateLogic(Action newAction, Action cachedAction) {
        if (!localLlmClient.isEnabled()) {
            return false;
        }

        try {
            String prompt = String.format("""
                判断以下两个查询是否语义重复。

                查询A: %s
                查询B: %s

                语义重复的定义：
                - 询问的是同一事物
                - 查询意图相同
                - 参数差异不影响核心语义

                返回格式：{"duplicate": true/false, "reason": "简短原因"}
                """,
                newAction.getCacheKey(),
                cachedAction.getCacheKey());

            String response = localLlmClient.generate(prompt);
            JsonNode result = objectMapper.readTree(response);

            if (result.has("duplicate")) {
                return result.get("duplicate").asBoolean();
            }
        } catch (Exception e) {
            log.error("Failed to check duplicate logic: {}", e.getMessage());
        }

        return false;
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0f;
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0f;
        }

        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    public void clear() {
        cache.clear();
        log.info("ActionCache cleared");
    }

    public int size() {
        return cache.size();
    }

    private static class CacheEntry {
        Action action;
        ActionResult result;
        float[] embedding;

        CacheEntry(Action action, ActionResult result) {
            this.action = action;
            this.result = result;
        }
    }
}