package com.rag.application.chat.react;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.application.chat.react.model.TaskComplexity;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
public class ComplexityRouter {

    private static final Logger log = LoggerFactory.getLogger(ComplexityRouter.class);

    private final AppConfig.React reactConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ComplexityRouter(AppConfig appConfig, ObjectMapper objectMapper) {
        this.reactConfig = appConfig.getReact();
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(reactConfig.getLightweightLlm().getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("ComplexityRouter initialized: enabled={}, model={}, timeout={}ms",
                reactConfig.getRouter().isEnabled(),
                reactConfig.getRouter().getModel(),
                reactConfig.getRouter().getTimeoutMs());
    }

    public TaskComplexity classify(String query, String context) {
        if (!reactConfig.isEnabled() || !reactConfig.getRouter().isEnabled()) {
            log.debug("ComplexityRouter is disabled, default to SIMPLE");
            return TaskComplexity.SIMPLE;
        }

        try {
            long startTime = System.currentTimeMillis();

            String response = callLlm(query, context);

            TaskComplexity result = parseResponse(response);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Complexity classification completed in {}ms: query={}, result={}",
                    duration, truncateQuery(query), result);

            if (duration > reactConfig.getRouter().getTimeoutMs()) {
                log.warn("Complexity classification exceeded timeout: {}ms > {}ms",
                        duration, reactConfig.getRouter().getTimeoutMs());
            }

            return result;

        } catch (Exception e) {
            log.error("Complexity classification failed, default to SIMPLE: {}", e.getMessage());
            return TaskComplexity.SIMPLE;
        }
    }

    private String callLlm(String query, String context) {
        try {
            String prompt = "判断以下问题是否为复杂任务（需要多步推理或多个知识源）。\n\n" +
                    "复杂任务的特征：\n" +
                    "- 需要多步推理（先...再...然后...）\n" +
                    "- 需要多个知识源（既查...又查...）\n" +
                    "- 存在依赖关系（...之后才能...）\n" +
                    "- 涉及多个实体或需要关联分析\n\n" +
                    "简单任务的特征：\n" +
                    "- 单意图\n" +
                    "- 无依赖关系\n" +
                    "- 单一知识源\n\n" +
                    "问题：" + query + "\n\n" +
                    "上下文：" + (context != null ? context : "无") + "\n\n" +
                    "返回格式：{\"complex\": true/false, \"reason\": \"简短原因\"}";

            Map<String, Object> body = Map.of(
                    "model", reactConfig.getLightweightLlm().getModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.1,
                    "max_tokens", 100
            );

            return webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + reactConfig.getLightweightLlm().getApiKey())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofMillis(reactConfig.getRouter().getTimeoutMs() + 1000))
                    .block();
        } catch (Exception e) {
            log.error("Failed to call LLM: {}", e.getMessage());
            throw new RuntimeException("LLM call failed", e);
        }
    }

    private TaskComplexity parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).get("message").get("content").asText();
                JsonNode result = objectMapper.readTree(content);
                boolean isComplex = result.get("complex").asBoolean();
                return isComplex ? TaskComplexity.COMPLEX : TaskComplexity.SIMPLE;
            }
        } catch (Exception e) {
            log.warn("Failed to parse LLM response, trying fallback parsing: {}", e.getMessage());
            if (response != null && response.toLowerCase().contains("\"complex\": true")) {
                return TaskComplexity.COMPLEX;
            }
        }
        return TaskComplexity.SIMPLE;
    }

    private String truncateQuery(String query) {
        if (query == null) return "";
        return query.length() > 50 ? query.substring(0, 50) + "..." : query;
    }

    public boolean isEnabled() {
        return reactConfig.isEnabled() && reactConfig.getRouter().isEnabled();
    }
}