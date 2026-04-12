package com.rag.application.chat.react;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class LocalLlmClient {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmClient.class);

    private final AppConfig.React reactConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public LocalLlmClient(AppConfig appConfig, ObjectMapper objectMapper) {
        this.reactConfig = appConfig.getReact();
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(reactConfig.getLightweightLlm().getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("LocalLlmClient initialized: provider={}, model={}, baseUrl={}",
                reactConfig.getLightweightLlm().getProvider(),
                reactConfig.getLightweightLlm().getModel(),
                reactConfig.getLightweightLlm().getBaseUrl());
    }

    public String generate(String prompt) {
        if (!isEnabled()) {
            throw new RuntimeException("Local LLM is not enabled");
        }

        try {
            String requestBody = String.format("""
                {
                    "model": "%s",
                    "messages": [
                        {
                            "role": "user",
                            "content": %s
                        }
                    ],
                    "temperature": 0.1,
                    "max_tokens": 500
                }
                """,
                reactConfig.getLightweightLlm().getModel(),
                escapeJsonString(prompt));

            String response = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + reactConfig.getLightweightLlm().getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofMillis(reactConfig.getLightweightLlm().getTimeoutMs()))
                    .block();

            return parseResponse(response);

        } catch (Exception e) {
            log.error("Failed to call Local LLM: {}", e.getMessage());
            throw new RuntimeException("Local LLM call failed", e);
        }
    }

    public String extractFacts(String action, String observation) {
        String prompt = String.format("""
            从以下文本中提取核心事实，剔除时间戳、ID、随机数等噪声。

            格式：["事实1", "事实2", ...]

            文本：
            Action: %s
            Observation: %s
            """,
            action,
            observation);

        try {
            return generate(prompt);
        } catch (Exception e) {
            log.error("Failed to extract facts: {}", e.getMessage());
            return "[]";
        }
    }

    public boolean isDuplicate(String queryA, String queryB) {
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
            queryA,
            queryB);

        try {
            String response = generate(prompt);
            JsonNode result = objectMapper.readTree(response);

            if (result.has("duplicate")) {
                return result.get("duplicate").asBoolean();
            }
        } catch (Exception e) {
            log.error("Failed to check duplicate: {}", e.getMessage());
        }

        return false;
    }

    private String parseResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            return choices.get(0).get("message").get("content").asText();
        }
        throw new RuntimeException("Invalid response format");
    }

    private String escapeJsonString(String input) {
        if (input == null) return "\"\"";
        return "\"" + input.replace("\\", "\\\\")
                           .replace("\"", "\\\"")
                           .replace("\n", "\\n")
                           .replace("\r", "\\r")
                           .replace("\t", "\\t") + "\"";
    }

    public boolean isEnabled() {
        return reactConfig.isEnabled() &&
               "local".equalsIgnoreCase(reactConfig.getLightweightLlm().getProvider());
    }

    public String getModel() {
        return reactConfig.getLightweightLlm().getModel();
    }
}