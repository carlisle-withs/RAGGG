package com.rag.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.application.chat.MemoryService;
import com.rag.application.chat.QueryRewriter;
import com.rag.application.retrieval.RetrievalApplicationService;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class DeepSeekStreamingService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekStreamingService.class);
    private static final long STREAM_TIMEOUT = 300_000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final QueryRewriter queryRewriter;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public DeepSeekStreamingService(AppConfig appConfig, QueryRewriter queryRewriter) {
        AppConfig.Llm llmConfig = appConfig.getLlm();
        this.apiUrl = llmConfig.getBaseUrl() + "/chat/completions";
        this.apiKey = llmConfig.getApiKey();
        this.model = llmConfig.getModel();
        this.queryRewriter = queryRewriter;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("DeepSeekStreamingService initialized: model={}, url={}", model, apiUrl);
    }

    public ResponseBodyEmitter streamChat(String userMessage, String kbId,
                                  RetrievalApplicationService retrievalService,
                                  MemoryService memoryService,
                                  String userId, String conversationId) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(STREAM_TIMEOUT);
        executor.execute(() -> doStream(emitter, userMessage, kbId, retrievalService,
                memoryService, userId, conversationId));
        return emitter;
    }

    private void doStream(ResponseBodyEmitter emitter, String userMessage, String kbId,
                           RetrievalApplicationService retrievalService,
                           MemoryService memoryService,
                           String userId, String conversationId) {
        try {
            String convId = conversationId != null ? conversationId : UUID.randomUUID().toString();

            send(emitter, jsonObj(Map.of("type", "meta", "conversationId", convId)));

            String memoryContext = "";
            if (conversationId != null && userId != null) {
                memoryContext = memoryService.buildContextPrompt(userId, conversationId);
            }

            StringBuilder prompt = new StringBuilder();
            if (kbId != null && !kbId.isEmpty() && retrievalService != null) {
                try {
                    // 指代消解：多轮追问先借记忆改写为独立问题再检索，
                    // 避免"那它怎么配置"这类原话直接向量化导致召回脱靶
                    String searchQuery = userMessage;
                    if (!memoryContext.isEmpty()) {
                        String condensed = queryRewriter.condenseWithMemory(userMessage, memoryContext);
                        if (condensed != null) {
                            searchQuery = condensed;
                        }
                    }
                    List<RetrievalApplicationService.RetrievalResult> sources =
                            retrievalService.hybridSearch(searchQuery, kbId, 5, true);
                    if (sources != null && !sources.isEmpty()) {
                        String ragContext = sources.stream()
                                .map(s -> "【文档】" + s.content())
                                .collect(Collectors.joining("\n\n"));
                        prompt.append("请基于以下参考文档回答用户问题。\n\n")
                              .append("【参考文档】\n").append(ragContext).append("\n\n");
                    }
                } catch (Exception e) {
                    log.warn("Retrieval failed for streaming", e);
                }
            }
            if (!memoryContext.isEmpty()) {
                prompt.append("【对话历史】\n").append(memoryContext).append("\n\n");
            }
            prompt.append("【用户问题】\n").append(userMessage);

            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "stream", true,
                    "messages", List.of(Map.of("role", "user", "content", prompt.toString())),
                    "max_tokens", 4096,
                    "temperature", 0.7
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("DeepSeek API error: HTTP {} - {}", response.statusCode(), errBody);
                send(emitter, jsonObj(Map.of("type", "error", "error", "API error: " + response.statusCode())));
                emitter.complete();
                return;
            }

            StringBuilder fullResponse = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            JsonNode root = objectMapper.readTree(data);
                            JsonNode choices = root.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).path("delta");
                                JsonNode content = delta.path("content");
                                if (!content.isMissingNode() && !content.asText().isEmpty()) {
                                    String token = content.asText();
                                    fullResponse.append(token);
                                    send(emitter, jsonObj(Map.of("type", "token", "delta", token)));
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Skip line: {}", data.substring(0, Math.min(50, data.length())));
                        }
                    }
                }
            }

            if (conversationId != null && userId != null) {
                memoryService.addMessage(userId, conversationId, "user", userMessage);
                memoryService.addMessage(userId, conversationId, "assistant", fullResponse.toString());
            }

            send(emitter, jsonObj(Map.of("type", "finish", "conversationId", convId, "fullLength", fullResponse.length())));
            emitter.complete();

        } catch (Exception e) {
            log.error("Streaming error", e);
            try {
                send(emitter, jsonObj(Map.of("type", "error", "error", e.getMessage() != null ? e.getMessage() : "未知错误")));
                emitter.complete();
            } catch (Exception ex) { }
        }
    }

    private void send(ResponseBodyEmitter emitter, String data) {
        try {
            emitter.send(data + "\n", org.springframework.http.MediaType.TEXT_PLAIN);
        } catch (Exception e) {
            log.debug("Send failed, client disconnected");
        }
    }

    private String jsonObj(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
