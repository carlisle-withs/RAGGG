package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Document;
import com.rag.domain.repository.DocumentRepository;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Tika tika;
    private final DocumentRepository documentRepository;

    public ParseService(MinioStorage minioStorage,
                       DocumentEventProducer eventProducer,
                       ObjectMapper objectMapper,
                       DocumentRepository documentRepository) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.tika = new Tika();
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_UPLOAD, groupId = "${spring.kafka.consumer.group-id}-parse")
    public void consume(String message) {
        doProcess(message);
    }

    @Transactional
    public void doProcess(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            String traceId = event.getTraceId();
            String documentId = event.getDocumentId();

            TraceLogger tracer = TraceLogger.get(ParseService.class, traceId, documentId);

            // 检查文档是否已删除
            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || docOpt.get().isDeleted()) {
                tracer.info("文档已删除，跳过处理: documentId=%s", documentId);
                return;
            }

            tracer.step("3. PARSE_START");

            // Update status to PARSING
            docOpt.get().setStatus(Document.DocumentStatus.PARSING);
            documentRepository.save(docOpt.get());

            tracer.info("收到 Kafka 消息: topic=document-upload, minioPath=%s", event.getMinioPath());

            // Download from MinIO
            tracer.info("下载原始文件: minioPath=%s", event.getMinioPath());
            String parsedPath = event.getKbId() + "/" + event.getDocumentId() + "/parsed.txt";
            int textLength;
            String mimeType = "application/octet-stream";
            Path tempParsedFile = Files.createTempFile("rag-parsed-", ".txt");
            try (InputStream fileStream = minioStorage.getObjectStream(event.getMinioPath())) {
                tracer.step("3.1 EXTRACT_TEXT");
                tracer.info("使用 Tika 解析文档...");

                // 检测 MIME 类型
                mimeType = detectMimeType(fileStream);
                tracer.info("Tika 检测到 MIME 类型: %s", mimeType);
            }
            try (InputStream fileStream = minioStorage.getObjectStream(event.getMinioPath())) {
                String parsedText = tika.parseToString(fileStream);
                textLength = parsedText.length();
                tracer.info("文本提取完成: textLength=%d characters", textLength);

                tracer.info("上传解析后文本到 MinIO: path=%s", parsedPath);
                Files.writeString(tempParsedFile, parsedText, StandardCharsets.UTF_8);
            }
            try (InputStream parsedStream = Files.newInputStream(tempParsedFile)) {
                minioStorage.upload(parsedPath, parsedStream, Files.size(tempParsedFile), "text/plain");
            }
            tracer.stepComplete("3.1 EXTRACT_TEXT", "textLength=" + textLength);
            Files.deleteIfExists(tempParsedFile);

            // Update status to PARSED
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.DocumentStatus.PARSED);
                documentRepository.save(doc);
            });

            // Update event and send to next topic
            tracer.step("3.2 SEND_KAFKA_MESSAGE");
            event.setEventType(DocumentEvent.EventType.PARSED);
            event.setParsedMinioPath(parsedPath);

            // 添加 MIME 类型到元数据
            Map<String, Object> metadata = event.getMetadata() != null ? event.getMetadata() : new HashMap<>();
            metadata.put("mimeType", mimeType);
            event.setMetadata(metadata);

            tracer.info("发送 Kafka 消息: topic=document-parsed, parsedPath=%s, mimeType=%s", parsedPath, mimeType);
            eventProducer.sendParsed(event);

            tracer.stepComplete("3. PARSE_COMPLETE", "parsedPath=" + parsedPath);
            tracer.info("文档解析完成: documentId=%s, traceId=%s", documentId, traceId);

        } catch (Exception e) {
            log.error("Failed to parse document: {}", e.getMessage(), e);
            // Update status to FAILED
            try {
                DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
                documentRepository.findById(event.getDocumentId()).ifPresent(doc -> {
                    doc.setStatus(Document.DocumentStatus.FAILED);
                    documentRepository.save(doc);
                });
            } catch (Exception ex) {
                log.error("Failed to update document status to FAILED", ex);
            }
        }
    }

    /**
     * 使用 Tika 检测文件的 MIME 类型
     */
    private String detectMimeType(InputStream stream) {
        try {
            // 使用 Tika 的 detect 方法检测 MIME 类型
            org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
            return tika.detect(stream, metadata);
        } catch (Exception e) {
            log.warn("Failed to detect MIME type: {}", e.getMessage());
            return "application/octet-stream";
        }
    }
}
