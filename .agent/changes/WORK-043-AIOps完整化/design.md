---
artifact: design
work_id: WORK-043
work: AIOps 完整化：事件、基线、链路、SLO 与评测
status: approved
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 13:52:33 +0800
approver_role: CTO
---

# AIOps 完整化设计

## 系统上下文

```
Grafana Alerting（6 旧 + 基线 + SLO burn-rate）
        │ webhook（回环 + 令牌，同 WORK-042）
        ▼
OpsHookController ──> 事件指纹 / upsert ──> ops_incidents
        │                                      │
        │                                      └─ ops_incident_events（时间线）
        ▼
OpsDiagnosisService（每事件冷却 / 单飞）
        │
        ├── OpsEvidenceCollector v2
        │     ├ Prometheus：摘要 + 固定指标 + 基线（1h/24h/7d）+ SLO burn
        │     ├ Loki：脱敏日志（带 traceId）
        │     ├ Tempo：错误/慢 span 摘要
        │     └ 发版时间/版本
        │
        ├── OpsDiagnoser（一次 DeepSeek 调用，五节报告）
        └── ops_diagnoses（incident_id / rating / feedback）
                    │
                    ├── 管理端：事件列表 + 时间线 + 报告 + 评分
                    └── 金集重放（手动 / 非 CI）

应用 ──OTLP HTTP──> Tempo（127.0.0.1:4318）
应用 ──Prometheus──> 现有指标（HTTP/AI/登录/定时任务）
日志 ──Alloy──> Loki（traceId/spanId 在行内）
```

## 组件与职责

| 组件 | 位置 | 职责 |
| --- | --- | --- |
| `OpsIncident` / `OpsIncidentEvent` | `paideia-app/ops` | 事件行与时间线行 |
| `OpsIncidentMapper` | `paideia-app/ops` | 按指纹 upsert、关闭、查询、时间线追加 |
| `OpsIncidentService` | `paideia-app/ops` | 指纹、状态机、抑制、与诊断的挂载 |
| `OpsHookController` | `paideia-app/ops` | 解析 Grafana 载荷（含指纹/标签/状态），调用事件服务 |
| `OpsDiagnosisService` | `paideia-app/ops` | 增加 incidentId、按事件冷却、写时间线 |
| `OpsEvidenceCollector` | `paideia-app/ops` | 证据 v2：基线、trace 摘要、SLO burn |
| `TempoClient` | `paideia-app/ops` | Tempo TraceQL 搜索与单 trace 读取 |
| `OpsFeedbackController` | `paideia-app/ops` | 诊断评分 |
| `OpsIncidentController` | `paideia-app/ops` | 事件列表/详情/时间线 |
| `DefaultRunRecorder` | `paideia-ai/telemetry` | 发 `paideia.ai.runs` / 时长指标 |
| `LoginService` | `paideia-account/login` | 发登录结果指标 |
| `CreditService` | `paideia-ai/billing` | 发结算不足指标 |
| `TraceIdFilter` | `paideia-web` | 优先使用 OTel trace id，保留 `X-Trace-Id` |
| 管理端 | `frontend/apps/admin` | 事件列表/时间线、诊断评分、SLO 链接 |
| 部署 | `deploy/ops` | Tempo、录制规则、SLO/基线规则、数据源、日志关联 |

## 事件与告警流程

### webhook → 事件

1. Grafana 通知策略按 `alertname + severity` 分组；一条通知可含多个 alert。
2. 控制器校验回环 + 令牌（沿用 WORK-042），解析 `alerts[]`：
   - `fingerprint`（Grafana 提供则用；否则 SHA-256(`alertname|severity|instance|job`)）；
   - `alertname`、`severity`、`status`、`labels`、`startsAt`/`endsAt`。
3. 对每个 firing alert：
   - `INSERT ... ON DUPLICATE KEY UPDATE last_seen=VALUES(last_seen), alert_count=alert_count+1`，
     其中 `dedupe_key = fingerprint`（OPEN 时非空，RESOLVED 后置 NULL）。
   - 追加 `FIRED` 时间线；首次插入记 `first_seen`。
4. 对 resolved alert：
   - 找到 `dedupe_key = fingerprint` 的 OPEN 事件，置 `RESOLVED`、`resolved_at`、`dedupe_key=NULL`，追加 `RESOLVED` 时间线。
5. 抑制：同一通知里若存在 `severity=critical` 的事件，`warning` 事件只记时间线、不触发诊断；同一事件已有 OPEN 诊断在冷却内也不触发。
6. 至多触发 **1 次**诊断（成本控制）：取第一个“新开/未被冷却”的事件；其余事件等待下一次通知或手动。

### 事件 → 诊断

