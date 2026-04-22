package com.rag.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Component
public class StreamingChatModelService {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatModelService.class);

    private final String apiKey;
    private final String groupId;
    private final String model;
    private final ObjectMapper objectMapper;
    private final Executor executor = Executors.newSingleThreadExecutor();

    private static final String API_URL = "https://api.minimax.chat/v1/text/chatcompletion_v2";
    private static final String AUTH_HEADER = "Bearer sk-cp-GZiEUROmyANxyr0sL20NVZeCQHUoivuZo0GXEAA6B55Ob6C5aCxXL2jKz2ELKsLXkdCJN-P8ANj-681kzpmeyL1Vj7EEbLfIlLgBcYmJlG-i53b94nUfpdY";
    private static final String GROUP_ID = "2034153629136458495";

    public StreamingChatModelService(AppConfig appConfig) {
        AppConfig.Llm llmConfig = appConfig.getLlm();
        this.model = llmConfig.getModel();
        this.apiKey = llmConfig.getApiKey();
        this.groupId = llmConfig.getGroupId();
        this.objectMapper = new ObjectMapper();
        log.info("StreamingChatModel initialized: model={}, groupId={}", model, groupId);
    }

    public CompletableFuture<String> stream(String prompt, StreamingCallback callback) {
        CompletableFuture<String> future = new CompletableFuture<>();
        executor.execute(() -> {
            Path tmpFile = null;
            Process curlProcess = null;
            try {
                // 将 JSON body 写入临时文件（避免命令行引号问题）
                String jsonBody = "{\"model\":\"" + model + "\",\"stream\":true," +
                        "\"messages\":[{\"role\":\"user\",\"content\":\"" + escapeJson(prompt) + "\"}]}";
                tmpFile = Files.createTempFile("minimax_req", ".json");
                Files.writeString(tmpFile, jsonBody, StandardCharsets.UTF_8);

                log.info("Calling MiniMax API via curl, prompt len={}", prompt.length());

                // 使用 curl 调用 MiniMax API
                ProcessBuilder pb = new ProcessBuilder(
                    "curl", "-s", "-N",
                    API_URL,
                    "-X", "POST",
                    "-H", "Content-Type: application/json",
                    "-H", "Authorization: " + AUTH_HEADER,
                    "-H", "group_id: " + GROUP_ID,
                    "-d", "@" + tmpFile.toString(),
                    "--max-time", "120"
                );
                pb.redirectErrorStream(true);
                curlProcess = pb.start();

                StringBuilder fullResponse = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(curlProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) {
                                log.info("SSE DONE, fullResponse len={}", fullResponse.length());
                                callback.onComplete(fullResponse.toString());
                                future.complete(fullResponse.toString());
                                return;
                            }
                            String content = extractContent(data);
                            if (content != null && !content.isEmpty()) {
                                fullResponse.append(content);
                                callback.onNext(content);
                            }
                        }
                    }
                }

                int exitCode = curlProcess.waitFor();
                log.info("Curl exited with code: {}, fullResponse len={}", exitCode, fullResponse.length());
                callback.onComplete(fullResponse.toString());
                future.complete(fullResponse.toString());

            } catch (Exception e) {
                log.error("Stream error", e);
                callback.onError(e);
                future.completeExceptionally(e);
            } finally {
                if (curlProcess != null && curlProcess.isAlive()) {
                    curlProcess.destroyForcibly();
                }
                if (tmpFile != null) {
                    try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
                }
            }
        });
        return future;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
