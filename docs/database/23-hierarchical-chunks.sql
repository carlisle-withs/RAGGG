-- ============================================
-- RAGGG P1 增强：层级分块关系表
-- 支持 Sentence Window + Auto-Merging 检索策略
-- ============================================
-- 使用方式: mysql -u root -p rag_system < docs/database/23-hierarchical-chunks.sql

USE rag_system;

-- ============================================
-- 层级分块关系表
-- 存储 Parent Chunk 和 Leaf Chunk 之间的父子关系
-- ============================================
DROP TABLE IF EXISTS t_chunk_hierarchy;
CREATE TABLE t_chunk_hierarchy (
    id                    BIGINT       PRIMARY KEY AUTO_INCREMENT,
    chunk_id              VARCHAR(64)  NOT NULL,
    parent_chunk_id       VARCHAR(64),                     -- 父 Chunk ID（NULL 表示顶级 Parent Chunk）
    document_id           BIGINT       NOT NULL,
    kb_id                 BIGINT       NOT NULL,
    chunk_level           TINYINT      NOT NULL DEFAULT 0,  -- 0=Parent(段落级), 1=Leaf(句子级)
    position_in_parent    INT,                              -- 在父节点中的位置索引（从 0 开始）
    sibling_count         INT,                              -- 父节点下的叶子节点总数
    window_content        LONGTEXT,                         -- Sentence Window 扩展内容
    window_start          INT,                             -- 窗口起始句子索引
    window_end            INT,                             -- 窗口结束句子索引
    parent_sentence_count  INT,                             -- 父节点的总句子数
    original_content      LONGTEXT,                         -- Leaf Chunk 的原始内容（用于追溯）
    embedding_boost       DOUBLE DEFAULT 1.0,              -- Parent 被命中时的分数 boost 系数
    create_time           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_parent_chunk_id (parent_chunk_id),
    INDEX idx_document_id (document_id),
    INDEX idx_kb_id (kb_id),
    INDEX idx_chunk_level (chunk_level),
    INDEX idx_chunk_id (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- Rerank 调用日志表（用于分析 CrossEncoder 效果）
-- ============================================
DROP TABLE IF EXISTS t_rerank_log;
CREATE TABLE t_rerank_log (
    id                    BIGINT       PRIMARY KEY AUTO_INCREMENT,
    conversation_id      VARCHAR(64),
    kb_id                 VARCHAR(64),
    query                 TEXT,
    rerank_provider       VARCHAR(32),                       -- siliconflow / cohere / bi_encoder
    candidate_count       INT,
    final_count           INT,
    latency_ms            BIGINT,
    success               TINYINT(1) DEFAULT 1,
    error_message         VARCHAR(512),
    trace_id              VARCHAR(64),
    create_time           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_kb_id (kb_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 检索质量追踪表（用于 RAGAS 等指标持续监控）
-- ============================================
DROP TABLE IF EXISTS t_retrieval_quality_log;
CREATE TABLE t_retrieval_quality_log (
    id                    BIGINT       PRIMARY KEY AUTO_INCREMENT,
    conversation_id       VARCHAR(64),
    kb_id                 VARCHAR(64),
    query                 TEXT,
    intent                VARCHAR(32),
    retrieval_strategy    VARCHAR(32),                       -- hybrid / hierarchical
    candidates_count      INT,
    final_count           INT,
    rerank_enabled         TINYINT(1) DEFAULT 0,
    milvus_latency_ms     BIGINT,
    es_latency_ms         BIGINT,
    rrf_fusion_latency_ms BIGINT,
    rerank_latency_ms     BIGINT,
    total_latency_ms      BIGINT,
    trace_id              VARCHAR(64),
    create_time           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_kb_id (kb_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
