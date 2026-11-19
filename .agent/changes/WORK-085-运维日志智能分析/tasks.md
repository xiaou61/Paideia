---
work_id: WORK-085
work: 运维日志智能分析（AIOps 补齐）
artifact: tasks
status: approved
mode: strict
updated: 2026-11-19
approved_by: xiaou61
approved_at: 2026-11-19 10:34:51 +0800
approver_role: CTO
---

# 运维日志智能分析（AIOps 补齐）实施任务

## 依据

- 需求：`requirements.md`（approved 2026-11-19 10:17:47，REQ-001~010 / AC-001~007）
- 提案：`proposal.md`（approved 2026-11-19 10:19:12，四切片）
- 设计：`design.md`（approved 2026-11-19 10:31:47）——变更清单 §1、组件 §3、流程 §4、持久化 §5（DDL 见 `design-appendix-schema.md`）、测试/追踪/待定见 `design-appendix-detail.md`

## 全局约束

- 不新增常驻进程：定时任务跑在应用 JVM 内（`@EnableScheduling` 已由 `backend/paideia-account/.../AccountSupportConfiguration.java:21` 打开）。
- 迁移文件名 14 位时间戳，只建结构不插业务数据；新包必须在 `paideia-ops` 内（`ModularityTest`）。
- 新端点全部 `@ApiAccess(ADMIN)`，绑回环；无新公网端口。
- 脱敏：模板/样例/异常 detail/证据摘要经 `OpsLogRedactor`；凭据/令牌/PII 不入库、不入日志、不入响应。
- 查询注入面：同步是常量 LogQL（unit 只来自配置白名单）；`question`/`q` 只进 `<question>` 与 `MATCH AGAINST ?` 绑定参数。
- 内环测试：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`；守卫三件另跑（见 design 附录二 §11）。
- 公开仓库禁入凭据；生产 `paideia.ops.patterns.*` 值只在服务器侧 `application.yml`。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 两轮同步并发跑，重复写桶/触发 | TASK-005 | `PatternSyncServiceTest#test_sync_single_flight_skips_when_running` |
| `/loki/api/v1/patterns` 404 或 Loki 不可达 | TASK-005 | `PatternSyncServiceTest#test_patterns_endpoint_404_degrades` |
| 频次 ratio 达标但 z 小（平时波动大）被误报 | TASK-006 | `PatternAnomalyDetectorTest#test_freq_spike_low_z_not_anomaly` |
| 模板消失只 1 个窗口就报 | TASK-006 | `PatternAnomalyDetectorTest#test_disappeared_after_two_windows` |
| 诊断服务忙（`TOO_MANY_REQUESTS`）抛出导致调度中断 | TASK-009 | `PatternTriggerServiceTest#test_busy_skips_without_exception` |
| 证据超 `maxEvidenceChars` 未截断 | TASK-008 | `PatternQueryServiceTest#test_summary_truncated_to_max_chars` |
| 新端点未声明访问级别 / 越权访问 | TASK-010 | `ApiContractTest`、`AuthorizationIsolationTest` |
| 全文检索 q 含引号/中文边界注入 | TASK-011 | `PatternControllerTest#test_knowledge_search_binds_param` |

## 任务

### TASK-001 | done | Loki 启用 pattern_ingester 并可查询（探针）

- 对应：`REQ-002`、`AC-001`
- 依赖：无
- 新建/修改：`deploy/ops/loki-config.yaml`（新增 `pattern_ingester` 段）、`deploy/ops/loki.service`（`MemoryMax=512M`→实测值，先 1024M）
- 接口：产出决策记录 `.agent/notes/ops-pattern-ingester.md`——记录「`/loki/api/v1/patterns` 是否可用」「走 patterns 接口还是 `loki_patterns_*` 指标」「实测内存」「压缩比样例」
- 测试：部署实测（无单测）
- 步骤：
  1. 改 `loki-config.yaml` 加 `pattern_ingester: {enabled: true, metric_aggregation: {enabled: true, loki_address: 127.0.0.1:3100, downsample_period: 10s}}`
  2. 重启 `loki.service`，`journalctl -u loki` 确认无 ERROR、内存占用落定
  3. `curl 'http://127.0.0.1:3100/loki/api/v1/patterns?query=%7Bunit%3D%22paideia.service%22%7D&start=..&end=..'` 看返回结构；同时 `curl` Prometheus 查 `loki_patterns_*` 是否存在
  4. 取一段真实窗口算「原始行数→模板数」压缩比并抽样核对
  5. 结论写入 `.agent/notes/ops-pattern-ingester.md`
