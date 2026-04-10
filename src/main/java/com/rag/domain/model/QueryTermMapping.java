package com.rag.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_query_term_mapping", indexes = {
    @Index(name = "idx_domain", columnList = "domain"),
    @Index(name = "idx_source_term", columnList = "sourceTerm"),
    @Index(name = "idx_qtm_enabled", columnList = "enabled")
})
public class QueryTermMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String domain;

    @Column(name = "source_term", nullable = false, length = 128)
    private String sourceTerm;

    @Column(name = "target_term", nullable = false, length = 128)
    private String targetTerm;

    @Column(name = "match_type", nullable = false)
    private Integer matchType = 1;

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 255)
    private String remark;

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

    public QueryTermMapping() {
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
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getSourceTerm() { return sourceTerm; }
    public void setSourceTerm(String sourceTerm) { this.sourceTerm = sourceTerm; }
    public String getTargetTerm() { return targetTerm; }
    public void setTargetTerm(String targetTerm) { this.targetTerm = targetTerm; }
    public Integer getMatchType() { return matchType; }
    public void setMatchType(Integer matchType) { this.matchType = matchType; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
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

    public static final int MATCH_EXACT = 1;
    public static final int MATCH_FUZZY = 2;
    public static final int MATCH_SYNONYM = 3;
}
