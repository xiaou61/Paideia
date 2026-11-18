-- 试卷（WORK-083 TASK-002/003）。
--
-- 试卷是**历史快照**：允许把题干/选项/答案复制进 paper_items，判分与回看都以快照为准，
-- 内容作者之后改正文不影响已出的卷。question_index 仍是筛选用的派生索引，两者互补。

CREATE TABLE papers (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  title         VARCHAR(200)    NOT NULL,
  -- 组卷规则快照（JSON），便于复现与审计
  rule_json     MEDIUMTEXT      NULL,
  -- ready | submitted
  status        VARCHAR(16)     NOT NULL,
  submitted_at  DATETIME(3)     NULL,
  correct_count INT             NULL,
  total_count   INT             NULL,
  created_at    DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_papers_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE paper_items (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  paper_id              BIGINT UNSIGNED NOT NULL,
  user_id               BIGINT UNSIGNED NOT NULL,
  seq                   INT             NOT NULL,
  entry_id              BIGINT UNSIGNED NOT NULL,
  block_key             VARCHAR(64)     NOT NULL,
  question_key          VARCHAR(128)    NOT NULL,
  fingerprint           CHAR(64)        NOT NULL,
  concept_id            BIGINT UNSIGNED NULL,
  difficulty_label      VARCHAR(8)      NOT NULL,
  stem_snapshot         TEXT            NOT NULL,
  options_json          TEXT            NOT NULL,
  answer_index_snapshot INT             NOT NULL,
  explanation_snapshot  TEXT            NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_paper_items_seq (paper_id, seq),
  KEY idx_paper_items_paper (paper_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE paper_answers (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  paper_id     BIGINT UNSIGNED NOT NULL,
  user_id      BIGINT UNSIGNED NOT NULL,
  item_id      BIGINT UNSIGNED NOT NULL,
  choice_index INT             NOT NULL,
  correct      TINYINT(1)      NOT NULL,
  created_at   DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_paper_answers_item (paper_id, item_id),
  KEY idx_paper_answers_paper (paper_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
