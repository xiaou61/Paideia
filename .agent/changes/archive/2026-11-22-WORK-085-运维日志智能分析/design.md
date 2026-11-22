---
work_id: WORK-085
work: 运维日志智能分析（AIOps 补齐）
artifact: design
status: approved
mode: strict
updated: 2026-11-19
approved_by: xiaou61
approved_at: 2026-11-19 10:31:47 +0800
approver_role: CTO
---

# 运维日志智能分析（AIOps 补齐）设计

> 依据：requirements.md（approved 2026-11-19 10:17:47）、proposal.md（approved 2026-11-19 10:19:12）。
> 范围：REQ-001~010 全量；聚类落点 = Loki 侧 pattern ingester，应用侧只做「模板同步 → 三类异常判定 → 组证据 → 触发诊断 → 结构化沉淀」。

## 变更清单

位置一律写真实路径 + 行号 / 真实接口 / 真实界面元素名。新增文件的「改前」写「无」，删除/移动无。

| # | 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `deploy/ops/loki-config.yaml`（`limits_config`、`compactor` 段之后，文件末尾） | 修改 | 无 `pattern_ingester` 段，Loki 只做日志存查 | 新增 `pattern_ingester:` 段（`enabled: true`、`metric_aggregation: {enabled: true, loki_address: 127.0.0.1:3100, downsample_period: 10s}`、`loki_address: 127.0.0.1:3100`）；`limits_config` 其余键不动 | REQ-002、REQ-009 |
| 2 | `deploy/ops/loki.service`（`MemoryMax=` 行） | 修改 | `MemoryMax=512M` | `MemoryMax=1024M`（以切片一实测内存为准，先按 1G 起；实测后回落则在本行写实测值） | REQ-002、AC-007 |
| 3 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsProperties.java` | 修改 | 只有 `Diagnosis` / `Incidents` / `Notify` / `Thresholds` 四组内部类 | 新增内部类 `Patterns`（字段见「持久化与迁移」§5.4）并在外层加 `getPatterns()`/`setPatterns()` | REQ-003、REQ-004、REQ-009、REQ-010 |
| 4 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/pattern/PatternSyncJob.java` | 新增 | 无 | 新增 `@Scheduled(fixedDelayString = "${paideia.ops.patterns.sync-interval-seconds:300}000")` + `initialDelay` 的周期同步入口（同 JVM，不开新进程） | REQ-002、REQ-003 |
| 5 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/pattern/PatternSyncService.java` | 新增 | 无 | 新增：调 `LokiClient` 模板接口拉当前窗口模板+频次 → 脱敏 → upsert 模板表与频次桶表 | REQ-002、REQ-003 |
| 6 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/pattern/PatternAnomalyDetector.java` | 新增 | 无 | 新增：三类判定 FREQ_SPIKE / NEW_TEMPLATE / DISAPPEARED，落异常表 + 更新 Micrometer gauge | REQ-004、REQ-010 |
| 7 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/pattern/PatternTriggerService.java` | 新增 | 无 | 新增：按 (templateHash, kind) 冷却后调 `OpsDiagnosisService.start("pattern", …)`，把异常绑回诊断 | REQ-006 |
| 8 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/pattern/PatternQueryService.java` | 新增 | 无 | 新增：只读查询（Top 模板 / 异常列表 / 证据摘要文本，受 `maxPatternChars` 截断） | REQ-005、REQ-007 |
| 9 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/pattern/PatternMapper.java` | 新增 | 无 | 新增手写 MyBatis Mapper（三表 insert/upsert/query/purge） | REQ-002~004、REQ-007 |
| 10 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsEvidenceCollector.java:105-108`（`return new OpsDiagnoser.Evidence(...)`） | 修改 | `new OpsDiagnoser.Evidence(trigger,alertName,question,summaryText,metricsText,baselinesText,contextText,alertsText,logsText,tracesText,deploysText)` | 在 `logsText` 之后插入 `patternsText`（`PatternQueryService.summary(from,to,logBudget剩余)`），并给生产构造器加 `PatternQueryService` 依赖参数 | REQ-005 |
| 11 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/OpsDiagnoser.java:32` | 修改 | `record Evidence(String trigger,String alertName,String question,String summary,String metrics,String baselines,String context,String alerts,String logs,String traces,String deploys)` | 在 `logs` 与 `traces` 之间新增 `String patterns` | REQ-005 |
| 12 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/diagnosis/OpsDiagnosisPrompt.java:20`（`VERSION`）、`:65-88`（`user`） | 修改 | `VERSION = "ops-diagnosis-v5"`；`user()` 依次输出 summary/metrics/baselines/context/alerts/logs/traces/deploys | `VERSION = "ops-diagnosis-v6"`；`user()` 在 `logs` 之后输出 `section(out,"patterns",evidence.patterns())`；`system()` 规则 3 的「不可信数据」补充 `patterns` 亦为不可信数据且只读摘要 | REQ-005 |
| 13 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/PatternController.java`（原路径不存在） | 新增 | 无 | 新增 `@RequestMapping("/api/v1/ops/admin/patterns")` 只读端点 + `/pattern-anomalies` + `/knowledge/search`（全部 `@ApiAccess(ADMIN)`） | REQ-005、REQ-007 |
| 14 | `backend/paideia-app/src/main/resources/db/migration/V20261119110000__create_ops_log_patterns.sql` | 新增 | 无 | 新增三张表 `ops_log_templates` / `ops_log_template_buckets` / `ops_pattern_anomalies`（结构见 §5） | REQ-002~004、REQ-007 |
| 15 | `backend/paideia-app/src/main/resources/db/migration/V20261119111000__ops_diagnoses_fulltext.sql` | 新增 | `ops_diagnoses.report_md` 无全文索引；`ops_pattern_anomalies` 无 | `ALTER TABLE ops_diagnoses ADD FULLTEXT KEY ft_ops_diagnoses_report (report_md) WITH PARSER ngram;` 并对 `ops_pattern_anomalies(detail)` 加 `ft_ops_pattern_anomalies_detail` | REQ-007 |
| 16 | `deploy/ops/provisioning/dashboards/paideia-ops.json` | 修改 | 只有既有指标/备份/告警面板 | 新增「日志模板 / 异常」row 与两个 panel：Top 模板表（Prom/Loki 模板指标）+ 异常模板时序 | REQ-002、REQ-004 |
| 17 | `deploy/ops/provisioning/alerting/paideia-rules.yaml` | 修改 | 无 pattern 规则 | 新增 rule `uid: paideia-pattern-anomaly`，`expr` 基于 `paideia_pattern_anomaly_active`，用 `bool` 修饰符、`noDataState: OK`、`notification_settings.receiver: paideia-ops` | REQ-006 |
| 18 | `frontend/apps/admin/src/pages/OpsPage.tsx`（既有「运维」页） | 修改 | 只有诊断/事件/评分卡片 | 新增一张「日志模板与异常」只读卡片，读 `/api/v1/ops/admin/patterns` 与 `/pattern-anomalies`；不改路由、不动 `AdminShell` 导航 | REQ-005、REQ-007 |
| 19 | `backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/diagnosis/OpsDiagnosisPromptTest.java:38`、`:59` | 修改 | `new OpsDiagnoser.Evidence(...)` 11 参数 | 补第 12 参数 `patterns`（含一条断言 `<patterns>` 小节） | REQ-005 |
| 20 | `backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/diagnosis/OpsDiagnosisGoldenSetTest.java:183` | 修改 | 11 参数构造 | 补 `patterns` 参数（金标集新增一条「模板异常」样例） | REQ-005 |
| 21 | `backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/diagnosis/DefaultOpsDiagnoserTest.java:132` | 修改 | 11 参数构造 | 补 `patterns` 参数 | REQ-005 |
| 22 | `backend/paideia-app/src/test/java/io/github/xiaou61/ops/OpsDiagnosisServiceTest.java:69` | 修改 | `new OpsDiagnoser.Evidence(...)` 11 参数 | 补 `patterns` 参数 | REQ-005 |
| 23 | `docs/architecture/*`（数据模型图 + `paideia-architecture.archify.json` 的 `meta.repository.revision`） | 修改 | 数据模型不含 ops_log_templates 等三表 | 同步三张新表关系，并把 `.archify.json` revision 钉到实现提交 | AGENTS.md「表结构变更同提交改图」 |

> 本清单无删除、无移动/改名；第 10/11/12 行是同一处证据扩链的落点，须同一次提交改完。

## 2. 系统上下文

```
journald → Alloy → Loki 3.7.9（+pattern_ingester）
                         └→ paideia-ops（同 JVM）
                              ├ PatternSyncJob/Service（拉模板+频次，脱敏落库）
                              ├ PatternAnomalyDetector（三类判定）
                              ├ PatternTriggerService → OpsDiagnosisService（复用冷却/单飞/邮件）
                              └ PatternQueryService → OpsEvidenceCollector(+patterns) → OpsDiagnoser(LLM)
Micrometer /actuator/prometheus → Prometheus → Grafana（模板面板/异常告警）
MySQL：ops_log_templates / _buckets / ops_pattern_anomalies / ops_diagnoses（全文索引）
```

- 外部依赖不变：Prometheus / Loki / Tempo / MySQL 均在原位；**不新增常驻进程**，定时任务跑在应用 JVM 内（`@EnableScheduling` 已由 `AccountSupportConfiguration` 打开）。
- 唯一新增的「新东西」是 Loki 的 `pattern_ingester` 配置与三张 MySQL 表；都是既有组件内的扩展。

## 3. 组件与职责

| 组件 | 位置 | 职责 | 不做什么 |
| --- | --- | --- | --- |
| `PatternSyncJob` | `paideia-ops/.../pattern/` | 定时（默认 300s）触发一次同步；单飞（`AtomicBoolean`），上一轮未完直接跳过 | 不做检测、不调 LLM |
| `PatternSyncService` | 同上 | 用固定 LogQL（`{unit="X.service"}`，unit 取 `paideia.ops.patterns.units`，默认 `paideia`）或 Loki patterns 接口拉当前窗口模板与频次；`OpsLogRedactor` 脱敏后 upsert `ops_log_templates` 与 `ops_log_template_buckets`；超 `maxTemplatesPerSync` 按 count 截断 | 不拼用户输入进查询、不写证据 |
| `PatternAnomalyDetector` | 同上 | 对每模板判三类异常（§4）；写 `ops_pattern_anomalies`；更新 Micrometer gauge `paideia.ops.pattern.anomaly.active{kind}`；调 `PatternTriggerService` | 不自动修复、不改配置、不删模板 |
| `PatternTriggerService` | 同上 | 按 `(templateHash, kind)` 冷却（默认 900s，查异常表最近一次）后调 `OpsDiagnosisService.start("pattern", null, "LogPattern:"+kind, question, config.getUserId())`；捕获 `TOO_MANY_REQUESTS`/`CONFLICT` 降级跳过 | 不新建告警通道、不改 start 语义 |
| `PatternQueryService` | 同上 | 只读：Top 模板、异常列表、`summary(from,to,maxChars)` 生成证据摘要（截断） | 不做判定 |
| `PatternMapper` | 同上 | 手写 MyBatis：三表 upsert/query/purge + `MATCH AGAINST` 全文检索 | 无 |
| `OpsEvidenceCollector`（改） | `paideia-ops/.../` | 新增 `patternsText`：`patterns` 不可用或空时写「（不可达或无数据）」；占用 `maxEvidenceChars` 预算（在 logs 之后计算，剩余预算给 patterns） | 不读原始日志、不自己聚类 |
| `OpsDiagnosisPrompt`（改） | `paideia-ai/.../internal/diagnosis/` | VERSION v6；`patterns` 小节注入；系统提示声明 patterns 为不可信摘要 | 不放大原始日志 |

## 4. 请求或事件流程

**A. 同步（周期，默认每 5 分钟）**
1. `PatternSyncJob` 取锁；若 `patterns.enabled=false` 或 Loki 不可达 → 记 WARN 指标、返回（AC-005）。
2. `PatternSyncService` 对每个 unit 拉近 `window-minutes`（默认 15）模板与频次；脱敏后 upsert 模板（`first_seen/last_seen/last_count/total_count`）与按小时桶（`bucket_start` + `dow/hour`）。
3. 结束更新 `paideia.ops.pattern.sync.duration` 与模板数 gauge。

**B. 检测（紧接同步，或独立 `@Scheduled`）**
1. FREQ_SPIKE：当前桶 count 与「同 `dow+hour`、近 `baseline-weeks`（默认 4）周均值 μ 与标准差 σ」比较；`ratio=count/max(μ,1)`，`z=(count-μ)/max(σ,1)`；`ratio≥freqSpikeRatio(3.0)` 且 `z≥freqSpikeZ(3.0)` 判异常（与提示词「|z|>3 才算显著」一致）。
2. NEW_TEMPLATE：`template_hash` 从未入库（`first_seen` 落在本窗口）且 `count≥newTemplateMinCount(10)`。
3. DISAPPEARED：模板历史每窗口 `count≥disappearedMinBaseline(20)`，但当前连续 2 个窗口 `count==0`（服务静默，比报错更危险）。
4. 每个异常写 `ops_pattern_anomalies(status=OPEN)`，gauge +1；同一 `(hash,kind)` 在冷却窗口内不重复触发诊断。

**C. 触发诊断（事件，异常产生时）**
1. `PatternTriggerService` 查 `ops_pattern_anomalies` 最近同 `(hash,kind)` 记录，冷却内跳过。
2. 冷却外 → `OpsDiagnosisService.start("pattern", null, "LogPattern:"+kind, question, patterns.userId)`；`question` 用固定模板句（「检测到模板 X 的 FREQ_SPIKE，当前 N，基线 μ」），≤500 字。
3. `start` 内部单飞、插 `ops_diagnoses(PENDING)`、异步执行、完成后邮件（复用 WORK-042/049）。返回的 `diagnosisId` 回写异常行 `diagnosis_id`。
4. 事件级冷却：当前 `start` 对非 `alert` 触发不套 `incidentId` 冷却、`manual` 忙时抛 `TOO_MANY_REQUESTS`；本项由 `PatternTriggerService` 自行做 DB 冷却并捕获异常降级（§7），不改 `OpsDiagnosisService` 语义。

**D. 证据组装（诊断执行时）**
`OpsEvidenceCollector.collect` 在算完 `logsText` 后，用剩余 `maxEvidenceChars` 预算调 `PatternQueryService.summary(from,to,budget)`：
- 头部：`窗口 15m，模板 N 个（原始行 M → 压缩比 M/N）`；
- Top 模板（≤`topN` 15 条）：`[count] pattern`；
- 异常模板：`[kind] pattern (current vs baseline, z)`；
- 超预算截断并标注「…已截断」；无数据写「（不可达或无数据）」。
该文本作为 `Evidence.patterns` → `OpsDiagnosisPrompt.user()` 的 `<patterns>` 小节 → LLM 只读这段摘要。

**E. 知识闭环（REQ-007）**
诊断完成写 `ops_diagnoses.report_md`；`PatternController.knowledge/search` 用 `MATCH(report_md) AGAINST(? IN NATURAL LANGUAGE MODE)` 与 `ops_pattern_anomalies.detail` 检索，供管理员在管理端查「上次类似模板异常怎么处理的」。不建向量库。

## 5. 持久化与迁移

新增迁移（14 位时间戳；生产迁移只建结构，不插业务数据）：
- `V20261119110000__create_ops_log_patterns.sql`
- `V20261119111000__ops_diagnoses_fulltext.sql`

**5.1–5.4 表 DDL 与配置默认值**：见附录 `design-appendix-schema.md`（`ops_log_templates` / `ops_log_template_buckets` / `ops_pattern_anomalies` 三表结构，以及 `paideia.ops.patterns.*` 全量默认值）。生产 `paideia.ops.patterns.*` 值写在服务器侧 `application.yml`（release.ps1 不覆盖），仓库只留默认值与注释。

**5.5 保留策略**：`PatternSyncService` 每轮顺带 purge：`ops_log_template_buckets` 删 `bucket_start < now - template-retention-days`；`ops_log_templates` 删 `last_seen < now - template-retention-days`；`ops_pattern_anomalies` 保留 90 天。容量硬顶由 `max-templates-per-sync` 与 retention 双限。

## 6. 接口与数据

全部 `@ApiAccess(ADMIN)`，绑回环（应用既有 127.0.0.1 绑定），公网仍只有 8081 反代的管理端。

| 方法与路径 | 请求 | 响应 | 说明 |
| --- | --- | --- | --- |
| `GET /api/v1/ops/admin/patterns?unit=&windowMinutes=&limit=` | query | `[{templateHash,unit,pattern,count,lastSeen}]` | Top 模板，`limit≤100` |
| `GET /api/v1/ops/admin/pattern-anomalies?status=&kind=&limit=` | query | `[{id,kind,unit,pattern,ratio,z,baselineCount,currentCount,windowStart,status,diagnosisId,createdAt}]` | 异常列表 |
| `GET /api/v1/ops/admin/knowledge/search?q=&limit=` | query | `[{source:"diagnosis"\|"anomaly",id,snippet,createdAt}]` | MySQL 全文检索，`q` 走 `MATCH AGAINST ?` 绑定、长度 ≤100 |
| `POST /api/v1/ops/admin/patterns/sync` | 无 body | `{synced:boolean,triggered:boolean}` | 手动触发一轮（ADMIN，可降级为不提供） |

内部：`OpsDiagnosisService.start("pattern", null, "LogPattern:"+kind, question, userId)` 复用既有 `POST /api/v1/ops/admin/diagnoses` 的存储与邮件链路，不新增诊断对外接口。

## 7. 失败处理与恢复

- **Loki 不可达 / patterns 接口 404**：`PatternSyncService` 记 WARN + `paideia.ops.pattern.sync.failures`，本轮跳过；证据 `patterns` 段写「（不可达或无数据）」，诊断照常（AC-005）。**降级**：`/loki/api/v1/patterns` 不可用则改读 Prometheus 的 `loki_patterns_*`（两条读取路径都已实现，实测后二选一）。
- **pattern_ingester 未启用**：同上，模板为空，Grafana 面板显示「未启用」。
- **检测异常隔离**：单模板解析失败、单 unit 失败不影响其它 unit / 其它模板；异常写日志不抛出到调度线程。
- **触发风暴**：DB 冷却 + `start` 单飞双保险；`TOO_MANY_REQUESTS`/`CONFLICT` 捕获后把异常行置 `TRIGGER_SKIPPED`，不重试。
- **重启恢复**：`@Scheduled` 带 `initialDelay`（默认 60s），重启后一轮同步重建窗口；异常表 `OPEN` 行不会被重新触发（冷却查 DB 最近记录）。
- **DB 不可用**：`PatternQueryService.summary` 异常 → 证据段「（不可达或无数据）」；同步/检测整轮跳过，`@Scheduled` 下轮重试。
- **预算保护**：`maxEvidenceChars` 先扣非日志段、再扣 logs、最后给 patterns；patterns 超出即截断，保证整体 token 不超。

## 8. 安全与权限

- 新增端点全部 `@ApiAccess(ADMIN)`；`ApiContractTest` 必须为每个端点声明访问级别，`AuthorizationIsolationTest` 覆盖 401/403。
- 不新增公网端口、不新增 systemd 单元；定时任务在应用 JVM 内，内存受应用既有约束。
- 查询注入面：同步查询是常量 LogQL（`{unit="X.service"}`，unit 只来自配置白名单）；管理员 `question`/`q` 仅进 `<question>` 与 `MATCH AGAINST ?` 绑定参数，绝不拼进 LogQL/PromQL。
- 脱敏：模板 `pattern`、`sample_line`、异常 `detail`、证据摘要全文经 `OpsLogRedactor`；口令/令牌/验证码/PII 不入库、不入证据、不入响应（延续 `OpsDiagnoser`「已脱敏证据」约束）。
- 公开仓库禁入凭据：设计/迁移/代码不写任何服务器地址与口令；生产 `paideia.ops.patterns.*` 值只在服务器侧 `application.yml`。

## 9. 可观测性

- Micrometer（复用 `/actuator/prometheus`，Prometheus 已抓取）：`paideia.ops.pattern.sync.duration`、`paideia.ops.pattern.templates`、`paideia_pattern_anomaly_active{kind}`。
- 日志：`pattern.sync.start/finish/fail`、`pattern.anomaly.detected{kind,templateHash}`、`pattern.trigger.skipped{reason}`，均带既有 traceId（MDC），可在 Loki/Grafana 串联。
- Grafana：`paideia-ops` 仪表盘新增「日志模板 / 异常」row；告警 `paideia-pattern-anomaly` 基于 `paideia_pattern_anomaly_active`（`bool` 修饰符、NoData=OK、receiver=paideia-ops）。
- 成本：每次诊断沿用 `OpsDiagnosisService` 的 WORK-009 记账；patterns 摘要显著降低输入 token（对比原始日志），纳入既有封顶。

## 10. 实施顺序

按 proposal 四切片，每切片独立可验证、独立提交（只提交本次范围文件）：

1. **切片一（可见性）**：改 `loki-config.yaml` 加 `pattern_ingester` + 调 `loki.service` 内存 → 重启 Loki → 实测内存/压缩率 → Grafana 面板能看到模板与频次。验证 REQ-002、AC-001；同时实测 `/loki/api/v1/patterns` 是否可用，定 §7 读取路径。
2. **切片二（模板库 + 检测）**：`OpsProperties.Patterns` + 两迁移 + `PatternSyncJob/Service` + `PatternAnomalyDetector` + `PatternMapper` + 单测与构造用例。验证 REQ-003/004、AC-002。
3. **切片三（接入诊断 + 告警）**：`OpsEvidenceCollector`/`OpsDiagnoser.Evidence`/`OpsDiagnosisPrompt` 扩链（含 5 处测试构造点）+ `PatternTriggerService` + Grafana 告警规则 + `PatternController` 只读端点 + `OpsPage` 卡片。验证 REQ-005/006、AC-003/004/006。
4. **切片四（知识闭环 + 拓扑）**：全文索引迁移 + `knowledge/search` + `PatternQueryService` 检索 + 最小拓扑（`PatternQueryService` 输出一行 `app → MySQL/Redis/RustFS/Laya/Tempo` 追加进 summary）。验证 REQ-007/008、AC-005/007。

实现提交后按 AGENTS.md 更新 `docs/architecture` 数据模型图与 `.archify.json` revision。

## 11. 测试策略

单测（`PatternAnomalyDetectorTest` / `PatternSyncServiceTest` / `PatternQueryServiceTest` / `PatternMapper`）、既有四处测试同步修（Evidence 加第 12 参数、prompt 升 v6）、迁移三件与守卫三件；外环只跑管理端 `OpsPage` 与 Loki 实测。完整命令、内环/外环拆分与 AC-002 fixture 见附录 `design-appendix-detail.md` §11。

## 12. 需求追踪

REQ-001~010 与 AC-001~007 的逐条落点见附录 `design-appendix-detail.md` §12（实现说明分布在本设计 §3–§9）。

## 13. 待决定事项

1. Loki `pattern_ingester` 参数与 `MemoryMax` 实测后定值；2. `/loki/api/v1/patterns` 可用性（不可用则降级读 `loki_patterns_*`）；3. 三类阈值初值先保守、观察一周再收紧；4. 日志日量待实测（参数全可配，不阻塞）；5. 触发暂不写 `ops_incidents`（只复用 `start` 的单飞/邮件/落库）。全文见附录 `design-appendix-detail.md` §13。
