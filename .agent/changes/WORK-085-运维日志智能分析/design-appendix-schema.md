# WORK-085 设计附录：表结构与配置默认值

> 本文件是 `design.md` §5 的明细附录（表 DDL、配置默认值）。核心决策与流程见 `design.md`；本文件只放可复制的结构定义，便于实现时对照，避免把 `design.md` 撑过恢复软阈值。

## 5.1 `ops_log_templates`

```
id BIGINT AUTO_INCREMENT PK
template_hash CHAR(64) NOT NULL            -- sha256(规范化模板文本)
unit VARCHAR(64) NOT NULL                  -- paideia / laya …
pattern VARCHAR(512) NOT NULL              -- 脱敏后的人类可读模板
sample_line VARCHAR(1024) NULL             -- 脱敏后的样例
first_seen DATETIME(3) NOT NULL
last_seen  DATETIME(3) NOT NULL
last_count INT NOT NULL DEFAULT 0
total_count BIGINT NOT NULL DEFAULT 0
created_at DATETIME(3) NOT NULL
updated_at DATETIME(3) NOT NULL
UNIQUE KEY uk_ops_log_templates (template_hash, unit)
KEY idx_ops_log_templates_last (unit, last_seen)
```

## 5.2 `ops_log_template_buckets`

```
id BIGINT AUTO_INCREMENT PK
template_hash CHAR(64) NOT NULL
unit VARCHAR(64) NOT NULL
bucket_start DATETIME NOT NULL             -- 整点对齐
dow TINYINT NOT NULL                       -- 0..6，便于基线查询
hour TINYINT NOT NULL                      -- 0..23
count INT NOT NULL
created_at DATETIME(3) NOT NULL
UNIQUE KEY uk_ops_log_template_buckets (template_hash, unit, bucket_start)
KEY idx_ops_log_template_buckets_base (unit, dow, hour, bucket_start)
```

## 5.3 `ops_pattern_anomalies`

```
id BIGINT AUTO_INCREMENT PK
kind VARCHAR(16) NOT NULL                  -- FREQ_SPIKE / NEW_TEMPLATE / DISAPPEARED
template_hash CHAR(64) NOT NULL
unit VARCHAR(64) NOT NULL
ratio DOUBLE NULL
z DOUBLE NULL
baseline_count INT NULL
current_count INT NULL
window_start DATETIME NULL
window_end DATETIME NULL
status VARCHAR(16) NOT NULL DEFAULT 'OPEN' -- OPEN / RESOLVED / TRIGGER_SKIPPED
incident_id BIGINT NULL
diagnosis_id BIGINT NULL
detail VARCHAR(1024) NULL
created_at DATETIME(3) NOT NULL
resolved_at DATETIME(3) NULL
KEY idx_ops_pattern_anomalies_q (unit, kind, created_at)
KEY idx_ops_pattern_anomalies_hash (template_hash)
FULLTEXT KEY ft_ops_pattern_anomalies_detail (detail) WITH PARSER ngram
```

## 5.4 `paideia.ops.patterns.*` 配置（`OpsProperties.Patterns`，默认值）

```
enabled=true
sync-interval-seconds=300
window-minutes=15
baseline-weeks=4
units=[paideia]
freq-spike-ratio=3.0
freq-spike-z=3.0
new-template-min-count=10
disappeared-min-baseline=20
disappeared-windows=2
trigger-enabled=true
cooldown-seconds=900
max-templates-per-sync=2000
template-retention-days=30
top-n=15
max-pattern-chars=2000
userId=<与 diagnosis.userId 同源，默认 0 表示取平台账号>
```

生产值写在服务器侧 `application.yml`（release.ps1 不覆盖），仓库只留默认值与注释。
