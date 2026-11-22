---
artifact: design
work_id: WORK-022
work: AI 调用追踪与运营统计
status: approved
workflow: full
depends_on: [requirements, proposal]
related_to: [WORK-006, WORK-009, WORK-010, WORK-013, WORK-019]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 17:54:59 +0800
approver_role: CTO
---

# AI 调用追踪与运营统计设计

## 系统上下文

```text
七条 AI 路径
  对话 AssistantConversationController.runTurn
  记忆整理 MemoryExtractionService.run
  面试点评 InterviewReviewService
  外部问答 AskTool
  简历导入 ResumeImportService
  简历优化 ResumeOptimizationService
  条目精简 SummaryService
        │  只依赖端口（包根，无框架类型）
        ▼
  RunRecorder ── RunScope（一轮）── StepScope（一步，AutoCloseable）
        │        在内存里攒 RunRecord（含 SpanRecord 列表）
        ▼
  RunWriteQueue（ArrayBlockingQueue，有界）
        │  调用方线程到此结束，不做任何 IO
        ▼
  单写线程：采样判定 → RunSink.accept(...)
        ▼
  MysqlRunSink（本期唯一）
     ├─ INSERT ai_runs (status=running)         ← begin 时立刻投递，不等结束
     ├─ UPDATE ai_runs SET ... status=终态      ← finish 时
     └─ INSERT ai_run_spans (batch, run_trace_id)← 与上一条同一个任务里
        │
        ▼
  管理端 /api/v1/ai/admin/runs/**  ← 概览 / 列表 / 详情
        │
  AiUserDeletionListener ← 注销级联加两行
  TelemetryRetentionPurger ← 过期清理 + 僵尸行标记
```

## 组件与职责

| 组件 | 位置（planned） | 职责 |
| --- | --- | --- |
| `RunRecorder` | `paideia-ai/.../ai/RunRecorder.java`（包根） | 端口：`begin(RunSpec)` 返回 `RunScope`；`boolean enabled()` |
| `RunSpec` | 同上 | 一轮的入参：`kind`、`userId`、`conversationId`、`harness`、`model`、`promptVersion` |
| `RunOutcome` | 同上 | 终态：`OK` / `FAILED` / `TIMEOUT` / `CANCELLED` / `INTERRUPTED` |
| `RunKind` | 同上 | `kind` 常量集合（字符串常量，非枚举落库） |
| `RunRecorder.RunScope` | 同上 | `step(SpanSpec)`、`attribute(key,value)`、`tokens(in,out,cacheRead)`、`finish(outcome,errorType)`、`close()` |
| `RunRecorder.StepScope` | 同上 | `attribute(key,value)`、`tokens(in,out,cacheRead)`、`succeeded()` / `failed(errorType)`、`close()` |
| `RunRecord` / `SpanRecord` | `internal/telemetry/` | 不可变快照，自包含（不含懒加载） |
| `DefaultRunRecorder` | `internal/telemetry/` | 组装快照、生成 trace_id、投递队列 |
| `NoopRunRecorder` | `internal/telemetry/` | 开关关闭时装配；`begin` 返回空 scope |
| `RunWriteQueue` | `internal/telemetry/` | 有界队列 + 单写线程 + 批量落库 + 丢弃计数 |
| `RunSink` | `internal/telemetry/` | 落地接口，一个方法 `accept(RunPayload)` |
| `MysqlRunSink` | `internal/telemetry/` | 本期唯一实现：三组 SQL 与批量插入 |
| `AiRunMapper` / `AiRunSpanMapper` | `internal/telemetry/` | 手写 MyBatis |
| `TelemetryProperties` | `internal/telemetry/` | `enabled` / `sampling` / `retentionDays` / `queueCapacity` / `stuckAfterHours` / `batchSize` |
| `TelemetryRetentionPurger` | `internal/telemetry/` | `@Scheduled` 清理与僵尸行标记 |
| `AiRunAdminService` | `internal/telemetry/` | 概览、列表、详情的查询与归一化 |
| `AiRunAdminController` | `internal/web/` | `/api/v1/ai/admin/runs/**`，`@ApiAccess(ADMIN)` |
| `MdcTaskDecorator` | `paideia-web/.../web/` | 把 MDC 复制到异步任务线程 |

## 请求或事件流程

### 一轮对话（最细的一条）

