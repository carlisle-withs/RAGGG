package com.rag.domain.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chunk domain object used by ChunkStrategy for in-memory chunking.
 * This is NOT a JPA entity - use KnowledgeChunk for persistence.
 */
public class Chunk {

    private String id;
    private String documentId;
    private String kbId;
    private String content;
    private int tokenCount;
    private int chunkIndex;
    private LocalDateTime createdAt;
    private Map<String, String> metadata;

    public Chunk() {
        this.metadata = new HashMap<>();
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
    }

    public Chunk(String documentId, String kbId, String content, int chunkIndex) {
        this.id = UUID.randomUUID().toString();
        this.documentId = documentId;
        this.kbId = kbId;
        this.content = content;
        this.chunkIndex = chunkIndex;
        this.metadata = new HashMap<>();
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
