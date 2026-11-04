---
artifact: tasks
work_id: WORK-069
work: AI 失败的处置语义
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 14:14:25 +0800
approver_role: owner
---

# AI 失败的处置语义实施任务

## 依据

- 已确认需求：`requirements.md`（REQ-001..REQ-005 / AC-001..AC-005）。
- 方案与设计：`proposal.md`、`design.md`。
- 实施前必须把分支 rebase 到 master `3d834141`（含 WORK-070），并先处理 `WORK-069` requirements 的确认与签署。

## 全局约束

- 迁移文件名使用 14 位时间戳；不改 `V21`，只新增迁移。
- `failure_kind` 只写低基数枚举，不写异常消息、提示词或正文。
- `failure_kind` 不加索引、不加 CHECK；历史行不回填。
- 记忆提取之外的两条链路只记录，不改变现有重试行为。
- 所有碰库测试走 `scripts/test-db.sh`，不直接连共用 `paideia_test`。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| `failed` 终态没有 `failure_kind`（包括未显式分类的旧调用） | TASK-001 | `DefaultRunRecorderTest.failedOutcomeGetsRetryableFallback` |
| 记忆不可重试失败仍被中继重新拾取 | TASK-002 | `MemoryExtractionJobsIntegrationTest.permanentFailureStopsRetry` |
| `retryable` 分支被误改成永久失败 | TASK-002 | `MemoryExtractionJobsIntegrationTest.retryableFailureStillBacksOff` |
| 营地 `EMPTY_RESPONSE` 被映射成 `bad_output` | TASK-003 | `CampServiceTest.emptyResponseIsFailure` |
| 面经抓取失败被当成单篇抽取失败 | TASK-004 | `EssayCollectionFailureKindTest.fetchFailureIsRetryable` |
| 面经单篇失败导致整次抓取中止 | TASK-004 | `EssayCollectionFailureKindTest.perPostFailureIsBadOutputAndDoesNotAbortRun` |
| 管理端筛选 `failureKind` 后总数与列表不一致 | TASK-005 | `AiObservabilityIntegrationTest.failureKindFilterReturnsOnlyMatchingRows` |
| 迁移在零数据库/已有数据库上行为不一致 | TASK-001 | `MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest` |

## 任务

### TASK-001 | done | 数据模型与采集管道

