---
artifact: test-plan
work_id: WORK-069
work: AI 失败的处置语义
status: draft
created: 2026-11-04
updated: 2026-11-04
---

# AI 失败的处置语义测试计划

## 测试范围

- `failure_kind` 迁移在零数据新库与已有数据上的可执行性（迁移三守卫）。
- `RunRecorder` / `RunRecord` / `MysqlRunSink` / `AiRunMapper` 对 `failure_kind` 的写入与读取。
- 记忆提取的 `bad_output` / `needs_human` 直接 `failed`、`retryable` 继续退避。
- 营地出题三档映射；面经抓取与单篇抽取的两条失败路径。
- 管理端按 `failure_kind` 的筛选、概览计数与前端类型检查。

## 环境与前置条件

- 后端测试一律走 `scripts/test-db.sh`（独占 `paideia_run_<时间>_<pid>` 库）。
- `PAIDEIA_TEST_DB_PASSWORD` 按项目既有方式从被忽略配置注入；未配置时集成测试会整模块跳过，报告需单独写 skipped。
- 前端 `pnpm --filter @paideia/admin typecheck`。
- 实施前上游工件（requirements/proposal/design/tasks）均已批准；本计划执行前确认不是 `draft`。

## 验收矩阵

| 验收 | 检查 | 预期 |
| --- | --- | --- |
| AC-001 | `MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest`；零数据新库启动 | 三件全绿；迁移在空库与已有库都能应用 |
| AC-002 | `MemoryExtractionJobsIntegrationTest.permanentFailureStopsRetry` / `retryableFailureStillBacksOff`；`MemoryFailureClassifierTest` | 不可重试 `failed` 且 attempts/next_attempt 不推进；可重试仍退避；反向验证变红后还原复绿 |
| AC-003 | `CampServiceTest.emptyResponseIsFailure` / `badShapeMapsToBadOutput` / `bothRoundsBadRefunds` | 三档映射正确（`EMPTY_RESPONSE → needs_human`、`BAD_SHAPE / NO_VALID_ITEM → bad_output`） |
| AC-004 | `EssayCollectionFailureKindTest.fetchFailureIsRetryable` / `perPostFailureIsBadOutputAndDoesNotAbortRun`；`EssayCollectionIntegrationTest` | 抓取失败 `retryable`、单篇失败 `bad_output`、不重试且不中断整轮 |
| AC-005 | `AiObservabilityIntegrationTest.failureKindFilterReturnsOnlyMatchingRows`；`pnpm --filter @paideia/admin typecheck` | 筛选只返回对应行；`byFailureKind` 计数正确；类型检查 0 错 |

## 自动化检查

- `scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=DefaultRunRecorderTest,MemoryFailureClassifierTest,CampServiceTest,EssayCollectionFailureKindTest test`
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MemoryExtractionJobsIntegrationTest,AiObservabilityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `cd frontend && pnpm --filter @paideia/admin typecheck`

## 人工检查

- 管理端「AI 调用」列表：按 `retryable` / `bad_output` / `needs_human` 筛选，确认只显示对应行且标签可读。
- 管理端概览：「按失败处置」排行卡在有数据时出现，空窗口显示空状态。
- 线上（部署后）：触发一次可控的记忆提取失败或使用测试库注入一行，确认 `ai_runs.failure_kind` 与任务行状态一致。

## 回归范围

- 现有 `AiObservabilityIntegrationTest` 的状态计数、分桶、对账与筛选总数一致性。
- `EssayCollectionIntegrationTest` 既有抓取/去重/分类路由行为。
- `CampServiceTest` 既有成功、余额不足、空简历、修复重试路径。
- `MemoryExtractionJobsIntegrationTest` 既有入队原子性、抢占单飞、退避上限、卡死回收、注销/保留回收。

## 已知缺口

- 不覆盖真实上游的 `needs_human` 401/403 场景（需真实凭据）；用 `BizException(ErrorCode.INVALID_ARGUMENT)` 桩覆盖分类。
- 面经 `RunRecorder` 新增的 token 用量暂不采集（`EssayExtractor` 当前不返回 usage）；本项只保证失败处置可统计。
- 管理端新筛选暂无独立 e2e（`ai-runs.spec.ts` 现状以空状态/有数据二选一断言）；由集成测试 + typecheck 覆盖，部署后人工检查。
