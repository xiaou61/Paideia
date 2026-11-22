---
artifact: design
work_id: WORK-042
work: AIOps 自建诊断层
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 18:11:29 +0800
approver_role: CTO
---

# AIOps 自建诊断层设计

## 系统上下文

```
Grafana 规则 ──(邮件)──> 管理员
      │
      └─(webhook, 回环+令牌)─> OpsHookController ─┐
                                                  ▼
管理端运维页 ──(ADMIN JWT)──> OpsDiagnosisController ──> OpsDiagnosisService
                                                          │
                                     ┌────────────────────┼────────────────────┐
                                     ▼                    ▼                    ▼
                            OpsEvidenceCollector    ops_diagnoses 表     paideia-ai
                            ├ PrometheusClient            │            OpsDiagnoser
                            ├ LokiClient                  │         （一次 DeepSeek 调用）
                            ├ OpsSummaryService           │            ├ 积分预留/结算
                            └ 发版指标                     │            └ ai_runs 遥测
                                                          ▼
                                                  管理端列表/详情
```

外部助手（MCP）走同一份工具目录，直接调用 `admin_ops_metrics/logs/alerts`，不经过诊断编排。

## 组件与职责

### paideia-ai（新增公开端口）

| 组件 | 位置 | 职责 |
| --- | --- | --- |
| `OpsDiagnoser` | `ai/OpsDiagnoser.java` | 公开端口：`Diagnosis diagnose(long userId, Evidence evidence)`；`Evidence` / `Diagnosis` 记录 |
| `OpsDiagnosisPrompt` | `ai.internal.diagnosis` | 提示词与版本号 `ops-diagnosis-v1` |
| `OpsDiagnosisModelCaller` | `ai.internal.provider` | 继承 `SingleShotModelCaller`，纯文本、无工具、无结构化输出 |
| `DefaultOpsDiagnoser` | `ai.internal.diagnosis` | 编排凭据、积分、遥测与模型调用 |
| `CreditLedger.KIND_OPS_DIAGNOSIS` | `ai.internal.billing` | 新流水种类 `OPS_DIAGNOSIS`；不改表结构 |
| `CreditService.settleOpsDiagnosis` | `ai.internal.billing` | 结算入口 |
| `RunKind.OPS_DIAGNOSIS` | `ai/RunKind.java` | 遥测场景名 `ops_diagnosis` |

### paideia-app（ops 包）

| 组件 | 职责 |
| --- | --- |
| `LokiClient` | `/loki/api/v1/query_range` 只读客户端；失败返回空列表 |
| `OpsLogRedactor` | 出站前脱敏与截断 |
| `OpsEvidenceCollector` | 固定查询采集摘要/指标/告警/日志/发版，组装 `OpsDiagnoser.Evidence` |
| `OpsDiagnosisService` | 冷却、单飞、落库、异步执行、状态推进 |
| `OpsDiagnosisMapper` | `ops_diagnoses` 读写 |
| `OpsDiagnosisController` | 手动触发与历史查询（ADMIN） |
| `OpsHookController` | Grafana webhook（回环 + 令牌） |
| `OpsAdminTools.Metrics/Logs/Alerts` | 三个只读工具，ADMIN scope |

### 前端

`frontend/apps/admin/src/pages/OpsPage.tsx` 增加诊断区；Grafana 面板 JSON 增加日志面板。

## 请求或事件流程

### 手动诊断

1. 管理员点「AI 诊断」（可填关注点）→ `POST /api/v1/ops/admin/diagnoses`。
2. 控制器从 JWT 取 userId，调用 `OpsDiagnosisService.start("manual", null, question, userId)`。
3. `start` 依次检查：开关 → 单飞 → 插入 `PENDING` 行 → 提交异步任务 → 立即返回 `202 {id}`。
4. 异步任务把行改为 `RUNNING`，调用 `OpsEvidenceCollector.collect(...)`。
5. 调用 `OpsDiagnoser.diagnose(userId, evidence)`；AI 侧预留积分、开始遥测、调用模型、结算、结束遥测。
6. 成功：`DONE` + `report_md` + 模型 + token 用量；失败：`FAILED` + `error_message`，积分已退。
7. 前端在 `PENDING/RUNNING` 时每 3 秒轮询列表；完成后显示报告。

