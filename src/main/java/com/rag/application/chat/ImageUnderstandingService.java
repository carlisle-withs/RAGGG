package com.rag.application.chat;

import com.rag.infrastructure.llm.MultimodalChatClient;
import com.rag.infrastructure.storage.MinioStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * 图片理解服务
 *
 * 使用 MiniMax M3 多模态能力对图片进行理解、描述和文字提取。
 * 支持将图片上传到 MinIO 并生成可检索的描述文本。
 */
@Service
public class ImageUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(ImageUnderstandingService.class);

    private final MultimodalChatClient multimodalClient;
    private final MinioStorage minioStorage;

    public ImageUnderstandingService(MultimodalChatClient multimodalClient,
                                      MinioStorage minioStorage) {
        this.multimodalClient = multimodalClient;
        this.minioStorage = minioStorage;
    }

    /**
     * 图片理解结果
     */
    public record ImageDescription(
            String imageId,
            String description,
            String extractedText,
            List<String> keywords,
            String category
    ) {}

    /**
     * Vision QA 结果
     */
    public record VisionQAResult(
            String answer,
            String imageDescription,
            List<Map<String, String>> references
    ) {}

    /**
     * 分析图片并生成结构化描述
     *
     * @param imageStream 图片输入流
     * @param imageId 图片标识（用于存储路径）
     * @param kbId 知识库 ID
     * @return 图片描述结果
     */
    public ImageDescription analyzeImage(InputStream imageStream, String imageId, String kbId) {
        try {
            if (!multimodalClient.isMultimodalEnabled()) {
                log.warn("Multimodal not enabled, skipping image analysis");
                return new ImageDescription(imageId, "多模态未启用", "", List.of(), "unknown");
            }

            // 1. 将图片上传到 MinIO
            String minioPath = kbId + "/images/" + imageId + ".jpg";
            byte[] imageBytes = imageStream.readAllBytes();
            minioStorage.upload(minioPath,
                    new java.io.ByteArrayInputStream(imageBytes),
                    imageBytes.length,
                    "image/jpeg");
            log.info("Image uploaded to MinIO: {}", minioPath);

            // 2. 使用 M3 生成图片描述
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
            String description = multimodalClient.describeImage(imageBase64);
            log.info("Image description generated: {} chars", description.length());

            // 3. 用 M3 结构化提取信息
            String structuredPrompt = String.format("""
                    基于以下图片描述，提取结构化信息，返回 JSON 格式：
                    {
                      "extracted_text": "图片中的所有可读文字",
                      "keywords": ["关键词1", "关键词2", ...],
                      "category": "图表/文档截图/照片/示意图/其他"
                    }

                    图片描述：
                    %s
                    """, description);

            String structuredResult = multimodalClient.chat(structuredPrompt);
            StructuredInfo info = parseStructuredInfo(structuredResult);

            return new ImageDescription(imageId, description, info.extractedText, info.keywords, info.category);

        } catch (Exception e) {
            log.error("Image analysis failed: imageId={}", imageId, e);
            return new ImageDescription(imageId, "分析失败: " + e.getMessage(), "", List.of(), "unknown");
        }
    }

    /**
     * Vision QA - 用户上传图片 + 问题
     *
     * @param imageBase64 图片 base64
     * @param question 用户问题
     * @param context 可选的上下文文档
     * @return QA 结果
     */
    public VisionQAResult visionQA(String imageBase64, String question, String context) {
        try {
            StringBuilder prompt = new StringBuilder();
            if (context != null && !context.isEmpty()) {
                prompt.append("【参考文档】\n").append(context).append("\n\n");
            }
            prompt.append("【用户问题】\n").append(question).append("\n\n");
            prompt.append("请根据图片内容和参考文档回答问题。");

            String answer = multimodalClient.chat(prompt.toString(), List.of(imageBase64), 2000);

            // 获取图片描述作为引用来源
            String imageDesc = multimodalClient.describeImage(imageBase64);

            List<Map<String, String>> references = new ArrayList<>();
            Map<String, String> ref = new HashMap<>();
            ref.put("type", "image");
            ref.put("description", imageDesc);
            references.add(ref);

            return new VisionQAResult(answer, imageDesc, references);

        } catch (Exception e) {
            log.error("Vision QA failed", e);
            return new VisionQAResult("抱歉，图片分析失败：" + e.getMessage(), "", List.of());
        }
    }

    /**
     * 多模态文档处理 - 从文档中提取图片并生成描述
     *
     * @param imageBase64List 文档中的图片列表
     * @return 所有图片的描述文本（用于索引）
     */
    public String generateDocumentImageDescriptions(List<String> imageBase64List) {
        if (imageBase64List == null || imageBase64List.isEmpty()) {
            return "";
        }
        if (!multimodalClient.isMultimodalEnabled()) {
            return "";
        }

        try {
            StringBuilder allDescriptions = new StringBuilder();
            allDescriptions.append("【文档图片描述】\n\n");

            for (int i = 0; i < imageBase64List.size(); i++) {
                String desc = multimodalClient.describeImage(imageBase64List.get(i));
                allDescriptions.append("图片 ").append(i + 1).append(": ").append(desc).append("\n\n");
            }

            return allDescriptions.toString();
        } catch (Exception e) {
            log.error("Document image description failed", e);
            return "";
        }
    }

    /**
     * 检查多模态是否可用
     */
    public boolean isAvailable() {
        return multimodalClient.isMultimodalEnabled();
    }

    // ──────────────── private helpers ────────────────

    private static class StructuredInfo {
        String extractedText = "";
        List<String> keywords = List.of();
        String category = "unknown";
    }

    private StructuredInfo parseStructuredInfo(String llmOutput) {
        StructuredInfo info = new StructuredInfo();
        try {
            // 尝试提取 JSON
            int braceStart = llmOutput.indexOf('{');
            int braceEnd = llmOutput.lastIndexOf('}') + 1;
            if (braceStart >= 0 && braceEnd > braceStart) {
                String json = llmOutput.substring(braceStart, braceEnd);
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = om.readTree(json);
                if (node.has("extracted_text")) info.extractedText = node.get("extracted_text").asText();
                if (node.has("keywords")) {
                    info.keywords = new ArrayList<>();
                    node.get("keywords").forEach(k -> info.keywords.add(k.asText()));
                }
                if (node.has("category")) info.category = node.get("category").asText();
            }
        } catch (Exception e) {
            log.debug("Failed to parse structured info from LLM output, using raw text");
            info.extractedText = llmOutput;
        }
        return info;
    }
}
