package com.rag.domain.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chunk DTO for serialization/deserialization without JPA overhead.
 * Used for MinIO storage and Kafka message passing.
 */
public class ChunkDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String documentId;
    private String content;
    private int tokenCount;
    private int chunkIndex;
    private Map<String, String> metadata;

    public ChunkDTO() {
        this.metadata = new HashMap<>();
    }

    public static ChunkDTO fromChunk(Chunk chunk) {
        ChunkDTO dto = new ChunkDTO();
        dto.setId(chunk.getId() != null ? chunk.getId() : UUID.randomUUID().toString());
        dto.setDocumentId(chunk.getDocumentId());
        dto.setContent(chunk.getContent());
        dto.setTokenCount(chunk.getTokenCount());
        dto.setChunkIndex(chunk.getChunkIndex());
        dto.setMetadata(new HashMap<>(chunk.getMetadata()));
        return dto;
    }

    public Chunk toChunk() {
        Chunk chunk = new Chunk();
        chunk.setId(this.id != null ? this.id : UUID.randomUUID().toString());
        chunk.setDocumentId(this.documentId);
        chunk.setContent(this.content);
        chunk.setTokenCount(this.tokenCount);
        chunk.setChunkIndex(this.chunkIndex);
        chunk.setMetadata(new HashMap<>(this.metadata));
        return chunk;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
