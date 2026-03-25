package com.rag.api.rest.document;

import com.rag.application.document.DocumentApplicationService;
import com.rag.domain.model.Document;
import com.rag.domain.model.User;
import com.rag.domain.repository.DocumentRepository;
import com.rag.domain.repository.KnowledgeBaseRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final KnowledgeBaseRepository kbRepository;

    public DocumentController(DocumentApplicationService documentService, DocumentRepository documentRepository,
                              KnowledgeBaseRepository kbRepository) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.kbRepository = kbRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(value = "kbId", required = false) String kbId) {
        List<Document> documents;
        User currentUser = getCurrentUser();

        if (isAdmin()) {
            documents = documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        } else {
            List<String> userKbIds = kbRepository.findByOwner_Id(currentUser.getId())
                    .stream().map(kb -> kb.getId()).collect(Collectors.toList());

            documents = documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .stream()
                    .filter(doc -> userKbIds.contains(doc.getKbId()))
                    .collect(Collectors.toList());
        }

        if (kbId != null && !kbId.isEmpty()) {
            documents = documents.stream()
                    .filter(doc -> kbId.equals(doc.getKbId()))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> result = documents.stream()
                .map(doc -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", doc.getId());
                    map.put("fileName", doc.getFileName() != null ? doc.getFileName() : "");
                    map.put("kbId", doc.getKbId() != null ? doc.getKbId() : "");
                    map.put("status", doc.getStatus() != null ? doc.getStatus().name() : "");
                    map.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
                    map.put("chunkCount", doc.getChunkCount());
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
            @RequestParam(value = "kbId", required = false) String kbId,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed") String chunkStrategy,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
            @RequestParam(value = "minParagraphLength", required = false) Integer minParagraphLength,
            @RequestParam(value = "maxParagraphLength", required = false) Integer maxParagraphLength,
            @RequestParam(value = "maxTokensPerChunk", required = false) Integer maxTokensPerChunk,
            @RequestParam(value = "similarityThreshold", required = false) Double similarityThreshold) {

        try {
            if (kbId != null && !kbId.isEmpty() && !hasKbAccess(kbId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "Access denied",
                        "message", "You don't have access to this knowledge base"
                ));
            }

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
            @RequestParam(value = "kbId", required = false) String kbId,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed") String chunkStrategy,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
            @RequestParam(value = "minParagraphLength", required = false) Integer minParagraphLength,
            @RequestParam(value = "maxParagraphLength", required = false) Integer maxParagraphLength,
            @RequestParam(value = "maxTokensPerChunk", required = false) Integer maxTokensPerChunk,
            @RequestParam(value = "similarityThreshold", required = false) Double similarityThreshold) {

        if (kbId != null && !kbId.isEmpty() && !hasKbAccess(kbId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Access denied",
                    "message", "You don't have access to this knowledge base"
            ));
        }

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
        documentRepository.findById(id).ifPresent(doc -> {
            if (!isAdmin() && !hasKbAccess(doc.getKbId())) {
                throw new SecurityException("Access denied");
            }
            documentRepository.deleteById(id);
        });
        return ResponseEntity.noContent().build();
    }

    private boolean hasKbAccess(String kbId) {
        if (isAdmin()) return true;
        User currentUser = getCurrentUser();
        return kbRepository.findById(kbId)
                .map(kb -> kb.getOwner() != null && kb.getOwner().getId().equals(currentUser.getId()))
                .orElse(false);
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