- 验证：笔记文件存在且包含上述四项结论；Loki 服务 active、无 ERROR 日志

### TASK-002 | done | Grafana「日志模板 / 异常」面板

- 对应：`REQ-002`、`AC-001`
- 依赖：`TASK-001`
- 新建/修改：`deploy/ops/provisioning/dashboards/paideia-ops.json`（新增 row + Top 模板表 panel + 异常时序 panel）
- 接口：消费 TASK-001 确定的查询源（patterns 接口或 `loki_patterns_*`）
- 测试：JSON 可被 Grafana provisioning 加载（部署实测）
- 步骤：
  1. 按 TASK-001 结论选数据源，加「Top 模板」表 panel
  2. 加「异常模板」时序 panel（数据源 `paideia-ops` 指标，TASK-006 产出）
  3. 部署后 Grafana 打开仪表盘确认两块面板出图
- 验证：Grafana 面板可见且返回数据（截图/描述写入验证记录）

### TASK-003 | done | 迁移：三张表 + 全文索引

- 对应：`REQ-002`、`REQ-003`、`REQ-004`、`REQ-007`
- 依赖：无
- 新建/修改：`backend/paideia-app/src/main/resources/db/migration/V20261119110000__create_ops_log_patterns.sql`、`V20261119111000__ops_diagnoses_fulltext.sql`（DDL 见 `design-appendix-schema.md` §5.1–5.3）
- 接口：三表 `ops_log_templates` / `ops_log_template_buckets` / `ops_pattern_anomalies`；`ops_diagnoses.report_md` 加 `FULLTEXT ... WITH PARSER ngram`
- 测试：`MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest`
- 步骤：
  1. 写两个迁移文件（14 位时间戳）
  2. `scripts/test-db.sh` 起库，确认三表与全文索引存在（`SHOW INDEX`）
  3. 跑迁移三件守卫
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` 全绿

### TASK-004 | done | `OpsProperties.Patterns` 配置 + `PatternMapper`

- 对应：`REQ-003`、`REQ-004`、`REQ-009`
- 依赖：`TASK-003`
- 新建/修改：`backend/paideia-ops/.../ops/OpsProperties.java`（新增 `Patterns` 内部类 + getter，默认值见 design 附录 §5.4）、`backend/paideia-ops/.../ops/pattern/PatternMapper.java`（手写 MyBatis）
- 接口：`PatternMapper.upsertTemplate(...)`、`upsertBucket(...)`、`topTemplates(unit,from,to,limit)`、`baseline(hash,unit,dow,hour,weeks)`、`insertAnomaly(...)`、`purgeBefore(...)`、`searchDiagnoses(q,limit)`、`searchAnomalies(q,limit)`
- 测试：`PatternsSqlTest#test_upsert_template_idempotent`、`PatternsSqlTest#test_bucket_unique`
- 步骤：
  1. 加 `Patterns` 内部类与默认值，`OpsPropertiesTest` 断言默认值
  2. 写 `PatternMapper` 接口 + XML/SQL（upsert 用 `ON DUPLICATE KEY UPDATE`）
  3. 写映射测试
- 验证：`scripts/test-db.sh mvn -B -T 1C -pl paideia-ops -am -Dtest=PatternsSqlTest -Dsurefire.failIfNoSpecifiedTests=false test` 绿

### TASK-005 | done | `PatternSyncService` + `PatternSyncJob` 同步落库

- 对应：`REQ-002`、`REQ-003`、`REQ-009`
- 依赖：`TASK-004`
- 新建/修改：`backend/paideia-ops/.../ops/pattern/PatternSyncService.java`、`PatternSyncJob.java`
- 接口：`PatternSyncService.syncOnce(Instant now)`；`PatternSyncJob` 用 `@Scheduled(fixedDelayString="${paideia.ops.patterns.sync-interval-seconds:300}000")` + `initialDelay`
- 测试：`PatternSyncServiceTest#test_sync_upserts_templates_and_buckets`、`#test_patterns_endpoint_404_degrades`、`#test_sync_single_flight_skips_when_running`
- 步骤：
  1. 实现拉取（TASK-001 选定路径）+ 脱敏 + upsert 模板/桶
  2. 单飞（`AtomicBoolean`）+ 超 `max-templates-per-sync` 截断 + purge（design §5.5）
  3. 写三个测试（假 `LokiClient`）