- 对应：`REQ-001`、`AC-001`
- 依赖：无
- 新建/修改：
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V20261104142000__add_ai_runs_failure_kind.sql`
  - 新建 `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/FailureKind.java`
  - 修改 `RunRecorder.java`、`RunRecord.java`、`DefaultRunRecorder.java`、`MysqlRunSink.java`、`AiRunMapper.java`
- 接口：
  ```java
  public enum FailureKind {
      RETRYABLE, BAD_OUTPUT, NEEDS_HUMAN;
      public String key();
      public boolean isRetryable();
  }
  // RunRecorder.RunScope
  default void finish(RunOutcome outcome, String errorType, FailureKind failureKind);
  ```
- 步骤：
  1. 新建迁移，只执行 `ALTER TABLE ai_runs ADD COLUMN failure_kind VARCHAR(16) NULL AFTER error_type;`；不写数据、不加索引/CHECK。
  2. 新建 `FailureKind`，实现 `key()` 与 `isRetryable()`。
  3. `RunRecord` 增加 `failureKind` 字段并更新 `running(...)` 构造；`DefaultRunRecorder.snapshot(...)` 接收并写入。
  4. `RunScope` 加默认三参 `finish`；`DefaultRunRecorder.RunScopeImpl` 覆盖它；两参 `finish` 对 `FAILED`/`TIMEOUT`/`INTERRUPTED` 兜底 `RETRYABLE`，`OK`/`CANCELLED` 保持 `null`。
  5. `MysqlRunSink.toRow` 与 `AiRunMapper` 的 UPDATE/SELECT 增加 `failure_kind`。
  6. 用 `-Dmigration.checksums.update=true` 跑 `MigrationChecksumTest` 登记新迁移，review `migration-checksums.txt` diff。
- 测试：`DefaultRunRecorderTest` 新增 `failedOutcomeGetsRetryableFallback`、`explicitFailureKindWins`；迁移三守卫。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=DefaultRunRecorderTest test` → 期望全部通过；`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望通过。

### TASK-002 | done | 记忆提取失败分流

- 对应：`REQ-002`、`AC-002`
- 依赖：TASK-001
- 新建/修改：
  - 新建 `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/memory/MemoryOutputException.java`
  - 新建 `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/memory/MemoryFailureClassifier.java`
  - 修改 `MemoryCandidateParser.java`、`MemoryExtractionJobs.java`、`MemoryExtractionJobMapper.java`、`MemoryExtractionService.java`
- 接口：
  ```java
  // MemoryFailureClassifier
  static FailureKind classify(Throwable failure);
  // MemoryExtractionJobs
  public void failPermanently(long jobId, String reason);
  // MemoryExtractionJobMapper
  int failPermanently(@Param("id") long id, @Param("lastError") String lastError);
  ```
- 步骤：
  1. 新建 `MemoryOutputException extends BizException`（`ErrorCode.INTERNAL`），把 `MemoryCandidateParser.failure(...)` 改抛它。
  2. 新建 `MemoryFailureClassifier`：`MemoryOutputException → BAD_OUTPUT`；`BizException(ErrorCode.INVALID_ARGUMENT) → NEEDS_HUMAN`；其余 `RETRYABLE`。
  3. `MemoryExtractionJobMapper` 增加 `failPermanently`（`WHERE id = #{id} AND status = 'running'`，只改 `status/last_error/locked_at`，不动 `attempts/next_attempt_at`）。
  4. `MemoryExtractionJobs` 增加 `failPermanently`。
  5. `MemoryExtractionService.run` 的 `catch (RuntimeException failure)` 先分类：`retryable` 调 `jobs.fail(...)`，否则调 `jobs.failPermanently(...)`；`run.finish(FAILED, "extraction_failed", kind)`。
- 测试：`MemoryFailureClassifierTest`；`MemoryExtractionJobsIntegrationTest` 新增 `permanentFailureStopsRetry`、`retryableFailureStillBacksOff`；反向验证：把 `failPermanently` 临时改回 `fail`，`permanentFailureStopsRetry` 必须变红。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=MemoryFailureClassifierTest test`；`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MemoryExtractionJobsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。

### TASK-003 | done | 营地出题失败映射

- 对应：`REQ-003`、`AC-003`
- 依赖：TASK-001
- 修改：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/camp/CampGenerationException.java`、`CampService.java`
- 接口：
  ```java
  // CampGenerationException
  public FailureKind failureKind();
  ```
- 步骤：
  1. `failureKind()` 按 `EMPTY_RESPONSE → NEEDS_HUMAN`、`BAD_SHAPE → BAD_OUTPUT`、`NO_VALID_ITEM → BAD_OUTPUT` 实现。
  2. `CampService.create` 的 catch 拆成 `CampGenerationException` 与其它 `RuntimeException` 两路；前者用 `failure.failureKind()`，后者交给采集层兜底。
  3. `CampService.retest` 同样处理。
- 测试：`CampServiceTest.emptyResponseIsFailure`、`badShapeMapsToBadOutput`、`bothRoundsBadRefunds`，并断言 `run.finish(...)` 的三参值。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=CampServiceTest test`。

### TASK-004 | done | 面经抓取失败的 `failure_kind`

