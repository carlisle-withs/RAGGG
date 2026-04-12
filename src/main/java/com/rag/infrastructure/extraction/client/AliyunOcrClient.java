package com.rag.infrastructure.extraction.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import com.rag.infrastructure.extraction.model.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;

@Component
public class AliyunOcrClient implements OcrClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunOcrClient.class);

    private final AppConfig.Extraction.Image.Ocr config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public AliyunOcrClient(AppConfig appConfig, ObjectMapper objectMapper) {
        this.config = appConfig.getExtraction().getImage().getOcr();
        this.objectMapper = objectMapper;
        this.enabled = config.getApiKey() != null && !config.getApiKey().isBlank();
        this.webClient = WebClient.builder()
                .baseUrl("https://ocr-api." + config.getRegion() + ".aliyuncs.com")
                .defaultHeader("Content-Type", "application/json; charset=utf-8")
                .build();
        log.info("AliyunOcrClient initialized: provider={}, region={}, enabled={}",
                config.getProvider(), config.getRegion(), enabled);
    }

    @Override
    public OcrResult extractText(byte[] imageData) {
        if (!enabled) {
            log.warn("Aliyun OCR is not enabled, returning empty result");
            return OcrResult.failure("OCR not enabled - API key not configured");
        }

        try {
            String imageBase64 = Base64.getEncoder().encodeToString(imageData);
            String requestBody = buildRequestBody(imageBase64);

            String response = webClient.post()
                    .uri("/api/ocr/handwriting")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Api-Key", config.getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .block();

            return parseResponse(response);

        } catch (Exception e) {
            log.error("OCR extraction failed: {}", e.getMessage(), e);
            return OcrResult.failure(e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getProviderName() {
        return "aliyun";
    }

    private String buildRequestBody(String imageBase64) {
        return String.format("""
            {
                "image": "%s",
                "prob": true,
                "rotate": true,
                "language": "%s"
            }
            """, imageBase64, config.getLanguage());
    }

    private OcrResult parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            OcrResult result = new OcrResult();

            if (root.has("success") && root.get("success").asBoolean()) {
                JsonNode data = root.get("data");
                if (data != null && data.has("text")) {
                    result.setText(data.get("text").asText());
                    result.setSuccess(true);

                    if (data.has("boxes")) {
                        result.setBlocks(objectMapper.convertValue(
                                data.get("boxes"),
                                new com.fasterxml.jackson.core.type.TypeReference<>() {}
                        ));
                    }
                    if (data.has("probability")) {
                        JsonNode prob = data.get("probability");
                        if (prob.has("average")) {
                            result.setConfidence((float) prob.get("average").asDouble());
                        }
                    }
                }
            } else {
                String errorMsg = root.has("message") ? root.get("message").asText() : "Unknown error";
                log.error("OCR API error: {}", errorMsg);
                return OcrResult.failure(errorMsg);
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to parse OCR response: {}", e.getMessage());
            return OcrResult.failure("Failed to parse response: " + e.getMessage());
        }
    }
}