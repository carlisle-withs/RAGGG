package com.rag.application.document;

import com.rag.application.chat.ImageUnderstandingService;
import com.rag.infrastructure.llm.MultimodalChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 多模态文档增强服务
 *
 * 在文档处理管线中集成图片理解能力：
 * 1. 从文档中提取图片
 * 2. 使用 M3 生成图片描述
 * 3. 将描述文本合并到文档内容中，使图片信息可被检索
 */
@Service
public class MultimodalDocumentEnhancer {

    private static final Logger log = LoggerFactory.getLogger(MultimodalDocumentEnhancer.class);

    private final ImageUnderstandingService imageService;
    private final MultimodalChatClient multimodalClient;

    public MultimodalDocumentEnhancer(ImageUnderstandingService imageService,
                                       MultimodalChatClient multimodalClient) {
        this.imageService = imageService;
        this.multimodalClient = multimodalClient;
    }

    /**
     * 增强文档解析结果 - 为图片生成描述文本
     *
     * @param documentText 原始文档文本
     * @param images 文档中的图片（base64 编码）
     * @param documentId 文档 ID
     * @param kbId 知识库 ID
     * @return 增强后的文档文本（原始文本 + 图片描述）
     */
    public String enhanceWithImageDescriptions(String documentText,
                                                List<String> images,
                                                String documentId,
                                                String kbId) {
        if (!isEnabled() || images == null || images.isEmpty()) {
            return documentText;
        }

        log.info("Enhancing document {} with {} image descriptions", documentId, images.size());

        try {
            StringBuilder enhanced = new StringBuilder();
            enhanced.append(documentText);

            if (!documentText.isEmpty()) {
                enhanced.append("\n\n");
            }

            enhanced.append("【文档图片内容】\n");

            for (int i = 0; i < Math.min(images.size(), 10); i++) {
                try {
                    String imageId = documentId + "_img_" + i;
                    ImageUnderstandingService.ImageDescription desc =
                            imageService.analyzeImage(
                                    new java.io.ByteArrayInputStream(Base64.getDecoder().decode(images.get(i))),
                                    imageId,
                                    kbId
                            );

                    enhanced.append(String.format("\n--- 图片 %d ---\n", i + 1));
                    enhanced.append("描述: ").append(desc.description()).append("\n");
                    if (desc.extractedText() != null && !desc.extractedText().isEmpty()) {
                        enhanced.append("文字: ").append(desc.extractedText()).append("\n");
                    }
                    if (desc.keywords() != null && !desc.keywords().isEmpty()) {
                        enhanced.append("关键词: ").append(String.join(", ", desc.keywords())).append("\n");
                    }
                    enhanced.append("类型: ").append(desc.category()).append("\n");

                } catch (Exception e) {
                    log.error("Failed to process image {} for document {}", i, documentId, e);
                    enhanced.append(String.format("\n--- 图片 %d (处理失败) ---\n", i + 1));
                }
            }

            if (images.size() > 10) {
                enhanced.append(String.format("\n... 还有 %d 张图片\n", images.size() - 10));
            }

            log.info("Document {} enhanced: original={} chars, enhanced={} chars",
                    documentId, documentText.length(), enhanced.length());

            return enhanced.toString();

        } catch (Exception e) {
            log.error("Multimodal document enhancement failed for {}", documentId, e);
            return documentText;
        }
    }

    /**
     * 快速生成文档摘要图片描述（仅描述前3张图片）
     */
    public String quickImageSummary(List<String> images, String documentId) {
        if (!isEnabled() || images == null || images.isEmpty()) {
            return "";
        }

        try {
            int count = Math.min(images.size(), 3);
            List<String> topImages = images.subList(0, count);
            return imageService.generateDocumentImageDescriptions(topImages);
        } catch (Exception e) {
            log.error("Quick image summary failed for {}", documentId, e);
            return "";
        }
    }

    /**
     * 提取图片中的文字（OCR 替代方案）
     */
    public String extractTextFromImage(String imageBase64) {
        if (!isEnabled()) return "";

        try {
            String prompt = "请提取这张图片中的所有文字，按原文顺序输出。不要添加任何解释。如果图片中没有文字，回复'无文字'。";
            return multimodalClient.chat(prompt, List.of(imageBase64), 500);
        } catch (Exception e) {
            log.error("Image text extraction failed", e);
            return "";
        }
    }

    public boolean isEnabled() {
        return multimodalClient.isMultimodalEnabled();
    }
}