1. `AssistantConversationController.send` 拿到 `TurnPreparation` 后立即 `recorder.begin(spec)`，并把 scope 传给 `runTurn`。**begin 发生在提交线程池之前**，所以排队时间可以被算出来（`started_at` 早于 `runTurn` 第一行的时间）。
2. `begin` 内部：生成 `trace_id`、记 `started_at`、把 `MDC.put("aiTraceId", traceId)` 记到 scope 上（由 `runTurn` 线程负责 put/remove，避免污染请求线程）。
3. `RunWriteQueue` 收到一条 `Begin` 任务，写 `INSERT ai_runs (..., status='running')`。
4. `runTurn` 里：
   - `assistant.stream(...)` 之前 `scope.attribute("queued", ...)` 已经由 controller 填好；
   - 第一个 `onContent` 时记 `ttft_ms`；
   - `onUsage(usage)` 时 `scope.tokens(usage.inputTokens(), usage.outputTokens(), 0)`；
   - `onCompleted` → `scope.finish(OK, null)`；`onFailed` → `scope.finish(FAILED, errorType(failure))`；
   - `finally` 中 `scope.close()`（`finish` 已调用过则是幂等空操作；没调用过则记 `INTERRUPTED`）。
5. `AgentScopeConversation.stream` 每轮：
   ```java
   try (var step = scope.step(SpanSpec.llm(round + 1))) {
       Round r = runRound(...);
       step.tokens(...);
       step.succeeded();
   }
   ```
   每轮把该轮的 `TokenUsage` 落在步骤上，同时 `scope.tokens(...)` 累加到轮次总量（两者都要，前者用于下钻、后者用于统计）。
6. `execute(...)` 里每个工具：
   ```java
   try (var step = scope.step(SpanSpec.tool(call.getName()))) {
       Outcome outcome = tools.run(...);
       if (outcome.error()) step.failed("tool_error"); else step.succeeded();
   }
   ```
7. `ConversationService.prepare` 拿到历史条数、记忆条数、条目字数后，由 controller 调 `scope.attribute(...)` 写入（`prepare` 本身不认识 recorder，避免把端口塞进服务层）。
8. `settleOnce` 拿到实扣金额后 `scope.attribute("credits", charged)`。

### 其余六条路径

同构但更粗：

```java
try (var run = recorder.begin(RunSpec.of(RunKind.MEMORY_EXTRACT, userId))) {
    // 原有逻辑
    run.tokens(usage.inputTokens(), usage.outputTokens(), 0);
    run.attribute("credits", charged);
    run.finish(RunOutcome.OK, null);
}
```

这些路径没有工具循环，不会有步骤行；`llm_rounds` 固定为 1。

### 写入线程

单线程循环：

1. `poll(timeout=200ms)` 取任务；攒到 `batchSize`（默认 50）或超时就 flush。
2. `Begin` 任务：立即执行 `INSERT`（单条，不攒）。
3. `Finish` 任务：按轮判断是否采样；不采样就跳过 `UPDATE` 与步骤插入，并把之前那条 running 行删掉（`DELETE ... WHERE trace_id=? AND status='running'`）。
   - 先判采样再决定"是否写终态"，比"先写再删"更省；但 begin 时不知道结果，所以 running 行一定已经写了。删除是采样生效的必要收尾。
   - 失败/超时/中断**不参与采样**（直接写终态）。
4. 任何异常：`log.warn("ai.telemetry.write-failed traceId={}", id, ex)`，继续下一条；**不重试**（重试会在库故障时把队列撑爆）。

### 一轮的生命周期与幂等

`RunScope` 的 `finish` 与 `close` 都可能被调用，用 `AtomicBoolean` 保证只有一个生效。`DefaultRunRecorder` 只投递一次 `Finish` 任务。

## 接口与数据

### 端口

```java
public interface RunRecorder {

    boolean enabled();

    RunScope begin(RunSpec spec);

    /** 一轮。实现必须幂等：finish 与 close 任意顺序、任意次数，只生效一次。 */
    interface RunScope extends AutoCloseable {

        StepScope step(SpanSpec spec);

        /** 冷字段统一走这里；由实现决定放列还是放 attrs。 */
        void attribute(String key, Object value);

        /** 累加到轮次总量。 */
        void tokens(int input, int output, int cacheRead);

        void finish(RunOutcome outcome, String errorType);

        @Override
        void close();
    }

    interface StepScope extends AutoCloseable {
        void attribute(String key, Object value);
        void tokens(int input, int output, int cacheRead);
        void succeeded();
        void failed(String errorType);
        @Override
        void close();
    }
}
```

`RunSpec.of(kind, userId)` 是最小构造；`conversationId` / `harness` / `model` / `promptVersion` 用 builder 或重载补齐，**只传当前位置确实有的值**，缺失就为 null。

### 快照

