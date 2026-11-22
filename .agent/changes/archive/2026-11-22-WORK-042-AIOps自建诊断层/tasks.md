---
artifact: tasks
work_id: WORK-042
work: AIOps 自建诊断层
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 18:11:29 +0800
approver_role: CTO
---

# AIOps 自建诊断层实施任务

## 依据

- [requirements.md](requirements.md)
- [proposal.md](proposal.md)
- [design.md](design.md)

## 全局约束

- 新增服务只绑 `127.0.0.1`；公网仍只有 8081。
- 不新增 Java 依赖；不引入 HolmesGPT/Keep/SigNoz。
- 迁移 14 位时间戳；只建结构；改前查同表冲突。
- 令牌、管理员 id、服务器地址不入库。
- 模型只能是纯文本调用，不带工具；不提供任何写操作。
- 现有四条告警与 `admin_ops_summary` 行为不变。
- 部署动作（装 Loki/Alloy、改生产配置、发版）在 TASK-012 统一执行；前面任务只准备配置与代码。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 日志里的邮箱/令牌原样出站 | TASK-004 | `OpsLogRedactorTest.redactsKnownSecrets` |
| 超长日志行或控制字符撑爆提示词 | TASK-004 | `OpsLogRedactorTest.truncatesAndStripsControlChars` |
| 日志内容被模型当成指令执行 | TASK-005 | `OpsDiagnosisPromptTest.logsAreMarkedUntrusted` |
| 余额不足仍发起上游请求 | TASK-005 | `DefaultOpsDiagnoserTest.insufficientCreditsMakesNoCall` |
| 上游失败后预留未退回 | TASK-005 | `DefaultOpsDiagnoserTest.upstreamFailureRefunds` |
| 应用重启留下永久 RUNNING 行 | TASK-008 | `OpsDiagnosisServiceTest.staleRunningRowsAreFailed` |
| 同一告警风暴重复诊断 | TASK-010 | `OpsHookIntegrationTest.cooldownSkipsSecondAlert` |
| 并发手动触发两次 | TASK-008 | `OpsDiagnosisIntegrationTest.concurrentManualIsRejected` |
| resolved 事件触发诊断 | TASK-010 | `OpsHookIntegrationTest.resolvedIsIgnored` |
| 错误令牌或非回环来源触发 | TASK-010 | `OpsHookIntegrationTest.badTokenIsRejected` |
| 非管理员读到诊断报告 | TASK-008 | `OpsDiagnosisIntegrationTest.learnerIsForbidden` |
| 非管理员在 MCP 看到 ops 工具 | TASK-009 | `OpsAdminToolsTest.nonAdminCannotSeeAdminTools` |
| 模型填未知指标枚举 | TASK-009 | `OpsAdminToolsTest.unknownMetricIsRejected` |
| 两条新告警变成 NoData 假 firing | TASK-003 | 部署后 Grafana API 检查 `noDataState` 与表达式返回值 |
| Alloy 没有 journal 权限、日志静默为空 | TASK-001 | 部署后 Loki 查询有结果；`id alloy` 组检查 |
| 迁移撞号导致应用起不来 | TASK-006 | `MigrationVersionFormatTest` + 合并前 `git log --all -- <迁移目录>` |
| 诊断报告缺少五节或没有证据 | TASK-005 | `OpsDiagnosisPromptTest.requiresFiveSectionsAndEvidence` |

## 任务

### TASK-001 | done | 数据面配置：保留期、Loki、Alloy、Grafana 数据源

- 对应：`REQ-001`、`REQ-002`、`REQ-003`、`AC-001`、`AC-002`、`AC-003`
- 依赖：无
- 新建/修改：
  - `deploy/ops/prometheus.service`（保留期 3d → 30d）
  - `deploy/ops/loki/loki-config.yaml`（新建）
  - `deploy/ops/loki.service`（新建）
  - `deploy/ops/alloy.alloy`（新建）
  - `deploy/ops/alloy.service`（新建）
  - `deploy/ops/provisioning/datasources/loki.yml`（新建）
  - `deploy/ops/install-ops.sh`（安装两个二进制、账号、组、单元）
- 接口：Loki 监听 `127.0.0.1:3100`；Alloy 推送 `http://127.0.0.1:3100/loki/api/v1/push`；标签 `unit` 来自 `__journal__systemd_unit`。
- 测试：部署后 `curl` 检查（无单元测试）。
- 步骤：
  1. 改保留期并写 Loki/Alloy 配置 → `promtool check config` 与 YAML 解析通过。
  2. 安装脚本加入 Loki `v3.7.9`、Alloy `v1.21.2` 下载与 `alloy` 账号 `adm,systemd-journal` 组 → 脚本 `bash -n` 通过。
  3. 加 Grafana Loki 数据源 → 文件可被 Grafana provisioning 解析。
