-- 积分预约表与流水 round_id（WORK-092 TASK-005 / REQ-005）。
--
-- 背景：旧实现 reserve 只扣余额、不留痕；崩在「预留后、结算前」的那一笔只能靠"余额 == 初始 + 流水和"
-- 这条不变式事后发现，既查不到是哪一轮，也无法自动退回。加一张预约表把「预留」变成一条可追踪、
-- 可幂等推进到终态的记录，再由恢复任务扫描超时未结算的行退回。
--
-- 与 V4/V11 一致：不建跨模块外键，注销级联由应用层清单负责（AiUserDeletionListener 已登记本表）。

CREATE TABLE ai_credit_reservations (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  -- 每一轮生成一个，唯一；结算/退回/恢复都以它做幂等判据。
  round_id        CHAR(36)        NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  -- 预扣额（= maxCreditsPerCall × reserveFactor），与余额扣减在同一事务内写入。
  amount          BIGINT          NOT NULL,
  -- RESERVED（已扣待结算）| SETTLED（已结算）| REFUNDED（已全额退回）
  status          VARCHAR(16)     NOT NULL,
  ref_message_id  BIGINT UNSIGNED NULL,
  note            VARCHAR(200)    NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_credit_reservation_round (round_id),
  -- 恢复任务按「状态 + 创建时间」扫超时未结算的行。
  KEY idx_ai_credit_reservation_status (status, created_at),
  KEY idx_ai_credit_reservation_user (user_id)
) ENGINE = InnoDB;

-- 结算流水带上 round_id，供「一笔预约 ↔ 一行结算流水」逐条对账；唯一键同时做最后一道幂等防线
-- （同一 round 最多一行结算）。历史行该列为 NULL，MySQL 唯一索引允许多个 NULL。
ALTER TABLE ai_credit_ledger
  ADD COLUMN round_id CHAR(36) NULL,
  ADD UNIQUE KEY uk_ai_credit_ledger_round (round_id);
