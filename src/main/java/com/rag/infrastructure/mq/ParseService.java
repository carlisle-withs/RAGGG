package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import com.rag.domain.model.Document;
import com.rag.domain.repository.DocumentRepository;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import jakarta.annotation.PostConstruct;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);
    private static final int QUEUE_CAPACITY = 10;

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Tika tika;
    private final DocumentRepository documentRepository;

    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

    @PostConstruct
    public void init() {
        executor.submit(this::processLoop);
        log.info("ParseService started with queue capacity: {}", QUEUE_CAPACITY);
    }

    private void processLoop() {
        while (true) {
            try {
                String message = messageQueue.take();
                doProcess(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing message", e);
            }
        }
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_UPLOAD, groupId = "${spring.kafka.consumer.group-id}-parse")
    public void consume(String message) {
        try {
            messageQueue.put(message);
            log.debug("Message queued, queue size: {}", messageQueue.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to queue message", e);
        }
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
            byte[] fileContent = minioStorage.download(event.getMinioPath());
            tracer.info("文件下载完成: size=%d bytes", fileContent.length);

            // Extract text using Tika
            tracer.step("3.1 EXTRACT_TEXT");
            tracer.info("使用 Tika 解析文档...");
            String parsedText = tika.parseToString(new java.io.ByteArrayInputStream(fileContent));
            tracer.info("文本提取完成: textLength=%d characters", parsedText.length());

            // Upload parsed text to MinIO
            String parsedPath = event.getKbId() + "/" + event.getDocumentId() + "/parsed.txt";
            tracer.info("上传解析后文本到 MinIO: path=%s", parsedPath);
            minioStorage.upload(parsedPath, parsedText.getBytes(), "text/plain");
            tracer.stepComplete("3.1 EXTRACT_TEXT", "textLength=" + parsedText.length());

            // Update status to PARSED
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.DocumentStatus.PARSED);
                documentRepository.save(doc);
            });

            // Update event and send to next topic
            tracer.step("3.2 SEND_KAFKA_MESSAGE");
            event.setEventType(DocumentEvent.EventType.PARSED);
            event.setParsedMinioPath(parsedPath);
            tracer.info("发送 Kafka 消息: topic=document-parsed, parsedPath=%s", parsedPath);
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
}
