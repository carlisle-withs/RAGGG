package com.rag.domain.chunking;

import com.rag.domain.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ChunkStrategyFactory {

    public ChunkStrategy getStrategy(String strategyName, Map<String, Object> params) {
        ChunkStrategy strategy = switch (strategyName.toLowerCase()) {
            case "fixed" -> new FixedChunkStrategy();
            case "structural" -> new StructuralChunkStrategy();
            case "semantic" -> new SemanticChunkStrategy();
            case "hybrid" -> new HybridChunkStrategy();
            default -> new FixedChunkStrategy();
        };

        // Apply parameters
        if (params != null) {
            applyParams(strategy, params);
        }

        return strategy;
    }

    /**
     * 获取智能分块策略（根据 Tika MIME 类型检测文档类型）
     */
    public List<Chunk> getIntelligentChunks(String text, String documentId, String kbId, String mimeType) {
        IntelligentChunkingStrategy strategy = new IntelligentChunkingStrategy(
            new DocumentTypeDetector(), this);
        return strategy.chunk(text, documentId, kbId, null, mimeType);
    }

    /**
     * 获取智能分块策略（根据文件名检测文档类型）
     */
    public List<Chunk> getIntelligentChunksByFileName(String text, String documentId, String kbId, String fileName) {
        IntelligentChunkingStrategy strategy = new IntelligentChunkingStrategy(
            new DocumentTypeDetector(), this);
        return strategy.chunk(text, documentId, kbId, fileName, null);
    }

    /**
     * 获取智能分块策略（仅根据文本内容检测）
     */
    public List<Chunk> getIntelligentChunks(String text, String documentId, String kbId) {
        return getIntelligentChunks(text, documentId, kbId, (String) null);
    }

    private void applyParams(ChunkStrategy strategy, Map<String, Object> params) {
        if (strategy instanceof FixedChunkStrategy fixed) {
            if (params.containsKey("chunkSize")) {
                fixed.setChunkSize((Integer) params.get("chunkSize"));
            }
            if (params.containsKey("chunkOverlap")) {
                fixed.setChunkOverlap((Integer) params.get("chunkOverlap"));
            }
        } else if (strategy instanceof StructuralChunkStrategy structural) {
            if (params.containsKey("minParagraphLength")) {
                structural.setMinParagraphLength((Integer) params.get("minParagraphLength"));
            }
            if (params.containsKey("maxParagraphLength")) {
                structural.setMaxParagraphLength((Integer) params.get("maxParagraphLength"));
            }
        } else if (strategy instanceof SemanticChunkStrategy semantic) {
            if (params.containsKey("maxTokensPerChunk")) {
                semantic.setMaxTokensPerChunk((Integer) params.get("maxTokensPerChunk"));
            }
            if (params.containsKey("minTokensPerChunk")) {
                semantic.setMinTokensPerChunk((Integer) params.get("minTokensPerChunk"));
            }
        } else if (strategy instanceof HybridChunkStrategy hybrid) {
            if (params.containsKey("maxTokensPerChunk")) {
                hybrid.setMaxTokensPerChunk((Integer) params.get("maxTokensPerChunk"));
            }
        }
    }
}