- `OpsDiagnosisService.start(trigger, incidentId, alertName, question, userId)`。
- 手动触发 `incidentId=null`，不占事件冷却，但仍受全局单飞限制。
- 事件触发检查：
  - 事件必须 OPEN；
  - `last_diagnosed_at` 距今 ≥ 冷却（默认 600s）；
  - 没有正在跑的诊断。
- 开始时更新 `last_diagnosed_at`、追加 `DIAGNOSIS_STARTED`；完成/失败追加对应事件并更新 `last_diagnosis_id`。
- 事件恢复不关闭诊断；诊断是历史记录。

### 基线 → 告警

- Prometheus 录制规则（`prometheus-rules.yml`）计算：
  - `paideia:sli_*`（SLO 用）；
  - `paideia:baseline:*:ratio_7d`（当前 5m / 上周同时段 1h）；
  - `paideia:baseline:*:samples_7d`（最小样本）。
- Grafana 基线告警只读录制序列，条件简单：
  - 5xx：`current > 0.01 AND ratio > 3 AND samples_7d > 100`；
  - CPU：`current > 70 AND ratio > 2`；
  - P95：`current > 1 AND ratio > 2`；
  - ERROR 日志：Loki 两条查询（当前 5m、`offset 1w`）+ 数学表达式，`NoData=OK`。
- 录制规则用 `promtool test rules` 覆盖正常/尖峰/样本不足三种输入。

### 链路

- 应用引入 `spring-boot-starter-opentelemetry`；OTLP HTTP 发到 `127.0.0.1:4318`，指标仍走 Prometheus（关闭 OTLP metrics 导出）。
- `TraceIdFilter`：注入 Micrometer `Tracer`，优先使用当前 span 的 trace id；没有 span 时沿用入站 `X-Trace-Id` 或生成。响应头仍是 `X-Trace-Id`。
- 日志 pattern 增加 `%X{traceId:-}`；Loki 行内出现 trace id，Grafana Loki 数据源配置 derived field 跳到 Tempo。
- 诊断证据通过 `TempoClient` 查事件窗口：
  - TraceQL：`{ resource.service.name = "paideia" && status = error }` 与 `{ ... && duration > 1s }`；
  - 最多 5 条 trace；每条取最慢/错误 span 的名称、耗时、状态、白名单属性；
  - 不读取请求体、SQL 参数、Authorization/Cookie。
- 若 Tempo 不可达：证据写“trace 不可达”，诊断继续。

### SLO

- SLI 录制规则（30s 间隔）：
  - HTTP 可用性（排除 actuator 与 4xx）；
  - HTTP 延迟（<1s 占比，使用 WORK-042 已开启的直方图）；
  - AI 成功率（`paideia.ai.runs` 的 ok / 全部；402 不进 run）；
  - 登录成功率；
  - 定时任务成功率。
- burn rate：`(1 - SLI) / (1 - SLO)`；按 Google SRE 多窗口：
  - 快烧：1h > 14.4 且 5m > 14.4；
  - 慢烧：6h > 6 且 30m > 6；
  - 工单：3d > 1 且 6h > 1。
- 初始目标：HTTP 可用性 99.5%、延迟 95%、AI 99%、登录 99%、定时任务 99%；写在 `deploy/ops/slo-targets.env` 或录制规则注释中，改目标只改一处。
- SLO 面板：Grafana `paideia-ops` 增加 3 个 panel（SLI 趋势、burn rate、预算剩余）；运维页保留链接/iframe，不新增复杂后端接口。

### 反馈与评测

- `ops_diagnoses` 增加 `rating`（1=准，0=部分，-1=不准）、`feedback_note`、`feedback_at`。
- `POST /api/v1/ops/admin/diagnoses/{id}/feedback`：ADMIN；校验 rating；有 incident 时追加 `NOTE` 时间线。
- `GET /api/v1/ops/admin/diagnoses/stats`：返回 rated/good/partial/bad/helpfulRate。
- 金集：`backend/paideia-app/src/test/resources/ops-eval/*.json`，每条含 `name`、`evidence`、`expected.mustMention`/`mustNotMention`、`notes`。
- 评测两种模式：
  - **prompt 模式（默认/CI）**：只组装提示词，断言五节、证据标签、长度上限；不调用模型。
  - **live 模式（手动，`PAIDEIA_OPS_EVAL=1`）**：调用真实模型，按 `mustMention`/`mustNotMention` 打分，输出 `target/ops-eval-report.md`；缺一条必须提及即失败。
- 不做 LLM-as-judge 自动门禁；不根据反馈自动改提示词。

## 接口与数据

### 数据表

`ops_incidents`：

