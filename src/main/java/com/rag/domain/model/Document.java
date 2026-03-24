package com.rag.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    private String id;

    @Column(name = "kb_id")
    private String kbId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "minio_path")
    private String minioPath;

    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.PENDING;

    @ElementCollection
    @CollectionTable(name = "document_metadata", joinColumns = @JoinColumn(name = "document_id"))
    @MapKeyColumn(name = "meta_key")
    @Column(name = "meta_value", columnDefinition = "TEXT")
    private Map<String, String> metadata = new HashMap<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    @Column(name = "chunk_count")
    private int chunkCount = 0;

    public enum DocumentStatus {
        PENDING, PROCESSING, INDEXED, FAILED
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getMinioPath() { return minioPath; }
    public void setMinioPath(String minioPath) { this.minioPath = minioPath; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getIndexedAt() { return indexedAt; }
    public void setIndexedAt(LocalDateTime indexedAt) { this.indexedAt = indexedAt; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
}
