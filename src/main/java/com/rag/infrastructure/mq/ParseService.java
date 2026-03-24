package com.rag.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.event.DocumentEvent;
import com.rag.infrastructure.storage.MinioStorage;
import com.rag.util.TraceLogger;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    private final MinioStorage minioStorage;
    private final DocumentEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Tika tika;

    public ParseService(MinioStorage minioStorage,
                       DocumentEventProducer eventProducer,
                       ObjectMapper objectMapper) {
        this.minioStorage = minioStorage;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.tika = new Tika();
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_UPLOAD, groupId = "${spring.kafka.consumer.group-id}-parse")
    public void consume(String message) {
        try {
            DocumentEvent event = objectMapper.readValue(message, DocumentEvent.class);
            String traceId = event.getTraceId();
            String documentId = event.getDocumentId();

            TraceLogger tracer = TraceLogger.get(ParseService.class, traceId, documentId);

            tracer.step("3. PARSE_START");
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
        }
    }
}