- 验证：部署后在服务器执行 `curl -s 127.0.0.1:9090/api/v1/status/flags | grep retention` → 期望 `30d`；`curl -s '127.0.0.1:3100/loki/api/v1/query_range?query=%7Bunit%3D%22paideia.service%22%7D&limit=1'` → 期望 `success` 且至少一条。

### TASK-002 | done | 发版标记指标

- 对应：`REQ-005`、`AC-005`
- 依赖：TASK-001（node_exporter textfile 目录已存在）
- 新建/修改：`scripts/release.ps1`
- 接口：写 `/var/lib/node_exporter/textfile/deploy.prom`，内容 `paideia_build_info{version="<版本>"} 1` 与 `paideia_deploy_epoch_seconds <unix秒>`。
- 测试：部署后 Prometheus 查询。
- 步骤：
  1. 在重启成功后、脚本结束前，通过现有 SSH 通道写 `deploy.prom` → 失败只告警不阻断发版。
  2. 用 `version` 变量（已有或取 git 短哈希）填标签。
- 验证：`curl -s 'http://127.0.0.1:9090/api/v1/query?query=paideia_deploy_epoch_seconds'` → 期望 `success` 且值在最近 10 分钟内。

### TASK-003 | done | 两条日志/5xx 告警与合并收件人

- 对应：`REQ-004`、`AC-004`
- 依赖：TASK-001
- 新建/修改：`deploy/ops/provisioning/alerting/paideia-rules.yaml`、`deploy/ops/provisioning/alerting/contact-points.yaml`
- 接口：contact point `paideia-ops` 同时含邮件与 webhook 两个 integration；四条旧规则与两条新规则都指向它。
- 测试：部署后 Grafana API 读取规则与表达式。
- 步骤：
  1. 加 5xx 比例规则（`bool`，NoData=NoData）。
  2. 加 ERROR 日志规则（LogQL，NoData=OK）。
  3. 合并收件人，更新六条规则的 `notification_settings.receiver`。
- 验证：`curl -s -u admin:... 'http://127.0.0.1:3000/api/v1/provisioning/alert-rules'` → 期望 6 条规则、`paideia-ops` 收件人；Prometheus 表达式有返回值。

### TASK-004 | done | 日志脱敏与 Loki 客户端

- 对应：`REQ-009`、`REQ-010`、`AC-009`
- 依赖：无
- 新建/修改：
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsLogRedactor.java`（新建）
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/LokiClient.java`（新建）
  - 对应测试
- 接口：
  - `OpsLogRedactor.redact(String line): String`；`OpsLogRedactor.redactLines(List<String>, int maxLines, int maxChars): List<String>`
  - `LokiClient.queryRange(String query, Instant from, Instant to, int limit): List<LogLine>`；`record LogLine(Instant at, String line)`
- 测试：`OpsLogRedactorTest`、`LokiClientTest`（用本地 `HttpServer` 假响应）
- 步骤：
  1. 写失败测试（邮箱/Bearer/JWT/password 赋值/长十六进制）→ 红。
  2. 实现脱敏与截断 → 绿。
  3. 写 Loki 客户端失败/成功测试 → 实现 → 绿。
