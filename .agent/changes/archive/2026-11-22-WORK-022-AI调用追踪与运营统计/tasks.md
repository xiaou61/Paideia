---
artifact: tasks
work_id: WORK-022
work: AI 调用追踪与运营统计
status: approved
workflow: full
depends_on: [requirements, proposal, design]
related_to: [WORK-006, WORK-009, WORK-010, WORK-013, WORK-019]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 17:54:59 +0800
approver_role: CTO
---

# AI 调用追踪与运营统计实施任务

## 依据

- `requirements.md`（REQ-001～REQ-016、AC-001～AC-017）
- `proposal.md`（端口 + sink、一行一轮 + 一行一步、trace_id 关联、先插 running、按轮采样、不碰计费流水）
- `design.md`（两张表结构、端口签名、七个 `attrs` 键、四个管理端点、分位数算法、清理与采样）

## 全局约束

- 迁移编号取执行时的下一个（当前 V21），**不写死**；新增迁移后按 `MigrationChecksumTest` 的提示用 `-Dmigration.checksums.update=true` 登记校验和。
- 时间全部由应用传 `Instant`（UTC），不写 `DEFAULT CURRENT_TIMESTAMP`；SQL 只写 mapper。
- 采集端口在 `paideia-ai` 包根，签名里**不得出现**任何 Spring / AgentScope / MyBatis 类型。
- 管理端点位于 `/api/v1/ai/admin/**`（既有 `hasRole("ADMIN")` 规则已覆盖），类上标 `@ApiAccess(ApiAccess.Access.ADMIN)`。
- **调用方线程零 IO**：`begin` / `step` / `finish` / `close` 里不得出现数据库、日志以外的任何阻塞动作。
- 不新增第三方依赖。
- `attrs` 只接受设计里列出的七个键，其它键忽略。
- 测试不依赖本地种子的具体内容。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 采集失败导致回答失败（sink 抛异常冒泡到业务线程） | TASK-003 | `sinkFailureDoesNotBreakTurn` |
| 队列满时阻塞用户提问 | TASK-003 | `fullQueueDropsWithoutBlocking` |
| `finish` 与 `close` 都被调用时写两条终态 | TASK-002 | `finishAndCloseWriteOnce` |
| 异常路径完全不留记录 | TASK-002 | `closeWithoutFinishRecordsInterrupted` |
| trace_id 取自客户端可伪造的 `X-Trace-Id` | TASK-002 | `traceIdIsServerGenerated` |
| 工具失败被吞成文本后步骤仍记 ok | TASK-004 | `failedToolStepIsMarkedFailed` |
| 多轮上游只记最后一轮的 token | TASK-004 | `perRoundTokensSumToRunTotal` |
| 步骤行用自增 run_id 关联导致插入顺序依赖 | TASK-003 | `spansInsertedByTraceIdWithoutIdRoundTrip` |
| 采样把失败轮次也丢掉 | TASK-003 | `samplingNeverDropsFailures` |
| 采样丢弃后 running 行残留 | TASK-003 | `sampledOutRunLeavesNoRow` |
| 异步线程丢失 MDC，日志对不上 | TASK-005 | `mdcSurvivesExecutorHop` |
| 结算金额不外传导致 credits 恒为 0 | TASK-006 | `creditsMatchLedger` |
| 七条路径漏接 | TASK-007 | `allSevenKindsProduced` |
| 注销后步骤行残留 | TASK-008 | `deletionRemovesRunsAndSpans` |
| 僵尸 running 行永不处理 | TASK-009 | `stuckRunsMarkedInterrupted` |
| 概览 p95 与手算不一致 | TASK-010 | `percentilesMatchManualComputation` |
| 统计响应带出正文或工具参数 | TASK-011 | `detailNeverExposesContent` |
| 非管理员可读统计 | TASK-011 | `nonAdminForbidden` |
| 时间窗无上限导致全表扫 | TASK-010 | `windowClampedToRetention` |
| 管理端空数据白屏 | TASK-012 | `emptyOverviewShowsPlaceholder` |

## 任务

### TASK-001 | done | 两张表迁移与数据访问

- 对应：`REQ-001`、`REQ-003`
- 依赖：无
- 新建/修改：
  - `backend/paideia-app/src/main/resources/db/migration/V21__create_ai_telemetry_tables.sql`（新建）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/telemetry/AiRunMapper.java`（新建）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/telemetry/AiRunSpanMapper.java`（新建）
