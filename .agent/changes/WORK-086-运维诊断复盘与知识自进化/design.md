---
work_id: WORK-086
work: 运维诊断复盘与知识自进化
artifact: design
status: draft
mode: strict
updated: 2026-11-19
---

# 运维诊断复盘与知识自进化 设计

> 依据：`requirements.md`（草案，待批准）、`proposal.md`（草案，待批准）。需求一旦改动，本文件的变更清单与任务需重校验。
> 范围：REQ-001~010。落点 = `paideia-ops` 证据与复盘 + `paideia-ai` 提示词契约 + 邮件通道 + 管理端两页。

## 变更清单

| 位置 | 类型 | 改前 | 改后 |
| --- | --- | --- | --- |
| `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/OpsDiagnoser.java:33-36` | 修改 | `record Evidence(trigger, alertName, question, summary, metrics, baselines, context, alerts, logs, patterns, traces, deploys)` 共 12 个组件 | 增加 `String window`、`String knowledge` 两个组件（14 个）；所有构造点同步 |
| `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/diagnosis/OpsDiagnosisPrompt.java:28-66` | 修改 | 硬性规则 1~9；`user()` 只有 `<evidence>` 内 11 个子标签 | 增规则 10（`<knowledge>` 不可信、冲突以本次证据为准）；`user()` 增 `<window>` 与 `<knowledge>` |
| `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/diagnosis/DefaultOpsDiagnoser.java` | 不变 | — | 不感知窗口/知识，只转发 Evidence |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsEvidenceCollector.java:97-121` | 修改 | `collect(trigger, alertName, question, context)`；日志预算 = 12000 − 非日志段 | `collect(..., OpsEvidenceWindow window)`；预算按分段配额分配；新增 `runtime`/`signatures` 两段 |
| 同文件 `:378-395` | 修改 | `to=Instant.now(); from=to-1h`；每 unit 一条 `\|~ "(?i)(error\|exception\|failed\|timeout)"` | 用传入窗口；每 unit 三段查询（异常/生命周期/上下文），每段独立 limit；无数据显式文案 |
| 同文件 `:341-343` | 修改 | `formatTraces()` 固定 `now-1h` | `formatTraces(window)` |
| 同文件 `:31-91`（`METRICS`） | 修改 | 10 项：targets_down、cpu、memory、disk、5xx、p95、jvm_heap、mysql_connections、redis_memory、scheduled_failures | 追加 9 项运行态指标（见 §3），窗口类查询单列 |
| 同文件 `:397-405`（`formatLogLines`） | 修改 | 按时间倒序拼接、`joinRedacted` 截断 | 先按签名去重聚合（首末时间 + 计数 + 首条样例），再渲染 |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsEvidenceWindow.java` | 新增 | 无 | `record OpsEvidenceWindow(Instant start, Instant end, String source)` + `of(...)` 工厂 |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsLogSignature.java` | 新增 | 无 | 归一化签名（数字/十六进制/UUID/时间/引号 id → 占位符），`String of(String line)` |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisEmail.java:23-79` | 修改 | `body(...)` 纯文本；`toPlainText` 只降级标题 | 保留 `body`；新增 `html(...)`、`conclusion(...)` |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisMailer.java:88` | 修改 | `new MimeMessageHelper(message, false, "UTF-8")` + `setText(plain)` | `new MimeMessageHelper(message, true, "UTF-8")` + `setText(plain, html)` + `addAttachment(diagnosis-<id>.md)` |
| `backend/paideia-ops/.../OpsDiagnosisMailer.java` 方法签名 | 修改 | `void sendDiagnosis(String alertName, String reportMd, List<String> traceIds)` | `void sendDiagnosis(long diagnosisId, String alertName, String reportMd, List<String> traceIds)` |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisService.java:253-270` | 修改 | `contexts.build(...)` → `evidence.collect(trigger, alertName, question, context)`，无窗口；`mailer.sendDiagnosis(alertName, report, traceIds)` | 先算 `OpsEvidenceWindow`；召回知识 → `evidence.collect(..., window, knowledge)`；`markDone` 写 `knowledge_ids`；邮件传 diagnosisId |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsProperties.java:187-201` | 修改 | `maxLogLines=60`、`maxEvidenceChars=12000`、`listLimit=20`、`units=[paideia,laya]` | 增 `evidenceWindowMinutes=30`、`evidenceWindowMaxMinutes=60`、`logsPerUnit=30`、`signatureTop=10`；`maxEvidenceChars=16000` |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/PostmortemService.java` | 新增 | 无 | 草稿生成/编辑/审批/归档/下载/召回编排 |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/PostmortemMapper.java` | 新增 | 无 | CRUD + `searchApproved`（MATCH…AGAINST + alert_name 过滤） |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/PostmortemController.java` | 新增 | 无 | `/api/v1/ops/admin/postmortems/**`，全部 ADMIN + 回环 |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/PostmortemRow.java` | 新增 | 无 | 表行映射 |
| `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/PostmortemKnowledge.java` | 新增 | 无 | `List<Hit> recall(String alertName, String signatures, int limit)` 端口（供诊断调用，避免 Service 循环依赖） |
| `backend/paideia-app/src/main/resources/db/migration/V2026111916xxxx__create_ops_postmortems.sql` | 新增 | 无 | 建表 + FULLTEXT ngram（文件名在实现时取当下时间戳） |
| `backend/paideia-app/src/main/resources/db/migration/V2026111916xxxx__ops_diagnoses_knowledge.sql` | 新增 | 无 | `ALTER TABLE ops_diagnoses ADD COLUMN knowledge_ids VARCHAR(255) NULL` |
| `backend/paideia-ops/pom.xml` | 修改 | 无 commonmark 依赖 | + `org.commonmark:commonmark`、`commonmark-ext-gfm-tables`（版本钉死） |
| `frontend/apps/admin/src/pages/OpsPage.tsx:492-494` | 修改 | 只渲染 `reportMd` | 增「生成复盘」按钮、知识引用列表、复盘链接 |
| `frontend/apps/admin/src/pages/PostmortemsPage.tsx` | 新增 | 无 | 列表 + 编辑（Markdown 预览）+ 审批/归档/下载 |
| `frontend/apps/admin/src/router.tsx` | 修改 | 无 `/admin/ops/postmortems` 路由 | 新增路由，并在导航/账号菜单加入口 |
| `backend/paideia-ops/src/test/java/.../OpsEvidenceCollectorTest.java:103` | 修改 | `collect("manual", null, "为什么慢")` | 传窗口；新增重启/签名断言 |
| `backend/paideia-ops/src/test/java/.../OpsDiagnosisServiceTest.java:69` | 修改 | `evidence.collect(anyString(), any(), any(), any())` | 补窗口参数；断言 `knowledge_ids` |
| `backend/paideia-ai/src/test/java/.../DefaultOpsDiagnoserTest.java:132`、`OpsDiagnosisGoldenSetTest.java:183`、`OpsDiagnosisPromptTest.java:38/59` | 修改 | `new OpsDiagnoser.Evidence(12 个参数)` | 14 个参数（补 window/knowledge） |

## 1. 证据窗口

```java
record OpsEvidenceWindow(Instant start, Instant end, String source) {
    // 有事件：end = last_seen + 5min，start = min(first_seen - 10min, end - 10min)
    // 无事件：end = triggerAt，start = end - evidenceWindowMinutes
    // 两种都受 evidenceWindowMaxMinutes 约束（默认 60）
}
```

- 来源字符串就是证据头要显示的那句：`事件 #17（首次 14:17:40，最近 14:18:15）` / `手动诊断（触发 14:31）`。
- 证据头固定一行：`证据窗口 14:07–14:23（事件 #17）`，写入 `Evidence.window`，Prompt 用 `<window>` 渲染。
- 窗口早于 `now - 30d` ⇒ `source` 追加 `（超出 Loki 保留期，仅指标可用）`。

