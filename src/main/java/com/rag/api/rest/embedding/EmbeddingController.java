package com.rag.api.rest.embedding;

import com.rag.infrastructure.llm.EmbeddingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Embedding 接口（供外部评测脚本调用）
 */
@RestController
@RequestMapping("/api/v1/embedding")
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    public EmbeddingController(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /** 单条文本向量化 */
    @PostMapping("/embed")
    public ResponseEntity<?> embed(@RequestBody Map<String, Object> request) {
        String text = (String) request.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        float[] vector = embeddingService.embed(text);
        return ResponseEntity.ok(Map.of(
                "vector", vector,
                "dimension", vector.length
        ));
    }

    /** 批量向量化 */
    @PostMapping("/embed/batch")
    public ResponseEntity<?> embedBatch(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> texts = (List<String>) request.get("texts");
        if (texts == null || texts.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "texts is required"));
        }
        List<float[]> vectors = embeddingService.embedBatch(texts);
        return ResponseEntity.ok(Map.of(
                "vectors", vectors,
                "count", vectors.size(),
                "dimension", vectors.isEmpty() ? 0 : vectors.get(0).length
        ));
    }
}
