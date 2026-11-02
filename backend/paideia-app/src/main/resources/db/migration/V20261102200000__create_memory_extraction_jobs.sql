-- 记忆自动整理的持久任务行（WORK-061 TASK-004）。
--
-- 补的缺口是原设计 §10 登记的「抢到即推进游标 ⇒ 整理失败不重跑」：失败即使被吞掉，
-- 现在也留一行任务等中继重试；进程崩溃留下的 running 行由卡死超时回收重试。
-- 游标与任务行在同一条事务里写：要么两者都发生，要么都不发生。
--
-- 为什么不用 RabbitMQ：全项目没有跨进程消费者；单实例下「表 + 进程内中继」语义等价，
-- 少一个会挂的部署单元（裁决见 WORK-061 的 requirements 非目标）。
CREATE TABLE ai_memory_extraction_jobs (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    conversation_id BIGINT       NOT NULL,
    from_message_id BIGINT       NOT NULL,
    to_message_id   BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3)  NOT NULL,
    locked_at       DATETIME(3)  NULL,
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    -- 同一段切片只允许一行：唯一键在「入队与游标同事务」里兼作回滚哨兵
    UNIQUE KEY uk_memory_job_slice (conversation_id, from_message_id, to_message_id),
    KEY idx_memory_job_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;