```java
record RunRecord(
        String traceId, long userId, Long conversationId,
        String kind, String harness, String model, String promptVersion,
        Instant startedAt, Instant endedAt,
        String status, String errorType,
        Integer queueMs, Integer durationMs, Integer ttftMs,
        int llmRounds, int toolCalls,
        int inputTokens, int outputTokens, int cacheReadTokens,
        int credits,
        Map<String, Object> attrs,
        List<SpanRecord> spans) { }

record SpanRecord(
        String traceId, Long parentSeq, int seq,
        String kind, String name, String status, String errorType,
        Instant startedAt, Integer durationMs, Integer ttftMs,
        int inputTokens, int outputTokens, int cacheReadTokens,
        Map<String, Object> attrs) { }
```

`Map` 在构造时 `Map.copyOf`，保证不可变。

### `attrs` 的键（本期）

| 键 | 何时写 | 类型 |
| --- | --- | --- |
| `httpTraceId` | 对话（取自请求属性） | string |
| `historyMsgs` | 对话 | int |
| `memoryHits` | 对话 | int |
| `entryChars` | 对话 | int |
| `learningSummaryChars` | 对话 | int |
| `queued` | 对话（提交线程池前的等待） | boolean |
| `reqChars` | 所有路径 | int |
| `respChars` | 所有路径 | int |

只存长度与计数，**不存内容**。

### 状态与错误标识

`status`：`running` / `ok` / `failed` / `timeout` / `cancelled` / `interrupted`。

`errorType`：低基数，取自既有 `ErrorCode` 的 `name()`；上游 HTTP 失败时用 `upstream_http_5xx` 这类归一值，绝不写异常消息（可能含 URL 或响应片段）。映射放在 `TelemetryErrorTypes.of(Throwable)` 一处。

### 管理端端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/ai/admin/runs/overview?from=&to=` | 概览统计 |
| GET | `/api/v1/ai/admin/runs?from=&to=&kind=&status=&userId=&model=&page=&size=` | 列表 |
| GET | `/api/v1/ai/admin/runs/{traceId}` | 详情 + 步骤 |
| GET | `/api/v1/ai/admin/runs/meta` | 时间窗内出现过的 `kind` / `status` / `model` 去重值，供筛选下拉 |

`from` / `to` 为 ISO-8601 时刻（缺省 `to=now`、`from=now-7d`）；窗口超过 `retentionDays` 时收敛到上限并返回 `clamped=true`。

概览响应：

```json
{
  "from": "2026-10-12T00:00:00Z", "to": "2026-10-19T00:00:00Z", "clamped": false,
  "total": 412, "ok": 388, "failed": 18, "timeout": 4, "running": 2,
  "successRate": 0.9463,
  "durationP50": 3120, "durationP95": 11400,
  "ttftP50": 640, "ttftP95": 2100,
  "inputTokens": 812345, "outputTokens": 96421, "credits": 3120,
  "byKind": [{"kind":"chat","count":380,"failed":15}],
  "byModel": [{"model":"deepseek-flash","count":412}],
  "errorTypes": [{"errorType":"upstream_timeout","count":9}],
  "dropped": 0
}
```

列表项**不返回** `attrs`（列表只需要筛选与定位）；详情返回 `attrs`。

详情响应额外带 `spans`（按 `seq` 升序）与 `attrs`。

### 分位数算法

MySQL 没有 `PERCENTILE_CONT`。用窗口函数：

```sql
WITH ranked AS (
  SELECT duration_ms,
         ROW_NUMBER() OVER (ORDER BY duration_ms) AS rn,
         COUNT(*) OVER () AS n
  FROM ai_runs
  WHERE started_at >= #{from} AND started_at < #{to} AND status IN ('ok','failed','timeout')
    AND duration_ms IS NOT NULL
)
SELECT MAX(CASE WHEN rn = CEIL(n * 0.50) THEN duration_ms END) AS p50,
       MAX(CASE WHEN rn = CEIL(n * 0.95) THEN duration_ms END) AS p95
FROM ranked
```

最近秩法，与"排序后手算"一致（AC-010）。TTFT 用同样的形状，条件换成 `ttft_ms IS NOT NULL`。

## 持久化与迁移

迁移文件名取执行时的下一个编号（当前下一个是 **V21**，不要写死）。

