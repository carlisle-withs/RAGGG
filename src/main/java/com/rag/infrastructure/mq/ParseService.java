package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.repository.KnowledgeDocumentRepository;
import com.rag.infrastructure.extraction.service.EnhancedContentProcessor;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Tika tika;
    private final KnowledgeDocumentRepository documentRepository;
    private final EnhancedContentProcessor enhancedContentProcessor;
    private final boolean extractionEnabled;
    private final MeterRegistry meterRegistry;

    private final Timer parseTimer;
    private final Timer tikaExtractTimer;
    private final Timer minioDownloadTimer;
    private final Timer minioUploadTimer;
    private final Counter parseSuccessCounter;
    private final Counter parseFailureCounter;
    private final Counter parseSkipCounter;
    private final DistributionSummary textLengthSummary;

    public ParseService(MinioStorage minioStorage,
                       DocumentEventProducer eventProducer,
                       ObjectMapper objectMapper,
                       KnowledgeDocumentRepository documentRepository,
                       EnhancedContentProcessor enhancedContentProcessor,
                       AppConfig appConfig,
                       MeterRegistry meterRegistry) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.enhancedContentProcessor = enhancedContentProcessor;
        this.extractionEnabled = appConfig.getExtraction().isEnabled();
        this.meterRegistry = meterRegistry;
        this.tika = new Tika();
        log.info("ParseService initialized: extractionEnabled={}", extractionEnabled);

        this.parseTimer = Timer.builder("doc.pipeline").tag("stage", "parse").description("文档解析总耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.tikaExtractTimer = Timer.builder("doc.pipeline.extract").tag("method", "tika").description("Tika 文本提取耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.minioDownloadTimer = Timer.builder("doc.pipeline.io").tag("op", "minio_download").description("MinIO 下载耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.minioUploadTimer = Timer.builder("doc.pipeline.io").tag("op", "minio_upload").description("MinIO 上传耗时")
                .publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.parseSuccessCounter = Counter.builder("doc.pipeline.count").tag("stage", "parse").tag("status", "success").description("解析成功次数").register(meterRegistry);
        this.parseFailureCounter = Counter.builder("doc.pipeline.count").tag("stage", "parse").tag("status", "failure").description("解析失败次数").register(meterRegistry);
        this.parseSkipCounter = Counter.builder("doc.pipeline.count").tag("stage", "parse").tag("status", "skip").description("解析跳过次数（已删除/不存在）").register(meterRegistry);
        this.textLengthSummary = DistributionSummary.builder("doc.pipeline.text_length").description("解析后文本长度分布").baseUnit("chars").register(meterRegistry);
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_UPLOAD, groupId = "${spring.kafka.consumer.group-id}-parse")
    public void consume(String message) {
        doProcess(message);
    }

    @Transactional
    public void doProcess(String message) {
        long t0 = System.nanoTime();
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            String traceId = event.getTraceId();
            Long documentId = Long.parseLong(event.getDocumentId());

            TraceLogger tracer = TraceLogger.get(ParseService.class, traceId, documentId.toString());

            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty()) {
                tracer.info("文档不存在，跳过处理: documentId=%s", documentId);
                parseSkipCounter.increment();
                return;
            }
            if (docOpt.get().getDeleted()) {
                tracer.info("文档已被标记为已删除，跳过处理: documentId=%s", documentId);
                parseSkipCounter.increment();
                return;
            }

            String minioPath = event.getMinioPath();
            tracer.step("3. PARSE_START");

            docOpt.get().setStatus(KnowledgeDocument.DocumentStatus.PARSING);
            documentRepository.save(docOpt.get());

            tracer.info("收到 Kafka 消息: topic=document-upload, minioPath=%s", event.getMinioPath());

            String parsedPath = event.getKbId() + "/" + event.getDocumentId() + "/parsed.txt";
            String mimeType = "application/octet-stream";
            Path tempParsedFile = Files.createTempFile("rag-parsed-", ".txt");

            // ---- MinIO 下载 ----
            long t1 = System.nanoTime();
            tracer.info("下载原始文件: minioPath=%s", event.getMinioPath());
            byte[] documentData;
            try (InputStream fileStream = minioStorage.getObjectStream(event.getMinioPath())) {
                mimeType = detectMimeType(fileStream);
                tracer.info("Tika 检测到 MIME 类型: %s", mimeType);
            }
            try (InputStream fileStream = minioStorage.getObjectStream(event.getMinioPath())) {
                documentData = fileStream.readAllBytes();
            }
            minioDownloadTimer.record(System.nanoTime() - t1, TimeUnit.NANOSECONDS);

            // ---- Tika 文本提取 ----
            tracer.step("3.1 EXTRACT_TEXT");
            tracer.info("使用 Tika 解析文档...");
            long t2 = System.nanoTime();
            String parsedText;
            int imageCount = 0;
            int tableCount = 0;
            if (extractionEnabled && enhancedContentProcessor.isEnabled()) {
                tracer.info("使用增强内容提取 (图片 OCR + 表格解析)...");
                EnhancedContentProcessor.EnhancementResult result =
                    enhancedContentProcessor.process(documentData, event.getDocumentId(), event.getKbId());
                parsedText = result.getTextContent();
                imageCount = result.getImages().size();
                tableCount = result.getTables().size();
                tracer.info("增强提取完成: textLength=%d, images=%d, tables=%d",
                    parsedText != null ? parsedText.length() : -1, imageCount, tableCount);
            } else {
                parsedText = tika.parseToString(new ByteArrayInputStream(documentData));
                tracer.info("文本提取完成 (标准模式): textLength=%d characters", parsedText.length());
            }
            // ---- Tika 返回空时，尝试作为纯文本直接读取 ----
            // 针对 .txt 文件被错误检测为 application/octet-stream 的兜底处理
            if (parsedText == null || parsedText.isBlank()) {
                tracer.info("Tika 解析结果为空，尝试作为纯文本读取...");
                parsedText = new String(documentData, java.nio.charset.StandardCharsets.UTF_8);
                tracer.info("纯文本读取完成: textLength=%d characters", parsedText.length());
            }
            tikaExtractTimer.record(System.nanoTime() - t2, TimeUnit.NANOSECONDS);
            documentData = null; // help GC

            int textLength = parsedText.length();
            textLengthSummary.record(textLength);

            // ---- MinIO 上传解析结果 ----
            long t3 = System.nanoTime();
            tracer.info("上传解析后文本到 MinIO: path=%s", parsedPath);
            Files.writeString(tempParsedFile, parsedText, StandardCharsets.UTF_8);
            try (InputStream parsedStream = Files.newInputStream(tempParsedFile)) {
                minioStorage.upload(parsedPath, parsedStream, Files.size(tempParsedFile), "text/plain");
            }
            minioUploadTimer.record(System.nanoTime() - t3, TimeUnit.NANOSECONDS);
            tracer.stepComplete("3.1 EXTRACT_TEXT", "textLength=" + textLength);
            Files.deleteIfExists(tempParsedFile);

            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(KnowledgeDocument.DocumentStatus.PARSED);
                documentRepository.save(doc);
            });

            tracer.step("3.2 SEND_KAFKA_MESSAGE");
            event.setEventType(DocumentEvent.EventType.PARSED);
            event.setParsedMinioPath(parsedPath);

            Map<String, Object> metadata = event.getMetadata() != null ? event.getMetadata() : new HashMap<>();
            metadata.put("mimeType", mimeType);
            if (extractionEnabled) {
                metadata.put("extractionEnabled", true);
                metadata.put("imageCount", imageCount);
                metadata.put("tableCount", tableCount);
            }
            event.setMetadata(metadata);

            tracer.info("发送 Kafka 消息: topic=document-parsed, parsedPath=%s, mimeType=%s", parsedPath, mimeType);
            eventProducer.sendParsed(event);

            parseTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            parseSuccessCounter.increment();
            tracer.stepComplete("3. PARSE_COMPLETE", "parsedPath=" + parsedPath);
            tracer.info("文档解析完成: documentId=%s, traceId=%s", documentId, traceId);

        } catch (Exception e) {
            log.error("Failed to parse document: {}", e.getMessage(), e);
            parseFailureCounter.increment();
            parseTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            try {
                DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
                documentRepository.findById(Long.parseLong(event.getDocumentId())).ifPresent(doc -> {
                    doc.setStatus(KnowledgeDocument.DocumentStatus.FAILED);
                    documentRepository.save(doc);
                });
            } catch (Exception ex) {
                log.error("Failed to update document status to FAILED", ex);
            }
        }
    }

    private String detectMimeType(InputStream stream) {
        try {
            org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
            return tika.detect(stream, metadata);
        } catch (Exception e) {
            log.warn("Failed to detect MIME type: {}", e.getMessage());
            return "application/octet-stream";
        }
    }
}
