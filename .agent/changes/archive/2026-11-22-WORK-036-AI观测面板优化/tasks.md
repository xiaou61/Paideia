---
artifact: tasks
work_id: WORK-036
work: AI 观测面板优化
status: approved
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 14:34:03 +0800
approver_role: CTO
---

# 任务 · AI 观测面板优化

**排序原则**：A、B 两组一条迁移都不加，先做完全部可验证；C 组要一张迁移，放在 A/B 之后单独验；D 组最后，因为它纯属还债。

**硬约束**：迁移文件名 14 位时间戳；不新增前端依赖；不改计费规则；不动七条 AI 路径的埋点结构。

## A 分桶序列

### TASK-001 | done | 分桶纯函数与测试

- 新增 `Segmentation`：`record(Granularity, Instant from, Instant to, ZoneId)` 与 `List<Instant> boundaries(...)`。
- `≤48 小时 → HOUR`，否则 `DAY`；日桶按 zone 的 00:00 对齐，小时桶按整点对齐；边界列表长度 = 桶数 + 1。
- **验收**：`SegmentationTest` 覆盖 48 小时边界（47h59m→HOUR、48h→DAY）、跨月、跨年、跨 DST 无关的固定 +08:00、跨度为 0。
- **反向验证**：把 `<= 48` 改成 `< 48` 后边界用例必须红。

### TASK-002 | done | 空桶补零

- `Segmentation.fill(raw, boundaries)`：按边界逐桶放置，缺的给零值（分位数 `null`）。
- **验收**：`SegmentationFillTest` 覆盖「中间缺一个桶」「全部缺」「首尾缺」「raw 有多余桶（应被忽略）」。
- **反向验证**：去掉补零直接返回 raw 后，中间缺桶的用例必须红。

### TASK-003 | done | 按桶聚合的 SQL

- `TelemetrySummaryMapper` 加 `countByBucket(...)` 与 `percentilesByBucket(...)`，参数含四个筛选与 `fmt`/`offset`。
- **验收**：`AiTelemetryIntegrationTest` 造三天的数据（中间一天空），`series` 返回 3 个桶、中间那个全零、每桶 total 相加等于总数。
- **反向验证**：把中间那天的数据也造上，断言中间桶不再是零（证明断言真的在看数据）。

### TASK-004 | done | series 接口与筛选

- `AiRunAdminService.series(from, to, kind, status, model, userId)`；`AiRunAdminController` 加 `GET /series`。
- `overview` 也接受这四个筛选参数，所有聚合按筛选后的集合算。
- **验收**：AC-003 —— 同一 `kind=chat&status=failed` 下，`series` 各桶 total 之和、`overview.total`、明细 `total` 三者相等。

### TASK-005 | done | 缓存命中与名义成本

- `Totals` 加 `cacheReadTokens`；`Overview` 加 `cacheReadTokens`、`cacheSavedRatio`、`nominalCredits`。
- `nominalCredits` 由 `BillingProperties.creditsFor(model, in, out)` 按模型分组累加；`credits` 保持实扣。
- **验收**：AC-004、AC-005。

## B 采集可信度

### TASK-006 | done | 队列健康

- `RunWriteQueue` 加 `depth()`、`lastSuccessAt()`；`write()` 成功后设置时间戳。
- `AiRunAdminService.health()` 汇总 `enabled` / `sampling` / 上述四项 / `queueCapacity`。
- **验收**：`GET /health` 返回五个字段；关闭采集时 `enabled=false` 但其它字段仍可读（AC-015 的一部分）。

### TASK-007 | done | 丢弃告警条与采样提示

- `overview` 响应加 `sampling`；前端在 `dropped > 0 || writeFailures > 0` 时渲染告警条，在 `sampling < 1` 时渲染偏低说明。
- **验收**：AC-006、AC-007（e2e 断言文案存在）。

### TASK-008 | done | 积分对账

- `TelemetrySummaryMapper.reconcileByDay(...)`：两段查询（`ai_runs` 按天 `SUM(credits)`、`ai_credit_ledger` 按天 `-SUM(delta)` 且排除 `RESERVE`/`ADJUST`），应用层按天合并算差值。
- `TelemetryProperties` 加 `reconcileThreshold`（默认 10）。
- **验收**：AC-008。**必须同时断言"采样 < 1 时差值为正"这条已知差异**，否则将来有人当成 bug。
- **反向验证**：把 `RESERVE` 从排除列表移除，用例必须红（证明排除是真的在起作用）。

### TASK-009 | done | 耗时构成

- `detail()` 响应加 `breakdown`；四段计算放 `AiRunAdminService`，`other` 负数夹到 0。
- **验收**：AC-009。

## C 用户反馈

### TASK-010 | done | 迁移与表

- `V20261023xxxxxx__create_ai_feedback.sql`（14 位时间戳，**先查有没有人改同一张表**——这是张新表，不冲突）。
- **验收**：`scripts/test-db.sh` 起的库上迁移成功；`MigrationVersionFormatTest` 绿。

