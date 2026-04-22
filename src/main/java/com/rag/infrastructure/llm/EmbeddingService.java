package com.rag.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int MAX_EMBED_CHARS = 5000;

    private final HttpClient ollamaClient;            // 仅 Ollama 模式使用
    private final HttpClient sfClient;                // 仅 SiliconFlow 模式使用
    private final String ollamaModel;
    private final int ollamaDimension;
    private final int langchainDimension;
    private final String provider;  // "ollama" 或 "siliconflow"
    private final String ollamaBaseUrl;  // Ollama 服务地址（来自配置）
    private final String sfBaseUrl;
    private final String sfApiKey;
    private final String sfModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmbeddingService(AppConfig appConfig,
                           @Value("${embedding.batch-size:32}") int batchSize) {
        this.provider = appConfig.getEmbedding().getProvider();
        this.ollamaModel = appConfig.getEmbedding().getModel();
        this.ollamaDimension = appConfig.getEmbedding().getDimension();
        this.langchainDimension = appConfig.getEmbedding().getDimension();

        if ("ollama".equalsIgnoreCase(provider)) {
            // ========== Ollama 本地模式 ==========
            this.ollamaBaseUrl = appConfig.getEmbedding().getBaseUrl();
            this.ollamaClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            this.sfBaseUrl = null;
            this.sfApiKey = null;
            this.sfModel = null;
            this.sfClient = null;
            log.info("EmbeddingService initialized [Ollama 模式]: model={}, dimension={}, baseUrl={}",
                    ollamaModel, ollamaDimension, ollamaBaseUrl);
        } else {
            // ========== SiliconFlow 模式——使用原生 HTTP 客户端 ==========
            this.ollamaBaseUrl = null;
            this.sfBaseUrl = appConfig.getEmbedding().getBaseUrl();
            this.sfApiKey = appConfig.getEmbedding().getApiKey();
            this.sfModel = appConfig.getEmbedding().getModel();
            this.sfClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            this.ollamaClient = null;
            log.info("EmbeddingService initialized [SiliconFlow 模式]: model={}, dimension={}, baseUrl={}",
                    sfModel, langchainDimension, sfBaseUrl);
        }
    }

    /** 单条 embedding */
    public float[] embed(String text) {
        String cleaned = sanitizeText(text);
        if ("ollama".equalsIgnoreCase(provider)) {
            return embedOllama(cleaned);
        } else {
            return embedSiliconFlow(cleaned);
        }
    }

    /**
     * 批量 embedding：一次 API 调用处理多条文本。
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = texts.stream()
                .map(this::sanitizeText)
                .filter(t -> !t.isEmpty())  // 过滤空字符串，SiliconFlow 不接受空字符串
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }

        if ("ollama".equalsIgnoreCase(provider)) {
            return embedBatchOllama(cleaned);
        } else {
            return embedBatchSiliconFlow(cleaned);
        }
    }

    // ========== Ollama 模式实现 ==========

    private List<float[]> embedBatchOllama(List<String> texts) {
        // Ollama API 不支持真正的批量，每条文本需要单独调用
        // 使用并行请求提升吞吐量（每条独立，不互相阻塞）
        List<CompletableFuture<float[]>> futures = new ArrayList<>(texts.size());
        for (String text : texts) {
            futures.add(CompletableFuture.supplyAsync(() -> embedOllama(text)));
        }
        List<float[]> results = new ArrayList<>(texts.size());
        for (CompletableFuture<float[]> f : futures) {
            try {
                results.add(f.join());
            } catch (Exception e) {
                log.error("Ollama embedding failed: {}", e.getMessage());
                throw new RuntimeException("Ollama embedding failed", e);
            }
        }
        return results;
    }

    private float[] embedOllama(String text) {
        try {
            String jsonPayload = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\"}",
                    ollamaModel,
                    text.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n").replace("\r", "\\r")
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = ollamaClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API error: HTTP " + response.statusCode() + " - " + response.body());
            }
            JsonNode root = new ObjectMapper().readTree(response.body());
            JsonNode embeddingNode = root.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new RuntimeException("Invalid Ollama response: missing 'embedding' field");
            }
            float[] result = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                result[i] = (float) embeddingNode.get(i).asDouble();
            }
            return result;
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("Ollama embedding 超时（120s仍未返回）: {}", ollamaBaseUrl);
            throw new RuntimeException("Ollama embedding timed out after 120s — Ollama may be loading the model", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Ollama embedding interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Ollama embedding failed: " + e.getMessage(), e);
        }
    }

    // ========== SiliconFlow 原生 HTTP 实现 ==========
    // SiliconFlow 期望 input 为字符串数组，而非 LangChain4j 发送的对象数组

    private List<float[]> embedBatchSiliconFlow(List<String> texts) {
        try {
            // SiliconFlow 格式：{"model":"BAAI/bge-m3","input":["text1","text2"]}
            var payload = objectMapper.createObjectNode();
            payload.put("model", sfModel);
            var inputArray = objectMapper.createArrayNode();
            for (String t : texts) {
                inputArray.add(t);
            }
            payload.set("input", inputArray);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sfBaseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + sfApiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sfClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("SiliconFlow embedding failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataNode = root.get("data");
            if (dataNode == null || !dataNode.isArray()) {
                throw new RuntimeException("Invalid SiliconFlow response: missing 'data' field");
            }

            List<float[]> results = new ArrayList<>(dataNode.size());
            for (JsonNode item : dataNode) {
                JsonNode embNode = item.get("embedding");
                if (embNode == null || !embNode.isArray()) {
                    throw new RuntimeException("Invalid embedding item: missing 'embedding' field");
                }
                float[] arr = new float[embNode.size()];
                for (int i = 0; i < embNode.size(); i++) {
                    arr[i] = (float) embNode.get(i).asDouble();
                }
                results.add(arr);
            }
            return results;
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("SiliconFlow embedding 超时（120s）: {}", sfBaseUrl);
            throw new RuntimeException("SiliconFlow embedding timed out after 120s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SiliconFlow embedding interrupted", e);
        } catch (Exception e) {
            log.error("SiliconFlow batch embedding failed for {} texts: {}", texts.size(), e.getMessage(), e);
            throw new RuntimeException("SiliconFlow batch embedding failed", e);
        }
    }

    private float[] embedSiliconFlow(String text) {
        try {
            var payload = objectMapper.createObjectNode();
            payload.put("model", sfModel);
            payload.put("input", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sfBaseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + sfApiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sfClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("SiliconFlow embedding failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embNode = root.path("data").path(0).path("embedding");
            if (!embNode.isArray()) {
                throw new RuntimeException("Invalid SiliconFlow response: missing embedding vector");
            }
            float[] result = new float[embNode.size()];
            for (int i = 0; i < embNode.size(); i++) {
                result[i] = (float) embNode.get(i).asDouble();
            }
            return result;
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("SiliconFlow embedding 超时（120s）: {}", sfBaseUrl);
            throw new RuntimeException("SiliconFlow embedding timed out after 120s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SiliconFlow embedding interrupted", e);
        } catch (Exception e) {
            log.error("SiliconFlow single embedding failed: {}", e.getMessage(), e);
            throw new RuntimeException("SiliconFlow embedding failed", e);
        }
    }

    // ========== 文本清洗 ==========

    private String sanitizeText(String text) {
        if (text == null) return "";
        String cleaned = text;
        cleaned = cleaned.replaceAll("(\\r?\\n){3,}", "\n\n");
        cleaned = cleaned.strip();
        if (cleaned.length() > MAX_EMBED_CHARS) {
            cleaned = cleaned.substring(0, MAX_EMBED_CHARS);
        }
        return cleaned;
    }

    public int getDimension() {
        if ("ollama".equalsIgnoreCase(provider)) {
            return ollamaDimension;
        }
        return langchainDimension;
    }
}