- 接口：
  - `AiRunMapper.insertRunning(AiRunRow row): int`
  - `AiRunMapper.finish(AiRunRow row): int`（按 `trace_id` 更新终态字段）
  - `AiRunMapper.deleteRunning(String traceId): int`（采样丢弃用，带 `status='running'` 条件）
  - `AiRunMapper.findByTraceId(String traceId): AiRunRow`
  - `AiRunMapper.countByWindow(Instant from, Instant to): long`
  - `AiRunMapper.page(Instant from, Instant to, String kind, String status, Long userId, String model, PageQuery page): List<AiRunRow>`
  - `AiRunMapper.countPage(...): long`（参数同上，不含 `PageQuery`）
  - `AiRunSpanMapper.insertBatch(List<AiRunSpanRow> rows): int`
  - `AiRunSpanMapper.findByTraceId(String traceId): List<AiRunSpanRow>`（`ORDER BY seq`）
- 测试：`MigrationChecksumTest` 通过
- 步骤：
  1. 写迁移，两表与外键/索引与 `design.md` 逐字一致；
  2. 起一次测试库让 Flyway 跑到 V21，`SHOW CREATE TABLE` 核对索引与列类型；
  3. 写两个 mapper，`attrs` 列按既有约定以 `String` 收写、以 Jackson 解析（与 `token_usage` 同一做法，注意**显式起别名**）；
  4. 跑 `mvn -pl backend/paideia-app -am test -Dtest=MigrationChecksumTest` 并按提示登记校验和。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=MigrationChecksumTest` → 1 passed

### TASK-002 | done | 采集端口与内存实现

- 对应：`REQ-001`、`REQ-002`、`REQ-005`、`AC-001`、`AC-005`
- 依赖：无
- 新建：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/RunRecorder.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/RunSpec.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/RunOutcome.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/RunKind.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/telemetry/RunRecord.java`
  - `.../internal/telemetry/SpanRecord.java`
  - `.../internal/telemetry/DefaultRunRecorder.java`
  - `.../internal/telemetry/NoopRunRecorder.java`
  - `.../internal/telemetry/TelemetryErrorTypes.java`
- 测试：`backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/telemetry/DefaultRunRecorderTest.java`
- 接口：与 `design.md`「端口」「快照」两节逐字一致；`RunKind` 常量七个。
- 步骤：
  1. 先写测试：`finishAndCloseWriteOnce`、`closeWithoutFinishRecordsInterrupted`、`traceIdIsServerGenerated`、`unknownAttributeKeyIgnored`、`perRoundTokensAccumulate`，跑红；
  2. 实现 `RunRecorder` 及其两个实现，跑绿；
  3. `attribute()` 只接受白名单键，其它打 debug 并丢弃；
  4. `TelemetryErrorTypes.of(Throwable)` 返回低基数标识，测试断言返回串里不含异常 message。
- 验证：`mvn -pl backend/paideia-ai test -Dtest=DefaultRunRecorderTest` → 6 passed

### TASK-003 | done | 写队列与 MySQL sink

- 对应：`REQ-003`、`REQ-006`、`REQ-016`、`AC-002`、`AC-007`、`AC-008`、`AC-015`
- 依赖：TASK-001、TASK-002
- 新建：
  - `.../internal/telemetry/RunSink.java`
  - `.../internal/telemetry/MysqlRunSink.java`
  - `.../internal/telemetry/RunWriteQueue.java`
  - `.../internal/telemetry/TelemetryProperties.java`
  - `.../internal/telemetry/TelemetryConfiguration.java`（`@ConfigurationProperties` + 按开关装配 recorder）
- 测试：`RunWriteQueueTest`
- 接口：
  - `RunSink.accept(RunPayload payload): void`；`RunPayload.RunBegin(RunRecord run)` / `RunPayload.RunFinish(RunRecord run)`
  - `RunWriteQueue.offer(RunPayload payload): boolean`（满返回 false）
  - `RunWriteQueue.dropped(): long`、`writeFailures(): long`
  - 配置键：`paideia.ai.telemetry.enabled`（true）、`.sampling`（1.0）、`.retention-days`（90）、`.queue-capacity`（10000）、`.stuck-after-hours`（6）、`.batch-size`（50）
