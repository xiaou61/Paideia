---
artifact: design
work_id: WORK-069
work: AI 失败的处置语义
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 14:14:25 +0800
approver_role: owner
---

# AI 失败的处置语义设计

> 实施前先把本分支 rebase 到 master `3d834141`（含 WORK-070）；当前分支基线 `20293f74` 还没有 WORK-070 的 `TechEssayFilter.Verdict`，按旧基线改会与线上代码冲突。

## 系统上下文

`ai_runs` 是 WORK-022 建立的 AI 调用遥测表，由 `RunRecorder` 在业务线程零 IO 记录、`RunWriteQueue` 异步写库。当前 `run.finish(RunOutcome, errorType)` 只有「技术上怎么了」，没有「失败之后该怎么办」。本项在 `ai_runs` 上加一列 `failure_kind`，并在采集端口上扩展一个带处置语义的 `finish` 重载。

三条链路：

- `MemoryExtractionService.run`：异步记忆整理，现有 `MemoryExtractionJobs` 负责 `pending → running → pending/failed`、退避与上限。
- `CampService.create/retest`：训练营出卷与复测卷，`CampItemParser` 抛 `CampGenerationException` 三档。
- `EssayCollectionService.runOnce`：每日面经抓取，当前**没有任何 `RunRecorder` 记录**，本项新增采集。

## 组件与职责

| 组件 | 职责 |
| --- | --- |
| `io.github.xiaou61.ai.FailureKind` | 公开端口枚举：`RETRYABLE` / `BAD_OUTPUT` / `NEEDS_HUMAN`；`key()` 与 `isRetryable()`。 |
| `RunRecorder.RunScope` | 新增三参 `finish(outcome, errorType, failureKind)` 默认方法；默认转发两参方法。 |
| `DefaultRunRecorder` | 实现三参 finish；两参 finish 按 outcome 给兜底处置。 |
| `RunRecord` / `RunPayload.RunFinish` | 不可变快照携带 `failureKind`。 |
| `MysqlRunSink` / `AiRunMapper` | 把 `failureKind` 落到 `ai_runs.failure_kind`，并支持筛选查询。 |
| `MemoryFailureClassifier`（`internal.memory`） | 把记忆提取的 `Throwable` 归一为 `FailureKind`。 |
| `MemoryOutputException`（`internal.memory`） | 解析器输出不合规的专用异常，供分类器识别为 `bad_output`。 |
| `MemoryExtractionJobs` | 新增 `failPermanently(jobId, reason)`：直接 `failed`，不再推进 attempts 与 next_attempt_at。 |
| `CampGenerationException` | 新增 `failureKind()`，封装三档映射。 |
| `EssayCollectionService` | 注入 `RunRecorder`；抓取失败与单篇抽取失败各记录一条。 |
| `AiRunAdminService` / `AiRunAdminController` | 列表筛选、概览按处置计数、`RunSummary` 带出 `failureKind`。 |

## 接口与数据

### `FailureKind`

```java
package io.github.xiaou61.ai;

public enum FailureKind {
    RETRYABLE("retryable"),
    BAD_OUTPUT("bad_output"),
    NEEDS_HUMAN("needs_human");

    private final String key;

    FailureKind(String key) { this.key = key; }
    public String key() { return key; }
    public boolean isRetryable() { return this == RETRYABLE; }
}
```

### `RunRecorder.RunScope`

```java
/** 失败处置：null 表示非失败终态；默认实现不改变现有两参调用。 */
default void finish(RunOutcome outcome, String errorType, FailureKind failureKind) {
    finish(outcome, errorType);
}
```

### `RunRecord`

在 `errorType` 后增加 `String failureKind`。`RunRecord.running(...)` 传 `null`；`DefaultRunRecorder.snapshot(status, errorType, failureKind)` 把值写入。`MysqlRunSink.toRow` 调 `row.setFailureKind(run.failureKind())`。

### `AiRunMapper`

- `insertRunning` 不写 `failure_kind`（该列可空，running 行不该有处置）。
- `finish` 的 UPDATE 增加 `failure_kind = #{failureKind}`。
- `findByTraceId` / `page` / `countPage` 的 SELECT 与 WHERE 增加 `failure_kind`；`page` 与 `countPage` 增加 `@Param("failureKind") String failureKind` 参数。
- `AiRunRow` 增加 `String failureKind` 与 getter/setter。