## 2. 日志三段采集

每个 unit 三次查询（常量 LogQL，unit 来自配置白名单，用户输入不进查询）：

| 段 | LogQL 过滤 | 每 unit limit |
| --- | --- | --- |
| 异常 | `\|~ "(?i)(error\|exception\|failed\|timeout\|refused\|panic)"` | `logsPerUnit`（默认 30） |
| 生命周期 | `\|~ "(?i)(starting\|started\|stopping\|shutdown\|restart\|exit\|killed\|oom\|pool\|health)"` | 20 |
| 上下文 | 无过滤，取窗口末尾 | 2 |

- 三段合并后按 `OpsLogSignature.of(line)` 聚合：同签名保留 `首 14:16:59 … 末 14:26:30 ×4`，样例取最新一条，样例再过 `OpsLogRedactor`。
- 渲染顺序按「首次出现时间升序」，让人能看到因果链；不再按倒序把同一行的 4 次重复刷屏。
- 每 unit 独立配额；某 unit 无数据时写 `mysql.service：该 unit 未接入 Loki（alloy 白名单待核实）`，不静默省略。

## 3. 指标目录扩展

追加到 `METRICS`（标量，沿用现有基线机制）：

| name | PromQL | 单位 |
| --- | --- | --- |
| `process_uptime` | `time() - process_start_time_seconds` | 秒 |
| `hikari_pending` | `max(hikaricp_connections_pending)` | 连接 |
| `hikari_acquire_max` | `max(hikaricp_connections_acquire_seconds_max)` | 秒 |
| `hikari_timeouts` | `sum(increase(hikaricp_connections_timeout_total[1h]))` | 次/小时 |
| `jvm_gc_pause_max` | `max(jvm_gc_pause_seconds_max)` | 秒 |
| `jvm_threads_blocked` | `sum(jvm_threads_states_threads{state="blocked"})` | 线程 |
| `node_load1` | `node_load1` | — |
| `node_oom_kill` | `increase(node_vmstat_oom_kill[1h])` | 次/小时 |
| `mysql_threads` | `mysql_global_status_threads_connected` | 连接 |

