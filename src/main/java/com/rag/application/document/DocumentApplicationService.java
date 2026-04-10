package com.rag.application.document;

import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.repository.KnowledgeDocumentRepository;
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
    private final KnowledgeDocumentRepository documentRepository;

    public DocumentApplicationService(MinioStorage minioStorage,
                                     DocumentEventProducer eventProducer,
                                     KnowledgeDocumentRepository documentRepository) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.documentRepository = documentRepository;
    }

    public DocumentUploadResult upload(MultipartFile file, Long kbId, String chunkStrategy, Map<String, Object> chunkParams) {
        String traceId = UUID.randomUUID().toString();

        try {
            Long documentId = System.currentTimeMillis();
            String fileName = file.getOriginalFilename();
            String fileType = file.getContentType();

            TraceLogger tracer = TraceLogger.get(DocumentApplicationService.class, traceId, documentId.toString());

            tracer.step("1. UPLOAD_START");
            tracer.info("开始上传文件: fileName=%s, size=%d, kbId=%s, chunkStrategy=%s",
                    fileName, file.getSize(), kbId, chunkStrategy);

            String objectName = kbId + "/" + documentId + "/" + fileName;

            tracer.info("上传到 MinIO: path=%s", objectName);
            minioStorage.upload(objectName, file);

            tracer.stepComplete("1. UPLOAD_MINIO", objectName);

            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setId(documentId);
            doc.setDocName(fileName);
            doc.setFileType(fileType);
            doc.setKbId(kbId);
            doc.setFileUrl(objectName);
            doc.setStatus(KnowledgeDocument.DocumentStatus.PENDING);

            Map<String, String> metadata = new HashMap<>();
            metadata.put("fileSize", String.valueOf(file.getSize()));
            metadata.put("chunkStrategy", chunkStrategy);
            metadata.put("traceId", traceId);
            doc.setChunkStrategy(chunkStrategy);

            documentRepository.save(doc);
            tracer.info("文档保存到数据库: documentId=%s, status=UPLOADED", documentId);

            Map<String, Object> eventMetadata = new HashMap<>();
            eventMetadata.putAll(chunkParams);
            eventMetadata.put("fileSize", String.valueOf(file.getSize()));
            eventMetadata.put("chunkStrategy", chunkStrategy);
            DocumentEvent event = DocumentEvent.create(documentId.toString(), kbId.toString(), fileName, fileType, objectName, eventMetadata);
            event.setTraceId(traceId);

            tracer.step("2. SEND_KAFKA_MESSAGE");
            tracer.info("发送 Kafka 消息: topic=document-upload, eventType=%s", event.getEventType());

            eventProducer.sendUploaded(event);

            tracer.stepComplete("2. KAFKA_SENT", "document-upload");
            tracer.info("文档上传完成: documentId=%s, traceId=%s", documentId, traceId);

            return new DocumentUploadResult(documentId.toString(), fileName, "UPLOADED", traceId);

        } catch (Exception e) {
            log.error("[%s] Upload failed: %s".formatted(traceId, e.getMessage()), e);
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    public record DocumentUploadResult(String documentId, String fileName, String status, String traceId) {}
}
