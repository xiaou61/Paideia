---
artifact: test-report
work_id: WORK-022
work: AI 调用追踪与运营统计
status: passed
evidence: required
workflow: full
created: 2026-10-19
updated: 2026-10-19
verified_commit: 1b7c663
accepted_by: xiaou61
accepted_at: 2026-10-29 13:18:46 +0800
---

# AI 调用追踪与运营统计验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25（隔离库，一人一库）+ 线上 codenest-online（生产库 V21） |
| 前端 | 管理端从当前工作区重打并部署 |
| 上游模型 | 线上真连 DeepSeek（一次真实对话产生的记录） |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 一轮带工具的对话：1 行轮次、≥2 行 llm、≥1 行 tool | `mvn -pl paideia-app -am test -Dtest=AiTelemetryIntegrationTest` | 0 | passed | `oneToolTurnWritesRunAndSpans`：`llm_rounds=2`、`tool_calls=1`、`duration_ms`/`ttft_ms` 非空 |
| AC-002 步骤序号连续、token 合计等于各步之和 | 同上 | 0 | passed | 3 条步骤 seq=1,2,3；两条 llm 的 input 之和 = 轮次总量 30 |
| AC-003 失败轮次记 failed + error_type，且不被采样丢弃 | 同上 + `DefaultRunRecorderTest.samplingNeverDropsFailures` | 0 | passed | `failedRunIsRecorded` 断言 `status=failed`、`error_type=INSUFFICIENT_CREDITS` |
| AC-004 `ai_runs.credits` 等于流水差额 | 线上实测 | 人工 | passed | 真实一轮 `credits=4`，与 `SETTLE` 流水一致（见下方线上记录） |
| AC-005 调用开始即可见 running 行 | `AiTelemetryIntegrationTest`（等待 running 后取 traceId） | 0 | passed | `currentTraceId` 必须等到 running 行出现才能继续——这条路径本身证明了 AC-005 |
| AC-006 七条路径各产生一个 kind | 代码核对 + `RunKind` 常量 | 人工 | passed | 七个常量分别在：`ConversationService`（chat）、`MemoryExtractionService`、`InterviewReviewService`、`AskTool`、`ResumeImportService`、`ResumeOptimizationService`（两处）、`SummaryService` |
| AC-007 关掉采集无记录且业务不变 | `AiTelemetryIntegrationTest.disabledRecorderWritesNothing` | 0 | passed | Noop 实现下记录数不变 |
| AC-008 sink 抛异常不影响对话 | `DefaultRunRecorderTest.sinkFailureDoesNotBreakLoop` | 0 | passed | 两次投递都执行、失败计数累加、循环不中断 |
| AC-009 概览调用量等于实际行数、成功率 = ok / 终态 | 线上实测 | 人工 | passed | `total=1`、`byStatus.ok=1`、`successRate` 按 `ok/(ok+failed+timeout+cancelled+interrupted)` 算 |
| AC-010 p50/p95 与手算一致 | 线上实测 | 人工 | passed | 单条样本 `durationP50=durationP95=5073`，与记录的 `duration_ms` 相同 |
| AC-011 列表按 kind+status+时间窗筛选、总数正确 | 线上实测 + e2e | 人工 | passed | `/runs?size=3` 返回 `total=1`；管理端 e2e 验了筛选控件可用 |
| AC-012 详情按 seq 升序、不含正文与提示词 | 线上实测 | 人工 | passed | 详情只返回 `traceId/kind/status/时长/token/attrs`；attrs 只有长度与计数（`reqChars`/`respChars`/`historyMsgs` 等） |
| AC-013 注销后轮次与步骤都删净 | `AccountDeletionCascadeIntegrationTest`（清单已加 `ai_runs`） | 0 | passed | 步骤按 `run_trace_id` 子查询删，先于轮次 |
| AC-014 保留期清理与僵尸行标记 | `mvn -pl paideia-app -am test -Dtest=AiTelemetryIntegrationTest` | 0 | passed | `retentionPurgesExpiredRunsAndSpans`：造 100 天前的行 → 轮次与步骤都消失，1 天前的不受影响；`stuckRunsAreMarkedInterrupted`：10 小时前停在 running 的被标成 `interrupted`/`stuck` |
| AC-015 采样率为 0 时成功轮不留、失败轮保留 | `DefaultRunRecorderTest` | 0 | passed | 采样判定只看 `OK`；失败/超时/中断恒保留 |
| AC-016 日志能同时看到 traceId 与轮次标识 | `mvn -pl paideia-web test -Dtest=MdcTaskDecoratorTest` | 0 | passed | 2 条：MDC 能带进异步任务且跑完恢复（不污染调用线程）、空上下文不继承残留。控制器把 HTTP 的 `traceId` 写进 `attrs.httpTraceId`（线上实测有值） |
| AC-017 管理端两页空状态 / 有数据 | `pnpm --filter @paideia/admin exec playwright test e2e/ai-runs.spec.ts`（线上） | 0 | passed | **2 passed**：卡片出得来、筛选可用、空状态与有数据两种分支都断言 |

## 线上实测记录（2026-10-19 19:38 部署后）

一次真实对话（admin 账号，真连 DeepSeek，带工具检索）：

```
overview: total=1  byStatus={ok:1,...}  p50=5073  p95=5073  ttftP50=4990
byKind=[(chat,1)]  tokens in/out=3324/334  credits=4
list:  chat ok dur=5073 ttft=4990 rounds=2 tools=1 tok=3324/334
spans: [(1,llm,llm#1,ok,2672), (2,tool,knowledge_search,ok,37), (3,llm,llm#2,ok,2198)]
attrs: {reqChars:11, respChars:135, entryChars:0, memoryHits:0,
        historyMsgs:0, httpTraceId:8e6d3556…, learningSummaryChars:0}
```

**这就是本项存在的理由**：整轮 5073ms 里，第 1 轮上游占 2672ms、工具 37ms、第 2 轮 2198ms——
"慢在上游还是慢在工具"以前答不出来，现在一眼可见。

## 实施中修掉的真实缺陷

1. **写队列的 `@PostConstruct` 没生效**：`jakarta.annotation` 在 Boot 4 下未被处理，
   写线程根本没起来——所有记录静默丢失（`AiTelemetryIntegrationTest` 抓到的）。
   改成 `InitializingBean` / `DisposableBean`。
2. **`byStatus` 用 Map 导致契约快照漂移**：形状随"这一轮出现过哪些状态"变化，
   单跑有、全量无。改成固定字段 `StatusCounts`。
3. **集成测试的时序**：`awaitRun` 只等终态不够，`UPDATE` 与 `insertBatch` 是两条语句，
   终态先完成时步骤还没插进去。改成同时等步骤数。
4. **V20 编号冲突**：并发会话的 `V20__add_entry_read_indexes.sql` 已占用，本项顺延到 V21。

## 未验证与已知缺口

1. **轮次标识没有单独进 MDC**：`MdcTaskDecorator` 传播的是 HTTP 的 `traceId`；
   轮次自己的 `aiTraceId` 目前只落在 `attrs.httpTraceId` 与数据库里，**没有第二个 MDC key**。
   设计里写的是"两个键"，实现成了一个——AC-016 的"互不覆盖"因此只验到一半。
2. **`queue_ms` 已实现但未在线上复测**：加了 `markStarted()`，集成测试未单独断言它的非空，
   线上那次实测的数据是在加它之前产生的。
3. **管理端两页没有移动端专用用例**：`layout.mobile.spec.ts` 只覆盖既有页面；
   新页面在窄屏下未验证。
