-- 运维诊断记录（WORK-042）。
-- 一次诊断一行；报告全文、模型、用量与失败原因都可回看。
-- 时间由应用写 UTC，不设数据库默认值（项目规则：不让数据库取业务时间）。
CREATE TABLE ops_diagnoses (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    trigger_type    VARCHAR(16)   NOT NULL COMMENT 'manual / alert',
    alert_name      VARCHAR(128)  NULL,
    question        VARCHAR(2000) NULL,
    status          VARCHAR(16)   NOT NULL COMMENT 'PENDING / RUNNING / DONE / FAILED',
    model           VARCHAR(64)   NULL,
    input_tokens    INT           NULL,
    output_tokens   INT           NULL,
    report_md       MEDIUMTEXT    NULL,
    error_message   VARCHAR(1000) NULL,
    requested_by    BIGINT        NULL,
    created_at      DATETIME(3)   NOT NULL,
    finished_at     DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_ops_diagnoses_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