```sql
CREATE TABLE ops_incidents (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    fingerprint       VARCHAR(64)  NOT NULL,
    dedupe_key        VARCHAR(64)  NULL,
    alert_name        VARCHAR(128) NOT NULL,
    severity          VARCHAR(16)  NULL,
    status            VARCHAR(16)  NOT NULL,
    first_seen        DATETIME(3)  NOT NULL,
    last_seen         DATETIME(3)  NOT NULL,
    resolved_at       DATETIME(3)  NULL,
    alert_count       INT          NOT NULL DEFAULT 1,
    last_diagnosed_at DATETIME(3)  NULL,
    last_diagnosis_id BIGINT       NULL,
    labels_json       JSON         NULL,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_incidents_dedupe (dedupe_key),
    KEY idx_ops_incidents_status_last (status, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`ops_incident_events`：

```sql
CREATE TABLE ops_incident_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id   BIGINT       NOT NULL,
    kind          VARCHAR(32)  NOT NULL,
    message       VARCHAR(1000) NULL,
    actor_user_id BIGINT       NULL,
    payload_json  JSON         NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ops_incident_events_incident (incident_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`ops_diagnoses` 增量：

```sql
ALTER TABLE ops_diagnoses
    ADD COLUMN incident_id BIGINT NULL,
    ADD COLUMN rating SMALLINT NULL,
    ADD COLUMN feedback_note VARCHAR(500) NULL,
    ADD COLUMN feedback_at DATETIME(3) NULL,
    ADD KEY idx_ops_diagnoses_incident (incident_id);
```

- 迁移 14 位时间戳；只建结构。
- `dedupe_key` 在 OPEN 时等于 fingerprint，RESOLVED 时置 NULL；唯一键保证“同一指纹同时只有一个 OPEN 事件”，历史 RESOLVED 行可无限多。

### 指纹与状态机

- `fingerprint = lower(hex(sha256(alertname + "|" + severity + "|" + instance + "|" + job)))`；Grafana 提供 `fingerprint` 时优先用它。
- 不含时间、摘要、注释——这些每次通知都可能变，含进去会让去重失效。
- 状态：`OPEN` ↔ `RESOLVED`；`ACKNOWLEDGED` 可选，本期不做分派，只在时间线允许备注。
- 关闭后再 firing：`dedupe_key` 为 NULL，新插入一行 → 新事件（满足 AC-001）。

### 端点

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/ops/admin/incidents` | ADMIN | 最近 N 条，可按 status 过滤 |
| GET | `/api/v1/ops/admin/incidents/{id}` | ADMIN | 事件 + 时间线 + 关联诊断摘要 |
| POST | `/api/v1/ops/admin/incidents/{id}/notes` | ADMIN | 追加人工备注 |
| POST | `/api/v1/ops/admin/diagnoses/{id}/feedback` | ADMIN | rating + note |
| GET | `/api/v1/ops/admin/diagnoses/stats` | ADMIN | helpful 率 |
| POST | `/api/v1/ops/hooks/grafana` | 回环 + 令牌 | 扩为事件 upsert/resolve |

所有新端点沿用 `ApiResponse` 与 `hasRole("ADMIN")`；webhook 仍用 `LocalOnlyAuthorizationManager` + `X-Paideia-Ops-Token`。

### 证据 v2

`OpsDiagnoser.Evidence` 增加 `baselines` 与 `traces` 两个字段（或一个 `String extra`？）：

```java
record Evidence(String trigger, String alertName, String question,
                String summary, String metrics, String baselines,
                String alerts, String logs, String traces, String deploys) {}
```

- 顺序即优先级；`OpsDiagnosisPrompt` 增加 `<baselines>` 与 `<traces>` 段。
- 总上限仍 12k：摘要 > 指标 > 基线 > 告警 > 事件 > trace > 日志，超限从后往前截。
- `baselines` 行格式：
  `metric=http_5xx_percent current=0.40% 1h=0.20% 24h=0.30% 7d_same_hour=0.25% ratio=1.6x samples_7d=20160`
- `traces` 行格式：
  `trace=<id> span=POST /api/v1/ai/... duration=2.4s status=error route=/api/v1/ai/...`
  只保留白名单属性。

### 配置

应用：

```yaml
management:
  tracing:
    sampling:
      probability: ${PAIDEIA_TRACE_SAMPLE_PROBABILITY:1.0}
  otlp:
    tracing:
      endpoint: ${PAIDEIA_OTLP_TRACES_ENDPOINT:http://127.0.0.1:4318/v1/traces}
    metrics:
      export:
        enabled: false
logging:
  pattern:
    correlation: "[%X{traceId:-}] "
```

运维：

- `paideia.ops.incidents.cooldown-seconds`（默认 600）
- `paideia.ops.incidents.max-alerts-per-notification`（默认 5）
- `paideia.ops.incidents.suppress-warning-when-critical`（默认 true）
- `paideia.ops.tempo.url`（默认 `http://127.0.0.1:3200`）
- `paideia.ops.eval.enabled`（默认 false）

## 持久化与迁移

- 新表 + `ops_diagnoses` 加列；全部 additive，不改既有列。
- 迁移前查 `git log --all -- backend/paideia-app/src/main/resources/db/migration`，避免与并发会话撞号。
- 回滚：停用基线/SLO/链路开关，Tempo 单元可停；表保留（不影响 WORK-042 路径）。

## 失败处理与恢复

| 失败 | 行为 |
| --- | --- |
| 同一 webhook 重复投递 | unique dedupe_key + upsert，只更新时间/计数 |
| 事件已 RESOLVED 又来 firing | 新事件（dedupe_key NULL → 插入） |
| 诊断失败 | 事件保持 OPEN，追加 FAILED；下次冷却后可再诊断 |
| 事件长期未恢复 | 不自动关闭；保留 OPEN，管理端可备注/手动关闭（后续可选） |
| Tempo 不可达 | 证据写“trace 不可达”，诊断继续 |
| OTLP 导出失败 | 不影响请求；日志记录导出错误（低频） |
| 基线样本不足 | 证据写“基线不足”，规则不触发 |
| SLO 无流量 | `NoData=OK`，不触发 |
| 事件抑制 | warning 事件只记时间线，不诊断；critical 事件正常诊断 |
| 反馈非法 rating | 400；不改行 |
| 金集 live 模式失败 | 输出失败清单；不阻断 CI（手动运行） |

## 安全与权限

- 新端点全部 ADMIN；webhook 回环 + 令牌。
- 事件 `labels_json` 只保留白名单标签（alertname/severity/instance/job/unit），截断长度；不存令牌/URL。
- trace 只读白名单属性；不采集请求体、SQL 参数、Authorization/Cookie；Tempo 只绑回环。
- 反馈备注截断 500 字；金集 evidence 使用脱敏后的快照，不含真实用户数据。
- SLO/基线规则不使用用户 id、路径参数等高基数标签。
- OTLP endpoint 只允许回环；配置中不写入凭据。

## 可观测性

- 新指标：`paideia_ai_runs_total{kind,outcome}`、`paideia_ai_run_duration_seconds{kind}`、`paideia_login_attempts_total{outcome}`、`paideia_credit_settlement_shortfall_total`。
- Tempo 自身 `/metrics` 加入 Prometheus 抓取；`up{job="tempo"}` 覆盖服务存活。
- 事件/诊断/反馈都有表记录；运维页可回看。
- SLO 面板 + burn-rate 告警。

## 实施顺序

1. **P0 事件地基**：迁移、事件服务、webhook upsert、诊断挂载、事件端点/UI、Grafana 分组/抑制。
2. **P1 基线**：录制规则、证据基线、动态告警、日志基线、promtool 测试。
3. **P2 链路**：OTel starter、Tempo、trace id 统一、日志关联、trace 证据。
4. **P3 SLO**：AI/登录/结算指标、SLI 录制规则、burn-rate 规则、面板。
5. **P4 评测**：评分、金集、重放、helpful 率。
6. **P5 收口**：证据上限/脱敏复核、全链路演练、部署与生产验证。

## 测试策略

- 单元：指纹稳定性、状态机、抑制、证据截断、评分校验、trace id 选择。
- 集成（MySQL 测试库）：webhook firing/resolved/重复/重开、事件时间线、诊断挂载/冷却、反馈。
- 规则：`promtool check rules` + `promtool test rules`（基线录制规则、SLO 录制规则）；Grafana 规则加载检查。
- 链路：`X-Trace-Id` == OTel trace id；日志含 traceId；Tempo 查询到 span；trace 证据不含黑名单属性。
- 前端：事件列表/时间线、诊断评分、SLO 链接的 e2e。
- 金集：prompt 模式在 CI；live 模式手动。
- 生产：部署后制造一次合成告警 → 事件 → 诊断；检查 Tempo trace、SLO 指标、无活动误报。

## 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001..007 | 事件表、指纹、状态机、webhook、事件端点/UI、Grafana 分组/抑制 |
| REQ-008..012 | 录制规则、证据 baselines、基线告警、日志基线、最小样本 |
| REQ-013..018 | OTel starter、Tempo、TraceIdFilter、Loki derived field、TempoClient、属性白名单 |
| REQ-019..022 | 评分列/端点/UI、金集、promtool/prompt 测试、helpful 率 |
| REQ-023..028 | AI/登录/结算指标、SLI 录制规则、burn-rate、SLO 面板、事件路径 |

## 待决定事项

无。四项选型与 SLO/评测默认值已由用户确认。
