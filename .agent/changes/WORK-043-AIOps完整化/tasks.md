---
artifact: tasks
work_id: WORK-043
work: AIOps 完整化：事件、基线、链路、SLO 与评测
status: approved
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 13:52:33 +0800
approver_role: CTO
---

# AIOps 完整化实施任务

## 依据

- [requirements.md](requirements.md)
- [proposal.md](proposal.md)
- [design.md](design.md)

## 全局约束

- 一个 WORK、六个阶段；每阶段独立可验证，P0 完成即可先上线。
- 不引入自动修复；不新增公网端口；Tempo/OTLP 只绑回环。
- 迁移 14 位时间戳，只建结构；改前查同表冲突。
- 诊断仍走 `SingleShotModelCaller` + `GuardedHttpTransport`；证据总上限 12k。
- trace/日志证据只保留白名单属性；不采集请求体、SQL 参数、Authorization/Cookie。
- 新增页面必须加入管理端导航/入口（AGENTS.md 硬规则）。
- 部署：Tempo 由 `install-ops.sh` 安装；`release.ps1` 仍只重启 `paideia`。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 同一告警重复 webhook 建出多个事件 | TASK-003 | `OpsHookIntegrationTest.duplicateFiringCreatesOneIncident` |
| resolved 后再次 firing 复用了旧事件 | TASK-003 | `OpsHookIntegrationTest.reopenCreatesNewIncident` |
| 指纹含时间/摘要导致去重失效 | TASK-002 | `OpsIncidentFingerprintTest.ignoresVolatileLabels` |
| 同一事件冷却内重复诊断 | TASK-004 | `OpsDiagnosisServiceTest.perIncidentCooldown` |
| critical 抑制 warning 时连自己也被抑制 | TASK-003 | `OpsHookIntegrationTest.criticalSuppressesOnlyWarnings` |
| 低流量/夜间基线误报 | TASK-010/011 | `promtool test rules` 的 samples 不足用例 |
| 基线无数据被当成 0 | TASK-009/010 | `OpsEvidenceCollectorTest.baselineInsufficientIsExplicit` |
| trace id 与 X-Trace-Id 不一致 | TASK-012 | `TraceIdFilterTest.prefersOtelTraceId` |
| Tempo 不可达拖垮诊断 | TASK-015 | `OpsEvidenceCollectorTest.tempoDownIsExplicit` |
| trace 证据带出 SQL/请求体 | TASK-016 | `TempoTraceRedactorTest.dropsForbiddenAttributes` |
| SLO 无流量误报 | TASK-018 | `promtool test rules` 的 no-data 用例 |
| 新指标高基数 | TASK-017 | `SloMetricsTest.labelsAreLowCardinality` |
| 非法评分写入 | TASK-021 | `OpsFeedbackIntegrationTest.invalidRatingRejected` |
| 金集 live 模式进 CI 花钱 | TASK-022 | 默认 `@EnabledIfEnvironmentVariable` 关闭 |
| 迁移撞号导致应用起不来 | TASK-001 | `MigrationVersionFormatTest` + `git log --all -- <迁移目录>` |
| 事件页没有导航入口 | TASK-006 | 管理端导航断言/e2e |

## 任务

### TASK-001 | done | 事件/时间线表与诊断增量迁移

- 对应：`REQ-001`、`REQ-002`、`REQ-019`、`AC-001`、`AC-002`、`AC-010`
- 依赖：无
- 新建/修改：`backend/paideia-app/src/main/resources/db/migration/V20261027xxxxxx__create_ops_incidents.sql`、`backend/paideia-app/src/test/resources/migration-checksums.txt`
- 接口：表结构见 design；`ops_diagnoses` 增加 `incident_id`/`rating`/`feedback_note`/`feedback_at`。
- 测试：`MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest`
- 步骤：
  1. 写迁移 → `promtool` 无关；先跑迁移命名/校验和守卫。
  2. 用 `-Dmigration.checksums.update=true` 登记新迁移并复核 diff。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed。

