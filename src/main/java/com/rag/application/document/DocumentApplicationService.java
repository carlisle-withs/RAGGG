package com.rag.application.document;

import com.rag.config.MetricsConfig;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.repository.KnowledgeDocumentRepository;
import com.rag.infrastructure.mq.DocumentEventProducer;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final KnowledgeDocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    // Timers for each upload sub-stage
    private final Timer minioUploadTimer;
    private final Timer dbSaveTimer;
    private final Timer kafkaSendTimer;
    private final Timer fullUploadTimer;

    // Counters
    private final Counter uploadSuccessCounter;
    private final Counter uploadFailureCounter;

    public DocumentApplicationService(MinioStorage minioStorage,
                                     DocumentEventProducer eventProducer,
                                     KnowledgeDocumentRepository documentRepository,
                                     ApplicationEventPublisher eventPublisher,
                                     MeterRegistry meterRegistry) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.documentRepository = documentRepository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;

        this.minioUploadTimer = Timer.builder("doc.upload.stage")
                .tag("stage", "minio").tag("kb_id", "all")
                .description("MinIO 文件上传耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.dbSaveTimer = Timer.builder("doc.upload.stage")
                .tag("stage", "db_save").tag("kb_id", "all")
                .description("数据库保存耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.kafkaSendTimer = Timer.builder("doc.upload.stage")
                .tag("stage", "kafka_send").tag("kb_id", "all")
                .description("Kafka 消息发送耗时（publishEvent）")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.fullUploadTimer = Timer.builder("doc.upload")
                .tag("kb_id", "all")
                .description("完整上传链路耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.uploadSuccessCounter = Counter.builder("doc.upload.count")
                .tag("status", "success")
                .description("上传成功次数").register(meterRegistry);

        this.uploadFailureCounter = Counter.builder("doc.upload.count")
                .tag("status", "failure")
                .description("上传失败次数").register(meterRegistry);
    }

    @Transactional
    public DocumentUploadResult upload(MultipartFile file, Long kbId, String chunkStrategy, Map<String, Object> chunkParams) {
        String traceId = UUID.randomUUID().toString();
        long t0 = System.nanoTime();

        try {
            String fileName = file.getOriginalFilename();
            String fileType = file.getContentType();

            TraceLogger tracer = TraceLogger.get(DocumentApplicationService.class, traceId, "upload");

            tracer.step("1. UPLOAD_START");
            tracer.info("开始上传文件: fileName=%s, size=%d, kbId=%s, chunkStrategy=%s",
                    fileName, file.getSize(), kbId, chunkStrategy);

            Long tempId = System.currentTimeMillis();
            String objectName = kbId + "/" + tempId + "/" + fileName;

            // ---- MinIO 上传 ----
            long t1 = System.nanoTime();
            tracer.info("上传到 MinIO: path=%s", objectName);
            minioStorage.upload(objectName, file);
            long minioMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);
            minioUploadTimer.record(minioMs, TimeUnit.MILLISECONDS);
            tracer.stepComplete("1. UPLOAD_MINIO", objectName);

            // ---- DB 保存 ----
            long t2 = System.nanoTime();
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setDocName(fileName);
            doc.setFileType(fileType);
            doc.setKbId(kbId);
            doc.setFileUrl(objectName);
            doc.setStatus(KnowledgeDocument.DocumentStatus.PENDING);
            doc.setCreatedBy("guest");
            doc.setChunkStrategy(chunkStrategy);
            doc.setEnabled(true);
            doc.setDeleted(false);

            KnowledgeDocument saved = documentRepository.save(doc);
            Long documentId = saved.getId();
            long dbMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t2);
            dbSaveTimer.record(dbMs, TimeUnit.MILLISECONDS);
            tracer.info("文档保存到数据库: documentId=%s, fileUrl=%s", documentId, objectName);

            // ---- Kafka 消息 ----
            long t3 = System.nanoTime();
            Map<String, Object> eventMetadata = new HashMap<>();
            eventMetadata.putAll(chunkParams);
            eventMetadata.put("fileSize", String.valueOf(file.getSize()));
            eventMetadata.put("chunkStrategy", chunkStrategy);
            DocumentEvent event = DocumentEvent.create(documentId.toString(), kbId.toString(), fileName, fileType, objectName, eventMetadata);
            event.setTraceId(traceId);

            tracer.step("2. SEND_KAFKA_MESSAGE");
            tracer.info("发布 DocumentSavedEvent (将在事务提交后发送 Kafka 消息): eventType=%s", event.getEventType());

            eventPublisher.publishEvent(new DocumentSavedEvent(this, event));
            long kafkaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t3);
            kafkaSendTimer.record(kafkaMs, TimeUnit.MILLISECONDS);
            tracer.stepComplete("2. KAFKA_SENT", "document-upload");

            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            fullUploadTimer.record(totalMs, TimeUnit.MILLISECONDS);
            uploadSuccessCounter.increment();
            tracer.info("文档上传完成: documentId=%s, traceId=%s, totalMs=%d", documentId, traceId, totalMs);

            return new DocumentUploadResult(documentId.toString(), fileName, "UPLOADED", traceId);

        } catch (Exception e) {
            uploadFailureCounter.increment();
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            fullUploadTimer.record(totalMs, TimeUnit.MILLISECONDS);
            log.error("[%s] Upload failed after %d ms: %s".formatted(traceId, totalMs, e.getMessage()), e);
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    public record DocumentUploadResult(String documentId, String fileName, String status, String traceId) {}
}
