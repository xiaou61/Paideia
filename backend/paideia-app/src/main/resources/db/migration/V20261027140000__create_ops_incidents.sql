-- 告警事件与时间线（WORK-043）。
-- 同一个告警指纹在 OPEN 时只能有一行：dedupe_key = fingerprint，RESOLVED 时置 NULL；
-- 唯一键允许历史 RESOLVED 行无限多，但同一指纹同时只有一个 OPEN 事件。
CREATE TABLE ops_incidents (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    fingerprint       VARCHAR(64)   NOT NULL,
    dedupe_key        VARCHAR(64)   NULL,
    alert_name        VARCHAR(128)  NOT NULL,
    severity          VARCHAR(16)   NULL,
    status            VARCHAR(16)   NOT NULL,
    first_seen        DATETIME(3)   NOT NULL,
    last_seen         DATETIME(3)   NOT NULL,
    resolved_at       DATETIME(3)   NULL,
    alert_count       INT           NOT NULL DEFAULT 1,
    last_diagnosed_at DATETIME(3)   NULL,
    last_diagnosis_id BIGINT        NULL,
    labels_json       JSON          NULL,
    created_at        DATETIME(3)   NOT NULL,
    updated_at        DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_incidents_dedupe (dedupe_key),
    KEY idx_ops_incidents_status_last (status, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ops_incident_events (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    incident_id   BIGINT        NOT NULL,
    kind          VARCHAR(32)   NOT NULL,
    message       VARCHAR(1000) NULL,
    actor_user_id BIGINT        NULL,
    payload_json  JSON          NULL,
    created_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ops_incident_events_incident (incident_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ops_diagnoses
    ADD COLUMN incident_id BIGINT NULL,
    ADD COLUMN rating SMALLINT NULL,
    ADD COLUMN feedback_note VARCHAR(500) NULL,
    ADD COLUMN feedback_at DATETIME(3) NULL,
    ADD KEY idx_ops_diagnoses_incident (incident_id);
