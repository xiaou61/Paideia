---
artifact: test-plan
work_id: WORK-084
work: 面经问答验证型生成管线
status: ready
mode: strict
workflow: full
created: 2026-11-22
updated: 2026-11-22
---

# 面经问答验证型生成管线测试计划

## 测试范围

本项给面经（essay）生成链路加一道「事实自检」闸：抽题结果先判，只有判定通过或已修订的才 `createCollected`，其余进复核队列。验证分三层：

- **行为不变的重构**：`EssayBodyRenderer` 从 `EssayCollectionService` 抽出来后，正文渲染逐字节一致（TASK-002）。
- **闸门逻辑**：判定分流、状态机、预算与异常兜底、写记录（TASK-004）。
- **效果**：基线 vs 校验后的样本级对比（TASK-008，离线评测，非单元测试）。

外部依据检索与联网核验按需求的范围变更（2026-11-19）**本期不实施**，相应验收不在本计划内。

## 环境与前置条件

- 上游：`requirements.md`（approved 2026-11-19 10:42:26，李子凡/技术）与 `tasks.md`（approved 2026-11-19 10:44:04）。
- 后端测试经 `scripts/test-db.sh`，在 `backend/` 下用 `../scripts/test-db.sh`；远程测试库同一时刻只允许一轮。
- 评测（TASK-008）不碰数据库：`eval/run_verify.py` 读 `eval/meta.tsv` 与 `raw-samples*.jsonl`，调模型 API，输出 `verify-results.jsonl`。
- 关闭开关的回归（AC-004）靠配置 `verify.enabled=false` 下走原分支，由 `EssayCollectionServiceVerifyTest` 覆盖。

## 验收矩阵

| AC | 验证方式 | 具体用例/命令 |
| --- | --- | --- |
| AC-001 事实错误率相对基线下降 | 人工 + 离线评测 | `eval/compare.md` 的 58 条抽样对比（基线 1.72% → 校验后，含放行/拦下明细） |
| AC-002 不引入可观的正向改坏率（≤5%） | 离线评测 | 同上：`compare.md` 的「正向改坏」计数为 0；`EssayVerificationServiceTest` 覆盖 REVISED 分支不改答案的约束 |
| AC-003 每条处理有可追溯记录 | 单测 | `EssayVerificationServiceTest`（每次判定落 `ai_essay_verifications`，含判定/依据/用量）；复核队列读侧 `EssayReviewServiceTest` |
| AC-004 关闭校验时行为与现状一致 | 单测 | `EssayCollectionServiceVerifyTest`（`verify.enabled=false` 走原 `judge` 直通路径） |
| 重构不改渲染输出 | 单测 | `EssayBodyRendererTest`（3 条：标题转义、URL 与安全标题、空答案） |
| 抽题分流与失败归类 | 单测 | `EssayCollectionRoutingTest`（5）、`EssayCollectionFailureKindTest`（3）、`EssaySourceFanOutTest`（9） |
| 新增管理端点声明了访问级别 | 守卫 | `ApiSurfaceTest` / `ApiContractTest` / `AuthorizationIsolationTest`（`/api/v1/ai/admin/essay-verifications` 必须 `@ApiAccess(ADMIN)`） |
| 复核页能编译并被路由挂上 | 类型检查 | `pnpm --filter @paideia/admin typecheck` |

## 自动化检查

1. `cd backend && ../scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=EssayVerificationServiceTest,EssayCollectionServiceVerifyTest,EssayCollectionRoutingTest,EssayCollectionFailureKindTest,EssaySourceFanOutTest,EssayBodyRendererTest,EssayReviewServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
2. 守卫切片（与 WORK-083 共用一次运行，两处报告引用同一份输出）：`cd backend && ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test`
3. `pnpm --filter @paideia/admin typecheck`
4. `pnpm --filter @paideia/knowledge test`（TASK-007 的 `knowledge-shared` 客户端方法改动影响面）
5. 评测：`python eval/run_verify.py`（TASK-008 已执行，产物 `eval/verify-results.jsonl` 与 `eval/compare.md`）

## 人工检查

- 复核页（`/knowledge/essay-reviews`）在管理端菜单可见、列表能翻页、采纳与驳回各点一次（TASK-007 已做，本轮以类型检查 + 路由存在为界，不重复点）。
- 评测样本的标签（事实无误/错误/无法判定）是人工标注，属 TASK-001 产物，不重复标注。

## 回归范围

- 面经抓取主链路（`EssayCollectionService` 的抓取、正文渲染、`createCollected` 入库）行为不变：`EssayBodyRendererTest` + `EssayCollectionServiceVerifyTest` 的关闭分支。
- 其他 AI 运行记录（`AiRunSpan`）口径未改，仅新增 `RunKind.ESSAY_VERIFY` / `ESSAY_REVISE` 两个枚举值。

## 已知缺口

- **AC-003 的验收口径与实现不一致**：`requirements.md` 的范围变更写「AC-003（复核队列）本期不验收」，但 TASK-006/007 已把复核队列实现并有测试。本报告按实测记录（有实现、有测试），同时在验收结论里注明需求方口径未要求本期验收。
- **外部依据检索（原 TASK-005）本期不实施**，`verify.external=false`；因此「有依据的修订」这条路径没有测试，也不能声称覆盖。
- **评测样本量 58**：不是全量面经，结论只能说到「这批样本上的错误率」。