- 验证：`cd backend && mvn -B -pl paideia-app -am -Dtest=OpsLogRedactorTest,LokiClientTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures。

### TASK-005 | done | AI 诊断端口、提示词、积分与遥测

- 对应：`REQ-009`、`REQ-010`、`REQ-011`、`REQ-012`、`AC-010`、`AC-012`
- 依赖：无
- 新建/修改：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/OpsDiagnoser.java`（新建）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/diagnosis/OpsDiagnosisPrompt.java`（新建）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/diagnosis/DefaultOpsDiagnoser.java`（新建）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/provider/OpsDiagnosisModelCaller.java`（新建）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/RunKind.java`（加常量）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/billing/CreditLedger.java`、`CreditService.java`（加种类与结算）
  - 对应测试
- 接口：
  - `OpsDiagnoser.diagnose(long userId, Evidence evidence): Diagnosis`
  - `Evidence(String trigger, String alertName, String question, String summary, String metrics, String alerts, String logs, String deploys)`
  - `Diagnosis(String report, TokenUsage usage, String model)`
  - `CreditService.settleOpsDiagnosis(long userId, long reserved, String model, Integer inputTokens, Integer outputTokens): int`
- 测试：`OpsDiagnosisPromptTest`、`DefaultOpsDiagnoserTest`
- 步骤：
  1. 写提示词测试（五节、不可信标记、证据引用）→ 红 → 实现 → 绿。
  2. 写余额不足测试（假 caller 计数为 0）→ 红 → 实现预留 → 绿。
  3. 写上游失败退款测试 → 红 → 实现 try/catch → 绿。
  4. 接入 `RunRecorder` 与结算 → 测试断言 kind 与流水。
- 验证：`cd backend && mvn -B -pl paideia-ai -Dtest=OpsDiagnosisPromptTest,DefaultOpsDiagnoserTest test` → 期望 0 failures。

### TASK-006 | done | 诊断表迁移、行与 mapper

- 对应：`REQ-011`、`AC-007`、`AC-015`
- 依赖：TASK-005
- 新建/修改：
  - `backend/paideia-app/src/main/resources/db/migration/V20261026180000__create_ops_diagnoses.sql`（新建，提交前确认无冲突）
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsDiagnosisRow.java`（新建）
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsDiagnosisMapper.java`（新建）
  - 集成测试
- 接口：
  - `insert(OpsDiagnosisRow row)`
  - `markRunning(long id)`
  - `markDone(long id, String model, Integer inputTokens, Integer outputTokens, String report, Instant finishedAt)`
  - `markFailed(long id, String error, Instant finishedAt)`
  - `findById(long id): Optional<OpsDiagnosisRow>`
  - `listRecent(int limit): List<OpsDiagnosisRow>`
  - `markStale(Instant before, String error): int`
- 测试：`OpsDiagnosisMapperTest`（集成，走 `scripts/test-db.sh`）
- 步骤：
  1. 写迁移 → `MigrationVersionFormatTest` 绿。
  2. 写 mapper 插入/更新/查询测试 → 红 → 实现 → 绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsDiagnosisMapperTest,MigrationVersionFormatTest,MigrationChecksumTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures。

### TASK-007 | done | 证据采集

- 对应：`REQ-009`、`AC-012`
- 依赖：TASK-004、TASK-006
- 新建/修改：
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsEvidenceCollector.java`（新建）
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsProperties.java`（加 Loki 地址、诊断配置）
  - 对应测试
- 接口：`OpsDiagnoser.Evidence collect(String trigger, String alertName, String question)`
- 测试：`OpsEvidenceCollectorTest`（假 Prometheus/Loki/摘要）
- 步骤：
  1. 写固定查询目录与格式化测试 → 红 → 实现 → 绿。
  2. 写"Loki 不可达仍返回证据"测试 → 红 → 实现降级 → 绿。
  3. 写总字符上限测试 → 红 → 实现截断 → 绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsEvidenceCollectorTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures。

### TASK-008 | done | 诊断编排、手动端点与权限

- 对应：`REQ-007`、`REQ-011`、`REQ-013`、`AC-007`、`AC-010`、`AC-011`、`AC-013`
- 依赖：TASK-005、TASK-006、TASK-007
- 新建/修改：
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsDiagnosisService.java`（新建）
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsDiagnosisController.java`（新建）
  - `backend/paideia-security/src/main/java/io/github/xiaou61/security/SecurityConfiguration.java`（如需显式规则）
  - 集成测试
- 接口：
  - `long start(String trigger, String alertName, String question, Long requestedBy)`
  - `List<OpsDiagnosisRow> list(int limit)`、`OpsDiagnosisRow get(long id)`
  - `POST /api/v1/ops/admin/diagnoses` → `202 {id}`
  - `GET /api/v1/ops/admin/diagnoses`、`GET /api/v1/ops/admin/diagnoses/{id}`
- 测试：`OpsDiagnosisIntegrationTest`
- 步骤：
  1. 写管理员触发 → 行从 PENDING 到 DONE（假 `OpsDiagnoser`）→ 红 → 实现编排 → 绿。
  2. 写非管理员 403、关闭开关 400/409、余额不足 FAILED 测试 → 红 → 实现 → 绿。
  3. 写启动清理超时 RUNNING 的测试 → 红 → 实现 → 绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsDiagnosisIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures。

### TASK-009 | done | 三个只读运维工具

- 对应：`REQ-006`、`REQ-014`、`AC-006`、`AC-014`
- 依赖：TASK-004、TASK-007
- 新建/修改：
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsAdminTools.java`（加三组）
  - 对应测试