- 验证：`... -Dtest=PatternSyncServiceTest ... test` 全绿

### TASK-006 | done | `PatternAnomalyDetector` 三类检测 + Micrometer 指标

- 对应：`REQ-004`、`REQ-010`
- 依赖：`TASK-005`
- 新建/修改：`backend/paideia-ops/.../ops/pattern/PatternAnomalyDetector.java`
- 接口：`PatternAnomalyDetector.detect(Instant now)`→写入 `ops_pattern_anomalies`；`Counter/Gauge` 注册 `paideia_pattern_anomaly_active{kind}`
- 测试：`PatternAnomalyDetectorTest#test_freq_spike_detected`、`#test_freq_spike_low_z_not_anomaly`、`#test_new_template_detected_above_min_count`、`#test_disappeared_after_two_windows`、`#test_no_anomaly_when_within_baseline`
- 步骤：
  1. 实现 FREQ_SPIKE（ratio≥3 且 z≥3）、NEW_TEMPLATE（minCount=10）、DISAPPEARED（连续 2 窗口）
  2. 落异常行 + 更新 gauge
  3. 写五个测试（含边界）
- 验证：`... -Dtest=PatternAnomalyDetectorTest ... test` 全绿

### TASK-007 | done | 证据扩链：`Evidence.patterns` + prompt v6

- 对应：`REQ-005`、`AC-003`
- 依赖：`TASK-004`
- 新建/修改：`backend/paideia-ai/.../ai/OpsDiagnoser.java:32`（加第 12 字段 `patterns`）、`backend/paideia-ops/.../ops/OpsEvidenceCollector.java:105-108`（构造加 `patternsText`，生产构造器加 `PatternQueryService` 依赖）、`backend/paideia-ai/.../internal/diagnosis/OpsDiagnosisPrompt.java:20`（VERSION→v6）、`:65-88`（加 `section(out,"patterns",...)` 与系统规则）
- 接口：`new OpsDiagnoser.Evidence(trigger, alertName, question, summary, metrics, baselines, context, alerts, logs, patterns, traces, deploys)`（12 参数，顺序固定）
- 测试：`OpsDiagnosisPromptTest#test_patterns_section_present`、`#test_version_bumped_v6`；同步修 `OpsDiagnosisServiceTest:69`、`DefaultOpsDiagnoserTest:132`、`OpsDiagnosisGoldenSetTest:183`、`OpsDiagnosisPromptTest:38/59` 四处构造
- 步骤：
  1. 改 record 12 字段 → 编译报错处即全部构造点，逐一补 `patterns`
  2. 改 prompt（v6 + patterns 小节 + 不可信声明）
  3. 写/改测试
- 验证：`... -Dtest=OpsDiagnosisPromptTest,DefaultOpsDiagnoserTest,OpsDiagnosisServiceTest,OpsDiagnosisGoldenSetTest ... test` 全绿（`paideia-ai` 与 `paideia-app` 两端各跑）

### TASK-008 | done | `PatternQueryService` 证据摘要（截断）

- 对应：`REQ-005`、`AC-003`
- 依赖：`TASK-004`、`TASK-007`
- 新建/修改：`backend/paideia-ops/.../ops/pattern/PatternQueryService.java`
- 接口：`String summary(Instant from, Instant to, int maxChars)`——头部压缩比 + Top 模板 + 异常模板，超预算截断
- 测试：`PatternQueryServiceTest#test_summary_truncated_to_max_chars`、`#test_summary_empty_when_loki_unavailable`
- 步骤：
  1. 实现摘要拼装（`top-n`、`max-pattern-chars`）
  2. 无数据/异常 → 「（不可达或无数据）」
  3. 写两个测试
- 验证：`... -Dtest=PatternQueryServiceTest ... test` 全绿

### TASK-009 | done | `PatternTriggerService` 触发既有诊断（冷却/降级）

- 对应：`REQ-006`、`AC-004`
- 依赖：`TASK-006`、`TASK-008`
- 新建/修改：`backend/paideia-ops/.../ops/pattern/PatternTriggerService.java`
- 接口：`void triggerIfDue(PatternAnomaly a)` → 冷却外调 `OpsDiagnosisService.start("pattern", null, "LogPattern:"+kind, question, userId)`，回写 `diagnosis_id`
- 测试：`PatternTriggerServiceTest#test_cooldown_suppresses_second_trigger`、`#test_busy_skips_without_exception`
- 步骤：
  1. 按 `(templateHash, kind)` 查最近异常做 DB 冷却
  2. 调 `start`，捕获 `TOO_MANY_REQUESTS`/`CONFLICT` → 置 `TRIGGER_SKIPPED`
  3. 写两个测试
