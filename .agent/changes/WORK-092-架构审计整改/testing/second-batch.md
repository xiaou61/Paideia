# 架构审计整改：第二批验证报告

记录于 2026-11-25。仅为阶段证据；TASK-004 完成，TASK-005～007 仍在实施，最终验收报告待全部范围有结论后再形成。

## 验证环境

`work/WORK-092` 独立工作树。后端经 `scripts/test-db.sh` 使用隔离测试库（远程 `paideia_e2e`，单轮独占，跑完不残留临时库）；前端用本地 vitest 与 tsc，合成夹具，不调用真实模型、外部 MCP 或生产。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-004：学习进度并发不丢写 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=LearningProgressIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`（`backend/`） | 0 | passed | `Tests run: 11, Failures: 0, Errors: 0`，BUILD SUCCESS；含新增 `concurrentDisjointBlocksBothSurvive`：两线程对不同知识块并发提交，两块的 `challenges.c1`、`c2` 都保留（旧「读快照 + 整行 upsert」会丢一块）。 |
| AC-004：笔记自动保存不丢写 | `pnpm --filter @paideia/knowledge exec vitest run src/note-autosave.test.ts`（`frontend/`） | 0 | passed | 5 条通过：慢保存期间继续编辑会补存最新正文 v2（旧实现会丢 v2）、保存失败保持 dirty 并报错、`markClean` 取消排队保存、`shouldApplyServerDocument` 真值表、切换文档丢弃旧文档排队保存。 |
| AC-004：类型检查 | `pnpm --filter @paideia/knowledge typecheck`；`pnpm --filter @paideia/app typecheck`（`frontend/`） | 0 | passed | 两包 tsc 通过。 |
| AC-005～007 | 无 | — | not run | TASK-005～007 仍在实施。 |

## 验收结果

- AC-004 有局部自动化证据（后端并发 + 前端自动保存）；尚未做用户业务验收。
- AC-005～007 不在本轮。整个 WORK-092 保持打开。

## 失败与未验证项

- 学习并发用例为 5 次循环的确定性调度注入，非压测；未在旧实现上另跑红测留档。
- 笔记自动保存回归为引擎级单测；页面装配由 tsc 保证编译，未跑真实浏览器 e2e 的慢保存路径。
- 未触碰线上库、共享库、生产或真实模型。
