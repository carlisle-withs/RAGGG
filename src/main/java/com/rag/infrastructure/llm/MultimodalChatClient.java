package com.rag.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * MiniMax M3 多模态聊天客户端
 *
 * 支持：
 * - 纯文本对话
 * - 图片 + 文本对话（Vision QA）
 * - base64 图片和 URL 图片
 * - 流式响应
 */
@Component
public class MultimodalChatClient {

    private static final Logger log = LoggerFactory.getLogger(MultimodalChatClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String groupId;
    private final String model;
    private final String baseUrl;
    private final AppConfig.Llm.Multimodal multimodalConfig;

    public MultimodalChatClient(AppConfig appConfig) {
        AppConfig.Llm llmConfig = appConfig.getLlm();
        this.apiKey = llmConfig.getApiKey();
        this.groupId = llmConfig.getGroupId();
        this.model = llmConfig.getModel();
        this.baseUrl = llmConfig.getBaseUrl();
        this.multimodalConfig = llmConfig.getMultimodal();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        log.info("MultimodalChatClient initialized: model={}, multimodal={}, vision={}",
                model, multimodalConfig.isEnabled(), multimodalConfig.getVision().isEnabled());
    }

    /**
     * 纯文本聊天（保持向后兼容）
     */
    public String chat(String prompt) throws IOException, InterruptedException {
        return chat(prompt, null, 2000);
    }

    /**
     * 图文混合聊天
     *
     * @param prompt 文本提示
     * @param images 图片列表（base64 编码字符串，不含 data URI 前缀）
     * @param maxTokens 最大 token 数
     */
    public String chat(String prompt, List<String> images, int maxTokens) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.7);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");

        // 构建多模态 content 数组
        if (images != null && !images.isEmpty() && multimodalConfig.getVision().isEnabled()) {
            ArrayNode content = objectMapper.createArrayNode();

            // 添加图片
            for (String image : images) {
                ObjectNode imagePart = objectMapper.createObjectNode();
                imagePart.put("type", "image_url");
                ObjectNode imageUrl = objectMapper.createObjectNode();
                // 自动检测 base64 vs URL
                if (image.startsWith("http://") || image.startsWith("https://")) {
                    imageUrl.put("url", image);
                } else {
                    imageUrl.put("url", "data:image/jpeg;base64," + image);
                }
                imagePart.set("image_url", imageUrl);
                content.add(imagePart);
            }

            // 添加文本
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            content.add(textPart);

            message.set("content", content);
        } else {
            message.put("content", prompt);
        }

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message);
        body.set("messages", messages);

        String jsonBody = objectMapper.writeValueAsString(body);
        log.debug("Multimodal request: model={}, images={}, promptLen={}",
                model, images != null ? images.size() : 0, prompt.length());

        HttpRequest request = buildRequest(jsonBody);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("M3 API error: status={}, body={}", response.statusCode(), response.body());
            throw new IOException("MiniMax M3 API error: HTTP " + response.statusCode() + " - " + response.body());
        }

        return extractContent(response.body());
    }

    /**
     * 纯图片理解（生成图片描述）
     */
    public String describeImage(String imageBase64) throws IOException, InterruptedException {
        String prompt = "请详细描述这张图片的内容，包括其中的文字、物体、场景和任何重要信息。如果是文档截图，请提取所有可读文字。";
        return chat(prompt, List.of(imageBase64), 1000);
    }

    /**
     * 批量图片理解
     */
    public String describeImageBatch(List<String> imageBase64List) throws IOException, InterruptedException {
        String prompt = "请依次详细描述以下每张图片的内容。";
        return chat(prompt, imageBase64List, 2000);
    }

    /**
     * 从文件流读取图片并转为 base64
     */
    public static String imageStreamToBase64(InputStream imageStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = imageStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    /**
     * 检查多模态是否启用
     */
    public boolean isMultimodalEnabled() {
        return multimodalConfig.isEnabled() && multimodalConfig.getVision().isEnabled();
    }

    // ──────────────── private helpers ────────────────

    private HttpRequest buildRequest(String jsonBody) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120));

        if (groupId != null && !groupId.isEmpty()) {
            builder.header("group-id", groupId);
        }

        return builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // 检查错误
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                throw new IOException("API Error: " + error.toPrettyString());
            }
            return "";
        }
        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");

        // M3 推理模型可能返回 reasoning_content 而不是 content
        if (content.isMissingNode() || content.asText("").isEmpty()) {
            JsonNode reasoningContent = message.path("reasoning_content");
            if (!reasoningContent.isMissingNode() && !reasoningContent.asText("").isEmpty()) {
                log.info("M3 returned reasoning_content (len={}), no direct content", reasoningContent.asText().length());
            }
            // 检查 finish_reason
            String finishReason = choices.get(0).path("finish_reason").asText("");
            if ("length".equals(finishReason) || content.asText("").isEmpty()) {
                log.warn("M3 response finish_reason=length or empty content, may need larger max_tokens");
            }
        }

        return M3ResponseCleaner.clean(content.asText(""));
    }
}
