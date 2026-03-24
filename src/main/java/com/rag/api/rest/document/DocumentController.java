package com.rag.api.rest.document;

import com.rag.application.document.DocumentApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentApplicationService documentService;

    public DocumentController(DocumentApplicationService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbId", defaultValue = "default") String kbId,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed") String chunkStrategy,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
            @RequestParam(value = "minParagraphLength", required = false) Integer minParagraphLength,
            @RequestParam(value = "maxParagraphLength", required = false) Integer maxParagraphLength,
            @RequestParam(value = "maxTokensPerChunk", required = false) Integer maxTokensPerChunk,
            @RequestParam(value = "similarityThreshold", required = false) Double similarityThreshold) {

        try {
            // Build chunk params
            Map<String, Object> chunkParams = Map.of(
                "chunkStrategy", chunkStrategy
            );

            // Add optional params if provided
            if (chunkSize != null) {
                chunkParams = new java.util.HashMap<>(chunkParams);
                ((java.util.Map<String, Object>) chunkParams).put("chunkSize", chunkSize);
            }
            if (chunkOverlap != null) {
                if (chunkParams instanceof java.util.HashMap) {
                    ((java.util.Map<String, Object>) chunkParams).put("chunkOverlap", chunkOverlap);
                } else {
                    chunkParams = new java.util.HashMap<>(chunkParams);
                    ((java.util.Map<String, Object>) chunkParams).put("chunkOverlap", chunkOverlap);
                }
            }
            if (minParagraphLength != null) {
                chunkParams = new java.util.HashMap<>(chunkParams);
                ((java.util.Map<String, Object>) chunkParams).put("minParagraphLength", minParagraphLength);
            }
            if (maxParagraphLength != null) {
                chunkParams = new java.util.HashMap<>(chunkParams);
                ((java.util.Map<String, Object>) chunkParams).put("maxParagraphLength", maxParagraphLength);
            }
            if (maxTokensPerChunk != null) {
                chunkParams = new java.util.HashMap<>(chunkParams);
                ((java.util.Map<String, Object>) chunkParams).put("maxTokensPerChunk", maxTokensPerChunk);
            }
            if (similarityThreshold != null) {
                chunkParams = new java.util.HashMap<>(chunkParams);
                ((java.util.Map<String, Object>) chunkParams).put("similarityThreshold", similarityThreshold);
            }

            DocumentApplicationService.DocumentUploadResult result = documentService.upload(file, kbId, chunkStrategy, chunkParams);

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "documentId", result.documentId(),
                    "fileName", result.fileName(),
                    "status", result.status(),
                    "chunkStrategy", chunkStrategy
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Upload failed",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String id) {
        return ResponseEntity.ok(Map.of(
                "documentId", id,
                "status", "PROCESSING"
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return ResponseEntity.noContent().build();
    }
}
