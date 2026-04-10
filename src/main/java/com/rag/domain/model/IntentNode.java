package com.rag.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_intent_node")
public class IntentNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id")
    private Long kbId;

    @Column(name = "intent_code", nullable = false, length = 64)
    private String intentCode;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private Integer level;

    @Column(name = "parent_code", length = 64)
    private String parentCode;

    @Column(length = 512)
    private String description;

    @Column(columnDefinition = "TEXT")
    private String examples;

    @Column(name = "collection_name", length = 128)
    private String collectionName;

    @Column(name = "top_k")
    private Integer topK;

    @Column(name = "mcp_tool_id", length = 128)
    private String mcpToolId;

    @Column(nullable = false)
    private Integer kind = 0;

    @Column(name = "prompt_snippet", columnDefinition = "TEXT")
    private String promptSnippet;

    @Column(name = "prompt_template", columnDefinition = "TEXT")
    private String promptTemplate;

    @Column(name = "param_prompt_template", columnDefinition = "TEXT")
    private String paramPromptTemplate;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "create_by", length = 64)
    private String createBy;

    @Column(name = "update_by", length = 64)
    private String updateBy;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @Column(nullable = false)
    private Boolean deleted = false;

    public IntentNode() {
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
        if (kind == null) {
            kind = 0;
        }
        if (sortOrder == null) {
            sortOrder = 0;
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
    public String getIntentCode() { return intentCode; }
    public void setIntentCode(String intentCode) { this.intentCode = intentCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getExamples() { return examples; }
    public void setExamples(String examples) { this.examples = examples; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public String getMcpToolId() { return mcpToolId; }
    public void setMcpToolId(String mcpToolId) { this.mcpToolId = mcpToolId; }
    public Integer getKind() { return kind; }
    public void setKind(Integer kind) { this.kind = kind; }
    public String getPromptSnippet() { return promptSnippet; }
    public void setPromptSnippet(String promptSnippet) { this.promptSnippet = promptSnippet; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public String getParamPromptTemplate() { return paramPromptTemplate; }
    public void setParamPromptTemplate(String paramPromptTemplate) { this.paramPromptTemplate = paramPromptTemplate; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }

    public static final int KIND_DIALOG = 0;
    public static final int KIND_RETRIEVAL = 1;
}