窗口类（用 `[<windowMinutes>m]`，如 30m）：

- `changes(process_start_time_seconds[<window>m])` ⇒ **窗口内重启次数**（与日志交叉验证）；
- `increase(node_vmstat_oom_kill[<window>m])` ⇒ 窗口内 OOM kill 次数。

重启次数 > 0 时，`metrics` 段写一行：`重启 4 次（14:16/14:20/14:23/14:26），unit 策略 Restart=on-failure，退出码 143`（退出码从日志文本里取，取不到就不写）。

## 4. 邮件渲染

- `OpsDiagnosisEmail.html(alertName, reportMd, linkUrl, traceIds, grafanaBaseUrl)`：commonmark 渲染 → 后处理给 `h2/table/th/td/pre/code/blockquote/a` 注入内联 `style` → 外包一层带 `font-family:-apple-system,"Segoe UI",sans-serif; line-height:1.6` 的容器。
- `conclusion(reportMd)`：取 `## 结论` 下第一段，≤200 字，渲染成顶部灰底卡片；取不到就不显示（不猜）。
- `OpsDiagnosisMailer`：`new MimeMessageHelper(message, true, "UTF-8")`，`setText(plain, html)`；附件 `diagnosis-<id>.md`。
- 降级：`html.length() > 100_000` 或渲染抛异常 ⇒ 只发纯文本并 warn；附件抛异常独立捕获，不影响正文。
- 纯文本版本仍是现有 `body(...)` + `toPlainText`，不改行为（老客户端可读）。

## 5. 复盘数据模型

```sql
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
```

- `status` 状态机：`DRAFT → APPROVED → ARCHIVED`；`ARCHIVED → APPROVED` 允许重新启用。
- 同一 `incident_id` 出现第二个 APPROVED 时，前者自动 ARCHIVED（Service 内事务处理，DB 不建唯一索引——避免历史数据冲突导致迁移失败）。
- 正文由后端用固定模板拼 `body_md`（标题/时间线/根因/影响/处置/待办/证据），前端只编辑各字段，避免前后端两套渲染。

## 6. 知识召回与注入

```java
interface PostmortemKnowledge {
    List<Hit> recall(String alertName, String signatures, int limit);
    record Hit(long id, String title, Instant approvedAt, String rootCause, String actions) {}
}
```

SQL：

```sql
SELECT id, title, approved_at, root_cause, actions_md,
       MATCH(title, root_cause, body_md) AGAINST (? IN NATURAL LANGUAGE MODE) AS score
FROM ops_postmortems
WHERE status = 'APPROVED'
  AND (alert_name = ? OR MATCH(title, root_cause, body_md) AGAINST (? IN NATURAL LANGUAGE MODE) > 0)
ORDER BY (alert_name = ?) DESC, score DESC, approved_at DESC
LIMIT ?
```

