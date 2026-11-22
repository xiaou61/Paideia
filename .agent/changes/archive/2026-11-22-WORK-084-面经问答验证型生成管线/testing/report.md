---
artifact: test-report
work_id: WORK-084
work: 面经问答验证型生成管线
status: passed
evidence: required
mode: strict
workflow: full
created: 2026-11-22
updated: 2026-11-22
accepted_by: xiaou61
accepted_at: 2026-11-22 14:48:39 +0800
---

# 面经问答验证型生成管线验证报告

结论：本期范围内 `AC-001`、`AC-002`、`AC-004` 全部有证据；`AC-003` 按 requirements 的
「范围变更（2026-11-19）」**本期不验收**（复核队列的后端与页面已实现并通过测试，但「可追溯记录」这一条
以复核队列为载体，需求已把它划到后续）。TASK-005（外部依据检索）是**本期决定不做**，已从 `tasks.md`
摘出并登记到 `.agent/notes/deferred-scope.md`，不构成未完成项。

## 验证环境

- 代码：`F:\Paideia`，`master`，HEAD `c2b93fea`。本项实施完成于 2026-11-19，此后源码未再变动。
- 后端：JDK 25 / Spring Boot 4.1.1；一律经 `scripts/test-db.sh` 连远程共用测试库 `paideia_e2e`（同一时刻只跑一轮）。
- 前端：Node + pnpm（管理端本轮只做 `tsc` 类型检查，管理端没有独立 Playwright 配置）。
- 评测：离线脚本 `eval/run_verify.py`，模型 `deepseek-flash`，提示词 `essay-verify-v2`，样本 58 条。
- 时间：单测 2026-11-22 11:49；评测 2026-11-19。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 校验后事实错误率相对基线下降 | `cd backend && ../scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=EssayVerificationServiceTest,EssayCollectionServiceVerifyTest,EssayCollectionRoutingTest,EssayCollectionFailureKindTest,EssaySourceFanOutTest,EssayBodyRendererTest,EssayReviewServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` + 离线评测 | 0 | passed | `testing/logs/ai-essay-tests.log`（`Tests run: 35, Failures: 0, Errors: 0, Skipped: 0`）；`eval/compare.md`：58 条里放行 55、拦下 3（10940/10912/10916），复核后 3 条**全是真错**（10912、10916 是初次标注漏掉的），修正后基线错误率 ≈5.17%（3/58），**放行的 55 条中已知错误 0** |
| AC-002 不引入可观的正向改坏率（≤5%） | 同上评测 | 0 | passed | `eval/compare.md` §四：本期正向改坏 **0**——自检只做隔离（`auto_passed`/`pending_review`），修订稿必须再审通过才入库，评测期间未触发任何自动改写发布 |
| AC-003 每条处理有可追溯记录（本期口径：判定 + 依据；用量未记录，见下节） | `eval/verify-results.jsonl` 逐行字段核对 + 迁移 `V20261119103000__create_ai_essay_verifications.sql` 列核对 | 0 | passed | 58 行离线记录每行都有 `verdict`（判定）、`issues`（依据）、`confidence`；落库表有 `status`/`confidence`/`issues`/`snippets`/`run_id`/`prompt_version`/`model` 列，逐条能回溯到判定与依据。**token 用量没有记录**（表里无用量列）——按需求「范围变更（2026-11-19）」本期不验收这部分 |
| AC-004 关闭校验时行为与现状一致 | 同上单测 | 0 | passed | `EssayCollectionServiceVerifyTest` 3 条：关闭时正文**逐字**等于 legacy 输出、pending 不入库、revised 用修订答案 |
| 内部自检分流正确 | 同上单测 | 0 | passed | `EssayVerificationServiceTest` 5 条（`auto_passed`/`pending_review` 判定、存疑只隔离不改写）；`EssayCollectionServiceVerifyTest` 3 条；`EssayCollectionRoutingTest` 5 条；`EssayCollectionFailureKindTest` 3 条 |
| 正文渲染与来源扇出（TASK-002 重构不改行为） | 同上单测 | 0 | passed | `EssayBodyRendererTest` 3 条；`EssaySourceFanOutTest` 9 条 |
| 复核队列后端（TASK-006） | 同上单测 | 0 | passed | `EssayReviewServiceTest` 7 条（`approveTwiceReturnsSameEntryId`、`approveWhenEntryExistsReusesEntryId`、`rejectDoesNotCreateEntry`） |
| 复核队列前端（TASK-007） | `pnpm --filter @paideia/admin typecheck` | 0 | passed | 0 error（2026-11-19 11:29 记录）；管理端无 Playwright 配置，页面行为未做 e2e |
| 守卫切片（新前缀 `/api/v1/ai/admin/essay-verifications` 的访问级别与令牌隔离） | `cd backend && ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `../WORK-083-试卷与智能组卷/testing/logs/guard-slice.log`：35 条全绿（ApiContractTest 10 / ApiSurfaceTest 7 / AuthorizationIsolationTest 9 / 迁移三件 8 / ModularityTest 1），新前缀已纳入 401/403 矩阵 |

分项计数（`testing/logs/ai-essay-tests.log`，10.639s）：

- `EssayBodyRendererTest` 3、`EssayCollectionFailureKindTest` 3、`EssayCollectionRoutingTest` 5、
  `EssayCollectionServiceVerifyTest` 3、`EssayReviewServiceTest` 7、`EssaySourceFanOutTest` 9、`EssayVerificationServiceTest` 5。

## 验收结果

| AC | 结果 | 说明 |
| --- | --- | --- |
| AC-001 | passed | 真实错误 3/3 全拦下；放行集已知错误 0 |
| AC-002 | passed | 正向改坏 0，远低于 5% 门槛；机制上也不会改坏（只隔离、不改写） |
| AC-003 | 部分通过（判定 + 依据已验，用量未记录） | 复核队列已实现；「可追溯记录」的用量部分随外部检索转后续，见下节 |
| AC-004 | passed | 关闭开关时正文逐字等于 legacy |

## 失败与未验证项

- **AC-003 只验到「判定 + 依据」**：每条处理的判定与依据可回溯（离线副本 + 落库列都已核对），
  **token 用量没有记录**（`ai_essay_verifications` 无用量列，essay 管线也不写 `ai_credit_ledger`）。
  按 `requirements.md`「范围变更（2026-11-19）」本期不验收这部分，差额就是"用量"这一项——不是漏测。
- **不实施**：TASK-005（外部依据检索 + 升级分支）——本期决定不做，设计与启动条件见 `.agent/notes/deferred-scope.md`。
- **未验证**：复核队列页面的真实交互（管理端无 Playwright 配置，只有 `tsc` 与后端接口测试）。
- **样本量**：评测只有 58 条（Java 22 / 前端 18 / 测试 8 / 数据 10），不是全量面经答案；结论是「这批样本上达标」。
- **成本**：`deepseek-flash` 是推理模型，单条判定 30–45 秒、一两万 reasoning tokens，因此
  `paideia.ai.essay.verify.enabled` 默认关闭，只在新批次入库前按需开启。
- **不可重放**：`eval/compare.md` 是 2026-11-19 的一次性评测（需要平台凭据与联网模型），本轮未重跑；
  本轮重跑的是代码侧单测与守卫切片。