- 对应：`REQ-004`、`AC-004`
- 依赖：TASK-001
- 修改：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayCollectionService.java`、`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/RunKind.java`
- 接口：
  ```java
  public static final String ESSAY_COLLECT = "essay_collect";
  public static final String ESSAY_EXTRACT = "essay_extract";
  ```
- 步骤：
  1. `EssayCollectionService` 构造注入 `RunRecorder`。
  2. `runOnce` 的 `source.fetchSince` 外包 `RunSpec.of(ESSAY_COLLECT, 0L)`；捕获 RuntimeException 时 `finish(FAILED, "essay_fetch_failed", RETRYABLE)`，保持原有返回 `RunResult.skipped("抓取失败")`。
  3. 每篇帖子调用 `extractor.extract` 时用 `RunSpec.of(ESSAY_EXTRACT, 0L).withModel(...).withPromptVersion(EssayExtractionPrompt.VERSION)` 包一层；异常时 `finish(FAILED, "essay_extract_failed", BAD_OUTPUT)`，仍 `failed++` 并继续下一帖。
  4. 不新增重试、不改 `RunResult` 结构。
- 测试：新建 `EssayCollectionFailureKindTest`（mock `EssaySource`/`EssayExtractor`/`KnowledgeApi`），覆盖 `fetchFailureIsRetryable`、`perPostFailureIsBadOutputAndDoesNotAbortRun`、`successfulExtractionWritesOkRun`。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=EssayCollectionFailureKindTest test`；`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=EssayCollectionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。

### TASK-005 | done | 管理端查询与计数

- 对应：`REQ-005`、`AC-005`
- 依赖：TASK-001
- 修改：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/telemetry/AiRunAdminService.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/telemetry/TelemetrySummaryMapper.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/web/AiRunAdminController.java`
  - `frontend/apps/admin/src/pages/AiRunsListPage.tsx`、`AiRunsOverviewPage.tsx`
- 接口：
  ```java
  // AiRunAdminService.RunSummary
  record RunSummary(..., String status, String errorType, String failureKind, ...)
  // AiRunAdminService.Overview
  record Overview(..., List<TelemetrySummaryMapper.CountBy> byFailureKind, ...)
  // AiRunAdminService.page(...)
  PageResult<RunSummary> page(..., String failureKind, String tracePrefix, PageQuery query);
  ```
- 步骤：
  1. `AiRunMapper.page/countPage` 增加 `failureKind` 条件；`RunSummary` 增加 `failureKind`。
  2. `TelemetrySummaryMapper` 增加 `countByFailureKind`（与其它排行同一组筛选），`Overview` 增加 `byFailureKind`。
  3. `AiRunAdminController` 的列表增加可选 `failureKind` 参数；`meta` 返回 `failureKinds`；概览不新增筛选参数，只增加 `byFailureKind`。
  4. `AiRunsListPage` 加筛选下拉（`retryable` / `bad_output` / `needs_human`）与列标签，并在 `KIND_LABEL` 补 `essay_collect` / `essay_extract`；`AiRunsOverviewPage` 加「按失败处置」排行卡。
- 测试：`AiObservabilityIntegrationTest.failureKindFilterReturnsOnlyMatchingRows`（同时断言 `byFailureKind` 计数）；前端 `pnpm --filter @paideia/admin typecheck`；`ai-runs.spec.ts`（若可运行）只跑该文件。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AiObservabilityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`；`cd frontend && pnpm --filter @paideia/admin typecheck`。

### TASK-006 | done | 收口与证据

- 对应：全部 AC
- 依赖：TASK-001..TASK-005
- 修改：`.agent/changes/WORK-069-AI失败处置语义/testing/plan.md`、`testing/report.md`、`.agent/history/updates.md`
- 步骤：
  1. 按 `testing/plan.md` 写 AC-001..AC-005 的结构化证据表（检查项 / 命令 / 退出码 / 结果 / 证据）。
  2. 记录迁移登记 diff、后端切片测试、前端类型检查、e2e 结果与截图（如执行）。
  3. 把验证报告 `verified_commit` 绑定到实际提交哈希；未执行项如实写为未覆盖。
  4. 更新 `.agent/history/updates.md`（按 `references/update-history.md` 的口径），不写凭据。
- 验证：`project-lifecycle.ps1 validate . --strict`（或历史脚本等价命令）→ 0 error；报告状态与提交哈希一致。

## 完成条件

- `failure_kind` 迁移在零数据新库与已有数据库都能跑通，三件迁移守卫全绿。
- 记忆提取不可重试失败不再退避/增加 attempts，`retryable` 仍按既有退避；反向验证实测变红后还原复绿。
- 营地三种 `Kind` 映射正确；面经两条失败路径写入正确 `failure_kind` 且不重试。
- 管理端可以按 `failure_kind` 筛选，概览计数与列表总数一致。
- 所有验证结果写入 `testing/report.md`，并绑定实际提交哈希。