- 步骤：
  1. 写 `MysqlRunSink`：`RunBegin` → `insertRunning`；`RunFinish` → 采样判定 →（丢弃则 `deleteRunning`，否则 `finish` + `insertBatch`）；
  2. 写 `RunWriteQueue`：`ArrayBlockingQueue` + 单线程 `@PostConstruct` 启动 + `@PreDestroy` 排空；异常逐条吞；
  3. 测试 `fullQueueDropsWithoutBlocking`（容量 1 时连投 3 条，第三条 `offer` 返回 false 且调用耗时 <100ms）、`sinkFailureDoesNotBreakTurn`（sink 抛异常，后续投递仍成功）、`samplingNeverDropsFailures`、`sampledOutRunLeavesNoRow`；
  4. 接线开关：`enabled=false` 时装配 `NoopRunRecorder` 并 WARN 一次。
- 验证：`mvn -pl backend/paideia-ai test -Dtest=RunWriteQueueTest` → 4 passed

### TASK-004 | done | 对话链路埋点

- 对应：`REQ-001`、`REQ-003`、`REQ-011`、`AC-001`、`AC-002`、`AC-003`
- 依赖：TASK-002、TASK-003
- 修改：
  - `.../internal/web/AssistantConversationController.java`（`send` 里 `begin` 并把 scope 传进 `runTurn`；`runTurn` 里记 TTFT、finish、close）
  - `.../internal/conversation/AgentScopeConversation.java`（`stream` 里每轮一个 `llm` 步骤；`execute` 里每个工具一个 `tool` 步骤；签名加一个 `RunRecorder.RunScope` 参数）
  - `.../internal/tools/AgentToolBridge.java`（`Outcome` 已带 `error()`，无需改签名）
  - `.../AssistantConversation.java`（`stream` 加 `RunScope` 参数——端口签名变化，见下方说明）
- 接口：
  - `AssistantConversation.stream(Request request, ModelAccess access, RunRecorder.RunScope run, Listener listener)`
  - 步骤名：`llm#1`…`llm#4`、工具名原样
- 步骤：
  1. 先写集成测试 `AiTelemetryIntegrationTest`：用桩模型产生"一轮要工具、一轮出正文"，断言 `ai_runs` 1 行、`ai_run_spans` ≥3 行、token 合计一致、`status=ok`、`ttft_ms` 非空；
  2. 改 `AssistantConversation` 端口签名，所有实现与测试桩同步；
  3. 控制器 `send` 里 `begin`（此时还没有排队信息，`started_at` 就是提交时刻）；`runTurn` 第一行写 `attribute("queued", true)` 并用 `System.nanoTime` 差值算排队时间；
  4. 第一个 `onContent` 记 TTFT；
  5. `onCompleted` / `onFailed` 调 `finish`，`finally` 调 `close`；
  6. `ConversationService.prepare` 的历史条数、记忆条数、条目字数、学情字数由控制器转写成 `attribute`；
  7. `failedToolStepIsMarkedFailed`：桩一个抛异常的工具，断言该步 `status=failed`、`error_type` 非空；
  8. `perRoundTokensSumToRunTotal`：两轮分别 10/5 与 20/8，断言 run 为 30/13 且两个步骤各自正确。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AiTelemetryIntegrationTest` → 6 passed

### TASK-005 | done | MDC 传播与日志对齐

- 对应：`REQ-012`、`AC-016`
- 依赖：无
- 新建：
  - `backend/paideia-web/src/main/java/io/github/xiaou61/web/MdcTaskDecorator.java`
  - `backend/paideia-app/src/main/resources/logback-spring.xml`
- 修改：
  - `backend/paideia-app/src/main/java/io/github/xiaou61/PaideiaApplication.java` 或既有 Web 配置（`TaskDecorator` 挂到 `applicationTaskExecutor`）
- 接口：
  - `MdcTaskDecorator implements TaskDecorator`：`decorate` 时复制 `MDC.getCopyOfContextMap()`，运行后恢复原值
  - 日志格式含 `%X{traceId:-}` 与 `%X{aiTraceId:-}`
- 步骤：
  1. 写 decorator 与单测 `mdcSurvivesExecutorHop`：主线程 put 两个键，提交任务，任务内断言能看到两个键，结束后主线程 MDC 未被污染；
  2. 挂到 `applicationTaskExecutor`（Boot 4 的 `TaskExecutorBuilder.customizers(b -> b.taskDecorator(...))`）；
  3. 加 `logback-spring.xml` 控制台 pattern；
  4. 控制器在 `runTurn` 开头 `MDC.put("aiTraceId", traceId)`、`finally` 移除。
- 验证：`mvn -pl backend/paideia-web test -Dtest=MdcTaskDecoratorTest` → 2 passed；集成测试里一次对话的日志能 grep 到两个键

### TASK-006 | done | 结算金额外传

- 对应：`REQ-001`（实扣积分）、`AC-004`
- 依赖：无
- 修改：`.../internal/billing/CreditService.java`（`settle` / `settleAs` / `settleExternalAsk` 返回 `int` 实扣金额）
- 步骤：
  1. 三个方法把内部算出的 `charged` 返回出去（语义不变，只加返回值）；
  2. 既有调用方忽略返回值即可编译通过；`AssistantConversationController.settleOnce` 把返回值存进 `turn` 可读的位置供 `scope.attribute("credits", ...)`；
  3. 集成测试 `creditsMatchLedger`：一轮对话后 `ai_runs.credits` 等于同一 `ref_message_id` 上的流水差额绝对值。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AiCreditIntegrationTest,AiTelemetryIntegrationTest` → 全绿

