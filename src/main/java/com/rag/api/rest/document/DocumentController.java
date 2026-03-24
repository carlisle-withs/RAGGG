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
            @RequestParam(value = "kbId", defaultValue = "default") String kbId) {

        try {
            DocumentApplicationService.DocumentUploadResult result = documentService.upload(file, kbId);

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "documentId", result.documentId(),
                    "fileName", result.fileName(),
                    "status", result.status()
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
