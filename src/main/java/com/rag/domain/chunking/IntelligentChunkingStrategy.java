package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能分块策略
 * 根据文档类型自动选择最优分块策略
 *
 * 优先级: MIME类型 > 文件名 > 文本内容
 */
@Component
public class IntelligentChunkingStrategy implements ChunkStrategy {

    private final DocumentTypeDetector typeDetector;
    private final ChunkStrategyFactory strategyFactory;

    // 各文档类型的推荐策略配置: (策略名, chunkSize, maxParagraphLength, minParagraphLength)
    private static final StrategyConfig MARKDOWN_CONFIG = new StrategyConfig("structural", 300, 3000, 150);
    private static final StrategyConfig HTML_CONFIG = new StrategyConfig("structural", 300, 2000, 200);
    private static final StrategyConfig PDF_STRUCTURED_CONFIG = new StrategyConfig("structural", 300, 2500, 150);
    private static final StrategyConfig PDF_TEXT_CONFIG = new StrategyConfig("semantic", 200, 512, 50);
    private static final StrategyConfig DOCX_CONFIG = new StrategyConfig("structural", 300, 3000, 150);
    private static final StrategyConfig PPTX_CONFIG = new StrategyConfig("structural", 400, 2000, 200);
    private static final StrategyConfig XLSX_CONFIG = new StrategyConfig("fixed", 500, 0, 0);
    private static final StrategyConfig CODE_CONFIG = new StrategyConfig("fixed", 300, 50, 0);
    private static final StrategyConfig JSON_CONFIG = new StrategyConfig("fixed", 500, 0, 0);
    private static final StrategyConfig CSV_CONFIG = new StrategyConfig("fixed", 400, 0, 0);
    private static final StrategyConfig PLAIN_TEXT_CONFIG = new StrategyConfig("semantic", 512, 512, 50);

    public IntelligentChunkingStrategy(DocumentTypeDetector typeDetector, ChunkStrategyFactory strategyFactory) {
        this.typeDetector = typeDetector;
        this.strategyFactory = strategyFactory;
    }

    @Override
    public List<Chunk> chunk(String text, String documentId, String kbId) {
        return chunk(text, documentId, kbId, null, null);
    }

    /**
     * 根据文档类型自动分块（支持 MIME 类型）
     * @param text 文档文本
     * @param documentId 文档ID
     * @param kbId 知识库ID
     * @param fileName 文件名（可选）
     * @param mimeType Tika 检测的 MIME 类型（可选，优先使用）
     * @return 分块结果
     */
    public List<Chunk> chunk(String text, String documentId, String kbId, String fileName, String mimeType) {
        // 1. 检测文档类型（优先级：MIME > 文件名 > 内容）
        DocumentTypeDetector.DocumentType docType = typeDetector.detect(mimeType, text, fileName);

        // 2. 获取对应的策略配置
        StrategyConfig config = getConfig(docType);

        // 3. 应用对应的策略
        var params = config.toParams();
        ChunkStrategy strategy = strategyFactory.getStrategy(config.strategyName, params);

        List<Chunk> chunks = strategy.chunk(text, documentId, kbId);

        // 4. 为每个chunk添加文档类型元数据
        for (Chunk chunk : chunks) {
            chunk.getMetadata().put("detectedDocumentType", docType.name());
            chunk.getMetadata().put("chunkingStrategy", config.strategyName);
            if (mimeType != null) {
                chunk.getMetadata().put("mimeType", mimeType);
            }
        }

        return chunks;
    }

    /**
     * 根据文档类型获取策略配置
     */
    private StrategyConfig getConfig(DocumentTypeDetector.DocumentType docType) {
        return switch (docType) {
            case MARKDOWN -> MARKDOWN_CONFIG;
            case HTML -> HTML_CONFIG;
            case PDF_STRUCTURED -> PDF_STRUCTURED_CONFIG;
            case PDF_TEXT -> PDF_TEXT_CONFIG;
            case DOCX -> DOCX_CONFIG;
            case PPTX -> PPTX_CONFIG;
            case XLSX -> XLSX_CONFIG;
            case CODE -> CODE_CONFIG;
            case JSON -> JSON_CONFIG;
            case CSV -> CSV_CONFIG;
            case PLAIN_TEXT -> PLAIN_TEXT_CONFIG;
            default -> PLAIN_TEXT_CONFIG;
        };
    }

    @Override
    public String getStrategyName() {
        return "intelligent";
    }

    /**
     * 策略配置
     */
    private static class StrategyConfig {
        final String strategyName;
        final int chunkSize;
        final int maxParagraphLength;
        final int minParagraphLength;

        StrategyConfig(String strategyName, int chunkSize, int maxParagraphLength, int minParagraphLength) {
            this.strategyName = strategyName;
            this.chunkSize = chunkSize;
            this.maxParagraphLength = maxParagraphLength;
            this.minParagraphLength = minParagraphLength;
        }

        java.util.Map<String, Object> toParams() {
            var params = new java.util.HashMap<String, Object>();
            params.put("chunkSize", chunkSize);
            params.put("chunkOverlap", chunkSize / 5); // 20% 重叠
            params.put("maxParagraphLength", maxParagraphLength);
            params.put("minParagraphLength", minParagraphLength);
            params.put("maxTokensPerChunk", chunkSize / 4);
            params.put("minTokensPerChunk", chunkSize / 10);
            // overlapTokens = maxTokensPerChunk * 0.2 = chunkSize/20，约 25 tokens（语义分块的 overlap）
            params.put("overlapTokens", chunkSize / 20);
            return params;
        }
    }
}