```sql
CREATE TABLE ai_runs (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id         VARCHAR(40)     NOT NULL,
  user_id          BIGINT UNSIGNED NOT NULL,
  conversation_id  BIGINT UNSIGNED NULL,
  kind             VARCHAR(32)     NOT NULL,
  harness          VARCHAR(32)     NULL,
  model            VARCHAR(64)     NULL,
  prompt_version   VARCHAR(32)     NULL,
  status           VARCHAR(16)     NOT NULL,
  error_type       VARCHAR(64)     NULL,
  started_at       DATETIME(3)     NOT NULL,
  ended_at         DATETIME(3)     NULL,
  queue_ms         INT             NULL,
  duration_ms      INT             NULL,
  ttft_ms          INT             NULL,
  llm_rounds       SMALLINT        NOT NULL DEFAULT 0,
  tool_calls       SMALLINT        NOT NULL DEFAULT 0,
  input_tokens     INT             NOT NULL DEFAULT 0,
  output_tokens    INT             NOT NULL DEFAULT 0,
  cache_read_tokens INT            NOT NULL DEFAULT 0,
  credits          INT             NOT NULL DEFAULT 0,
  attrs            JSON            NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_runs_trace (trace_id),
  KEY idx_ai_runs_user (user_id, started_at),
  KEY idx_ai_runs_conversation (conversation_id, started_at),
  KEY idx_ai_runs_kind (kind, started_at),
  KEY idx_ai_runs_state (status, started_at)
) ENGINE = InnoDB;

CREATE TABLE ai_run_spans (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  run_trace_id     VARCHAR(40)     NOT NULL,
  parent_seq       SMALLINT        NULL,
  seq              SMALLINT        NOT NULL,
  kind             VARCHAR(24)     NOT NULL,
  name             VARCHAR(64)     NOT NULL,
  status           VARCHAR(16)     NOT NULL,
  error_type       VARCHAR(64)     NULL,
  started_at       DATETIME(3)     NOT NULL,
  duration_ms      INT             NULL,
  ttft_ms          INT             NULL,
  input_tokens     INT             NOT NULL DEFAULT 0,
  output_tokens    INT             NOT NULL DEFAULT 0,
  cache_read_tokens INT            NOT NULL DEFAULT 0,
  attrs            JSON            NULL,
  PRIMARY KEY (id),
  KEY idx_ai_run_spans_run (run_trace_id, seq),
  KEY idx_ai_run_spans_kind (kind, name, started_at)
) ENGINE = InnoDB;
```

要点：

- **不建跨模块外键**（与 V4/V5/V11 一致），`conversation_id` 只是一个归属列。
- **索引按查询形状建**，不是按字段重要程度：概览按 `started_at` + `status`；列表按 `kind/status + started_at`；详情按 `trace_id`（唯一键）与 `run_trace_id + seq`。
- **时间列全由应用传值**，不写 `DEFAULT CURRENT_TIMESTAMP`（项目纪律：库会话时区是 +08:00）。
- `attrs` 用 `JSON` 列而不是 `TEXT`：将来要按某个键过滤时可以直接加生成列或函数索引，不用改列类型。

## 失败处理与恢复

| 情形 | 行为 |
| --- | --- |
| sink 写库抛异常 | 队列线程内 `log.warn` 后继续；不重试、不影响业务；计入 `writeFailures` 计数 |
| 队列满 | `offer` 返回 false → `dropped` 计数 +1，本轮记录丢失；不阻塞 |
| `begin` 时数据库不可用 | running 行没写进去；结束时的 `UPDATE` 影响 0 行，步骤插入仍然执行（孤儿步骤）——接受，因为此时库本身已经不可用 |
| 进程崩溃 / 线程卡死 | 留下 `status='running'` 行；超过 `stuckAfterHours`（默认 6 小时）由清理任务改为 `interrupted` |
| 采样判定为丢弃 | 删除该轮的 running 行（`status='running'` 条件保护，避免误删已写终态的行） |
| `finish` 后 `close` | 幂等，无操作 |
| `finish` 前 `close`（异常路径） | 记 `interrupted`，`errorType = Unclosed` |
| 上游超时 | `status='timeout'`，`errorType='upstream_timeout'` |
| `from`/`to` 超出保留期 | 收敛到 `retentionDays` 上限，响应带 `clamped=true` |
| 概览无数据 | 返回全零与空数组，不报错 |

## 安全与权限

- 全部管理端端点位于 `/api/v1/ai/admin/**`，既有 `hasRole("ADMIN")` 规则已覆盖且**顺序正确**（在 `/api/v1/ai/**` 之前）；类上再加 `@ApiAccess(ApiAccess.Access.ADMIN)`，与 `AiCreditAdminController` 一致。
- 端点只读，不含任何写操作。
- 响应**永不包含**提示词、回答正文、工具参数原文、凭据、服务器地址。这是设计约束，不是"注意一下"：断言写在 AC-012 里。
- `trace_id` 由服务端生成，**不接受客户端传入**——否则客户端可以伪造并与他人记录撞键。
- `attrs` 的键是白名单（本文档列出的七个），不允许业务代码塞任意键值。实现上 `attribute()` 忽略未知键并打一条 debug 日志。
- 日志里的 `aiTraceId` 是随机的，不含用户可识别信息。

