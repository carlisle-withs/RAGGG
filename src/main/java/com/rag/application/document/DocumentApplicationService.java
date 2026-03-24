package com.rag.application.document;

import com.rag.domain.event.DocumentEvent;
import com.rag.infrastructure.mq.DocumentEventProducer;
import com.rag.infrastructure.storage.MinioStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;

    public DocumentApplicationService(MinioStorage minioStorage, DocumentEventProducer eventProducer) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
    }

    public DocumentUploadResult upload(MultipartFile file, String kbId) {
        try {
            String documentId = UUID.randomUUID().toString();
            String fileName = file.getOriginalFilename();
            String fileType = file.getContentType();

            // Upload to MinIO
            String objectName = kbId + "/" + documentId + "/" + fileName;
            minioStorage.upload(objectName, file.getBytes(), fileType);

            // Send event
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileSize", file.getSize());

            DocumentEvent event = DocumentEvent.create(documentId, kbId, fileName, fileType, objectName, metadata);
            eventProducer.sendUploaded(event);

            log.info("Document uploaded: {}", documentId);

            return new DocumentUploadResult(documentId, fileName, "PENDING");

        } catch (Exception e) {
            log.error("Upload failed", e);
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    public record DocumentUploadResult(String documentId, String fileName, String status) {}
}
