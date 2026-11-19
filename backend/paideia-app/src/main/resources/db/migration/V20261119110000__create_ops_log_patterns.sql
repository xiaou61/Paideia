-- 日志模板与模板级异常（WORK-085）。
-- 三张表：模板字典、按小时频次桶、模板级异常。
-- 全部按 (template_hash, unit) 归组；pattern 只存脱敏后文本（OpsLogRedactor 处理后再入库），
-- 原始日志仍只在 Loki，不复制、不外泄。
CREATE TABLE ops_log_templates (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    template_hash CHAR(64)      NOT NULL,
    unit          VARCHAR(64)   NOT NULL,
    pattern       VARCHAR(512)  NOT NULL,
    sample_line   VARCHAR(1024) NULL,
    first_seen    DATETIME(3)   NOT NULL,
    last_seen     DATETIME(3)   NOT NULL,
    last_count    INT           NOT NULL DEFAULT 0,
    total_count   BIGINT        NOT NULL DEFAULT 0,
    created_at    DATETIME(3)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_log_templates (template_hash, unit),
    KEY idx_ops_log_templates_last (unit, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ops_log_template_buckets (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    template_hash CHAR(64)    NOT NULL,
    unit          VARCHAR(64) NOT NULL,
    bucket_start  DATETIME    NOT NULL,
    dow           TINYINT     NOT NULL,
    hour          TINYINT     NOT NULL,
    count         INT         NOT NULL,
    created_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_log_template_buckets (template_hash, unit, bucket_start),
    KEY idx_ops_log_template_buckets_base (unit, dow, hour, bucket_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ops_pattern_anomalies (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    kind          VARCHAR(16)   NOT NULL,
    template_hash CHAR(64)      NOT NULL,
    unit          VARCHAR(64)   NOT NULL,
    ratio         DOUBLE        NULL,
    z             DOUBLE        NULL,
    baseline_count INT          NULL,
    current_count INT           NULL,
    window_start  DATETIME      NULL,
    window_end    DATETIME      NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    incident_id   BIGINT        NULL,
    diagnosis_id  BIGINT        NULL,
    detail        VARCHAR(1024) NULL,
    created_at    DATETIME(3)   NOT NULL,
    resolved_at   DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_ops_pattern_anomalies_q (unit, kind, created_at),
    KEY idx_ops_pattern_anomalies_hash (template_hash),
    FULLTEXT KEY ft_ops_pattern_anomalies_detail (detail) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
