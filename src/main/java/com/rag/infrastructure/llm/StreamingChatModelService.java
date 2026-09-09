package com.rag.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * OpenAI 兼容格式的 SSE 流式对话客户端。
 *
 * 基于 JDK HttpClient 实现（连接池/超时/无外部进程依赖），
 * 请求体经 ObjectMapper 序列化，不存在临时文件与手写 JSON 转义。
 * SSE 解析只提取 delta.content，忽略 reasoning_content（思考过程）。
 */
@Component
public class StreamingChatModelService {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatModelService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public StreamingChatModelService(AppConfig appConfig) {
        AppConfig.Llm llmConfig = appConfig.getLlm();
        this.model = llmConfig.getModel();
        this.apiKey = llmConfig.getApiKey();
        this.apiUrl = llmConfig.getBaseUrl() + "/chat/completions";
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("StreamingChatModel initialized: model={}, baseUrl={}", model, llmConfig.getBaseUrl());
    }

    public CompletableFuture<String> stream(String prompt, StreamingCallback callback) {
        CompletableFuture<String> future = new CompletableFuture<>();
        executor.execute(() -> doStream(prompt, callback, future));
        return future;
    }

    private void doStream(String prompt, StreamingCallback callback, CompletableFuture<String> future) {
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "stream", true,
                    "max_tokens", 4096,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            log.info("Calling LLM streaming API, prompt len={}", prompt.length());

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("LLM API error: HTTP " + response.statusCode() + " - " + errBody);
            }

            StringBuilder fullResponse = new StringBuilder();
            boolean done = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!done && (line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) {
                            done = true;
                            break;
                        }
                        String content = extractContent(data);
                        if (content != null && !content.isEmpty()) {
                            fullResponse.append(content);
                            callback.onNext(content);
                        }
                    }
                }
            }

            log.info("LLM stream finished, fullResponse len={}", fullResponse.length());
            String cleaned = M3ResponseCleaner.clean(fullResponse.toString());
            callback.onComplete(cleaned);
            future.complete(cleaned);

        } catch (Exception e) {
            log.error("Stream error", e);
            callback.onError(e);
            future.completeExceptionally(e);
        }
    }

    /**
     * MiniMax-M2.7 SSE 格式:
     *   回答块: {"choices":[{"delta":{"content":"Hi there!..."}}]}
     *     → 内容在 delta.content 中
     *   思考块: {"choices":[{"delta":{"reasoning_content":"The user..."}}]}
     *     → 思考过程在 delta.reasoning_content 中（不是回答，忽略）
     *   最终块: {"choices":[{"finish_reason":"stop","delta":{"content":"..."}, "message":{"content":"完整回答"}}]}
     *     → delta.content 包含最后一部分内容；message.content 是完整累积
     *     → 我们只需要 delta.content（内容已被累积），不需要 message.content
     *
     * 只提取 delta.content（回答内容）。忽略 reasoning_content 和 message.content
     */
    private String extractContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return null;
            JsonNode first = choices.get(0);
            JsonNode delta = first.path("delta");

            // 只取 delta.content（非空时）
            JsonNode cn = delta.path("content");
            if (!cn.isMissingNode() && !cn.asText("").isEmpty()) {
                return cn.asText();
            }

        } catch (Exception e) {
            log.warn("Jackson parse failed, fallback: {}", e.getMessage());
            return extractFallback(json);
        }
        return null;
    }

    private String extractFallback(String json) {
        if (!json.contains("finish_reason")) return null;
        int idx = json.lastIndexOf("\"content\":\"");
        if (idx < 0) return null;
        int start = idx + 11;
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; }
            else if (c == '"') { break; }
            else { end++; }
        }
        if (end > start) {
            return json.substring(start, end)
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        }
        return null;
    }

    public interface StreamingCallback {
        void onNext(String token);
        void onComplete(String fullResponse);
        void onError(Throwable e);
    }
}