### TASK-002 | done | 事件指纹、状态机与 mapper

- 对应：`REQ-001`、`REQ-002`、`AC-001`
- 依赖：TASK-001
- 新建/修改：`OpsIncident.java`、`OpsIncidentEvent.java`、`OpsIncidentMapper.java`、`OpsIncidentService.java`
- 接口：
  - `long upsertFiring(String fingerprint, String alertName, String severity, Map<String,String> labels, Instant at): long`
  - `void resolve(String fingerprint, Instant at): void`
  - `Optional<OpsIncident> findOpen(String fingerprint): Optional<OpsIncident>`
  - `List<OpsIncidentEvent> timeline(long incidentId, int limit): List<OpsIncidentEvent>`
- 测试：`OpsIncidentFingerprintTest`、`OpsIncidentMapperTest`
- 步骤：
  1. 写指纹测试（忽略 startsAt/annotations）→ 红 → 实现 → 绿。
  2. 写 upsert/resolve mapper 测试 → 红 → 实现 → 绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsIncidentFingerprintTest,OpsIncidentMapperTest -Dsurefire.failIfNoSpecifiedTests=false test`。

### TASK-003 | done | webhook 扩为事件 upsert/resolve + 抑制

- 对应：`REQ-003`、`REQ-004`、`REQ-005`、`AC-001`、`AC-002`
- 依赖：TASK-002
- 新建/修改：`OpsHookController.java`、`OpsHookIntegrationTest.java`
- 接口：`GrafanaPayload` 增加 `alerts[].fingerprint/startsAt/endsAt/status`；响应增加 `incidentIds`、`diagnosisId`/`skipped`。
- 测试：`duplicateFiringCreatesOneIncident`、`reopenCreatesNewIncident`、`criticalSuppressesOnlyWarnings`
- 步骤：
  1. 写重复/resolved/重开测试 → 红 → 实现 upsert/resolve → 绿。
  2. 写 critical/warning 同通知测试 → 红 → 实现抑制 → 绿。
- 验证：`scripts/test-db.sh mvn ... -Dtest=OpsHookIntegrationTest ...`。

### TASK-004 | done | 诊断挂到事件 + 按事件冷却

- 对应：`REQ-005`、`AC-003`
- 依赖：TASK-002、TASK-003
- 新建/修改：`OpsDiagnosisService.java`、`OpsDiagnosisRow.java`、`OpsDiagnosisMapper.java`、`OpsDiagnosisController.java`
- 接口：`start(String trigger, Long incidentId, String alertName, String question, long userId)`；`markDone`/`markFailed` 追加时间线。
- 测试：`OpsDiagnosisServiceTest.perIncidentCooldown`、`OpsDiagnosisIntegrationTest.manualHasNoIncident`
- 步骤：
  1. 写“同一 OPEN 事件第二次不建行”测试 → 红 → 实现冷却 → 绿。
  2. 写“手动诊断 incident_id 为空”测试 → 绿。
- 验证：`scripts/test-db.sh mvn ... -Dtest=OpsDiagnosisServiceTest,OpsDiagnosisIntegrationTest ...`。

### TASK-005 | done | 事件列表/详情/备注端点

- 对应：`REQ-006`、`AC-002`
- 依赖：TASK-002、TASK-004
- 新建/修改：`OpsIncidentController.java`、`OpsIncidentService.java`
- 接口：
  - `GET /api/v1/ops/admin/incidents?status=OPEN&limit=50`
  - `GET /api/v1/ops/admin/incidents/{id}` → 事件 + 时间线 + 诊断摘要
  - `POST /api/v1/ops/admin/incidents/{id}/notes` `{message}`
- 测试：`OpsIncidentIntegrationTest`（ADMIN 200、学习者 403、备注追加）
- 步骤：先写 403/200 测试 → 实现 → 绿。
- 验证：`scripts/test-db.sh mvn ... -Dtest=OpsIncidentIntegrationTest ...`。

### TASK-006 | done | 管理端事件页与时间线

- 对应：`REQ-006`、`AC-002`、`AC-015`
- 依赖：TASK-005
- 新建/修改：`frontend/apps/admin/src/pages/IncidentsPage.tsx`、`router.tsx`、`AdminShell.tsx`、`e2e/incidents.spec.ts`
- 接口：调用 TASK-005 的三个端点；状态徽标 OPEN/RESOLVED；时间线只读 + 备注输入。
- 测试：e2e 从侧栏进事件页、空状态/列表、备注提交。
- 步骤：加导航入口 → 页面 → e2e。
- 验证：`cd frontend && pnpm --filter @paideia/admin typecheck && pnpm --filter @paideia/admin test:e2e -- e2e/incidents.spec.ts`。

### TASK-007 | done | Grafana 通知分组与事件层抑制配置

- 对应：`REQ-007`
- 依赖：TASK-003
- 新建/修改：`deploy/ops/provisioning/alerting/contact-points.yaml`、`OpsHookController`/`OpsIncidentService`
- 接口：`group_by: ['alertname','severity']`；抑制规则在事件层（critical 抑制同通知 warning）。
- 测试：`OpsHookIntegrationTest.criticalSuppressesOnlyWarnings` + 部署后 Grafana 策略检查。
- 步骤：改 group_by → 部署检查；抑制逻辑与 TASK-003 同一实现。
- 验证：Grafana API 读取 policy 的 `group_by`；集成测试绿。

### TASK-008 | in_progress | 基线/SLO 录制规则与 promtool 测试

- 进度：基线录制规则（CPU/5xx/P95）与 promtool 用例已完成；SLO 录制规则在 TASK-018 收口。

- 对应：`REQ-008`、`REQ-025`、`AC-004`、`AC-012`、`AC-013`
- 依赖：TASK-001（无代码依赖，可并行）
- 新建/修改：`deploy/ops/prometheus-rules.yml`、`deploy/ops/prometheus.yml`（`rule_files`）、`deploy/ops/install-ops.sh`、`deploy/ops/prometheus-rules.test.yml`
- 接口：录制序列名见 design；`paideia:sli_*`、`paideia:baseline:*`、`paideia:slo_burn_rate:*`。
- 测试：`promtool check rules` + `promtool test rules`
- 步骤：
  1. 写录制规则 → `promtool check rules` 绿。
  2. 写正常/尖峰/样本不足三个用例 → `promtool test rules` 绿。
- 验证：`promtool check rules deploy/ops/prometheus-rules.yml && promtool test rules deploy/ops/prometheus-rules.test.yml`。

### TASK-009 | done | 证据 v2：基线字段与优先级截断

- 对应：`REQ-008`、`REQ-011`、`AC-004`
- 依赖：TASK-008
- 新建/修改：`OpsDiagnoser.java`（Evidence 增 `baselines`/`traces`）、`OpsEvidenceCollector.java`、`OpsDiagnosisPrompt.java`、对应测试
- 接口：`Evidence(trigger, alertName, question, summary, metrics, baselines, alerts, logs, traces, deploys)`。
- 测试：`OpsEvidenceCollectorTest.baselineInsufficientIsExplicit`、`OpsDiagnosisPromptTest.containsBaselineSection`
- 步骤：
  1. 先写 `baselines` 行格式测试 → 实现 → 绿。
  2. 写总字符上限/优先级截断测试 → 实现 → 绿。
- 验证：`mvn -B -pl paideia-ai ...` + `scripts/test-db.sh mvn ... -Dtest=OpsEvidenceCollectorTest ...`。

### TASK-010 | done | 指标基线告警（CPU/5xx/P95）

- 对应：`REQ-009`、`REQ-011`、`AC-005`
- 依赖：TASK-008
- 新建/修改：`deploy/ops/provisioning/alerting/paideia-rules.yaml`
- 接口：三条规则读录制序列；`NoData=OK`；`for: 10m`。
- 测试：部署后 Grafana rules API 加载检查 + `promtool test rules` 覆盖录制表达式。
- 步骤：加规则 → 部署检查 health/state。
- 验证：Grafana API 6+3 条规则、health ok、无活动误报。

### TASK-011 | done | ERROR 日志量基线告警

- 对应：`REQ-010`、`REQ-011`、`AC-006`
- 依赖：TASK-008、TASK-010
- 新建/修改：`paideia-rules.yaml`
- 接口：Loki 查询 A=当前 5m，B=`offset 1w`，C=`A > 3*B and A > 10`；`NoData=OK`。
- 测试：Grafana 规则加载 + 空闲不触发；LogQL 在 Loki API 可执行。
- 步骤：写两条查询 + 数学表达式 → 部署检查。
- 验证：Grafana API 规则 health ok；空闲 10 分钟无告警。

### TASK-012 | pending | OTel starter 与 trace id 统一

- 对应：`REQ-013`、`REQ-015`、`AC-008`
- 依赖：TASK-009（证据字段）
- 新建/修改：`backend/paideia-app/pom.xml`、`application.yml`、`TraceIdFilter.java`、`TraceIdFilterTest.java`
- 接口：`management.tracing.sampling.probability`、`management.otlp.tracing.endpoint`、`logging.pattern.correlation`。
- 测试：`TraceIdFilterTest.prefersOtelTraceId`、`fallsBackWhenNoSpan`
- 步骤：
  1. 加 starter + 配置 → 应用启动。
  2. 改 filter 优先用 `Tracer.currentSpan().context().traceId()` → 测试绿。
- 验证：`mvn -B -pl paideia-app -am -Dtest=TraceIdFilterTest ...`；启动后 `curl -i` 看 `X-Trace-Id` 与日志 traceId 一致。

### TASK-013 | pending | Tempo 单机部署与数据源

- 对应：`REQ-014`、`AC-007`、`AC-016`
- 依赖：无（可与 TASK-012 并行）
- 新建/修改：`deploy/ops/tempo-config.yaml`、`tempo.service`、`provisioning/datasources/tempo.yml`、`install-ops.sh`、`prometheus.yml`（tempo job）
- 接口：OTLP HTTP `127.0.0.1:4318`、gRPC `127.0.0.1:4317`、HTTP API `127.0.0.1:3200`。
- 测试：部署后 `systemctl is-active tempo`、Grafana 数据源 health、Tempo self metrics。
- 步骤：安装 → 起单元 → 数据源 → 查询一次 trace。
- 验证：`curl 127.0.0.1:3200/ready` → ready；Grafana datasource health OK。

### TASK-014 | pending | 日志 traceId 与 Grafana 关联

- 对应：`REQ-016`、`AC-008`
- 依赖：TASK-012、TASK-013
- 新建/修改：`application.yml` 日志 pattern、`provisioning/datasources/loki.yml` derivedFields
- 接口：日志行 `[<traceId>]`；Loki derived field 指向 `paideia-tempo`。
- 测试：生产日志含 traceId；Grafana 面板点日志能跳 trace。
- 步骤：改 pattern → 部署 → 人工点一次。
- 验证：`journalctl`/Loki 查询行含 traceId；Grafana 跳转可用。

### TASK-015 | pending | TempoClient 与 trace 证据

- 对应：`REQ-017`、`AC-009`
- 依赖：TASK-013、TASK-009
- 新建/修改：`TempoClient.java`、`OpsEvidenceCollector.java`、`OpsDiagnosisPrompt.java`、测试
- 接口：`List<TraceSummary> search(Instant from, Instant to, int limit)`；`Optional<TraceDetail> trace(String id)`。
- 测试：`TempoClientTest`（本地 HttpServer 假响应）、`OpsEvidenceCollectorTest.tempoDownIsExplicit`。
- 步骤：先写解析/降级测试 → 实现 → 绿；把 trace 摘要接入证据。
- 验证：`mvn -B -pl paideia-app -am -Dtest=TempoClientTest,OpsEvidenceCollectorTest ...`。

### TASK-016 | pending | trace 属性白名单与脱敏

- 对应：`REQ-018`、`AC-016`
- 依赖：TASK-015
- 新建/修改：`TempoTraceRedactor.java`、测试
- 接口：允许 `service.name/http.method/http.route/http.status_code/db.system/db.operation/duration/status`；丢弃 `db.statement/http.request.body/authorization/cookie`。
- 测试：`TempoTraceRedactorTest.dropsForbiddenAttributes`
- 步骤：写黑名单测试 → 实现 → 绿。
- 验证：`mvn ... -Dtest=TempoTraceRedactorTest`。

### TASK-017 | pending | AI/登录/结算业务指标

- 对应：`REQ-024`、`AC-012`
- 依赖：无
- 新建/修改：`DefaultRunRecorder.java`、`LoginService.java`、`CreditService.java`、对应测试
- 接口：`paideia.ai.runs{kind,outcome}`、`paideia.ai.run.duration{kind}`、`paideia.login.attempts{outcome}`、`paideia.credit.settlement.shortfall`。
- 测试：`SloMetricsTest.labelsAreLowCardinality`、`DefaultRunRecorderTest` 扩展。
- 步骤：在 `finish` 发 counter/timer → 单测；登录/结算同理。
- 验证：`mvn -B -pl paideia-ai -Dtest=DefaultRunRecorderTest ...`；`curl actuator/prometheus` 看指标。

### TASK-018 | pending | SLO burn-rate 规则

- 对应：`REQ-025`、`REQ-027`、`AC-013`
- 依赖：TASK-008、TASK-017
- 新建/修改：`prometheus-rules.yml`（SLI/burn 录制）、`paideia-rules.yaml`（4–6 条 burn 告警）
- 接口：规则名见 design；NoData=OK。
- 测试：`promtool test rules` 覆盖 no-data/快烧/慢烧。
- 步骤：录制 SLI → 写 burn 规则 → 部署检查。
- 验证：`promtool` 绿；Grafana 规则 health ok；构造一次快烧能触发。

### TASK-019 | pending | SLO 面板

- 对应：`REQ-026`、`AC-013`
- 依赖：TASK-018
- 新建/修改：`deploy/ops/provisioning/dashboards/paideia-ops.json`（3 个 panel）或新 dashboard
- 接口：SLI 趋势、burn rate、预算剩余。
- 测试：Grafana dashboard API 读取 panel 数；人工看一次。
- 步骤：加 panel → 部署检查。
- 验证：dashboard API 返回新增 panel。

### TASK-020 | pending | SLO 告警走事件/诊断路径

- 对应：`REQ-027`、`AC-014`
- 依赖：TASK-018、TASK-003
- 新建/修改：Grafana 规则 `notification_settings.receiver: paideia-ops`；事件指纹兼容 SLO 告警名。
- 接口：burn 告警与普通告警同一 webhook。
- 测试：构造一次 burn 告警 → 事件 + 诊断；报告引用 burn rate。
- 步骤：接 receiver → 演练 → 检查报告。
- 验证：生产/测试 webhook 触发后事件与诊断存在。

### TASK-021 | pending | 诊断评分端点与 UI

- 对应：`REQ-019`、`AC-010`
- 依赖：TASK-001、TASK-004
- 新建/修改：`OpsFeedbackController.java`、`OpsDiagnosisMapper.java`、`OpsPage.tsx`、e2e
- 接口：`POST /api/v1/ops/admin/diagnoses/{id}/feedback` `{rating, note?}`；rating ∈ {-1,0,1}。
- 测试：`OpsFeedbackIntegrationTest.invalidRatingRejected`、e2e 评分按钮。
- 步骤：写 400/200 测试 → 实现 → UI → e2e。
- 验证：`scripts/test-db.sh mvn ... -Dtest=OpsFeedbackIntegrationTest ...` + e2e。

### TASK-022 | pending | 金集与重放评测

- 对应：`REQ-020`、`REQ-021`、`AC-011`
- 依赖：TASK-009、TASK-015、TASK-021
- 新建/修改：`backend/paideia-app/src/test/resources/ops-eval/*.json`、`OpsDiagnosisGoldenSetTest.java`
- 接口：默认 prompt 模式不调模型；`PAIDEIA_OPS_EVAL=1` 才 live。
- 测试：prompt 模式 10–15 条；live 模式手动。
- 步骤：
  1. 收集/合成 10–15 条证据快照 → 写 JSON。
  2. 写 prompt 模式断言 → 绿。
  3. 手动跑 live 模式，记录报告。
- 验证：`mvn ... -Dtest=OpsDiagnosisGoldenSetTest`（默认不调模型）；`PAIDEIA_OPS_EVAL=1 ...` 手动。

### TASK-023 | pending | helpful 率端点与 UI

- 对应：`REQ-022`、`AC-010`
- 依赖：TASK-021
- 新建/修改：`OpsDiagnosisMapper.stats()`、`OpsDiagnosisController`、`OpsPage.tsx`
- 接口：`GET /api/v1/ops/admin/diagnoses/stats` → `{rated, good, partial, bad, helpfulRate}`。
- 测试：集成测试 stats；e2e 显示。
- 步骤：写统计查询 → 端点 → UI。
- 验证：`mvn ... -Dtest=OpsFeedbackIntegrationTest` + e2e。

### TASK-024 | pending | 证据/安全复核与文档

- 对应：`REQ-018`、`AC-016`
- 依赖：TASK-009、TASK-015、TASK-016
- 新建/修改：`testing/report.md`、`design.md` 局部修订、`dev-workflow.md`
- 接口：无
- 测试：人工检查证据字段、Tempo 属性、日志脱敏；全量切片。
- 步骤：逐条核对 AC-016；记录实际 trace 属性；补文档。
- 验证：报告中每个安全项有证据行。

### TASK-025 | pending | 部署与生产验证

- 对应：全部 `AC-*`
- 依赖：TASK-001..TASK-024
- 新建/修改：`install-ops.sh` 最终化、`application.yml` 配置、`updates.md`
- 接口：部署顺序：Tempo → 应用 → Grafana 规则/面板 → 演练。
- 测试：见 `testing/plan.md`
- 步骤：
  1. 部署 Tempo 与规则，重启监控单元。
  2. 从干净工作树发版。
  3. 制造一次合成告警 → 事件 → 诊断；检查 trace/SLO。
- 验证：生产清单逐条有输出。

### TASK-026 | pending | 测试报告与收尾

- 对应：全部 `AC-*`
- 依赖：TASK-025
- 新建/修改：`testing/report.md`、`tasks.md` 状态、`.agent/history/updates.md`
- 接口：无
- 测试：跑受影响切片 + promtool + e2e。
- 步骤：跑测试 → 写报告 → 更新历史 → 清理工作树。
- 验证：报告每条 AC 有证据；`validate --strict` 无 WORK-043 错误。

## 完成条件

- P0–P4 全部任务 `done`；`testing/report.md` 对 16 条 AC 有新鲜证据。
- 生产上事件、基线、trace、SLO、反馈各有一条真实记录。
- WORK-042 的 6 条告警、诊断、运维页、MCP 工具行为不变。
- 无新增公网端口；trace/日志证据不含请求体、SQL 参数或凭据。
- 金集 prompt 模式在 CI 绿；live 模式手动跑过并留下报告。