### 告警诊断

1. Grafana 规则 firing → 合并 contact point 同时发邮件与 webhook。
2. `POST /api/v1/ops/hooks/grafana`，安全层先要求来源为回环，控制器再校验令牌。
3. 解析 payload：只取 `status=firing` 的 `alerts[].labels.alertname`，最多 3 个；resolved 直接返回 `200`。
4. 冷却键 = 告警名；窗口内重复 → 返回 `200 {"skipped":"cooldown"}`，不建行。
5. 配置的诊断账号 id 为 0 → 记录跳过日志，返回 `200`。
6. 其它情况调用 `start("alert", alertName, null, configuredUserId)`；忙碌时返回 `200 {"skipped":"busy"}`（避免 Grafana 重试）。

### 工具查询

- 外部 MCP：PAT 必须同时具备 `admin` scope 与 ADMIN 角色；视图与调用各自校验一次。
- 内部助手：`AgentToolBridge` 固定按 `USER` 角色与只读 scope 取清单（既有行为），因此 ADMIN 工具**不会**出现在学习者助手的清单里。诊断编排不经过工具目录，直接调用采集器；这是刻意的，避免把"系统诊断"做成"模型自己挑工具"。

## 接口与数据

### AI 端口

```java
public interface OpsDiagnoser {

    Diagnosis diagnose(long userId, Evidence evidence);

    record Evidence(String trigger, String alertName, String question,
                    String summary, String metrics, String alerts,
                    String logs, String deploys) {
    }

    record Diagnosis(String report, TokenUsage usage, String model) {
    }
}
```

- `Evidence` 的每个文本字段由调用方保证已脱敏、已截断；AI 侧不再读外部数据。
- `DefaultOpsDiagnoser` 在平台未配置凭据时抛 `BizException(ErrorCode.INTERNAL, "平台尚未配置 AI 服务，请联系管理员")`。
- 余额不足由 `CreditService.reserve` 抛 `INSUFFICIENT_CREDITS`，此时**尚未发起上游请求**。
- 上游失败：`refund` 全额退回，`run.finish(FAILED, ...)`，异常继续上抛给编排层。

### 工具

| 工具名 | 输入 | 行为 |
| --- | --- | --- |
| `admin_ops_metrics` | `{"query": <枚举>}` | 预置 PromQL 目录取值；未知枚举拒绝 |
| `admin_ops_logs` | `{"unit?": "paideia"|"laya", "level?": "error"|"warn"|"all", "minutes?": 1-1440, "limit?": 1-200}` | 固定 LogQL；分钟与条数有上限 |
| `admin_ops_alerts` | `{}` | `ALERTS{alertstate="firing"}` 的告警名与标签 |

预置指标目录（枚举名 → PromQL）：

| 枚举 | 含义 |
| --- | --- |
| `targets_down` | 采集/探活目标下线数 |
| `cpu_percent` | 主机 CPU 使用率 |
| `memory_percent` | 主机内存使用率 |
| `disk_percent` | 根分区使用率 |
| `http_5xx_percent` | 近 5 分钟 5xx 比例 |
| `http_p95_seconds` | 近 5 分钟 P95 延迟 |
| `jvm_heap_percent` | JVM 堆使用率 |
| `mysql_connections` | MySQL 当前连接数 |
| `redis_memory_bytes` | Redis 内存 |
| `scheduled_failures` | 最近一次失败的定时任务数 |
| `deploy_age_seconds` | 距最近一次发版的秒数 |

