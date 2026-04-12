package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.AppConfig;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.KnowledgeDocument;
import com.rag.domain.repository.KnowledgeDocumentRepository;
import com.rag.infrastructure.extraction.service.EnhancedContentProcessor;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
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

    public ParseService(MinioStorage minioStorage,
                       DocumentEventProducer eventProducer,
                       ObjectMapper objectMapper,
                       KnowledgeDocumentRepository documentRepository,
                       EnhancedContentProcessor enhancedContentProcessor,
                       AppConfig appConfig) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.enhancedContentProcessor = enhancedContentProcessor;
        this.extractionEnabled = appConfig.getExtraction().isEnabled();
        this.tika = new Tika();
        log.info("ParseService initialized: extractionEnabled={}", extractionEnabled);
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
            Long documentId = Long.parseLong(event.getDocumentId());

            TraceLogger tracer = TraceLogger.get(ParseService.class, traceId, documentId.toString());

            var docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || docOpt.get().getDeleted()) {
                tracer.info("文档已删除，跳过处理: documentId=%s", documentId);
                return;
            }

            tracer.step("3. PARSE_START");

            docOpt.get().setStatus(KnowledgeDocument.DocumentStatus.PARSING);
            documentRepository.save(docOpt.get());

            tracer.info("收到 Kafka 消息: topic=document-upload, minioPath=%s", event.getMinioPath());

            tracer.info("下载原始文件: minioPath=%s", event.getMinioPath());
            String parsedPath = event.getKbId() + "/" + event.getDocumentId() + "/parsed.txt";
            int textLength;
            String mimeType = "application/octet-stream";
            Path tempParsedFile = Files.createTempFile("rag-parsed-", ".txt");
            
            byte[] documentData;
            try (InputStream fileStream = minioStorage.getObjectStream(event.getMinioPath())) {
                tracer.step("3.1 EXTRACT_TEXT");
                tracer.info("使用 Tika 解析文档...");

                mimeType = detectMimeType(fileStream);
                tracer.info("Tika 检测到 MIME 类型: %s", mimeType);
            }
            try (InputStream fileStream = minioStorage.getObjectStream(event.getMinioPath())) {
                documentData = fileStream.readAllBytes();
            }
            
            String parsedText;
            int imageCount = 0;
            int tableCount = 0;
            
            if (extractionEnabled && enhancedContentProcessor.isEnabled()) {
                tracer.info("使用增强内容提取 (图片 OCR + 表格解析)...");
                EnhancedContentProcessor.EnhancementResult result = 
                    enhancedContentProcessor.process(documentData, event.getDocumentId(), event.getKbId());
                parsedText = result.getTextContent();
                if (parsedText == null) {
                    parsedText = tika.parseToString(new ByteArrayInputStream(documentData));
                }
                imageCount = result.getImages().size();
                tableCount = result.getTables().size();
                tracer.info("增强提取完成: textLength=%d, images=%d, tables=%d", 
                    parsedText.length(), imageCount, tableCount);
            } else {
                parsedText = tika.parseToString(new ByteArrayInputStream(documentData));
                tracer.info("文本提取完成 (标准模式): textLength=%d characters", parsedText.length());
            }
            
            textLength = parsedText.length();
            tracer.info("上传解析后文本到 MinIO: path=%s", parsedPath);
            Files.writeString(tempParsedFile, parsedText, StandardCharsets.UTF_8);
            
            try (InputStream parsedStream = Files.newInputStream(tempParsedFile)) {
                minioStorage.upload(parsedPath, parsedStream, Files.size(tempParsedFile), "text/plain");
            }
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

            tracer.stepComplete("3. PARSE_COMPLETE", "parsedPath=" + parsedPath);
            tracer.info("文档解析完成: documentId=%s, traceId=%s", documentId, traceId);

        } catch (Exception e) {
            log.error("Failed to parse document: {}", e.getMessage(), e);
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
