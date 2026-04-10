-- RAG数据库建表脚本
-- 数据库: rag_system
-- 字符集: utf8mb4

CREATE DATABASE IF NOT EXISTS rag_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE rag_system;

-- ============================================
-- 1. 用户表
-- ============================================
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password        VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL DEFAULT 'USER',
    avatar          VARCHAR(128),
    create_time     DATETIME,
    update_time     DATETIME,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 2. 知识库表
-- ============================================
DROP TABLE IF EXISTS t_knowledge_base;
CREATE TABLE t_knowledge_base (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name             VARCHAR(128) NOT NULL,
    embedding_model   VARCHAR(128) NOT NULL,
    collection_name   VARCHAR(128) NOT NULL UNIQUE,
    created_by        VARCHAR(64)  NOT NULL,
    updated_by        VARCHAR(64),
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_name (name),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 3. 知识库文档表
-- ============================================
DROP TABLE IF EXISTS t_knowledge_document;
CREATE TABLE t_knowledge_document (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    kb_id            BIGINT       NOT NULL,
    doc_name         VARCHAR(256) NOT NULL,
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    chunk_count      INT          DEFAULT 0,
    file_url         VARCHAR(1024) NOT NULL,
    file_type        VARCHAR(32)  NOT NULL,
    file_size        BIGINT,
    process_mode     VARCHAR(32)  DEFAULT 'chunk',
    status           VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    source_type      VARCHAR(32),
    source_location  VARCHAR(1024),
    schedule_enabled TINYINT(1),
    schedule_cron    VARCHAR(128),
    chunk_strategy   VARCHAR(32),
    chunk_config     JSON,
    pipeline_id      BIGINT,
    created_by       VARCHAR(64)  NOT NULL,
    updated_by       VARCHAR(64),
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_kb_id (kb_id),
    INDEX idx_status (status),
    INDEX idx_created_by (created_by),
    INDEX idx_source_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 4. 知识库分块表
-- ============================================
DROP TABLE IF EXISTS t_knowledge_chunk;
CREATE TABLE t_knowledge_chunk (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    kb_id            BIGINT       NOT NULL,
    doc_id           BIGINT       NOT NULL,
    chunk_index      INT          NOT NULL,
    content          LONGTEXT     NOT NULL,
    content_hash     VARCHAR(64),
    char_count       INT,
    token_count      INT,
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    created_by       VARCHAR(64)  NOT NULL,
    updated_by       VARCHAR(64),
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_doc_id (doc_id),
    INDEX idx_kb_id (kb_id),
    INDEX idx_content_hash (content_hash),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 5. 对话会话表
-- ============================================
DROP TABLE IF EXISTS t_conversation;
CREATE TABLE t_conversation (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    conversation_id  VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    title            VARCHAR(128) NOT NULL,
    last_time        DATETIME,
    create_time      DATETIME,
    update_time      DATETIME,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    UNIQUE INDEX idx_conversation_id (conversation_id),
    INDEX idx_user_id (user_id),
    INDEX idx_last_time (last_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 6. 对话消息表
-- ============================================
DROP TABLE IF EXISTS t_message;
CREATE TABLE t_message (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    conversation_id  VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    role             VARCHAR(32)  NOT NULL,
    content          TEXT         NOT NULL,
    create_time      DATETIME,
    update_time      DATETIME,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    INDEX idx_msg_conversation_id (conversation_id),
    INDEX idx_msg_user_id (user_id),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 7. 对话摘要表
-- ============================================
DROP TABLE IF EXISTS t_conversation_summary;
CREATE TABLE t_conversation_summary (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    conversation_id  VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    last_message_id  VARCHAR(64)  NOT NULL,
    content          TEXT         NOT NULL,
    create_time      DATETIME,
    update_time      DATETIME,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    INDEX idx_conv_id (conversation_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 8. 摄入流水线表
-- ============================================
DROP TABLE IF EXISTS t_ingestion_pipeline;
CREATE TABLE t_ingestion_pipeline (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name             VARCHAR(100) NOT NULL,
    description      TEXT,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 9. 流水线节点定义表
-- ============================================
DROP TABLE IF EXISTS t_ingestion_pipeline_node;
CREATE TABLE t_ingestion_pipeline_node (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    pipeline_id      BIGINT       NOT NULL,
    node_id          VARCHAR(64)  NOT NULL,
    node_type        VARCHAR(30)  NOT NULL,
    next_node_id     VARCHAR(64),
    settings_json    JSON,
    condition_json   JSON,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_pipeline_id (pipeline_id),
    INDEX idx_node_type (node_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 10. 摄入任务表
-- ============================================
DROP TABLE IF EXISTS t_ingestion_task;
CREATE TABLE t_ingestion_task (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    pipeline_id      BIGINT       NOT NULL,
    source_type      VARCHAR(20)  NOT NULL,
    source_location  TEXT,
    source_file_name VARCHAR(255),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    chunk_count      INT          DEFAULT 0,
    error_message    TEXT,
    logs_json        JSON,
    metadata_json    JSON,
    started_at       DATETIME,
    completed_at     DATETIME,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_pipeline_id (pipeline_id),
    INDEX idx_status (status),
    INDEX idx_source_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 11. 任务节点执行记录表
-- ============================================
DROP TABLE IF EXISTS t_ingestion_task_node;
CREATE TABLE t_ingestion_task_node (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    task_id          BIGINT       NOT NULL,
    pipeline_id      BIGINT       NOT NULL,
    node_id          VARCHAR(64)  NOT NULL,
    node_type        VARCHAR(30)  NOT NULL,
    node_order       INT          NOT NULL DEFAULT 0,
    status           VARCHAR(20)  NOT NULL,
    duration_ms      BIGINT       NOT NULL DEFAULT 0,
    message          TEXT,
    error_message    TEXT,
    output_json      LONGTEXT,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_node_type (node_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 12. 消息反馈表
-- ============================================
DROP TABLE IF EXISTS t_message_feedback;
CREATE TABLE t_message_feedback (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    message_id       BIGINT       NOT NULL,
    conversation_id  VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    vote             TINYINT(1)   NOT NULL,
    reason           VARCHAR(255),
    comment          VARCHAR(1024),
    create_time      DATETIME     NOT NULL,
    update_time      DATETIME     NOT NULL,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_message_id (message_id),
    INDEX idx_fb_conversation_id (conversation_id),
    INDEX idx_fb_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 13. 意图节点表
-- ============================================
DROP TABLE IF EXISTS t_intent_node;
CREATE TABLE t_intent_node (
    id                      BIGINT       PRIMARY KEY AUTO_INCREMENT,
    kb_id                   BIGINT,
    intent_code             VARCHAR(64)  NOT NULL,
    name                    VARCHAR(64)  NOT NULL,
    level                   TINYINT      NOT NULL,
    parent_code             VARCHAR(64),
    description             VARCHAR(512),
    examples                TEXT,
    collection_name         VARCHAR(128),
    top_k                   INT,
    mcp_tool_id             VARCHAR(128),
    kind                    TINYINT(1)   NOT NULL DEFAULT 0,
    prompt_snippet          TEXT,
    prompt_template         TEXT,
    param_prompt_template   TEXT,
    sort_order              INT          NOT NULL DEFAULT 0,
    enabled                 TINYINT(1)   NOT NULL DEFAULT 1,
    create_by               VARCHAR(64),
    update_by               VARCHAR(64),
    create_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                 TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_intent_code (intent_code),
    INDEX idx_kb_id (kb_id),
    INDEX idx_level (level),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 14. 示例问题表
-- ============================================
DROP TABLE IF EXISTS t_sample_question;
CREATE TABLE t_sample_question (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    title       VARCHAR(64),
    description VARCHAR(255),
    question    VARCHAR(1024) NOT NULL,
    create_time DATETIME,
    update_time DATETIME,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    INDEX idx_sq_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 15. 查询词映射表
-- ============================================
DROP TABLE IF EXISTS t_query_term_mapping;
CREATE TABLE t_query_term_mapping (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    domain        VARCHAR(64),
    source_term   VARCHAR(128) NOT NULL,
    target_term   VARCHAR(128) NOT NULL,
    match_type    TINYINT      NOT NULL DEFAULT 1,
    priority      INT          NOT NULL DEFAULT 100,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    remark        VARCHAR(255),
    create_by     VARCHAR(64),
    update_by     VARCHAR(64),
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_domain (domain),
    INDEX idx_source_term (source_term),
    INDEX idx_qtm_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 16. RAG追踪运行表
-- ============================================
DROP TABLE IF EXISTS t_rag_trace_run;
CREATE TABLE t_rag_trace_run (
    id               BIGINT        PRIMARY KEY AUTO_INCREMENT,
    trace_id         VARCHAR(64)   NOT NULL UNIQUE,
    trace_name       VARCHAR(128),
    entry_method     VARCHAR(256),
    conversation_id   VARCHAR(64),
    task_id          VARCHAR(64),
    user_id          VARCHAR(64),
    status           VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    error_message    VARCHAR(1000),
    start_time       DATETIME(3),
    end_time         DATETIME(3),
    duration_ms      BIGINT,
    extra_data       TEXT,
    create_time      DATETIME,
    update_time      DATETIME,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    INDEX idx_trace_conversation_id (conversation_id),
    INDEX idx_trace_user_id (user_id),
    INDEX idx_trace_status (status),
    INDEX idx_trace_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 17. RAG追踪节点表
-- ============================================
DROP TABLE IF EXISTS t_rag_trace_node;
CREATE TABLE t_rag_trace_node (
    id               BIGINT        PRIMARY KEY AUTO_INCREMENT,
    trace_id         VARCHAR(64)   NOT NULL,
    node_id          VARCHAR(64)  NOT NULL,
    parent_node_id   VARCHAR(64),
    depth            INT          DEFAULT 0,
    node_type        VARCHAR(64),
    node_name        VARCHAR(128),
    class_name       VARCHAR(256),
    method_name      VARCHAR(128),
    status           VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    error_message    VARCHAR(1000),
    start_time       DATETIME(3),
    end_time         DATETIME(3),
    duration_ms      BIGINT,
    extra_data       TEXT,
    create_time      DATETIME,
    update_time      DATETIME,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    INDEX idx_tn_trace_id (trace_id),
    INDEX idx_tn_status (status),
    INDEX idx_parent_node_id (parent_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 18. 文档处理异步消息表
-- ============================================
DROP TABLE IF EXISTS doc_outbox;
CREATE TABLE doc_outbox (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    task_id       VARCHAR(64)  NOT NULL,
    step          VARCHAR(32)  NOT NULL,
    payload       JSON,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count   INT          DEFAULT 0,
    error_message VARCHAR(512),
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_outbox_task_id (task_id),
    INDEX idx_outbox_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 19. 文档处理日志表
-- ============================================
DROP TABLE IF EXISTS doc_processing_log;
CREATE TABLE doc_processing_log (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    task_id       VARCHAR(64)  NOT NULL,
    step          VARCHAR(32)  NOT NULL,
    processed_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_log_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 20. 文档分块处理日志表
-- ============================================
DROP TABLE IF EXISTS t_knowledge_document_chunk_log;
CREATE TABLE t_knowledge_document_chunk_log (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    doc_id              BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    process_mode        VARCHAR(20),
    chunk_strategy      VARCHAR(50),
    pipeline_id         BIGINT,
    extract_duration    BIGINT,
    chunk_duration      BIGINT,
    embedding_duration  BIGINT,
    total_duration      BIGINT,
    chunk_count         INT,
    error_message       TEXT,
    start_time          DATETIME,
    end_time            DATETIME,
    create_time         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_log_doc_id (doc_id),
    INDEX idx_log_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 21. 文档定时调度配置表
-- ============================================
DROP TABLE IF EXISTS t_knowledge_document_schedule;
CREATE TABLE t_knowledge_document_schedule (
    id                 BIGINT       PRIMARY KEY AUTO_INCREMENT,
    doc_id             BIGINT       NOT NULL UNIQUE,
    kb_id              BIGINT       NOT NULL,
    cron_expr          VARCHAR(128),
    enabled            TINYINT      DEFAULT 0,
    next_run_time      DATETIME,
    last_run_time      DATETIME,
    last_success_time  DATETIME,
    last_status        VARCHAR(32),
    last_error         VARCHAR(512),
    last_etag          VARCHAR(256),
    last_modified      VARCHAR(256),
    last_content_hash  VARCHAR(128),
    lock_owner         VARCHAR(128),
    lock_until         DATETIME,
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_schedule_kb_id (kb_id),
    INDEX idx_schedule_next_run (next_run_time),
    INDEX idx_schedule_lock_until (lock_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 22. 文档定时调度执行记录表
-- ============================================
DROP TABLE IF EXISTS t_knowledge_document_schedule_exec;
CREATE TABLE t_knowledge_document_schedule_exec (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    schedule_id      BIGINT       NOT NULL,
    doc_id           BIGINT       NOT NULL,
    kb_id            BIGINT       NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    message          VARCHAR(512),
    start_time       DATETIME,
    end_time         DATETIME,
    file_name        VARCHAR(512),
    file_size        BIGINT,
    content_hash     VARCHAR(128),
    etag             VARCHAR(256),
    last_modified    VARCHAR(256),
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_exec_schedule_id (schedule_id),
    INDEX idx_exec_doc_id (doc_id),
    INDEX idx_exec_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 初始化数据
-- ============================================

-- 插入默认管理员用户 (密码: admin123, BCrypt加密)
INSERT INTO t_user (username, password, role, create_time, update_time, deleted) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'ADMIN', NOW(), NOW(), 0);

-- 插入默认普通用户 (密码: user123)
INSERT INTO t_user (username, password, role, create_time, update_time, deleted) VALUES
('user', '$2a$10$X5mGGZ9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'USER', NOW(), NOW(), 0);

-- ============================================
-- 外键约束 (可选，建议在数据导入后添加)
-- ============================================
-- ALTER TABLE t_knowledge_base ADD CONSTRAINT fk_kb_created_by FOREIGN KEY (created_by) REFERENCES t_user(username);
-- ALTER TABLE t_knowledge_document ADD CONSTRAINT fk_doc_kb_id FOREIGN KEY (kb_id) REFERENCES t_knowledge_base(id);
-- ALTER TABLE t_knowledge_chunk ADD CONSTRAINT fk_chunk_doc_id FOREIGN KEY (doc_id) REFERENCES t_knowledge_document(id);
