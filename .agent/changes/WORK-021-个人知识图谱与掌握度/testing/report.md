---
artifact: test-report
work_id: WORK-021
work: 个人知识图谱与掌握度
status: passed
evidence: required
workflow: full
created: 2026-10-19
updated: 2026-10-19
accepted_by: xiaou61
accepted_at: 2026-10-29 13:18:38 +0800
---

# 个人知识图谱与掌握度验证报告

## 验证环境

- 后端：JDK 25 / Spring Boot 4.1.1 / 本机 MySQL（`paideia_test` 库，Flyway 已推进到 V19）。
- 前端：Node + pnpm 12.10.1，Chromium（Playwright），Vite 构建。
- 环境变量：`source backend/config/remote-db.local.env`（被忽略的本地文件）。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 | `mvn -B -pl paideia-app -am test -Dtest=ConceptGraphIntegrationTest` | 0 | passed | 8 tests, 0 failures |
| AC-002 | 同上（`scoreAveragesAcrossCarrierEntries`） | 0 | passed | 断言 `scoredEntries == 2`、`entries.length == 2` |
| AC-003 | 同上（`repeatedViewIncrementsSameRow`） | 0 | passed | `view_count=3`、行数=1 |
| AC-004 | 同上（`viewingDraftIsNotFound`） | 0 | passed | 404 且行数=0 |
| AC-005 | `mvn -B -pl paideia-knowledge -am test -Dtest=ConceptMasteryTest` | 0 | passed | 13 tests, 0 failures |
| AC-006 | 同上（`sameInputIsDeterministic`） | 0 | passed | 两次 `Result` 相等 |
| AC-007 | 同上（`reviewDueDoesNotZeroTheScore`） | 0 | passed | `reviewDue=true` 且 `score=1.0` |
| AC-008 | e2e `graph.spec.ts` + 集成测试空库分支 | 0 | passed | 空状态可见，接口返回空数组 |
| AC-009 | `mvn -B -pl paideia-knowledge -am test -Dtest=ConceptGraphSelectionTest` | 0 | passed | 4 tests：有证据者优先、分数降序、同分按 id、未超上限原序 |
| AC-010 | `pnpm --filter @paideia/knowledge test` + e2e | 0 | passed | 10 条坐标用例；图谱页可渲染 |
| AC-011 | `pnpm --filter @paideia/app build` + 产物检查 | 0 | passed | `3d-force-graph-*.js` 独立 chunk（1386 kB）；`index-*.js` 中 `WebGLRenderer` 计数 0 |
| AC-012 | `ConceptGraphIntegrationTest.deletingConceptCascadesLinksAndEdges` | 0 | passed | 删后关联与边均为 0 |
| AC-013 | `-Dtest=AccountDeletionCascadeIntegrationTest` | 0 | passed | 2 tests；**反向验证**：注释掉 `knowledge_entry_views` 那一行后报 `[knowledge_entry_views 里还留着这个人的数据]` |
| AC-014 | `-Dtest=AgentToolBridgeIntegrationTest` + `ConceptTools` 实现 | 0 | passed | 工具在清单内且 `readOnly=true`；工具内部只调 `build(userId)` |
| AC-015 | e2e `graph.spec.ts` 空状态用例 | 0 | passed | 空状态含"概念"字样 |
| AC-016 | e2e `graph.spec.ts`「没有 WebGL 时给出可读提示」 | 0 | passed | 注入 init script 打掉 WebGL 上下文后出现 `graph-fallback` |
| 全量后端 | `cd backend && mvn -B test` | 0 | passed | 各模块 6/118/6/25 全绿；paideia-app 191 tests, 0 failures, **4 skipped**（`AssistantLiveUpstreamIntegrationTest` 需真实上游） |
| 学习者端 e2e | `pnpm --filter @paideia/app exec playwright test` | 0 | passed | 80 passed, **0 skipped**, 0 failed（graph.spec 6 条单独复跑同样 6 passed） |
| 管理端 e2e | `pnpm --filter @paideia/admin exec playwright test` | 0 | passed | 19 passed（+ 新增 `concepts.spec.ts` 单独跑 1 passed） |
| 前端单测 | `pnpm --filter @paideia/knowledge test` | 0 | passed | 42 tests（含新增 10 条坐标用例） |
| 类型检查 | `pnpm --filter @paideia/app typecheck`、`--filter @paideia/admin typecheck` | 0 | passed | 0 errors |
| 接口快照 | `-Dapi.shape.update=true` 后重跑 `ApiWalkthroughTest` | 0 | passed | 新增 12 个端点条目；`lineKind`、`sortOrder` 为既有无意之变更，一并登记 |
| 模块边界 | `ModularityTest` | 0 | passed | 加载上下文即校验通过 |

## 验收结果

AC-001～AC-016 全部有可执行证据并通过。

**跳过项说明**：后端 4 条 `AssistantLiveUpstreamIntegrationTest` 需要真实 DeepSeek 上游，属于既有跳过，与本项无关。学习者端 e2e **0 跳过**——原先"有概念才出现"的 4 条分支已改为在用例内用管理员账号现造概念夹具，跑完删除。

## 失败与未验证项

开发过程中出现并已修的问题（都不是最终状态）：

1. `ConceptMastery` 初版把"浏览"当加权项，导致"满分 + 读过"算出 0.88 < 满分。改为浏览只作兜底信号。
2. `ApiWalkthroughTest` 的"全接口自查"漏了新端点，`AgentToolBridgeIntegrationTest` 的工具白名单未含新工具——两条都是新增端点/工具的必然结果，已补。
3. 测试间共享同一个库导致 `otherUsersEvidenceIsInvisible` 读到上一轮残留，加了 `clearEvidence`。

## 剩余风险

1. **掌握度阈值没有任何真实数据支撑**。0.8 及格线、0.5/0.3 权重、30 天半衰期都是按可解释性定的，不是拟合出来的。将来有真实学习数据后应重新评估，可能整体换 BKT。
2. **线上图谱页现在会是空的**。概念表为空，V19 只建表不灌数据。功能上线 ≠ 功能可用，需要管理员录入首批概念。
3. **条目页的分数上报依赖 WORK-020 TASK-013**，该任务尚未完成。在此之前掌握度只有浏览与自评两路证据，档位上限是「熟悉」。
4. **3D 的实际观感未经验收**。构建与切换都通过，但"3D 在小屏上是否可用"只有真人能判断。
5. **e2e 夹具会在共享测试库里短暂建概念**。用例内用 `try/finally` 删除，中途异常时会残留一条 `e2e 图谱概念 ...`；不影响功能，但会让测试库的概念数缓慢增长。
