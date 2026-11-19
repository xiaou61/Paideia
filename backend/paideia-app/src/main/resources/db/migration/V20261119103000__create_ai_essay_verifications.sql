-- 面经问答验证记录（WORK-084）。
--
-- 抽取出的每道题先过一遍内部事实自检：高置信直接入库，存疑进人工复核队列。
-- 只存"待复核/已复核"的判定结果与中间稿，不替代 knowledge_entries（采纳后才生成条目）。
-- revised_answer / used_refs 等列保留，后续启用外部依据检索时无需改表。
CREATE TABLE ai_essay_verifications (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  status          VARCHAR(24)     NOT NULL, -- auto_passed|revised|pending_review|approved|rejected
  question_title  VARCHAR(255)    NOT NULL,
  draft_answer    MEDIUMTEXT      NOT NULL,
  final_answer    MEDIUMTEXT      NULL,
  confidence      DECIMAL(4,3)    NULL,
  issues          JSON            NULL,
  snippets        JSON            NULL,
  category_id     BIGINT UNSIGNED NULL,
  fingerprint     VARCHAR(64)     NULL,
  source_url      VARCHAR(1024)   NULL,
  source_title    VARCHAR(255)    NULL,
  entry_id        BIGINT UNSIGNED NULL,
  run_id          VARCHAR(64)     NULL,
  prompt_version  VARCHAR(32)     NULL,
  model           VARCHAR(64)     NULL,
  review_note     VARCHAR(1000)   NULL,
  reviewed_by     BIGINT UNSIGNED NULL,
  reviewed_at     DATETIME(3)     NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_status_created (status, created_at),
  KEY idx_fingerprint (fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
