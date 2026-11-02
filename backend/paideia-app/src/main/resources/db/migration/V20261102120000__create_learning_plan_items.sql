-- 「今天要做的事」（WORK-053 阶段二）。
--
-- 计划项是**用户点「采用」之后**才落库的：助手只给候选，写入由人触发（REQ-007）。
-- `plan_date` 用 DATE 而不是 DATETIME：这一列表达的是"哪一天做"，不是某个时刻；
-- 时区换算发生在客户端（它知道自己所在的日期），服务端不做猜测。
--
-- 与其它用户私有表一致：**不建跨模块外键**，注销级联由应用层表清单负责。
CREATE TABLE learning_plan_items (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  plan_date  DATE            NOT NULL,
  goal       VARCHAR(200)    NOT NULL,
  reason     VARCHAR(300)    NOT NULL DEFAULT '',
  done_at    DATETIME(3)     NULL,
  created_at DATETIME(3)     NOT NULL,
  updated_at DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_plan_user_date (user_id, plan_date)
) ENGINE = InnoDB;