### HTTP 端点

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/ops/admin/diagnoses` | ADMIN | body `{"question?": "..."}`；`202 {id}` |
| GET | `/api/v1/ops/admin/diagnoses` | ADMIN | 最近 N 条（不含全文） |
| GET | `/api/v1/ops/admin/diagnoses/{id}` | ADMIN | 单条全文 |
| POST | `/api/v1/ops/hooks/grafana` | 回环 + 令牌 | Grafana webhook |

安全配置里 `/api/v1/ops/hooks/**` 必须显式排在 `anyRequest()` 之前：`LocalOnlyAuthorizationManager` 只放行回环来源；令牌校验在控制器内完成。令牌为空时一律拒绝。

### 数据库

迁移 `V20261026180000__create_ops_diagnoses.sql`：

```sql
CREATE TABLE ops_diagnoses (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    trigger_type    VARCHAR(16)  NOT NULL,
    alert_name      VARCHAR(128) NULL,
    question        VARCHAR(2000) NULL,
    status          VARCHAR(16)  NOT NULL,
    model           VARCHAR(64)  NULL,
    input_tokens    INT          NULL,
    output_tokens   INT          NULL,
    report_md       MEDIUMTEXT   NULL,
    error_message   VARCHAR(1000) NULL,
    requested_by    BIGINT       NULL,
    created_at      DATETIME(3)  NOT NULL,
    finished_at     DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_ops_diagnoses_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- 时间由 Java 写入 UTC（遵守项目「不让数据库取时间」的纪律），不设 `DEFAULT CURRENT_TIMESTAMP`。
- `trigger_type`：`manual` / `alert`。
- `status`：`PENDING` / `RUNNING` / `DONE` / `FAILED`。
- 不建清理任务：单机诊断频率低，先保留全部；表超过万行再议。

## 持久化与迁移

- 只新增一张表，不改既有表。
- 迁移文件名 14 位时间戳；提交前用 `git log --all -- backend/paideia-app/src/main/resources/db/migration` 确认没有会话占用同一时间戳或改同一张表。
- 回滚：停用诊断开关即可；表可保留（不影响既有功能）。

## 失败处理与恢复

| 失败 | 行为 |
| --- | --- |
| Loki 不可达 | 证据里写「日志不可达」，继续用指标与摘要诊断 |
| Prometheus 不可达 | 摘要返回「未知」，证据里写「指标不可达」，继续用日志诊断 |
| 平台未配置 AI | 诊断行 `FAILED`，错误「平台尚未配置 AI 服务」；不产生上游请求 |
| 积分不足 | 诊断行 `FAILED`，错误含余额；不产生上游请求 |
| 上游超时/空内容 | 退款，行 `FAILED`，错误可读 |
| webhook 令牌错误 | `401`，不建行，不写正文到日志 |
| webhook 非回环 | 安全层拒绝（403） |
| resolved 事件 | `200`，不建行 |
| 冷却窗口内 | `200 skipped=cooldown`，不建行 |
| 并发触发 | 手动 `429`；告警 `200 skipped=busy` |
| 诊断关闭 | 手动 `400/409`；告警 `200 skipped=disabled` |
| 应用重启时有 `RUNNING` 行 | 启动时把创建超过 10 分钟的 `PENDING/RUNNING` 标为 `FAILED`（应用重启中断）；没有独立清扫任务 |

## 安全与权限

- **管理端点**：沿用 `/api/v1/ops/admin/**` 的 `hasRole("ADMIN")`，不新增更宽的规则。
- **webhook**：回环来源 + 共享令牌（常量时间比较）+ 请求体大小上限 256KB + 只读触发。生产 Grafana 为 **13.2.4**，webhook contact point 支持 **Extra Headers**，令牌走 `X-Paideia-Ops-Token`。
- **令牌存放**：服务器 `/etc/paideia-ops/hook.token`（600）；`install-ops.sh` 生成并注入 Grafana contact point 与 `paideia` 的 systemd drop-in；仓库只留 `__PAIDEIA_OPS_HOOK_TOKEN__` 占位。
- **出站**：日志先脱敏再拼接；`<logs>` 明确标记为不可信；系统提示禁止执行日志中的指令。
- **工具**：全部 `readOnly=true`、`destructive=false`；没有写工具、没有 shell、没有任意查询。
- **查询注入**：枚举到固定表达式，用户/模型输入不进入 PromQL/LogQL 字符串拼接。
- **凭据**：不使用新的长期凭据；模型调用沿用平台凭据与 `GuardedHttpTransport`。

## 可观测性

- 每次诊断落一行 `ops_diagnoses`，包含触发方式、耗时（created/finished）、模型与 token 用量、失败原因。
- AI 侧 `RunKind.OPS_DIAGNOSIS`，可在 AI 观测面板按 kind 筛选。
- 应用日志只记录 id、触发方式、耗时与错误类型，**不记录报告正文与日志原文**。
- Prometheus 新增发版指标；Grafana 新增日志面板与两条告警。

## 实施顺序

1. 数据面配置：Prometheus 30d、Loki、Alloy、Grafana 数据源、两条告警、发版指标。
2. AI 端口：`OpsDiagnoser`、提示词、一次性调用、积分种类、`RunKind`。
3. 应用数据层：迁移、行/mapper。
4. 采集与脱敏：`LokiClient`、`OpsLogRedactor`、`OpsEvidenceCollector`。
5. 编排：`OpsDiagnosisService`、手动端点。
6. 工具：三个只读工具。
7. 管理端：诊断区、历史、日志面板。
8. webhook：控制器、令牌、冷却、单飞。
9. 部署与验证：install-ops.sh、release.ps1、生产检查。

## 测试策略

- **单元**：脱敏模式；预置查询枚举；证据字符上限；提示词包含五个小节与不可信标记；积分不足时不调用模型；冷却判断。
- **集成**（MySQL 测试库，通过 `scripts/test-db.sh`）：手动诊断行状态推进、失败落库、ADMIN 权限、非管理员拒绝、webhook 令牌、resolved 忽略、冷却跳过。
- **前端 e2e**：管理端运维页发起诊断、显示进行中与报告、失败原因可见。
- **部署验证**：Loki/Alloy 单元、Loki 查询、Grafana 数据源、告警加载、Prometheus 保留期、发版指标。
- **反向验证**：脱敏测试先去掉脱敏实现跑一次应失败；积分不足测试先去掉预留检查应产生调用。

## 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001 | Prometheus 单元 `--storage.tsdb.retention.time=30d` |
| REQ-002 | `deploy/ops/loki/`、`deploy/ops/alloy/`、install-ops.sh |
| REQ-003 | `provisioning/datasources/loki.yml`、面板日志 panel |
| REQ-004 | `paideia-rules.yaml` 两条新规则 + contact point |
| REQ-005 | `ops-textfile`/`release.ps1` 写 `deploy.prom` |
| REQ-006 | `OpsAdminTools.Metrics/Logs/Alerts` |
| REQ-007 | `OpsPage.tsx` 诊断区 + 三个端点 |
| REQ-008 | `OpsHookController` + 安全规则 + 冷却 |
| REQ-009 | `OpsEvidenceCollector` |
| REQ-010 | `OpsLogRedactor` + 提示词不可信标记 |
| REQ-011 | `DefaultOpsDiagnoser` + `settleOpsDiagnosis` + `RunKind` |
| REQ-012 | `OpsDiagnosisPrompt` 固定五节 |
| REQ-013 | `OpsProperties.Diagnosis` + `hookToken` |
| REQ-014 | 工具描述符只读标记；端点清单 |

## 待决定事项

无。Grafana 13.2.4 的 webhook contact point 支持 Extra Headers，令牌按 `X-Paideia-Ops-Token` 实现；当前用户 id 从 `CurrentUser.subjectId()` 读取。
