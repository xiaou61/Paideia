---
artifact: test-report
work_id: WORK-036
work: AI 观测面板优化
status: passed
evidence: required
created: 2026-10-23
updated: 2026-10-23
accepted_by: xiaou61
accepted_at: 2026-10-29 13:20:20 +0800
---

# WORK-036 验证报告

日期：2026-10-23。每条结论对应一次真实执行。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AiObservabilityIntegrationTest test` | 0 | 通过 | `dailySeriesTotalsMatch`（11/0） |
| AC-002 | 同上 | 0 | 通过 | `seriesFillsEmptyBuckets` + `SegmentationTest.fillUsesNullForMissingPercentiles` |
| AC-003 | 同上 | 0 | 通过 | `overviewMatchesListWithSameFilter`（曾红，见缺陷 2） |
| AC-004 | 同上 + `-pl paideia-ai -Dtest=AiRunMetricsTest` | 0 | 通过 | `cacheTokensAreExposed` + `cacheSavedRatioIsZeroWhenEverythingIsZero` |
| AC-005 | `mvn -B -pl paideia-ai -am -Dtest=AiRunMetricsTest test` | 0 | passed | `overview.nominalCredits` 代码路径 + 界面 `runs-cost-line`；**未真改价目表跑一轮** |
| AC-006 | `mvn -B -pl paideia-app -am -Dtest=AiObservabilityIntegrationTest test` | 0 | passed | `healthReportsQueueState` + 界面 `runs-health-alert`；**未真灌满队列** |
| AC-007 | 同上 | 0 | passed | `overview.sampling` 透传 + 界面 `runs-sampling-hint`；**未真调采样率** |
| AC-008 | 同上 | 0 | 通过 | `reconcileExcludesReserveAndAdjust`（含 RESERVE 反向验证） |
| AC-009 | `mvn -B -pl paideia-ai -am -Dtest=AiRunMetricsTest test` | 0 | 通过 | `breakdownSubtractsEachSegment` + `breakdownClampsOtherToZeroWhenSegmentsOverrun` |
| AC-010 | 集成 + `pnpm --filter @paideia/assistant test` | 0 | 通过 | `feedbackUpsertsInsteadOfAppending` + `conversation-panel.test.tsx` 的「回答反馈」 |
| AC-011 | 集成 + `pnpm --filter @paideia/admin test:e2e` | 0 | 通过 | `feedbackPageFilters` + e2e「反馈页」 |
| AC-012 | `... -Dtest=AccountDeletionCascadeIntegrationTest test` | 0 | 通过 | 2/0，清单新增 `ai_feedback` 与 `ai_runs` 两行种子与断言 |
| AC-013 | `... -Dtest=AiObservabilityIntegrationTest test` | 0 | 通过 | `tracePrefixSearch` |
| AC-014 | 同上 | 0 | passed | `childSpanKeepsParent`；仓库无真实子步骤产生点，见第五节 |
| AC-015 | 同上 | 0 | passed | `healthReportsQueueState` 的 `enabled` 字段；**未在关闭采集的上下文里跑全部接口** |
| 后端单测全集 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 0 | passed | 291 执行 / 0 失败 / 13 跳过 |
| 迁移与模块闸 | `... -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest test` | 0 | passed | 9 执行 / 0 失败 |
| assistant 单测 | `pnpm --filter @paideia/assistant test` | 0 | passed | 26 执行 / 0 失败 |
| 管理端 e2e | `scripts/test-db.sh pnpm --filter @paideia/admin test:e2e` | 0 | passed | 27 passed |
| 类型检查 | `pnpm --filter @paideia/admin typecheck` 与 `pnpm --filter @paideia/assistant typecheck` | 0 | passed | 两端 tsc 均无输出 |
| 反向验证：分桶边界 | 把 `Segmentation.of` 的 `<=` 改成 `<` 后跑 `-Dtest=SegmentationTest` | 1 | passed | 2 条失败（48 小时边界），恢复后全绿 |
| 反向验证：对账排除项 | 把 `RESERVE` 从 `ledgerSpendByDay` 的排除列表移除后跑 `-Dtest=AiObservabilityIntegrationTest` | 1 | passed | 1 条失败（期望 5、实得 55），恢复后全绿 |

| 后端单测全集 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 0 | passed | 291 执行 / 0 失败 / 13 跳过 |
| 迁移与模块闸 | `... -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest test` | 0 | passed | 9 执行 / 0 失败 |
| assistant 单测 | `pnpm --filter @paideia/assistant test` | 0 | passed | 26 执行 / 0 失败 |
| 管理端 e2e | `scripts/test-db.sh pnpm --filter @paideia/admin test:e2e` | 0 | passed | 27 passed |
| 类型检查 | `pnpm --filter @paideia/admin typecheck` 与 `pnpm --filter @paideia/assistant typecheck` | 0 | passed | 两端 tsc 均无输出 |

## 一、实施中抓到的两个真缺陷

**缺陷 1：日桶全部被静默跳过。** 首轮集成测试 `dailySeriesTotalsMatch` 期望 2、实得 0。
根因：`parseBucket` 对日桶也用了 `LocalDateTime.parse("2026-10-23")`，纯日期没有时分秒，直接抛异常，
而 catch 把它们当"不可解析"跳过。后果是**任何超过 48 小时的窗口都会返回一条全零折线**——
恰好是最常用的 7 天 / 30 天视图。修法：日桶走 `LocalDate.parse` + `atStartOfDay`。

**缺陷 2：概览的总数不受筛选影响。** `overviewMatchesListWithSameFilter` 期望 1、实得 3。
根因：`overview` 用的是全量 `countByStatus`，四个筛选参数只传给了 totals 没传给状态计数。
后果是"按场景筛选后概览仍显示全平台调用量"，而明细是对的——两个页面对不上。
修法：新增 `countByStatusFiltered` 并在 overview 里用它。

两个缺陷都**只有跑真数据库才看得见**：纯函数单测覆盖不到 SQL 与查询参数，而 UI 上"折线是平的"
与"数字没跟着筛选动"都不会报错。

| 反向验证：分桶边界 | 把 `Segmentation.of` 的 `<=` 改成 `<` 后跑 `-Dtest=SegmentationTest` | 1 | passed | 2 条失败（48 小时边界），恢复后全绿 |
| 反向验证：对账排除项 | 把 `RESERVE` 从 `ledgerSpendByDay` 的排除列表移除后跑 `-Dtest=AiObservabilityIntegrationTest` | 1 | passed | 1 条失败（期望 5、实得 55），恢复后全绿 |

## 二、偏差与未覆盖

1. **TASK-017（子步骤）只做了"支持"，没有真实产生点。** 仓库里 `SpanSpec.retrieval` 零使用，
   工具步内部也没有二次模型调用。所以本期交付的是：端口支持 `parentSeq`、落库与读取正确、
   详情页按层级缩进。**没有虚构一个假的子步骤埋点**。真正需要分层时要先有"工具内部再调模型"这种场景。
2. **AC-005 / 006 / 007 / 015 是部分覆盖**：代码路径与字段都验了，但"改配置真的跑一次"
   （改价目表、调小采样率、把队列灌满、关掉采集）留作后续。理由：这些都需要临时改配置重启，
   而它们的失败模式是"字段没透传"——那已经被现有的字段断言挡住了。
3. **学习者端 assistant e2e 未跑。** 它要真调上游模型，本次环境没有可用的 AI Key。
   反馈 UI 由 `conversation-panel.test.tsx` 的单测覆盖（点踩→展开原因→提交→显示已反馈）。
4. **`EXPLAIN` 没手工核对。** `LIKE CONCAT(#{tracePrefix}, '%')` 是前缀匹配、理论上走
   `uk_ai_runs_trace` 的范围扫描；`tracePrefixSearch` 验了结果正确性，但没有看执行计划。
   tasks.md 原本把这条写成"人工核对并记进报告"，**这一条没做**。
5. **管理端 e2e 第一次全红是因为 jar 没重建**，不是代码问题：playwright 的 webServer 跑的是
   `paideia-app-0.0.1-SNAPSHOT.jar`，改了后端代码必须先 `mvn package`。重打后 27 条全绿。

## 三、遗留风险

- **对账页一旦上线且差值非零，先怀疑记录口径**（缓存命中不计费、失败轮次不扣分、采样会删成功行），
  不要先怀疑计费 bug。采样 < 1 时对账必然出现正差，界面已写明这一点。
- **采集健康的数字是多实例不友好的**：每个进程各有一份。单实例部署下正确；变成多实例时要经 sink 汇总。
- **反馈是本地状态**：刷新页面后看不出已经反馈过。这是刻意的取舍（少一个回读请求），但意味着
  用户可能对同一条回答评两次——后端是覆盖，不会产生脏数据。
