package com.rag.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBase {

    @Id
    private String id;

    @Column(name = "name")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "document_count")
    private int documentCount = 0;

    @Column(name = "chunk_strategy")
    private String chunkStrategy = "intelligent";

    @Column(name = "chunk_size")
    private Integer chunkSize = 512;

    @Column(name = "chunk_overlap")
    private Integer chunkOverlap = 50;

    @Column(name = "min_paragraph_length")
    private Integer minParagraphLength = 50;

    @Column(name = "max_paragraph_length")
    private Integer maxParagraphLength = 2000;

    @Column(name = "max_tokens_per_chunk")
    private Integer maxTokensPerChunk = 512;

    @Column(name = "similarity_threshold")
    private Double similarityThreshold = 0.7;

    @ElementCollection
    @CollectionTable(name = "kb_metadata", joinColumns = @JoinColumn(name = "kb_id"))
    @MapKeyColumn(name = "meta_key")
    @Column(name = "meta_value", columnDefinition = "TEXT")
    private Map<String, String> metadata = new HashMap<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public String getOwnerId() { return owner != null ? owner.getId() : null; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getChunkStrategy() { return chunkStrategy; }
    public void setChunkStrategy(String chunkStrategy) { this.chunkStrategy = chunkStrategy; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public Integer getMinParagraphLength() { return minParagraphLength; }
    public void setMinParagraphLength(Integer minParagraphLength) { this.minParagraphLength = minParagraphLength; }
    public Integer getMaxParagraphLength() { return maxParagraphLength; }
    public void setMaxParagraphLength(Integer maxParagraphLength) { this.maxParagraphLength = maxParagraphLength; }
    public Integer getMaxTokensPerChunk() { return maxTokensPerChunk; }
    public void setMaxTokensPerChunk(Integer maxTokensPerChunk) { this.maxTokensPerChunk = maxTokensPerChunk; }
    public Double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(Double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
}
