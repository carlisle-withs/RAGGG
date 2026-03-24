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

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "document_count")
    private int documentCount = 0;

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
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