## 可观测性

本项自身也要能被观察，否则"统计系统挂了没人知道"：

| 信号 | 位置 |
| --- | --- |
| `ai.telemetry.dropped total=N` | 每 60 秒一次，N>0 时 INFO，等于容量说明持续过载 |
| `ai.telemetry.write-failed` | 每条失败一次 WARN，带 traceId 与异常类名（不带消息正文） |
| `ai.telemetry.sampled-out` | Debug 级，单条 |
| `ai.telemetry.purged rows=N` | 清理任务每次 INFO |
| `ai.telemetry.stuck-marked rows=N` | 僵尸行标记，N>0 时 WARN |
| `ai.telemetry.enabled=false` | 装配时 WARN 一次（"统计已关闭"这种事不该静默） |

## 实施顺序

1. 迁移 + 两个 mapper；
2. 端口类型 + `NoopRunRecorder` + `DefaultRunRecorder`；
3. 写队列 + `RunSink` + `MysqlRunSink`；
4. 对话链路埋点（控制器、每轮上游、每次工具、上下文规模）+ MDC 传播 + 结算金额外传；
5. 其余六条路径接入 + 注销级联；
6. 保留期清理与管理端端点、页面。

逐文件步骤见 `tasks.md`。

## 测试策略

| 层 | 覆盖 |
| --- | --- |
| 单测 | `DefaultRunRecorderTest`：finish/close 幂等、tokens 累加、未知 attrs 键被忽略、trace_id 唯一且不等于传入的 httpTraceId |
| 单测 | `RunWriteQueueTest`：队列满丢弃并计数、sink 抛异常不中断循环、采样为 0 时成功轮被删而失败轮保留 |
| 单测 | `TelemetryErrorTypesTest`：异常 → 低基数标识的映射不会把消息带出来 |
| 集成 | `AiTelemetryIntegrationTest`：一轮带工具对话的两表行数、token 合计、status、ttft、credits 与流水一致、running 行可见 |
| 集成 | `AiTelemetryAdminIntegrationTest`：概览数字、p50/p95 与手算一致、筛选与分页、非管理员 403、窗口收敛、响应不含正文 |
| 集成 | `AiTelemetryDeletionIntegrationTest`：注销后两表该用户行数为 0（并在 `AccountDeletionCascadeIntegrationTest` 的表清单里加两行） |
| 集成 | `AiTelemetryRetentionIntegrationTest`：保留期清理、僵尸行标记 |
| 集成 | `AiTelemetryPathsIntegrationTest`：七条路径各产生一个 `kind` |
| 前端单测 | 概览卡片的数值格式化（毫秒、万级 token、百分比），空数据返回占位 |
| e2e | `ai-runs.spec.ts`：概览页有数据时卡片可见、筛选后列表变化、详情页步骤表渲染、无数据空状态 |

测试数据一律自建（不自造真实用户），用"任意数字身份"的既有约定。

## 需求追踪

| 设计元素 | 需求 |
| --- | --- |
| `ai_runs` + `ai_run_spans` | REQ-001、REQ-003 |
| begin 写 running、finish 写终态 | REQ-002 |
| `RunKind` 七个常量 | REQ-004 |
| `RunRecorder` / `NoopRunRecorder` | REQ-005 |
| `RunWriteQueue`（有界、丢弃计数、吞异常） | REQ-006 |
| `status` / `errorType` 与 `TelemetryErrorTypes` | REQ-007 |
| 概览端点 | REQ-008 |
| 列表端点 | REQ-009 |
| 详情端点 | REQ-010 |
| `attrs` 七个键 | REQ-011 |
| `MdcTaskDecorator` + `aiTraceId` | REQ-012 |
| `AiUserDeletionListener` 两行 | REQ-013 |
| `@ApiAccess(ADMIN)` + 既有路径规则 | REQ-014 |
| `TelemetryRetentionPurger` | REQ-015 |
| 采样判定（失败不参与） | REQ-016 |
| 管理端两个页面与空状态 | 质量要求（空状态） |

## 待决定事项

无。`kind` 常量取名在实现时按既有服务名对齐（`chat` / `memory_extract` / `interview_review` / `mcp_ask` / `resume_import` / `resume_optimize` / `entry_summary`）。
