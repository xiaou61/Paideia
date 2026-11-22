---
artifact: tasks
work_id: WORK-049
work: AIOps 缺口修复：事件闭环、历史复用、告警关联与评测可信度
status: approved
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 13:55:09 +0800
approver_role: CTO
---

# AIOps 缺口修复实施任务

## 依据

- `requirements.md`（已批准，2026-10-28 13:51）
- `proposal.md`、`design.md`（同批）
- 现有实现：`backend/paideia-app/src/main/java/io/github/xiaou61/ops/**`、`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/diagnosis/**`、`frontend/apps/admin/src/pages/IncidentsPage.tsx`、`deploy/ops/**`

## 全局约束

- 零迁移、零新依赖、零新端口；MySQL 测试一律走 `scripts/test-db.sh`。
- 只提交本工作项归属的文件；工作区可能有其他会话的改动。
- 后端内环：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=Ops*Test -Dsurefire.failIfNoSpecifiedTests=false test`。
- 前端只跑 `pnpm --filter @paideia/admin typecheck` 与受影响 e2e 文件。
- 提示词任何语义变化必须升 `OpsDiagnosisPrompt.VERSION`。
- 生产演练需用户批准的窗口；收口任务之前不改线上阈值。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| resolved 重复投递追加第二条恢复时间线 | TASK-001 | `OpsHookIntegrationTest.resolvedTwiceAppendsOneEvent` |
| 已关闭事件再关闭追加第二条 CLOSED | TASK-002 | `OpsIncidentIntegrationTest.closeIsIdempotent` |
| 关闭后同指纹 firing 复用旧行 | TASK-002 | `OpsIncidentIntegrationTest.closeThenFiringCreatesNewIncident` |
| 相同 `last_seen` 翻页丢行或重复 | TASK-003 | `OpsIncidentMapperTest.pageWithEqualLastSeenDoesNotSkip` |
| 只给 `beforeLastSeen` 或只给 `beforeId` | TASK-003 | `OpsIncidentIntegrationTest.halfCursorIsRejected` |
| 历史为空时模型自行脑补 | TASK-004 | `OpsIncidentContextTest.emptyHistoryIsExplicit` |
| context 超预算把日志挤成空 | TASK-004 | `OpsEvidenceCollectorTest.contextCountsTowardBudget` |
| 同期事件把当前事件算进去 | TASK-004 | `OpsIncidentContextTest.concurrentEventsExcludeSelf` |
| FAILED 诊断被当成 DONE 复用 | TASK-005 | `OpsDiagnosisServiceTest.failedDiagnosisDoesNotSuppress` |
| 复用窗口配 0 仍然拦截 | TASK-005 | `OpsDiagnosisServiceTest.reuseDisabledWhenZero` |
| 低方差序列 z 变成 Inf/NaN | TASK-006 | `promtool test rules` 的 `z-score` 三组用例 |
| 占位符未渲染就启动 Grafana | TASK-007 | `scripts/check-ops-thresholds.mjs` 负向验证 |
| 环境变量未绑定，应用仍用默认值 | TASK-007 | 收口时读 `/actuator/env` 核对（TASK-012） |
| 诊断账号未配置时测试环境触发补诊断 | TASK-008 | `OpsDiagnosisServiceTest.catchUpSkipsWhenUserMissing` |
| 每次启动补多条 | TASK-008 | `OpsDiagnosisServiceTest.catchUpLimitOnePerStartup` |
| 深链日期非法产生 `NaN` 参数 | TASK-010 | `incidents-links.test.ts.invalidDateFallsBackToNow` |
| 配置加单元后仍拼 LogQL | TASK-011 | `OpsEvidenceCollectorTest.customUnitIsWhitelisted` |

## 任务

### TASK-001 | done | resolved 投递（REQ-001）

- 对应：`REQ-001`、`AC-001`
- 依赖：无
- 修改：`deploy/ops/provisioning/alerting/contact-points.yaml`
- 接口：无（配置）
- 测试：`OpsHookIntegrationTest`（现有 `reopenCreatesNewIncident`、`resolvedIsIgnored` 语义调整）
- 步骤：
  1. 把 `paideia-ops-ai` 收件人的 `disableResolveMessage` 改为 `false`，注释改为「resolved 也投递，用于关闭事件」。
  2. 跑 `OpsHookIntegrationTest`，确认「resolved 后再次 firing 是新事件」仍绿。
  3. 增加 `resolvedTwiceAppendsOneEvent`：连续两次 resolved，时间线只多一条 RESOLVED。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsHookIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failed

### TASK-002 | done | 手动关闭事件（REQ-002）

- 对应：`REQ-002`、`AC-002`
- 依赖：无
- 修改：`OpsIncidentService.java`、`OpsIncidentEvent.java`、`OpsIncidentMapper.java`、`OpsIncidentController.java`、`frontend/apps/admin/src/pages/IncidentsPage.tsx`
- 接口：
  - 产出 `OpsIncidentService.Detail close(long id, long actorUserId)`
  - 产出 `OpsIncidentMapper.resolveById(long id, Instant at): int`
  - 产出 `POST /api/v1/ops/admin/incidents/{id}/close` → `ApiResponse<OpsIncidentService.Detail>`
  - 常量 `OpsIncidentEvent.KIND_CLOSED = "CLOSED"`
- 测试：`OpsIncidentIntegrationTest.closeIsIdempotent`、`OpsIncidentIntegrationTest.closeThenFiringCreatesNewIncident`、`OpsIncidentMapperTest.resolveByIdOnlyOpen`
- 步骤：
  1. 写 mapper 测试：OPEN 行返回 1，再调返回 0 → 红。
  2. 加 `resolveById`（条件 `status='OPEN'`）→ 绿。
  3. 写集成测试：关闭后状态 RESOLVED、`dedupe_key` 为空、时间线含操作者；再关一次不新增 → 红。
  4. 实现 `close`：不存在 404；已 RESOLVED 直接返回；update=0 时重读返回；成功追加 CLOSED。
  5. 加控制器端点；前端详情在 OPEN 时显示「关闭事件」，成功后刷新 detail 与列表；`KIND_LABEL` 加 `CLOSED`。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='OpsIncident*Test' -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed；`pnpm --filter @paideia/admin typecheck` → 无输出

### TASK-003 | done | 事件列表分页与时间筛选（REQ-003）

- 对应：`REQ-003`、`AC-003`
- 依赖：TASK-002（同文件改动，避免冲突）
- 修改：`OpsIncidentMapper.java`、`OpsIncidentService.java`、`OpsIncidentController.java`、`IncidentsPage.tsx`
- 接口：
  - 产出 `OpsIncidentMapper.listPage(String status, Instant since, Instant beforeLastSeen, Long beforeId, int limit): List<OpsIncident>`
  - 产出 `OpsIncidentService.list(String status, int limit, Integer sinceHours, Instant beforeLastSeen, Long beforeId): List<View>`
  - 端点参数：`status`、`limit`、`sinceHours`、`beforeLastSeen`（ISO-8601）、`beforeId`
- 测试：`OpsIncidentMapperTest.pageWithEqualLastSeenDoesNotSkip`、`OpsIncidentIntegrationTest.halfCursorIsRejected`
- 步骤：
  1. 写 mapper 测试：插入 3 条相同 `last_seen` 的行，第一页 2 条、第二页 1 条且不重复 → 红。
  2. 实现 `listPage` 的 keyset SQL → 绿。
  3. 控制器解析游标：两个都给或都不给；只给一个 400，`sinceHours<=0` 400。
  4. 前端改 `useInfiniteQuery`；加「全部 / 24 小时 / 7 天」筛选与「加载更多」。
- 验证：同上 + typecheck

### TASK-004 | done | 证据上下文：历史同类 + 同期事件（REQ-004/005）

- 对应：`REQ-004`、`REQ-005`、`AC-004`、`AC-005`
- 依赖：无
- 新建：`backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsIncidentContext.java`（新类：历史与同期只服务诊断证据，放进现有服务会同时承担事件状态机与证据格式化）
- 修改：`OpsDiagnoser.java`、`OpsDiagnosisPrompt.java`、`OpsEvidenceCollector.java`、`OpsDiagnosisService.java`、`OpsIncidentMapper.java`、金集与相关测试
- 接口：
  - `OpsDiagnoser.Evidence` 改为 `Evidence(String trigger, String alertName, String question, String summary, String metrics, String baselines, String context, String alerts, String logs, String traces, String deploys)`
  - 产出 `OpsIncidentContext#build(Long incidentId, String alertName, Instant now): String`（≤2000 字符，永不返回 null）
  - `OpsEvidenceCollector.collect(String trigger, String alertName, String question, String context): OpsDiagnoser.Evidence`
  - 产出 `OpsIncidentMapper.listHistoryByFingerprint(...)`、`listHistoryByAlertName(...)`、`listOpenSince(...)`
  - `OpsDiagnosisPrompt.VERSION = "ops-diagnosis-v4"`
