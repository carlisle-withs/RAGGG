package com.rag.api.rest.document;

import com.rag.application.document.DocumentApplicationService;
import com.rag.domain.model.Document;
import com.rag.domain.repository.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentApplicationService documentService;
    private final DocumentRepository documentRepository;

    public DocumentController(DocumentApplicationService documentService, DocumentRepository documentRepository) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Document> documents = documentRepository.findAll();
        List<Map<String, Object>> result = documents.stream()
                .map(doc -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", doc.getId());
                    map.put("fileName", doc.getFileName() != null ? doc.getFileName() : "");
                    map.put("kbId", doc.getKbId() != null ? doc.getKbId() : "");
                    map.put("status", doc.getStatus() != null ? doc.getStatus().name() : "");
                    map.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
                    map.put("chunkCount", doc.getChunkCount());
                    // 从 metadata 获取 chunkStrategy
                    if (doc.getMetadata() != null && doc.getMetadata().containsKey("chunkStrategy")) {
                        map.put("chunkStrategy", doc.getMetadata().get("chunkStrategy"));
                    } else {
                        map.put("chunkStrategy", "fixed");
                    }
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
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
            Map<String, Object> chunkParams = buildChunkParams(chunkStrategy, chunkSize, chunkOverlap,
                    minParagraphLength, maxParagraphLength, maxTokensPerChunk, similarityThreshold);

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

    @PostMapping("/upload/batch")
    public ResponseEntity<Map<String, Object>> uploadBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "kbId", defaultValue = "default") String kbId,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed") String chunkStrategy,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
            @RequestParam(value = "minParagraphLength", required = false) Integer minParagraphLength,
            @RequestParam(value = "maxParagraphLength", required = false) Integer maxParagraphLength,
            @RequestParam(value = "maxTokensPerChunk", required = false) Integer maxTokensPerChunk,
            @RequestParam(value = "similarityThreshold", required = false) Double similarityThreshold) {

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (MultipartFile file : files) {
            try {
                Map<String, Object> chunkParams = buildChunkParams(chunkStrategy, chunkSize, chunkOverlap,
                        minParagraphLength, maxParagraphLength, maxTokensPerChunk, similarityThreshold);

                DocumentApplicationService.DocumentUploadResult result = documentService.upload(file, kbId, chunkStrategy, chunkParams);
                results.add(Map.of(
                        "documentId", result.documentId(),
                        "fileName", result.fileName(),
                        "status", result.status()
                ));
                successCount++;
            } catch (Exception e) {
                failCount++;
                results.add(Map.of(
                        "fileName", file.getOriginalFilename(),
                        "error", e.getMessage()
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "total", files.length,
                "success", successCount,
                "failed", failCount,
                "results", results
        ));
    }

    private Map<String, Object> buildChunkParams(String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
            Integer minParagraphLength, Integer maxParagraphLength, Integer maxTokensPerChunk, Double similarityThreshold) {
        Map<String, Object> chunkParams = new HashMap<>();
        chunkParams.put("chunkStrategy", chunkStrategy);
        if (chunkSize != null) chunkParams.put("chunkSize", chunkSize);
        if (chunkOverlap != null) chunkParams.put("chunkOverlap", chunkOverlap);
        if (minParagraphLength != null) chunkParams.put("minParagraphLength", minParagraphLength);
        if (maxParagraphLength != null) chunkParams.put("maxParagraphLength", maxParagraphLength);
        if (maxTokensPerChunk != null) chunkParams.put("maxTokensPerChunk", maxTokensPerChunk);
        if (similarityThreshold != null) chunkParams.put("similarityThreshold", similarityThreshold);
        return chunkParams;
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, String>> getStatus(@PathVariable String id) {
        return documentRepository.findById(id)
                .map(doc -> ResponseEntity.ok(Map.of(
                        "documentId", doc.getId(),
                        "status", doc.getStatus() != null ? doc.getStatus().name() : "UNKNOWN"
                )))
                .orElse(ResponseEntity.ok(Map.of(
                        "documentId", id,
                        "status", "NOT_FOUND"
                )));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        documentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
