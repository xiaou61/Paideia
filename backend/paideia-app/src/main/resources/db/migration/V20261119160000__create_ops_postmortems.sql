-- 运维复盘（WORK-086）：把"这次到底怎么回事、我们改了什么"沉淀成可检索的知识。
-- 与 ops_diagnoses 的区别：诊断是模型对一次事件的推测，复盘是人确认过的事实。
-- 只有 APPROVED 才进知识召回，DRAFT 只是草稿箱，ARCHIVED 退出检索但保留历史。
-- 全文索引用 ngram parser：中文没有空格，默认分词器按空格切会整段检索不到（WORK-085 同款做法）。
CREATE TABLE ops_postmortems (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id   BIGINT       NULL,
    diagnosis_id  BIGINT       NULL,
    alert_name    VARCHAR(128) NULL,
    title         VARCHAR(200) NOT NULL,
    severity      VARCHAR(16)  NULL,
    status        VARCHAR(16)  NOT NULL COMMENT 'DRAFT / APPROVED / ARCHIVED',
    root_cause    TEXT         NULL,
    impact        TEXT         NULL,
    timeline_md   MEDIUMTEXT   NULL,
    actions_md    MEDIUMTEXT   NULL,
    body_md       MEDIUMTEXT   NOT NULL,
    evidence_refs VARCHAR(500) NULL COMMENT '诊断 id / traceId / 日志签名，逗号分隔',
    origin        VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT 'manual / auto（诊断完成后自动起草）',
    created_by    BIGINT       NULL,
    approved_by   BIGINT       NULL,
    approved_at   DATETIME(3)  NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ops_pm_incident (incident_id),
    KEY idx_ops_pm_status (status, updated_at),
    FULLTEXT KEY ft_ops_pm_body (title, root_cause, body_md) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
