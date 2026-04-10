package com.rag.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_knowledge_document")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "doc_name", nullable = false, length = 256)
    private String docName;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    @Column(name = "file_url", nullable = false, length = 1024)
    private String fileUrl;

    @Column(name = "file_type", nullable = false, length = 32)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "process_mode", length = 32)
    private String processMode = "chunk";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "source_location", length = 1024)
    private String sourceLocation;

    @Column(name = "schedule_enabled")
    private Boolean scheduleEnabled;

    @Column(name = "schedule_cron", length = 128)
    private String scheduleCron;

    @Column(name = "chunk_strategy", length = 32)
    private String chunkStrategy;

    @Column(name = "chunk_config", columnDefinition = "JSON")
    private String chunkConfig;

    @Column(name = "pipeline_id")
    private Long pipelineId;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @Column(nullable = false)
    private Boolean deleted = false;

    public enum DocumentStatus {
        PENDING,
        PROCESSING,
        PARSING,
        PARSED,
        CHUNKING,
        CHUNKED,
        INDEXING,
        COMPLETED,
        FAILED
    }

    public KnowledgeDocument() {
    }

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        updateTime = createTime;
        if (deleted == null) {
            deleted = false;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getDocName() { return docName; }
    public void setDocName(String docName) { this.docName = docName; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getProcessMode() { return processMode; }
    public void setProcessMode(String processMode) { this.processMode = processMode; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceLocation() { return sourceLocation; }
    public void setSourceLocation(String sourceLocation) { this.sourceLocation = sourceLocation; }
    public Boolean getScheduleEnabled() { return scheduleEnabled; }
    public void setScheduleEnabled(Boolean scheduleEnabled) { this.scheduleEnabled = scheduleEnabled; }
    public String getScheduleCron() { return scheduleCron; }
    public void setScheduleCron(String scheduleCron) { this.scheduleCron = scheduleCron; }
    public String getChunkStrategy() { return chunkStrategy; }
    public void setChunkStrategy(String chunkStrategy) { this.chunkStrategy = chunkStrategy; }
    public String getChunkConfig() { return chunkConfig; }
    public void setChunkConfig(String chunkConfig) { this.chunkConfig = chunkConfig; }
    public Long getPipelineId() { return pipelineId; }
    public void setPipelineId(Long pipelineId) { this.pipelineId = pipelineId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
}