### 管理端 API

- `GET /api/v1/ai/admin/runs`：新增可选 `failureKind` 查询参数，原参数不变。
- `GET /api/v1/ai/admin/runs/overview`：不新增筛选参数；`Overview` 增加 `List<TelemetrySummaryMapper.CountBy> byFailureKind`，沿用既有 `kind/status/model/user` 筛选。
- `RunSummary` 增加 `String failureKind`；详情沿用同一 summary。
- `GET /meta` 返回 `failureKinds`（三个固定枚举值），前端下拉直接使用。
- `AiRunsListPage.KIND_LABEL` 补 `essay_collect` / `essay_extract` 的中文标签，否则新链路在明细里只有英文 key。

### `MemoryExtractionJobs`

```java
public void fail(long jobId, int attempts, String reason)         // 既有：退避或到上限 failed
public void failPermanently(long jobId, String reason)            // 新增：直接 failed，不动 attempts/next_attempt_at
```

`MemoryExtractionJobMapper.failPermanently`：

```java
@Update("""
        UPDATE ai_memory_extraction_jobs
        SET status = 'failed', last_error = #{lastError}, locked_at = NULL
        WHERE id = #{id} AND status = 'running'
        """)
int failPermanently(@Param("id") long id, @Param("lastError") String lastError);
```

### `MemoryFailureClassifier`

```java
static FailureKind classify(Throwable failure) {
    if (failure instanceof MemoryOutputException) return FailureKind.BAD_OUTPUT;
    if (failure instanceof BizException biz && biz.errorCode() == ErrorCode.INVALID_ARGUMENT) {
        return FailureKind.NEEDS_HUMAN;
    }
    return FailureKind.RETRYABLE;
}
```

`MemoryCandidateParser.failure(...)` 改为抛 `MemoryOutputException`（继承 `BizException`，`ErrorCode.INTERNAL`，对外错误码不变）。

### 营地出题

`CampGenerationException`：

```java
public FailureKind failureKind() {
    return switch (kind) {
        case EMPTY_RESPONSE -> FailureKind.NEEDS_HUMAN;
        case BAD_SHAPE, NO_VALID_ITEM -> FailureKind.BAD_OUTPUT;
    };
}
```

`CampService.create` 与 `retest` 的 catch 拆成两个分支：`CampGenerationException` 用 `failure.failureKind()`；其它 `RuntimeException` 用采集层兜底。`refundFailed` 仍在每个失败分支调用一次。

### 面经抓取

`EssayCollectionService` 增加 `private final RunRecorder recorder`，构造函数注入。`runOnce`：

1. 在 `source.fetchSince` 外包一个 `RunSpec.of(RunKind.ESSAY_COLLECT, 0L)` 的 run；捕获异常时 `run.finish(RunOutcome.FAILED, "essay_fetch_failed", FailureKind.RETRYABLE)` 后保持原有「整次跳过」返回。
2. 每篇帖子的 `extractor.extract(...)` 外包一个 `RunSpec.of(RunKind.ESSAY_EXTRACT, 0L).withModel(access.get().model()).withPromptVersion(EssayExtractionPrompt.VERSION)` 的 run；成功 `OK`，失败 `FAILED` + `bad_output`，仍 `failed++` 并继续下一帖。
3. 不新增重试；保持原有统计与返回值。

`RunKind` 新增两个常量：`ESSAY_COLLECT = "essay_collect"`、`ESSAY_EXTRACT = "essay_extract"`。

## 持久化与迁移

新增迁移 `V20261104141000__add_ai_runs_failure_kind.sql`（文件名以实施当刻的 14 位时间戳为准）：

```sql
-- AI 失败的处置语义（WORK-069）。NULL 表示非失败终态；不回填历史行。
ALTER TABLE ai_runs
  ADD COLUMN failure_kind VARCHAR(16) NULL AFTER error_type;
```

- 不加索引、不加 CHECK。
- 迁移只加结构，不写数据。
- 按 `MigrationChecksumTest` 提示用 `-Dmigration.checksums.update=true` 登记新文件，review `migration-checksums.txt` diff。

## 失败处理与恢复

