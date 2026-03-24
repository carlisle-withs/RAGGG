package com.rag.application.document;

import com.rag.domain.event.DocumentEvent;
import com.rag.infrastructure.mq.DocumentEventProducer;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
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

    public DocumentUploadResult upload(MultipartFile file, String kbId, String chunkStrategy, Map<String, Object> chunkParams) {
        // 生成 traceId 用于全链路追踪
        String traceId = UUID.randomUUID().toString();

        try {
            String documentId = UUID.randomUUID().toString();
            String fileName = file.getOriginalFilename();
            String fileType = file.getContentType();

            TraceLogger tracer = TraceLogger.get(DocumentApplicationService.class, traceId, documentId);

            tracer.step("1. UPLOAD_START");
            tracer.info("开始上传文件: fileName=%s, size=%d, kbId=%s, chunkStrategy=%s",
                    fileName, file.getSize(), kbId, chunkStrategy);

            // Upload to MinIO using stream
            String objectName = kbId + "/" + documentId + "/" + fileName;

            tracer.info("上传到 MinIO: path=%s", objectName);
            minioStorage.upload(objectName, file);

            tracer.stepComplete("1. UPLOAD_MINIO", objectName);

            // Prepare metadata with chunk strategy
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileSize", file.getSize());
            metadata.put("chunkStrategy", chunkStrategy);
            metadata.put("chunkParams", chunkParams);

            // 创建事件，包含 traceId
            DocumentEvent event = DocumentEvent.create(documentId, kbId, fileName, fileType, objectName, metadata);
            event.setTraceId(traceId);

            tracer.step("2. SEND_KAFKA_MESSAGE");
            tracer.info("发送 Kafka 消息: topic=document-upload, eventType=%s", event.getEventType());

            eventProducer.sendUploaded(event);

            tracer.stepComplete("2. KAFKA_SENT", "document-upload");
            tracer.info("文档上传完成: documentId=%s, traceId=%s", documentId, traceId);

            return new DocumentUploadResult(documentId, fileName, "PENDING", traceId);

        } catch (Exception e) {
            log.error("[%s] Upload failed: %s".formatted(traceId, e.getMessage()), e);
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    public record DocumentUploadResult(String documentId, String fileName, String status, String traceId) {}
}