### TASK-011 | done | 反馈写入与查询

- `AiFeedbackMapper`：`upsert`（`ON DUPLICATE KEY UPDATE`）、`page`、`count`。
- `AiFeedbackService.submit(...)`：白名单校验 `rating` / `reason`，`down` 必须有 `reason`，`up` 忽略 `reason`。
- `POST /api/v1/ai/feedback`（authenticated，不接受 `userId`）。
- **验收**：AC-010；非法枚举返回 400；`up` 传 reason 时 reason 被丢弃而不是报错。

### TASK-012 | done | done 事件带 traceId

- `RunScope` 加 `String traceId()`；`DefaultRunScope` 返回真实 id，`Noop` 返回 `null`。
- `AssistantConversationController.onCompleted` 的 done 负载加 `traceId`。
- **验收**：一次流式对话的 `done` 事件里 `traceId` 非空且能在 `ai_runs` 找到对应行。
- **反向验证**：关掉采集后 `traceId` 为 null，事件仍正常发出。

### TASK-013 | done | 学习者端点赞点踩

- `conversation-panel.tsx` 每条助手消息下加赞/踩；踩后展开四个原因；提交后显示"已反馈"。
- **验收**：`conversation-panel.test.tsx` 断言点击后调用 API 且按钮状态变化；e2e `assistant.spec.ts` 断言真实点击后按钮变成"已反馈"。

### TASK-014 | done | 管理端反馈页

- 新 `AiFeedbackPage`：时间窗 + rating + reason + kind 筛选、分页、展开复用 `RunSteps`。
- `AdminShell` 侧栏加菜单项（`preferredNav` 类型是联合字面量，要一起加）。
- **验收**：AC-011。

### TASK-015 | done | 注销级联

- `AiUserDeletionListener.USER_TABLES` 加 `{"ai_feedback", "user_id"}`。
- **验收**：AC-012。

## D 定位与层级

### TASK-016 | done | traceId 前缀搜索

- `AiRunMapper.page` / `countPage` 加 `tracePrefix`；controller 加参数。
- **验收**：AC-013。
- **反向验证**：把 `LIKE CONCAT(x,'%')` 改成 `LIKE CONCAT('%',x,'%')` 后，`EXPLAIN` 显示不走索引（记录在报告里，不写成断言——执行计划不适合做单测断言）。

### TASK-017 | done | 子步骤支持

- `SpanSpec` 加 `parentSeq` 与 `child(...)`；`StepScopeImpl.close()` 传下去。
- 前端 `RunSteps` 按层级缩进。
- **验收**：AC-014，用单测直接构造一个带 parent 的 span 并断言落库与读取正确。
- **诚实记录**：仓库里没有产生子步骤的调用点，本期只做支持与渲染。

## 评审焦点

规格暗示但没有任务直接测试的失败模式，逐条给归属：

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 分桶边界算错导致假断崖 | TASK-001/002 | `SegmentationTest` 48h 边界；`AiTelemetryIntegrationTest` 中间空桶 |
| 时区用错导致"今天"从早上 8 点开始 | TASK-001 | `SegmentationTest` 固定 `Asia/Shanghai` 断言桶起点是 00:00 |
| 空桶补零把"没有数据"与"0 毫秒"混同 | TASK-002 | 断言缺失桶的分位数是 `null` 而非 0 |
| 概览与明细筛选不一致 | TASK-004 | AC-003 三方相等 |
| 缓存命中为 0 时除零得到 NaN | TASK-005 | AC-004 全零返回 0 |
| 对账把 `RESERVE` 算进去导致每天都异常 | TASK-008 | 反向验证：移除排除后必须红 |
| 采样 < 1 时对账出现正差被当成 bug | TASK-008 | 显式断言这条差异存在且方向为正 |
| 并发工具调用让 `other` 变负 | TASK-009 | 断言夹到 0 |
| 反馈 `up` 时带 reason 崩溃 | TASK-011 | 断言 reason 被丢弃 |
| 反馈非法枚举静默入库 | TASK-011 | 断言返回 400 |
| 关掉采集后反馈提交 500 | TASK-012 | 断言 traceId 为 null 但提交成功 |
| 反馈表忘登记注销级联 | TASK-015 | `AccountDeletionCascadeIntegrationTest` |
| 前缀搜索退化成全表扫 | TASK-016 | `EXPLAIN` 人工核对，写进报告 |
| 子步骤 parent 指向不存在的 seq | TASK-017 | 断言只落库、不校验（无外键），渲染时容错 |

## 完成条件

- 全部 17 个任务 done。
- A/B 两组：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` 全绿。
- C/D 两组：追加 `paideia-app` 的集成用例与 `MigrationVersionFormatTest` / `MigrationChecksumTest`。
- 管理端 e2e 与学习者端 `assistant.spec.ts` 通过。
- 验证报告逐条对应 AC-001..AC-015，并如实记录 TASK-017 的偏差与 TASK-016 的执行计划核对结果。
