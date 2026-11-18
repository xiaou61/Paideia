-- 题库派生索引（WORK-083 TASK-001）。
--
-- **不是第二真相**：题干/选项/答案仍只在条目正文的 quiz 围栏里，本表只存
-- 「身份 + 难度标签 + 状态」，供刷题/组卷按标签筛选。条目保存/发布后由
-- QuestionIndexService.reconcile 对齐；可随时全量重建（幂等）。
--
-- 只建结构、不插业务数据（MigrationSeedOverlapTest 守着）。

CREATE TABLE question_index (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  entry_id          BIGINT UNSIGNED NOT NULL,
  -- 由条目反范式化，便于按分类子树筛选；条目无分类时为 NULL
  category_id       BIGINT UNSIGNED NULL,
  block_key         VARCHAR(64)     NOT NULL,
  question_key      VARCHAR(128)    NOT NULL,
  -- 与正文解析一致的 SHA-256；内容一改就变
  fingerprint       CHAR(64)        NOT NULL,
  option_count      INT             NOT NULL,
  -- easy | medium | hard（Elo 校准后分桶，未校准默认 medium）
  difficulty_label  VARCHAR(8)      NOT NULL,
  -- active | disabled
  status            VARCHAR(16)     NOT NULL DEFAULT 'active',
  refreshed_at      DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_question_index_identity (entry_id, block_key, question_key),
  KEY idx_question_index_entry (entry_id),
  KEY idx_question_index_category (category_id),
  KEY idx_question_index_difficulty (difficulty_label),
  KEY idx_question_index_fingerprint (fingerprint)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
