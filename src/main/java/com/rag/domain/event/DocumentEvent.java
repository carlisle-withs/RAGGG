package com.rag.domain.event;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

public class DocumentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum EventType {
        UPLOAD, CHUNKED, INDEXED, FAILED
    }

    private String eventId;
    private EventType eventType;
    private String documentId;
    private String kbId;
    private String fileName;
    private String fileType;
    private String minioPath;
    private Map<String, Object> metadata;
    private LocalDateTime timestamp;

    public DocumentEvent() {
        this.timestamp = LocalDateTime.now();
    }

    public static DocumentEvent create(String documentId, String kbId, String fileName,
                                       String fileType, String minioPath, Map<String, Object> metadata) {
        DocumentEvent event = new DocumentEvent();
        event.eventId = java.util.UUID.randomUUID().toString();
        event.eventType = EventType.UPLOAD;
        event.documentId = documentId;
        event.kbId = kbId;
        event.fileName = fileName;
        event.fileType = fileType;
        event.minioPath = minioPath;
        event.metadata = metadata;
        return event;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getMinioPath() { return minioPath; }
    public void setMinioPath(String minioPath) { this.minioPath = minioPath; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