- 验证：`... -Dtest=PatternTriggerServiceTest ... test` 全绿

### TASK-010 | done | 只读端点 + 告警规则 + 管理端卡片

- 对应：`REQ-005`、`REQ-006`、`AC-003`、`AC-004`、`AC-006`
- 依赖：`TASK-008`、`TASK-009`
- 新建/修改：`backend/paideia-ops/.../ops/PatternController.java`（`/api/v1/ops/admin/patterns`、`/pattern-anomalies`、`/knowledge/search`，`@ApiAccess(ADMIN)`）、`deploy/ops/provisioning/alerting/paideia-rules.yaml`（`uid: paideia-pattern-anomaly`，`bool` 修饰符、`noDataState: OK`、`receiver: paideia-ops`）、`frontend/apps/admin/src/pages/OpsPage.tsx`（加「日志模板与异常」卡片）
- 接口：见 `design.md` §6 端点表；告警 expr 基于 `paideia_pattern_anomaly_active`
- 测试：`ApiContractTest`（新端点声明访问级别）、`AuthorizationIsolationTest`（401/403）、`PatternControllerTest`（分页/空数据）
- 步骤：
  1. 写 Controller 三个只读端点
  2. 加 Grafana 告警规则（引号/`bool` 校验）
  3. OpsPage 加卡片（不改路由/导航）
  4. 跑守卫三件 + admin 端 `pnpm playwright test`（或人工验收）
- 验证：守卫三件绿；管理端「运维」页出现卡片且能读到数据

### TASK-011 | done | 知识闭环：全文检索端点与查询

- 对应：`REQ-007`
- 依赖：`TASK-003`、`TASK-010`
- 新建/修改：`backend/paideia-ops/.../ops/PatternMapper.java`（`searchDiagnoses`/`searchAnomalies` 用 `MATCH ... AGAINST(? IN NATURAL LANGUAGE MODE)`）、`PatternController` 的 `/knowledge/search`
- 接口：`GET /api/v1/ops/admin/knowledge/search?q=&limit=` → `[{source,id,snippet,createdAt}]`
- 测试：`PatternControllerTest#test_knowledge_search_binds_param`（含引号/中文边界）
- 步骤：
  1. 实现两条 `MATCH AGAINST ?`（参数绑定，不拼串）
  2. 写测试
- 验证：`... -Dtest=PatternControllerTest ... test` 绿

### TASK-012 | done | 最小服务拓扑输出

- 对应：`REQ-008`
- 依赖：`TASK-008`
- 新建/修改：`backend/paideia-ops/.../ops/pattern/PatternQueryService.java`（追加一行 `app → MySQL/Redis/RustFS/Laya/Tempo` 到 summary）、`OpsProperties.Patterns`（拓扑配置）
- 接口：summary 头部含依赖行；拓扑来自配置，不引 CMDB
- 测试：`PatternQueryServiceTest#test_summary_contains_topology_line`
- 步骤：
  1. 配置化拓扑（默认项）
  2. summary 拼入
  3. 写测试
- 验证：`... -Dtest=PatternQueryServiceTest ... test` 绿

### TASK-013 | done | 架构图与 `.archify.json` 同步

- 对应：`REQ-002`、`REQ-004`（结构变更同提交改图）
- 依赖：`TASK-003`
- 新建/修改：`docs/architecture/*`（数据模型图新增三表关系）、`docs/architecture/paideia-architecture.archify.json`（`meta.repository.revision` 钉到实现提交）
- 测试：`node scripts/check-diagrams.mjs`、`node scripts/gen-arch-diagram.mjs --check`
- 步骤：
  1. 改数据模型图与 `.archify.json`
  2. 重新生成 HTML
  3. 跑两条校验脚本
- 验证：两条脚本 exit 0

## 完成条件

- REQ-001~010 全部有归属任务且实现；AC-001~007 均有可复现验证证据（写入 `.agent/changes/WORK-085-.../verification.md`）。
- 内环测试与守卫三件全绿；Loki/Grafana 侧部署实测有记录（TASK-001 笔记 + 面板截图）。
- 迁移三件、`ApiContractTest`、`AuthorizationIsolationTest` 通过；`ModularityTest` 确认新包在 `paideia-ops`。
- `docs/architecture` 图与 `.archify.json` 与实现提交一致；无凭据入库。

