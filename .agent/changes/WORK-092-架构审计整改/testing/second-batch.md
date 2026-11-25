# 架构审计整改：第二批验证报告

记录于 2026-11-25。第二批 TASK-004～TASK-007 已完成；仍为阶段证据，用户业务验收尚未进行。

## 验证环境

`work/WORK-092` 独立工作树。后端经 `scripts/test-db.sh` 使用隔离测试库（远程 `paideia_e2e`，单轮独占，跑完不残留临时库）；前端用本地 vitest 与 tsc，合成夹具，不调用真实模型、外部 MCP 或生产。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-004：学习进度并发不丢写 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=LearningProgressIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`（`backend/`） | 0 | passed | `Tests run: 11, Failures: 0, Errors: 0`，BUILD SUCCESS；含新增 `concurrentDisjointBlocksBothSurvive`：两线程对不同知识块并发提交，两块的 `challenges.c1`、`c2` 都保留（旧「读快照 + 整行 upsert」会丢一块）。 |
| AC-004：笔记自动保存不丢写 | `pnpm --filter @paideia/knowledge exec vitest run src/note-autosave.test.ts`（`frontend/`） | 0 | passed | 5 条通过：慢保存期间继续编辑会补存最新正文 v2（旧实现会丢 v2）、保存失败保持 dirty 并报错、`markClean` 取消排队保存、`shouldApplyServerDocument` 真值表、切换文档丢弃旧文档排队保存。 |
| AC-004：类型检查 | `pnpm --filter @paideia/knowledge typecheck`；`pnpm --filter @paideia/app typecheck`（`frontend/`） | 0 | passed | 两包 tsc 通过。 |
| AC-005：AI 计费预约幂等与恢复 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AiCreditIntegrationTest,AccountDeletionCascadeIntegrationTest,ModularityTest -Dsurefire.failIfNoSpecifiedTests=false test`（`backend/`） | 0 | passed | `Tests run: 13, Failures: 0, Errors: 0`，BUILD SUCCESS。`AiCreditIntegrationTest` 8 条（含新增 `settleRoundIsIdempotent`：同一 round 结算两次只记一行 −150、状态 SETTLED、余额 10000−150；`refundRoundIsIdempotent`：退回两次余额只恢复一次、状态 REFUNDED；`staleReservationIsRecovered`：拨旧 `created_at` 后恢复任务整笔退回、重复恢复不再变动）；`AccountDeletionCascadeIntegrationTest` 4 条（含预约行注销后逐表归零）；`ModularityTest` 1 条。迁移 `V20261125141000__add_ai_credit_reservations.sql` 在隔离库（`paideia_e2e`，77+ 迁移）校验通过。 |
| AC-005：知识导入中断可恢复 + fencing | `../scripts/test-db.sh mvn -B -pl paideia-ai,paideia-app -am -Dtest=KnowledgeImportServiceTest,KnowledgeImportJobTest,KnowledgeImportRecoveryIntegrationTest,KnowledgeImportFlowIntegrationTest,ModularityTest -Dsurefire.failIfNoSpecifiedTests=false test`（`backend/`） | 0 | passed | BUILD SUCCESS。新增 `KnowledgeImportRecoveryIntegrationTest` 2 条：过期令牌写结果被 fencing 丢弃、不覆盖新结果；超时 `processing` 被 `requeueStale` 回收为 pending、清空令牌、旧令牌失效后可重新领取。回归：`KnowledgeImportFlowIntegrationTest` 3 条（草稿落库/指纹去重/RAG）仍通过；`KnowledgeImportServiceTest` 6、`KnowledgeImportJobTest` 3、`ModularityTest` 1。迁移 `V20261125142000__add_knowledge_import_lease.sql` 在隔离库校验通过。 |
| AC-007：SSE 未完整结束不显示正常完成 | `pnpm --filter @paideia/assistant test`；`pnpm --filter @paideia/app typecheck`（`frontend/`） | 0 | passed | assistant 4 文件 24 条通过：`sse.test.ts` 11 条含"仅正文断流 terminated=false""error 也算终止"；新增 `client.test.ts` 3 条：完整 done 不补错并按 `input/output/total` 归一、无终止断流合成一条"未能完整结束"错误、服务端已发 error 不叠加。app tsc 0。 |

## 验收结果

- AC-004 有局部自动化证据（后端并发 + 前端自动保存）；尚未做用户业务验收。
- AC-005 在 AI 计费预约这一条路径上有自动化证据（幂等/恢复/注销级联）；其余 AI 服务仍是旧 `reserve/settle/refund`（刻意的切片边界，见 tasks.md 实现补充）。
- AC-007 有自动化证据（截断流合成错误事件 + 面试页既有 error 处理）；尚未做真实浏览器截断 e2e。
- 第二批 4 项均有阶段性自动化证据；WORK-092 因需用户业务验收与第三批（REQ-006/007）仍保持打开。

## 失败与未验证项

- 学习并发用例为 5 次循环的确定性调度注入，非压测；未在旧实现上另跑红测留档。
- 笔记自动保存回归为引擎级单测；页面装配由 tsc 保证编译，未跑真实浏览器 e2e 的慢保存路径。
- 计费幂等/恢复为集成测试（真实 MySQL 事务），恢复任务按调度装配但未做"真实进程中途被杀"的端到端演练；`@Scheduled` 触发阈值用拨旧 `created_at` 的确定性方式验证。
- 导入恢复为真实 SQL 契约集成测试（领取/ fencing / 回收）；未做"真实进程在领取后被 kill -9"的端到端演练，超时用拨旧 `processing_since` 的确定性方式验证。
- 截断流为解析层 + 客户端层单测；未在真实浏览器里断网跑面试页 e2e，页面"没成功"提示依赖既有 `error` 分支（tsc 保证装配）。
- 未触碰线上库、共享库、生产或真实模型。
