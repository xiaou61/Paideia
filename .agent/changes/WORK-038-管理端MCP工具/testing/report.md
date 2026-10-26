---
artifact: test-report
work_id: WORK-038
work: 管理端 MCP 工具
status: passed
evidence: required
verified_commit: e6f9629
created: 2026-10-26
updated: 2026-10-26
---

# WORK-038 验证报告

日期：2026-10-26。每条结论对应一次真实执行；命令都在 `.worktrees/work-038/backend` 或
`.worktrees/work-038/frontend/apps/app` 下执行，数据库经 `scripts/test-db.sh` 分配独占库。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 | `scripts/test-db.sh mvn -B -pl paideia-mcp -am -Dtest=PatServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 与 `... -pl paideia-app -am -Dtest=McpAdminIntegrationTest ... test` | 0 | 通过 | `adminScopeIsIssuedOnlyToAdminRole` 发放成功；`adminScopeRequiresAdminRole` 学习者 POST 得 400、管理员得 200 |
| AC-002 | `... -Dtest=McpAdminIntegrationTest ... test` | 0 | 通过 | `contentGroupSplitsReadAndWrite`：`/mcp/x/admin_content` 只含 `admin_content_*` 读工具 |
| AC-003 | 同上 | 0 | 通过 | `adminViewIsNotSharedWithLearner`：学习者访问 `/mcp/x/admin_users` 得 **404** |
| AC-004 | 同上 | 0 | 通过 | 同一用例里**管理员先访问**再跑学习者；另有 `McpViewRegistryTest.cacheKeySeparatesAdminFromLearner` |
| AC-005 | `... -Dtest=McpToolInvokerTest ... test` | 0 | 通过 | `adminToolRequiresAdminRoleEvenWithAdminScope`：带 admin scope 但角色为 learner → 不执行、返回「没有名为」 |
| AC-006 | `... -Dtest=McpAdminIntegrationTest ... test` | 0 | 通过 | 只读视图不含 `save_entry` / `delete_entry`；`/mcp/x/admin_content/write` 含它们 |
| AC-007 | 同上 | 0 | 通过 | `allAdminToolsAreRegistered`：**38 个**、全部 `ADMIN`、7 个管理组、19 读 / 9 破坏；目录启动校验随上下文启动通过 |
| AC-008 | 同上 | 0 | 通过 | `adminViewIsNotSharedWithLearner`：学习者 `/mcp` **12 个**工具、无任何 `admin_` 前缀 |
| AC-009 | `... -Dtest=AdminUserToolsTest ... test` | 0 | 通过 | `cannotAdjustOwnCredits`、`adjustRequiresNoteAndNonZeroDelta`、`listMergesUsageIntoRows` |
| AC-010 | `... -Dtest=SandboxAdminToolsTest ... test` | 0 | 通过 | `privateDatasetCannotBeTakenDown`、`onlyTwoStatusesAreAccepted`、`takedownAlsoMarksReportsHandled` |
| AC-011 | `scripts/test-db.sh pnpm exec playwright test e2e/mcp-tokens.spec.ts` | 0 | 通过 | 6 passed，含「非管理员看不到管理工具组与管理范围」；管理员侧只有代码路径，见第二节 |
| AC-012 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 0 | 通过 | 10 个模块合计 **616 执行 / 0 失败 / 14 跳过** |
| MCP 单测 | `... -Dtest=PatServiceTest,McpToolInvokerTest,McpViewRegistryTest,McpViewsTest ... test` | 0 | 通过 | 34 执行 / 0 失败 |
| MCP 全链路 | `... -Dtest=McpStartupSmokeTest,McpAdminIntegrationTest ... test` | 0 | 通过 | 10 执行 / 0 失败（含真实 HTTP initialize + tools/list） |
| 模块与迁移闸 | `... -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest ... test` | 0 | 通过 | 9 执行 / 0 失败 |
| 前端类型检查 | `pnpm --filter @paideia/app typecheck` | 0 | 通过 | tsc 无输出 |
| 反向验证：管理组 404 | 移除 `McpViewRegistry` 的非管理员管理组判定后跑 `-Dtest=McpViewRegistryTest` | 1 | 通过 | 1 条失败（`adminGroupIsNotFoundForLearner`），恢复后 6/0 |
| 反向验证：admin scope 发放 | 移除 `PatService` 的管理员判定后跑 `-Dtest=PatServiceTest` | 1 | 通过 | 1 条失败（`rejectsBlankNameAndMissingScopes`），恢复后 10/0 |

## 一、实施中抓到的真缺陷

**凭据工具组白名单被构建路径丢掉。** 重构视图查询时把 `allowedToolsets` 从"构建 server 的清单"
里漏掉，只有调用阶段的校验还在。表现为：勾了「只授权知识库」的凭据，外部客户端清单里仍出现
全部 12 个工具——正是 2026-10-22 修过一次的同一个问题。

抓到它的是既有用例 `McpStartupSmokeTest.toolsetsLimitVisibleTools`（期望清单以 `knowledge_`
开头，实得 12 个混合工具），不是新写的测试。修法：构建时用
`view.queryFor(READ+WRITE+ADMIN, allowedToolsets, role)`，保留交集。

这印证了方案里那句"两道判断用同一份过滤链"：新增角色维度时，最容易漏的是**旧维度在重构中被
顺手删掉**，而不是新维度没加上。

## 二、未覆盖与已知限制

- **管理员侧的前端渲染没有 e2e**：`mcp-tokens` 用例的账号是 `seed-learner`（非管理员），
  它只证明"管理组与 admin 选项对非管理员不可见"。管理员的可见性由代码路径保证
  （`isAdmin` 过滤），没有用管理员账号跑这一页。服务端一侧有完整集成测试，泄漏风险不在这里。
- **全量学习者 e2e 在全新空库上会有 10 条红**：`learn`（6 条）与 `digest`（3 条）依赖已导入的
  课程/速递内容，而 `scripts/test-db.sh` 分配的是空库；`identity` 在全量跑里偶发红、单独跑 3/0。
  与本项无关（本项不碰学习线、速递抓取与画像），按项目规则内环只跑 `mcp-tokens.spec.ts`。
- **破坏性工具没有逐个人肉演练**：9 个破坏性工具由描述符不变量（`destructive=true`、
  `readOnly=false`）与各模块的行为单测覆盖代表路径；没有对生产数据执行过任何一次。
- **角色每次请求查一次账号表**：这是 REQ-003（降权立即生效）的代价，未做缓存。
- **工具数量 38 而非最初调研的 36**：设计阶段把概念-条目挂载与概念边拆成了独立的增删工具
  （需要各自的 id 语义），`create_track` 因管理端界面明确未做而移除；净结果 38。
