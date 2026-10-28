-- 记忆的少而准（WORK-050）。三件事，各自对应一条需求：
--
-- ① 召回要能算相关度（REQ-002/003）：结构化那一路此前**没有全文索引**，只能按 created_at 取最近 8 条，
--    于是"他还记得我不懂 Redis 吗"取决于那条记忆是不是最近写的。加 ngram 全文索引后两路对称。
-- ② 淘汰以"最近一次被召回"为准（REQ-004）：只按 created_at 会误删"我爱用 Java"这类长期事实。
--    `last_used_at` 可空，NULL 表示"从未被召回"，存量行因此**不需要回填**。
-- ③ 半自动候选独立成表（REQ-006/007）：与正式记忆的保留期、查询方式、删除语义都不同；
--    混在一张表里加 status 列会让召回永远要带一个 `status='accepted'` 条件，而它迟早会被漏掉。
--
-- 与 V4/V5/V6 一致：**不建跨模块外键**，注销级联由应用层的表清单负责（AiUserDeletionListener）。
-- 时间列全部由应用传值（UTC），不用 DEFAULT CURRENT_TIMESTAMP——项目纪律。

ALTER TABLE ai_memories_structured
  ADD FULLTEXT KEY ft_ai_memory_structured (content) WITH PARSER ngram;

ALTER TABLE ai_memories_structured
  ADD COLUMN last_used_at DATETIME(3) NULL,
  ADD KEY idx_ai_memory_structured_used (user_id, last_used_at);

ALTER TABLE ai_memories_text
  ADD COLUMN last_used_at DATETIME(3) NULL,
  ADD KEY idx_ai_memory_text_used (user_id, last_used_at);

CREATE TABLE ai_memory_candidates (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id          BIGINT UNSIGNED NOT NULL,
  -- structured | text
  store            VARCHAR(16)     NOT NULL,
  kind             VARCHAR(32)     NULL,
  ref_id           BIGINT UNSIGNED NULL,
  -- **被敏感信息过滤的行这一列必须为 NULL**：把含口令的原文存进候选表等于换个地方泄露。
  -- 那种行只留 content_bytes 与 decision_reason。
  content          VARCHAR(1024)   NULL,
  content_bytes    INT             NOT NULL DEFAULT 0,
  -- auto | manual（目前只有 auto 会写候选）
  source           VARCHAR(16)     NOT NULL,
  conversation_id  BIGINT UNSIGNED NULL,
  ref_message_id   BIGINT UNSIGNED NULL,
  reason           VARCHAR(255)    NULL,
  -- pending | accepted | rejected | filtered | expired
  status           VARCHAR(16)     NOT NULL,
  -- sensitive | user | expired
  decision_reason  VARCHAR(64)     NULL,
  created_at       DATETIME(3)     NOT NULL,
  expires_at       DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_memory_candidates_pending (user_id, status, created_at),
  KEY idx_ai_memory_candidates_expire (status, expires_at)
) ENGINE = InnoDB;
