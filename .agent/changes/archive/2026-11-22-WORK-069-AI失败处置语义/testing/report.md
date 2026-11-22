---
artifact: test-report
work_id: WORK-069
work: AI 失败的处置语义
status: passed
evidence: required
created: 2026-11-04
updated: 2026-11-04
verified_commit: 23e2e368
verified_at: 2026-11-04 15:02 +0800
---

# AI 失败的处置语义验证报告

## 验证环境

- 工作树：`F:\Paideia\.worktrees\work-069-failure-kind`，分支 `work-069-failure-kind`
- 实现提交：`23e2e368`（`feat(ai): ai_runs 增加 failure_kind 与三条链路处置（WORK-069）`）
- JDK 25.0.4、Spring Boot 4.1.1、MySQL 8（`scripts/test-db.sh` 分配的独占 `paideia_run_*` 库）
- 前端 Node v24.14.1、pnpm 12.10.1（admin 包）

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 迁移三守卫 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 8 条 / 0 失败；Flyway 在独占库上执行到 `20261104142000 - add ai runs failure kind`；`migration-checksums.txt` 已登记新迁移 |
| AC-002 记忆不可重试失败直接 failed | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MemoryExtractionJobsIntegrationTest#permanentFailureStopsRetry -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `tests run 1 / failures 0`；任务行 `status=failed`、`attempts=1`、`created_at→next_attempt_at=0s`，`ai_runs.failure_kind=needs_human` |
| AC-002 记忆 retryable 仍退避 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MemoryExtractionJobsIntegrationTest#retryableFailureStillBacksOff -Dsurefire.failIfNoSpecifiedTests=false test`（同文件完整运行） | 0 | passed | `MemoryExtractionJobsIntegrationTest` 10/0/0；`status=pending`，退避约 `retryBackoffSeconds` |
| AC-002 反向验证 | 临时把 `failPermanently` 调用改回 `fail` 后运行 `permanentFailureStopsRetry` | 1 | passed | 按预期变红，报错 `等永久失败落定超时`；恢复后重跑同测试为 1/0/0 |
| AC-003 营地三档映射 | `mvn -B -pl paideia-ai -am -Dtest=CampServiceTest test` | 0 | passed | 11 条 / 0 失败；`emptyResponseIsFailure`、`badShapeMapsToBadOutput`、`bothRoundsBadRefunds` 分别断言 `needs_human` / `bad_output` / `bad_output` 的三参 `finish` |
| AC-004 面经两条失败路径 | `mvn -B -pl paideia-ai -am -Dtest=EssayCollectionFailureKindTest,EssayCollectionRoutingTest test` | 0 | passed | 3 + 5 条 / 0 失败；抓取失败 `retryable`、单篇失败 `bad_output`；单篇失败后其它帖子继续 |
| AC-004 既有面经行为回归 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=EssayCollectionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 3 条 / 0 失败 |
| AC-005 管理端筛选与计数 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AiObservabilityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 12 条 / 0 失败；`failureKindFilterReturnsOnlyMatchingRows` 断言筛选只返回 `bad_output` 行且 `byFailureKind` 计数正确 |
| AC-005 前端类型检查 | `cd frontend && pnpm -r typecheck` | 0 | passed | 17 个包全部 `Done`，0 error |
| AC-005 管理端构建 | `cd frontend && pnpm --filter @paideia/admin build` | 0 | passed | `vite build` 成功，30.49 秒 |

### 附加检查

- `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`：**BUILD SUCCESS**；`paideia-ai` 405 条 / 0 失败 / 15 跳过（跳过均为需要真上游的 live 测试）。
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test`：**26 条 / 0 失败 / 0 跳过**（ApiSurface 7、ApiContract 10、AuthorizationIsolation 9）。
- `mvn -B -pl paideia-ai -am test`：**405 条 / 0 失败 / 15 跳过**。

## 验收结果

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| AC-001 | passed | 迁移与三件守卫通过 |
| AC-002 | passed | 永久失败不重试、可重试仍退避；反向验证红→绿 |
| AC-003 | passed | 三种 `CampGenerationException.Kind` 映射正确 |
| AC-004 | passed | 面经抓取/抽题两条失败路径写入正确 `failure_kind`，行为不变 |
| AC-005 | passed | 管理端筛选、`byFailureKind` 计数、类型检查与构建通过 |

## 失败与未验证项

- **`pnpm -r test` 未全绿（与本项无关，master 上已复现）**：`packages/knowledge` 的 `roleMeta('frontend').available` 期望 `false`、实际 `true`，以及 `scoreTracks` 在岗位无内容时的期望为空、实际返回 `redis`/`spring` 两条。该失败在同一提交的 `F:\Paideia` master 上同样出现，属 WORK-068 内容映射后的测试期望未更新，不是 WORK-069 引入。
- **管理端 e2e 未运行**：`ai-runs.spec.ts` 依赖本地后端 jar 与 Playwright 服务，本轮用 `AiObservabilityIntegrationTest`、admin typecheck 与 build 覆盖；已在 `e2e/ai-runs.spec.ts` 补上 `runs-filter-failure-kind` 可见性断言，留在有完整 e2e 环境时跑。
- `failure_kind` 只对三条链路做明确写入；其它 AI 路径仍保持 `NULL`，管理端显示「未记录」，不冒充失败归因。

## 剩余风险

- `EssayCollectionService` 新增的遥测只记录 `failure_kind`，不记录 token 用量（`EssayExtractor` 当前不返回 usage）；不影响失败处置判定。
- `failure_kind` 不加索引，当前数据量下全表扫可接受；`shortcut: 行数上百万后补 (failure_kind, started_at) 索引`。
- 记忆提取的分类基于异常类型与 `ErrorCode.INVALID_ARGUMENT`，未覆盖真实上游 401/403 的 live 场景；用桩异常覆盖分类逻辑。