- 记忆提取的 `run.finish(FAILED, "extraction_failed", kind)` 与任务行状态必须在同一个 catch 内完成；`retryable` 走既有 `jobs.fail`，`bad_output` / `needs_human` 走 `jobs.failPermanently`。
- `failPermanently` 不更新 `attempts` 与 `next_attempt_at`，只清 `locked_at`、写 `last_error`。
- 其它链路未显式分类的失败：`DefaultRunRecorder` 对 `FAILED`/`TIMEOUT`/`INTERRUPTED` 兜底 `retryable`；`OK`/`CANCELLED` 保持 `NULL`。这是采集层口径，不改变既有重试行为。
- `EssayCollectionService` 的失败仍然不抛给调度器；`RunRecorder` 写入失败按既有纪律只丢遥测，不影响抓取结果。

## 安全与权限

- `failure_kind` 是低基数枚举，不含异常消息、提示词或正文；`error_type` 的「绝不写异常消息」纪律继续有效。
- 管理端接口仍在 `/api/v1/ai/admin/**` 的 `hasRole("ADMIN")` 规则下，不新增放行。
- 面经 run 的 `userId = 0`，与管理端统计无用户归属的既有路径一致；不新增可识别个人身份的字段。

## 可观测性

- DB 维度：`ai_runs.failure_kind` 可按时间窗与既有筛选聚合。
- 管理端新增 `byFailureKind` 排行卡；`meta` 返回三个固定值。
- 不把 `failure_kind` 加进 Prometheus 标签，避免高基数/语义漂移；现有 `paideia.ai.runs{kind,outcome}` 指标不变。

## 实施顺序

1. `FailureKind`、`RunRecord`、`RunRecorder` 默认方法、`DefaultRunRecorder`、`MysqlRunSink`、`AiRunMapper`、迁移。
2. `MemoryOutputException`、`MemoryFailureClassifier`、`MemoryExtractionJobs.failPermanently`、`MemoryExtractionService` 接线。
3. `CampGenerationException.failureKind()`、`CampService` 两处 catch。
4. `EssayCollectionService` 的 `RunRecorder` 与两个 `RunKind`。
5. `AiRunAdminService` / `AiRunAdminController` / 两个管理端页面。
6. 迁移三守卫、结构化证据表、更新历史。

## 测试策略

- `DefaultRunRecorderTest`：三参 `finish` 写入 `failureKind`；两参 `finish` 对 `FAILED` 兜底 `retryable`、`OK` 为 `null`。
- `MemoryFailureClassifierTest`：`MemoryOutputException → bad_output`、`BizException(INVALID_ARGUMENT) → needs_human`、其它 `RuntimeException → retryable`。
- `MemoryExtractionJobsIntegrationTest` 新增：不可重试失败后任务行 `failed`、`attempts` 不增、`next_attempt_at` 不变；`retryable` 仍退避。反向验证：把 `failPermanently` 改回 `fail`，测试变红。
- `CampServiceTest` 新增三种 `Kind` 各自映射与 `run.finish` 的三参断言。
- `EssayCollectionIntegrationTest` 新增：抓取失败与单篇抽取失败各写对应 `failure_kind`；并断言抓取失败没有重试。
- `AiObservabilityIntegrationTest` 新增：`page(..., failureKind)` 只返回对应行；`Overview.byFailureKind` 计数正确。
- 前端：`pnpm --filter @paideia/admin typecheck`；管理端 e2e 只跑改动页对应文件（如 `ai-runs.spec.ts`）。

## 需求追踪

| 需求 | 设计落点 | 验收 |
| --- | --- | --- |
| REQ-001 | 迁移、`FailureKind`、`RunRecord`/`MysqlRunSink`/`AiRunMapper` | AC-001 |
| REQ-002 | `MemoryFailureClassifier`、`failPermanently`、`MemoryExtractionService` | AC-002 |
| REQ-003 | `CampGenerationException.failureKind()`、`CampService` 两处 catch | AC-003 |
| REQ-004 | `EssayCollectionService` 的 `RunRecorder`、两个 `RunKind` | AC-004 |
| REQ-005 | `RunSummary.failureKind`、`page/countPage`、`Overview.byFailureKind`、管理端页面 | AC-005 |

## 待决定事项

- 面经是否当轮重试：本项不做，留给后续按一周数据决定。
- `failure_kind` 是否进入 AIOps 诊断：本项不做，只做记录与查询。
