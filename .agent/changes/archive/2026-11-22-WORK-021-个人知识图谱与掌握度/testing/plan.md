---
artifact: test-plan
work_id: WORK-021
work: 个人知识图谱与掌握度
status: ready
workflow: full
created: 2026-10-19
updated: 2026-10-19
---

# 个人知识图谱与掌握度测试计划

## 测试范围

四张新表与迁移、掌握度纯函数、浏览上报、图谱聚合端点、管理端概念端点与页面、2D/3D 图谱页、注销级联、助手工具与导师提示词。

不在范围内：真实上游模型调用（需要 DeepSeek Key，且本项不改变调用路径）。

## 环境与前置条件

- 后端测试：`source backend/config/remote-db.local.env` 后 `cd backend && mvn -B test`（本机 MySQL，`paideia_test` 库）。
- 学习者端 e2e：同一份环境变量，`pnpm --filter @paideia/app exec playwright test`；需要先 `mvn -B -DskipTests package` 产出 jar。
- 管理端 e2e：同上，`pnpm --filter @paideia/admin exec playwright test`。
- 前端单测：`pnpm --filter @paideia/knowledge test`。

## 验收矩阵

| AC | 证据 | 位置 |
| --- | --- | --- |
| AC-001 概念可挂两个条目 | 集成测试 | `ConceptGraphIntegrationTest.scoreAveragesAcrossCarrierEntries`、管理端 e2e `concepts.spec.ts` |
| AC-002 两条目分数都进入掌握度 | 集成测试 | `ConceptGraphIntegrationTest.scoreAveragesAcrossCarrierEntries` |
| AC-003 浏览一行、次数累加 | 集成测试 | `ConceptGraphIntegrationTest.repeatedViewIncrementsSameRow` |
| AC-004 草稿浏览 404 且零行 | 集成测试 | `ConceptGraphIntegrationTest.viewingDraftIsNotFound` |
| AC-005 四档规则 | 单测 | `ConceptMasteryTest` 四条（unknown/viewed/familiar/mastered） |
| AC-006 同输入同输出 | 单测 | `ConceptMasteryTest.sameInputIsDeterministic` |
| AC-007 到期不归零 | 单测 | `ConceptMasteryTest.reviewDueDoesNotZeroTheScore` |
| AC-008 无概念返回空 | 集成测试 | `ConceptGraphIntegrationTest`（空库分支）+ e2e `graph.spec.ts` 空状态 |
| AC-009 超 500 截断 | 单测 | `ConceptGraphSelectionTest`（4 条：有证据优先、分数降序、同分确定性、未超上限） |
| AC-010 2D 渲染与坐标复用 | 前端单测 + e2e | `concept-graph-layout.test.ts` + `graph.spec.ts` |
| AC-011 3D 按需加载 | 构建产物检查 | `dist/assets/3d-force-graph-*.js` 独立 chunk，`index` 中无 Three.js 符号 |
| AC-012 删概念后不再返回 | 集成测试 | `ConceptGraphIntegrationTest.deletingConceptCascadesLinksAndEdges` |
| AC-013 注销删浏览记录 | 集成测试（含反向验证） | `AccountDeletionCascadeIntegrationTest` |
| AC-014 助手只返回本人薄弱概念 | 工具实现 + 集成测试 | 工具内部只调 `ConceptGraphService.build(userId)`；隔离由 `otherUsersEvidenceIsInvisible` 覆盖 |
| AC-015 空状态文案 | e2e | `graph.spec.ts` 空状态用例 |
| AC-016 无 WebGL 不白屏 | e2e（注入 init script 打掉 WebGL） | `graph.spec.ts`「没有 WebGL 时给出可读提示」 |

## 自动化检查

```bash
cd backend && source config/remote-db.local.env && mvn -B test
cd frontend && pnpm --filter @paideia/knowledge test
cd frontend && pnpm --filter @paideia/app typecheck
cd frontend && pnpm --filter @paideia/admin typecheck
cd frontend && pnpm --filter @paideia/app exec playwright test
cd frontend && pnpm --filter @paideia/admin exec playwright test
```

## 人工检查

- 管理端概念页：建概念 → 挂条目 → 学习者端图谱出现该节点（本地手工走一次）。
- 3D 视角：切换后节点位置与 2D 一致，无抖动。

## 回归范围

- 既有 191 条后端测试全跑（含接口快照、全接口自查、模块边界）。
- 学习者端 80 条 e2e 全跑。
- 管理端 20 条 e2e 全跑。

## 已知缺口

1. **掌握度语义未经真人验证**：四档阈值（0.8、0.5 权重、30 天半衰期）是按"可解释"设计的，没有任何真实学习数据支撑。这是刻意的取舍——BKT 需要的数据量本项没有。
2. **e2e 夹具会在共享测试库短暂建概念**：用例内 `try/finally` 删除；中途异常会残留一条 `e2e 图谱概念 ...`。
3. **概念表初始为空**：本项交付后，线上图谱页会是空状态，直到管理员建立概念。这不是缺陷，但意味着"功能上线"与"功能可用"之间还差一次数据录入。