- 注入文本（≤ `knowledgeMaxChars`，默认 1200）：

```
## 历史复盘（人工确认过的经验，仅作参考）
### 复盘 #12 · 2026-11-15 · CPU 相对 7 天基线异常（APPROVED）
根因：……
处置：……
注意：与本次证据冲突时，以本次证据为准。
```

- 失败/无命中 ⇒ `本次未取得历史复盘`。
- `OpsDiagnosisService.run` 在 `collect` 之前调用；命中 id 列表写 `ops_diagnoses.knowledge_ids`（逗号分隔，≤255 字，超过截断）。

## 7. 接口（全部 ADMIN + 回环）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/ops/admin/postmortems` | 由诊断生成草稿；body 含 `diagnosisId`，可覆盖各字段 |
| PUT | `/api/v1/ops/admin/postmortems/{id}` | 编辑草稿（APPROVED 编辑后回到 DRAFT 需重新确认） |
| POST | `/api/v1/ops/admin/postmortems/{id}/approve` | 确认入库（写 `approved_by/at`） |
| POST | `/api/v1/ops/admin/postmortems/{id}/archive` | 归档（退出知识库） |
| GET | `/api/v1/ops/admin/postmortems?status=&page=` | 列表 |
| GET | `/api/v1/ops/admin/postmortems/{id}` | 详情 |
| GET | `/api/v1/ops/admin/postmortems/{id}/download` | `text/markdown; charset=utf-8` + `Content-Disposition: attachment` |

## 8. 配置项

```yaml
paideia:
  ops:
    diagnosis:
      evidence-window-minutes: 30
      evidence-window-max-minutes: 60
      max-evidence-chars: 16000
      logs-per-unit: 30
      signature-top: 10
    mail:
      html-enabled: true
    postmortem:
      knowledge-limit: 2
      knowledge-max-chars: 1200
```

## 9. 失败模式

| 失败模式 | 行为 | 测试 |
| --- | --- | --- |
| Loki 不可达 | 日志三段都写「无数据/不可达」，诊断继续 | `OpsEvidenceCollectorTest#loki_down_degrades` |
| 窗口超出保留期 | 头部标注「超出保留期」，指标照常 | `OpsEvidenceWindowTest#out_of_retention` |
| 某 unit 未接入 | 写「未接入 Loki」，不静默丢 | `OpsEvidenceCollectorTest#unit_not_ingested` |
| commonmark 渲染异常 | 降级纯文本，邮件仍发出 | `OpsDiagnosisEmailTest#html_failure_falls_back` |
| 附件失败 | 正文照发 | `OpsDiagnosisMailerTest#attachment_failure_keeps_body` |
| 知识召回失败 | `<knowledge>` 写「本次未取得」，诊断继续 | `PostmortemKnowledgeTest#search_failure_empty` |
| 两个 APPROVED 并存 | 旧的自动 ARCHIVED | `PostmortemServiceTest#approve_archives_previous` |
| 越权访问 | 403 | `ApiContractTest`、`AuthorizationIsolationTest` |
| 复盘含敏感串 | 保存前 redactor 替换 | `PostmortemServiceTest#redacts_secrets` |

## 10. 测试计划

- 单测：窗口工厂、签名归一化、三段查询拼装（MockWebServer 断言语义）、重启聚合、邮件 HTML/降级、复盘状态机、知识 SQL 参数绑定。
- 集成：`ApiContractTest`（新端点必须声明访问级别）、`AuthorizationIsolationTest`（学习者 403）。
- 真实验证：拿 2026-11-19 14:10–14:30 窗口跑一次手动诊断，断言「重启 4 次 + PID + Hikari 关闭」出现在证据里（AC-001/002）。
- 邮件实测：本地 SMTP 收信看 HTML 与附件；构造超长报告验证降级。

## 11. 待定

- `mysql.service`/`redis-server.service` 为何没进 Loki：实现时在服务器 `systemctl list-units | grep -Ei 'mysql|redis'` 核实真名，再决定改 alloy 白名单还是仅标注「未接入」。**不在本工作项扩大采集面**。
- 复盘正文是否允许手工粘贴截图：本期只支持文本与 Markdown 链接，图片不支持（避免对象存储与鉴权面）。
- 是否在事件页直接展示「本事件已确认复盘」：本期列表页足够，事件页只放链接。
