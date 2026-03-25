package com.rag.domain.chunking;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChunkStrategyFactory {

    public ChunkStrategy getStrategy(String strategyName, Map<String, Object> params) {
        ChunkStrategy strategy = switch (strategyName.toLowerCase()) {
            case "fixed" -> new FixedChunkStrategy();
            case "structural" -> new StructuralChunkStrategy();
            case "semantic" -> new SemanticChunkStrategy();
            default -> new FixedChunkStrategy();
        };

        // Apply parameters
        if (params != null) {
            applyParams(strategy, params);
        }

        return strategy;
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
        }
    }
}