- 测试：`OpsIncidentContextTest.historyFormatted`、`emptyHistoryIsExplicit`、`concurrentEventsExcludeSelf`、`OpsEvidenceCollectorTest.contextCountsTowardBudget`、`OpsDiagnosisGoldenSetTest.promptMode`
- 步骤：
  1. 写 `OpsIncidentContextTest` 三条 → 红；实现查询与格式化，空段显式文案 → 绿。
  2. 改 `Evidence` 记录与所有构造点（`OpsEvidenceCollector`、金集 `evidenceOf`、诊断测试）→ 编译通过。
  3. `collect` 增加 `context` 参数并计入 `nonLogChars`；context 自身截断到 2000。
  4. 提示词插入 `<context>` 并升 v4；`system()` 增一条「历史不能当成本次结论」。
  5. 金集测试的证据标签增加 `<context>`。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='OpsEvidenceCollectorTest,OpsIncidentContextTest' -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed；`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest='OpsDiagnosisPromptTest,OpsDiagnosisGoldenSetTest' -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed

### TASK-005 | done | 同实例复用窗口（REQ-006）

- 对应：`REQ-006`、`AC-006`
- 依赖：TASK-004（Evidence 与测试基建）
- 修改：`OpsDiagnosisMapper.java`、`OpsIncidentService.java`、`OpsDiagnosisService.java`、`OpsProperties.java`
- 接口：
  - 产出 `OpsDiagnosisMapper.recentDoneSince(Instant since, int limit): List<RecentDone>`
  - `record RecentDone(long id, Long incidentId, Instant finishedAt, String labelsJson, String alertName)`
  - 产出 `OpsIncidentService.recentDoneDiagnosisForInstance(long incidentId, Instant now, long windowSeconds): Optional<Long>`
  - 配置 `paideia.ops.diagnosis.related-window-seconds`（默认 600，0 关闭）
- 测试：`OpsDiagnosisServiceTest.reuseWithinWindowSkips`、`failedDiagnosisDoesNotSuppress`、`reuseDisabledWhenZero`
- 步骤：
  1. 写三条服务测试 → 红。
  2. 加 mapper 查询与 instance 匹配；命中时追加 NOTE 并返回 -1，不写 `last_diagnosed_at` → 绿。
  3. 在 `start` 中接在事件冷却之后、插入诊断之前。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsDiagnosisServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed

### TASK-006 | done | z-score 基线（REQ-007）

- 对应：`REQ-007`、`AC-007`
- 依赖：TASK-004（证据格式化）
- 修改：`deploy/ops/prometheus-rules.yml`、`deploy/ops/prometheus-rules.test.yml`、`deploy/ops/provisioning/alerting/paideia-rules.yaml`、`OpsEvidenceCollector.java`、`OpsDiagnosisPrompt.java`（规则说明，保持 v4）
- 接口：新增录制序列 `paideia:baseline_{cpu,5xx,p95}:{mean7d,std7d,z}`
- 测试：`OpsEvidenceCollectorTest.baselineIncludesZ`；`promtool test rules` 三组用例
- 步骤：
  1. 加录制规则与分母下限；写 promtool 用例（z>3 / 方差大 / 样本不足）→ 红。
  2. 跑 promtool → 绿。
  3. 证据行追加 `z=` 并补单测。
  4. Grafana 三条基线告警并联 z 条件（保留绝对值门槛）。
- 验证：`scp deploy/ops/prometheus-rules*.yml codenest-online:/tmp/ && ssh codenest-online 'cd /tmp && promtool test rules prometheus-rules.test.yml'` → SUCCESS

### TASK-007 | done | 阈值单一来源（REQ-008）

- 对应：`REQ-008`、`AC-008`
- 依赖：无
- 新建：`deploy/ops/ops-thresholds.conf`、`scripts/check-ops-thresholds.mjs`（仓库内快速校验；安装期还有渲染后检查）
- 修改：`deploy/ops/install-ops.sh`、`deploy/ops/provisioning/alerting/paideia-rules.yaml`
- 接口：三个占位符 `__DISK_FREE_RATIO__`、`__UNIT_MEMORY_RATIO__`、`__BACKUP_STALE_HOURS__`；应用环境变量 `PAIDEIA_OPS_THRESHOLDS_DISKUSEDERROR`、`PAIDEIA_OPS_THRESHOLDS_UNITMEMORYERROR`、`PAIDEIA_OPS_THRESHOLDS_BACKUPSTALEHOURS`
- 测试：`node scripts/check-ops-thresholds.mjs`（正向 0、负向非 0）
- 步骤：
  1. 新建 conf 与校验脚本；脚本检查三个值范围与占位符存在 → 正向 0。
  2. 规则文件三处硬编码换占位符；安装脚本 source、计算、sed、残留检查、写 drop-in。
  3. 负向验证：把 `DISK_USED_ERROR` 改成 `1.5` → 脚本非 0。
- 验证：`node scripts/check-ops-thresholds.mjs` → exit 0；负向见步骤 3

### TASK-008 | done | 重启补诊断（REQ-009）

- 对应：`REQ-009`、`AC-009`
- 依赖：TASK-005（同服务文件）
- 修改：`OpsIncidentMapper.java`、`OpsDiagnosisService.java`、`OpsProperties.java`
- 接口：
  - 产出 `OpsIncidentMapper.findCatchUpCandidate(Instant since, Instant diagnosedBefore): Optional<OpsIncident>`
  - 配置 `paideia.ops.diagnosis.catch-up-enabled`（默认 true）
- 测试：`OpsDiagnosisServiceTest.catchUpPicksOpenWithoutDone`、`catchUpSkipsWhenUserMissing`、`catchUpLimitOnePerStartup`
- 步骤：
  1. 写三条测试（直接调监听方法）→ 红。
  2. 实现查询与启动钩子；`user-id<=0` 或开关关时直接返回 → 绿。
  3. 时间线写「重启补诊断」。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsDiagnosisServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed

### TASK-009 | done | 金集扩到 24 条（REQ-010/011）

- 对应：`REQ-010`、`REQ-011`、`AC-010`、`AC-011`
- 依赖：TASK-004（context 字段）
- 修改：`backend/paideia-ai/src/test/resources/ops-eval/golden-set.json`、`OpsDiagnosisGoldenSetTest.java`、`.agent/notes/dev-workflow.md`（live 命令）
- 接口：无
- 测试：`OpsDiagnosisGoldenSetTest.promptMode`（24 条）、`goldenSetIsWideEnough`
- 步骤：
  1. 新增 12 条证据快照（8 类告警 + 5 类降级，允许一条覆盖两类），每条 `mustMention` ≥2 个可核对事实。
  2. `goldenSetIsWideEnough` 阈值改 20。
  3. 跑 prompt 模式全绿；live 模式在有 Key 的环境跑一次并把报告路径写进收口报告。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=OpsDiagnosisGoldenSetTest -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed（live 跳过属预期，除非显式开启）

### TASK-010 | done | 事件页 Grafana 深链（REQ-013）

- 对应：`REQ-013`、`AC-013`
- 依赖：无
- 新建：`frontend/apps/admin/src/pages/incidents-links.ts`（纯函数，便于单测）
- 修改：`IncidentsPage.tsx`
- 接口：`grafanaLinks(incident: {firstSeen: string; lastSeen: string; resolvedAt: string | null}): { panel: string; logs: string }`
- 测试：`incidents-links.test.ts`（无效日期回退、时间窗 ±15 分钟）
- 步骤：
  1. 写纯函数与测试 → 红/绿（若 admin 包无 vitest，则先确认测试入口再决定是否落纯函数）。
  2. 事件详情头部加入两个 `a` 链接，新窗口打开。
- 验证：`pnpm --filter @paideia/admin typecheck`；若可用则 `pnpm --filter @paideia/admin test` → 0 failed

### TASK-011 | done | 日志单元可配置（REQ-014）

- 对应：`REQ-014`、`AC-014`
- 依赖：无
- 修改：`OpsProperties.java`、`OpsEvidenceCollector.java`
- 接口：`OpsProperties.Diagnosis.getUnits(): List<String>`；配置 `paideia.ops.diagnosis.units`（默认 `[paideia, laya]`）
- 测试：`OpsEvidenceCollectorTest.customUnitIsWhitelisted`、`unknownUnitRejected`
- 步骤：
  1. 写两条测试 → 红。
  2. 把 switch 改为列表遍历与 `contains` 校验；日志总行数仍受 `maxLogLines` 限制 → 绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsEvidenceCollectorTest -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed

### TASK-012 | done | 收口：回归、部署与生产证据（AC-001/008/011/012/015）

- 对应：`AC-001`、`AC-008`、`AC-011`、`AC-012`、`AC-015`
- 依赖：TASK-001..011
- 修改：`testing/plan.md`、`testing/report.md`
- 接口：无
- 测试：下列命令与生产步骤
- 步骤：
  1. 内环：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='Ops*Test' -Dsurefire.failIfNoSpecifiedTests=false test`；`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`。
  2. 前端：admin typecheck + `vite build`；学习者端不受影响，不跑全量。
  3. 部署：在干净工作树执行 `powershell -File scripts/release.ps1`，再在服务器跑 `install-ops.sh`（渲染阈值、装收件人）。
  4. 生产 AC-001：合成 firing → resolved → firing，核对 2 个事件与时间线。
  5. 生产 AC-008：改一个阈值 → 重启两侧 → 摘要与规则一致；恢复。
  6. 生产 AC-012：维护窗口把某条 SLO 阈值临时降到必触发 → 事件 → 诊断 DONE → 恢复；记录时间戳与命令。
  7. AC-011：在有 Key 的环境跑 live 金集，报告路径写进 `testing/report.md`。
- 验证：以上命令全部 0 failed，生产证据写进 `testing/report.md`

## 完成条件

- 12 个任务全部 `done`，`tasks.md` 状态同步。
- `testing/report.md` 通过并附 AC-001/008/011/012 的生产或环境证据；未能执行的项如实写「未覆盖」。
- `project-lifecycle validate .` 无本项错误；`brief --json` 显示 `verified`/已完成。
- 只在干净工作树发版；不带着其他会话的在飞改动部署。
