-- 本地 RAG 的条目块与向量（WORK-072）。
--
-- 只保存已发布知识条目的切片与 embedding；不存用户笔记/记忆/简历/对话。
-- MySQL 8 没有原生向量类型：embedding 以 float32 little-endian 的 BLOB 保存，
-- 当前条目规模在 Java 侧做精确余弦检索，不引入独立向量库。
CREATE TABLE ai_knowledge_chunks (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entry_id     BIGINT UNSIGNED NOT NULL,
    chunk_index  INT             NOT NULL,
    title        VARCHAR(255)    NOT NULL,
    content      MEDIUMTEXT      NOT NULL,
    content_hash VARCHAR(64)     NOT NULL,
    embedding    LONGBLOB        NOT NULL,
    dimension    INT             NOT NULL,
    updated_at   DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_knowledge_chunk_entry_seq (entry_id, chunk_index),
    KEY idx_ai_knowledge_chunk_entry (entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