### TASK-007 | done | 其余六条路径接入

- 对应：`REQ-004`、`AC-006`
- 依赖：TASK-002、TASK-003
- 修改：
  - `.../internal/memory/MemoryExtractionService.java`（`kind=memory_extract`）
  - `.../internal/interview/InterviewReviewService.java`（`kind=interview_review`）
  - `.../internal/tools/AskTool.java`（`kind=mcp_ask`）
  - `.../internal/resume/ResumeImportService.java`（`kind=resume_import`）
  - `.../internal/resume/ResumeOptimizationService.java`（`kind=resume_optimize`）
  - `.../internal/summary/SummaryService.java`（`kind=entry_summary`）
- 步骤：
  1. 每处用一个 `try (var run = recorder.begin(...))` 包住原有逻辑，结束前 `tokens(...)` 与 `credits(...)`，成功 `finish(OK, null)`；
  2. 各处的 `refund` 分支也要 `finish(FAILED, ...)`，不能留 running；
  3. 写 `AiTelemetryPathsIntegrationTest`：七条路径各触发一次，`GROUP BY kind` 得到七个值。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AiTelemetryPathsIntegrationTest` → 1 passed（内含七条断言）

### TASK-008 | done | 注销级联

- 对应：`REQ-013`、`AC-013`
- 依赖：TASK-001
- 修改：
  - `.../internal/user/AiUserDeletionListener.java`（表清单加 `ai_runs`；另加一条按 `trace_id` 子查询删 `ai_run_spans`）
  - `backend/paideia-app/src/test/java/io/github/xiaou61/AccountDeletionCascadeIntegrationTest.java`（表清单加两行断言）
- 步骤：
  1. 先删步骤再删轮次（顺序写进代码注释：`ai_run_spans` 没有 `user_id`）；
  2. 加注销测试：造两轮记录与步骤，注销后两表该用户行数为 0，他人行数不变；
  3. **反向验证**：临时去掉那两行，确认测试变红，再恢复。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AccountDeletionCascadeIntegrationTest` → 全绿

### TASK-009 | done | 保留期清理与僵尸行