- 接口：
  - `admin_ops_metrics` 输入 `{"query": <枚举>}`
  - `admin_ops_logs` 输入 `{"unit?","level?","minutes?","limit?"}`
  - `admin_ops_alerts` 输入 `{}`
  - 三者 `readOnly=true`、`destructive=false`、`scope=ADMIN`、`defaultGroup=false`
- 测试：`OpsAdminToolsTest`、`ToolCatalogTest`（若已有则补断言）
- 步骤：
  1. 写工具目录测试（非管理员不可见、只读标记）→ 红 → 实现描述符 → 绿。
  2. 写未知枚举拒绝测试 → 红 → 实现枚举白名单 → 绿。
  3. 写正常返回文本测试 → 绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsAdminToolsTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures。

### TASK-010 | done | Grafana webhook、冷却与单飞

- 对应：`REQ-008`、`REQ-013`、`AC-008`、`AC-011`
- 依赖：TASK-008
- 新建/修改：
  - `backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsHookController.java`（新建）
  - `backend/paideia-security/src/main/java/io/github/xiaou61/security/SecurityConfiguration.java`（回环规则）
  - `deploy/ops/provisioning/alerting/contact-points.yaml`、`deploy/ops/install-ops.sh`（令牌注入）
  - 集成测试
- 接口：
  - `POST /api/v1/ops/hooks/grafana`
  - 请求头 `X-Paideia-Ops-Token`（Grafana 13.2.4 的 Extra Headers）
  - 返回 `200 {"accepted":true|false,"skipped":"cooldown|busy|disabled|resolved|no-user"}`
- 测试：`OpsHookIntegrationTest`
- 步骤：
  1. 写正确/错误令牌、resolved、冷却测试 → 红 → 实现 → 绿。
  2. 加安全规则（回环）→ 用非回环请求测试 → 绿。
  3. 安装脚本生成令牌并注入 contact point 与 systemd drop-in → `bash -n` 绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsHookIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures。

### TASK-011 | done | 管理端诊断区与日志面板

- 对应：`REQ-003`、`REQ-007`、`AC-003`、`AC-007`、`AC-015`
- 依赖：TASK-008、TASK-010
- 新建/修改：
  - `frontend/apps/admin/src/pages/OpsPage.tsx`
  - `frontend/apps/admin/e2e/admin-ops.spec.ts`（新建或并入现有）
  - `deploy/ops/provisioning/dashboards/paideia-ops.json`（加日志 panel）
- 接口：直接调用三条 `/api/v1/ops/admin/diagnoses` 端点；报告用 `@paideia/ui` 的 `MarkdownBody` 渲染。
- 测试：`admin-ops.spec.ts` 的「发起诊断并显示报告」用例。
- 步骤：
  1. 写类型与查询 hooks → `pnpm --filter @paideia/admin typecheck` 绿。
  2. 加诊断卡片与历史列表 → 组件测试/e2e 绿。
  3. 仪表盘加 Loki 日志 panel → `node scripts/check-diagrams.mjs` 不涉及；Grafana 加载检查在 TASK-012。
- 验证：`cd frontend && pnpm --filter @paideia/admin typecheck && pnpm --filter @paideia/admin test:e2e -- e2e/admin-ops.spec.ts` → 期望 0 failed。

### TASK-012 | done | 部署、生产验证与历史记录

- 对应：全部 `AC-*`
- 依赖：TASK-001..TASK-011
- 新建/修改：`.agent/history/updates.md`、必要时 `.agent/notes/dev-workflow.md`
- 测试：见 `testing/plan.md`
- 步骤：
  1. 运行后端切片测试与前端检查 → 全绿。
  2. 部署：`install-ops.sh`（带代理）→ 重启 `paideia` → 生产逐条验证。
  3. 记录实际输出到 `testing/report.md`，追加更新历史。
- 验证：`powershell -File scripts/release.ps1` → 成功；随后执行 `testing/plan.md` 的生产检查清单 → 每条有输出。

## 完成条件

- 所有任务 `done`，`testing/report.md` 对每条 `AC-*` 有新鲜证据。
- 生产上日志可查、两条新告警已加载、手动与 webhook 诊断各成功一次。
- 现有四条告警、运维页七张卡片与 `admin_ops_summary` 行为不变。
- 无新增公网端口、无新增长期凭据入库、无自动修复动作。
