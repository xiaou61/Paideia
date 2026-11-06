-- 检索切片的“手工覆盖”版本表（WORK-076）。
--
-- 自动切片（ai_knowledge_chunks）仍由条目正文派生、可被重建；本表存管理员的手工编辑，
-- 按 (entry_id, chunk_index, version) 只追加。检索时用「该位置最大 version」覆盖自动块；
-- 索引重建不触碰本表，因此手工编辑不会被覆盖。
-- 回滚 = 追加一个新版本（内容取自历史版本），保持只追加、可追溯。
CREATE TABLE ai_knowledge_chunk_edits (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  entry_id          BIGINT UNSIGNED NOT NULL,
  chunk_index       INT             NOT NULL,
  version           INT             NOT NULL,
  content           MEDIUMTEXT      NOT NULL,
  content_hash      VARCHAR(64)     NOT NULL,
  -- embedding 可为空：embedding 服务不可用时先存内容、待后续补嵌（检索暂回退自动块）
  embedding         LONGBLOB        NULL,
  dimension         INT             NULL,
  -- 编辑时该位置自动块的内容哈希，用于“正文变更导致块错位”的冲突提示
  base_content_hash VARCHAR(64)     NULL,
  editor_id         BIGINT UNSIGNED NOT NULL,
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_knowledge_chunk_edit (entry_id, chunk_index, version),
  KEY idx_ai_knowledge_chunk_edit_lookup (entry_id, chunk_index, version),
  CONSTRAINT fk_ai_knowledge_chunk_edit_entry FOREIGN KEY (entry_id)
    REFERENCES knowledge_entries (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