- 对应：`REQ-015`、`AC-014`
- 依赖：TASK-001
- 新建：`.../internal/telemetry/TelemetryRetentionPurger.java`
- 接口：`@Scheduled(fixedDelay = 3600_000)` 的 `purge()`；每批 500 行，直到删完
- 步骤：
  1. 先删该批 trace 的步骤行，再删轮次行；
  2. 另一条：`UPDATE ai_runs SET status='interrupted', error_type='stuck' WHERE status='running' AND started_at < now - stuckAfterHours`；
  3. 测试 `stuckRunsMarkedInterrupted` 与保留期清理；
  4. 每次打 `ai.telemetry.purged rows=N`。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AiTelemetryRetentionIntegrationTest` → 2 passed

### TASK-010 | done | 管理端统计端点

- 对应：`REQ-008`、`REQ-009`、`REQ-010`、`REQ-014`、`AC-009`、`AC-010`、`AC-011`、`AC-012`
- 依赖：TASK-001
- 新建：
  - `.../internal/telemetry/AiRunAdminService.java`
  - `.../internal/telemetry/TelemetrySummaryMapper.java`（聚合查询）
  - `.../internal/web/AiRunAdminController.java`
- 接口：
  - `GET /api/v1/ai/admin/runs/overview?from=&to=`
  - `GET /api/v1/ai/admin/runs?from=&to=&kind=&status=&userId=&model=&page=&size=`
  - `GET /api/v1/ai/admin/runs/{traceId}`
  - `GET /api/v1/ai/admin/runs/meta?from=&to=`
- 步骤：
  1. 时间窗归一化：缺省 `to=now`、`from=now-7d`；跨度超过 `retentionDays` 时收敛并置 `clamped=true`；
  2. 概览用设计里的窗口函数算 p50/p95；
  3. 列表复用 `PageQuery` / `PageResult`；
  4. 详情按 `trace_id` 取轮次 + 按 `seq` 取步骤，`attrs` 解析成 Map；
  5. 测试：`percentilesMatchManualComputation`、`windowClampedToRetention`、`nonAdminForbidden`、`detailNeverExposesContent`（用含长上下文的记录，断言响应串里不含任何 prompt 片段）。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AiTelemetryAdminIntegrationTest` → 5 passed

### TASK-011 | done | 管理端两个页面

- 对应：`REQ-008`、`REQ-009`、`REQ-010`、`AC-017`
- 依赖：TASK-010
- 新建：
  - `frontend/apps/admin/src/pages/AiRunsOverviewPage.tsx`
  - `frontend/apps/admin/src/pages/AiRunsListPage.tsx`
- 修改：
  - `frontend/apps/admin/src/router.tsx`（`/ai-runs` 转向 `/ai-runs/overview`；两条路由）
  - `frontend/apps/admin/src/components/AdminShell.tsx`（侧栏加「AI 调用」父项与两个子项；面包屑两条）
- 步骤：
  1. 概览页：时间窗选择（近 1 天 / 7 天 / 30 天）+ 六张卡片 + 三个排行表；
  2. 列表页：四个筛选 + `Table` + 分页；点行进入详情（抽屉或独立路由，与既有模式一致）；
  3. 详情展示步骤表与 `attrs`；
  4. 无数据时显示空状态文案，不白屏；
  5. 写 e2e `ai-runs.spec.ts`：概览卡片、筛选、详情步骤表、空状态。
- 验证：`pnpm --filter @paideia/admin test:e2e -- ai-runs.spec.ts` → 4 passed

### TASK-012 | done | 配置、文档与收尾

- 对应：`REQ-016`、`AC-007`、`AC-015`
- 依赖：TASK-003、TASK-010
- 修改：
  - `backend/paideia-app/src/main/resources/application.yml`（`paideia.ai.telemetry.*` 六项，含注释说明默认值）
  - `.agent/notes/dev-workflow.md`（新增「AI 调用追踪」一节：表结构、开关、如何按 traceId 排查、保留期与采样）
- 步骤：
  1. 配置项写进 `application.yml` 并注明"关掉不删历史数据"；
  2. 关掉开关跑一次对话，断言无新行且业务正常（`AC-007`）；
  3. 采样设为 0，失败轮仍留（`AC-015`）；
  4. 把"新增 AI 路径要做什么（三行代码）"写进 dev-workflow，避免下次又从头设计。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AiTelemetryIntegrationTest` 重跑全绿；手工核对配置项生效

## 完成条件

- AC-001～AC-017 每条都能在 `testing/report.md` 里指到一次实际执行（命令 + 结果）。
- 注销级联有反向验证记录（去掉那两行会红）。
- 一次带工具对话的 `mvn test` 输出里有 `ai_runs` / `ai_run_spans` 的真实行数与 token 合计。
- 管理端两个页面在线上数据（或造出来的等价数据）下各打开一次，空状态与有数据两种形态都看过。
- `ai_runs.credits` 与积分流水在同一时间窗内可对账，差值有解释。
