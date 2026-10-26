---
artifact: tasks
work_id: WORK-038
work: 管理端 MCP 工具
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 10:27:08 +0800
approver_role: CTO
---

# 实施任务 · 管理端 MCP 工具

依据：[requirements.md](requirements.md)、[proposal.md](proposal.md)、[design.md](design.md)。

## 依据

工作树：`.worktrees/work-038`，分支 `work-038-mcp-admin`。全部命令在该目录下执行。

## 全局约束

- 工具实现放在拥有数据的模块的 `internal` 包内，只依赖本模块服务与公共契约；`paideia-mcp` 不 import 业务包。
- 工具名、工具组、scope、破坏性标注必须通过 `ToolCatalog` 启动期校验。
- 后端内环测试走 `scripts/test-db.sh`；不直连 `paideia_test`。
- 每个切片独立提交；`push` 需单独授权。
- 前端改共享包后跑对应 e2e（`mcp-tokens`）。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 角色不进视图缓存键，管理员清单被非管理员复用 | TASK-001 | `McpViewRegistryTest.adminAndLearnerDoNotShareView` |
| 非管理员建 PAT 时拿到 admin scope | TASK-001 | `PatServiceTest.rejectsAdminScopeForNonAdmin` |
| 猜到工具名绕过清单直接调用 | TASK-001 | `McpToolInvokerTest.rejectsAdminToolWithoutRole` |
| 学习者默认视图出现管理工具 | TASK-001 | `ToolCatalogTest.adminToolsHiddenWithoutAdminRole` |
| 工具声明 WRITE 而非 ADMIN，导致角色过滤绕过 | TASK-002~006 | `AdminToolDescriptorsTest.allAdminToolsRequireAdminScope` |
| 调自己的积分 / 不写原因 | TASK-003 | `AdminUsersToolsTest.rejectsSelfAdjust` / `rejectsBlankNote` |
| 非社区数据集被下架 | TASK-005 | `AdminReviewToolsTest.rejectsNonCommunityDataset` |
| 更新条目时把未传字段清空且无提示 | TASK-002 | 描述断言 + `AdminContentToolsTest.updateRequiresFullRow` |
| 速递日期格式错误冒成 500 | TASK-006 | `AdminDigestToolsTest.rejectsBadDate` |
| 前端只勾管理组却拿到学习者工具 | TASK-007 | `mcp-tokens.spec.ts` |

## 任务

### TASK-001 | done | 角色通道与 admin 鉴权

- 对应：REQ-001~005、REQ-009、REQ-013、AC-001~005、AC-007~008
- 依赖：无
- 新建：`backend/paideia-platform/src/main/java/io/github/xiaou61/platform/RoleLookup.java`、`backend/paideia-app/src/main/java/io/github/xiaou61/RoleLookupConfiguration.java`
- 修改：`ToolScope.java`（注释）、`McpViews.java`、`McpViewRegistry.java`、`McpToolInvoker.java`、`PatService.java`、`PatAuthenticationFilter.java`、`paideia-mcp/pom.xml`（如需要）
- 接口：
  - `public interface RoleLookup { Optional<String> roleOf(long userId); }`
  - `ToolQuery view(Set<String> toolsets, boolean write, String role, Set<ToolScope> scopes)`（已存在，补 role 传参）
  - `McpViewRegistry.view(String path, Set<String> allowedToolsets, String role)`
  - `PatService.create(long userId, String name, Set<ToolScope> scopes, Set<String> toolsets, Integer expiresInDays)`
- 步骤：
  1. 加 `RoleLookup` 纯接口与 app 侧装配（`AccountApi.findRoleById`）。
  2. `PatAuthenticationFilter` 解析角色并写入请求属性；解析不到按非管理员处理。
  3. `McpViews.queryFor` 与 `McpViewRegistry` 传 role；缓存键加 `admin|user`。
  4. `PatCaller` 加 role；`McpToolInvoker` 构造 `ToolContext` 时用真实角色，并在调用前补 ADMIN 角色判定。
  5. `PatService.create` 对 admin scope 改为「仅 ADMIN 角色可发」。
  6. 测试：`RoleLookup` 桩、视图缓存隔离、scope 发放、越权调用。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-platform,paideia-mcp -am test`

### TASK-002 | done | admin_content 15 个工具

- 对应：REQ-006~010、AC-006~007
- 依赖：TASK-001
- 新建：`backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/tools/KnowledgeAdminTools.java`
- 接口：
  - `Long CategoryAdminService.create(Long parentId, String name, String slug, Integer sortOrder, String lineKind)`
  - `void CategoryAdminService.update(Long id, Long parentId, String name, String slug, Integer sortOrder, String lineKind)`
  - `Long EntryAdminService.create(String title, String body, Long categoryId, String status, Integer sortOrder)`
  - `void EntryAdminService.update(Long id, String title, String body, Long categoryId, String status, Integer sortOrder)`
  - `Long ConceptAdminService.create(Long categoryId, String name, String summary)`
  - `void ConceptAdminService.update(Long id, Long categoryId, String name, String summary)`
  - `Long RelationAdminService.create(Long fromEntryId, Long toEntryId, String relationType)`
  - `Long ConceptAdminService.createEdge(Long fromConceptId, Long toConceptId, String relationType)`
- 步骤：
  1. 15 个工具类写在一个文件内（嵌套 `@Component`），共享 schema/参数辅助函数。
  2. 写操作区分 create（无 id）与 update（有 id）；描述写明「整行覆盖」。
  3. 删除类标注 `destructive=true, idempotent=false`。
  4. 测试：描述符不变量 + create/update 分支 + 错误文本。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-knowledge -am test`

### TASK-003 | done | admin_users 6 个工具

- 对应：REQ-010、AC-009
- 依赖：TASK-001
- 新建：`backend/paideia-account/src/main/java/io/github/xiaou61/account/internal/tools/AccountAdminTools.java`
- 修改：`paideia-account/pom.xml`（若需读 AI 用量则改为在 app 层装配；见步骤 1）
- 接口：
  - `PageResult<AdminUser> AccountAdminApi.list(String query, String role, int page, int size)`
  - `void AccountAdminApi.changeRole(long id, String role, long operatorId)`
  - `void AccountAdminApi.deleteByAdmin(long id, long operatorId)`
  - `long AiAdminApi.adjust(long userId, long delta, long operatorId, String note)`
- 步骤：
  1. **归属**：`admin_users_list/get` 需要账号 + 用量两模块数据。工具放在 `paideia-app` 的 `console` 包（与 `AdminUserController` 同一先例），避免让 account 依赖 ai。
  2. 调积分前校验：非零、原因非空、不是自己（与控制器同一口径）。
  3. 删号交给服务层拒绝自伤。
  4. 测试：拒绝自调、空原因、非法角色。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am test`

### TASK-004 | done | admin_ai 5 个工具

- 对应：REQ-006、AC-007
- 依赖：TASK-001
- 新建：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/tools/AiAdminTools.java`
- 接口：
  - `AiRunAdminService.Overview overview(Instant from, Instant to, String kind, String status, String model, Long userId)`
  - `AiRunAdminService.Health health()`
  - `AiRunAdminService.Reconcile reconcile(Instant from, Instant to)`
  - `PageResult<AiFeedbackService.FeedbackView> AiFeedbackService.page(Instant from, Instant to, String rating, String reason, String kind, PageQuery page)`
- 步骤：
  1. `admin_ai_overview` 一次返回概览 + 健康 + 对账三段文本。
  2. 时间参数按 ISO-8601 解析；缺省由服务层处理。
  3. 分页夹到 1..100。
  4. 测试：描述符不变量、非法时间返回错误。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am test`

### TASK-005 | done | admin_review 5 个工具

- 对应：REQ-010、AC-010
- 依赖：TASK-001
- 新建：`backend/paideia-note/src/main/java/io/github/xiaou61/note/internal/tools/NoteAdminTools.java`、`backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/tools/SandboxAdminTools.java`
- 接口：
  - `List<ShareService.ReportView> ShareService.listReports(String status)`
  - `boolean ShareService.takedown(long reportId)`
  - `List<SqlDatasetMapper.Row> SqlDatasetMapper.listCommunity(String status, int limit)`
  - `void SqlDatasetMapper.updateStatus(String key, String status, Instant now)`
  - `void SqlDatasetMapper.markReportsHandled(String key, String status, Instant now)`
- 步骤：
  1. 下架数据集前复刻 `requireCommunity`：找不到 → 错误；非 community → 错误。
  2. `set_dataset_status` 只接受 `published` / `taken_down`；下架时同步标记举报。
  3. 测试：非社区数据集被拒、非法状态被拒。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-note,paideia-sandbox -am test`

### TASK-006 | done | admin_learning 4 + admin_ops 1 + admin_digest 2

- 对应：REQ-006、AC-007
- 依赖：TASK-001
- 新建：`backend/paideia-knowledge/.../tools/LearningAdminTools.java`、`backend/paideia-app/.../ops/OpsAdminTools.java`、`backend/paideia-ai/.../digest/DigestAdminTools.java`
- 接口：
  - `List<QuestionConceptAdminService.QuestionView> QuestionConceptAdminService.list(long entryId)`
  - `void QuestionConceptAdminService.replace(long entryId, String blockKey, String questionKey, List<TagRequest> concepts, String source)`
  - `OpsSummary OpsSummaryService.summary()`
  - `List<DigestMapper.Row> DigestMapper.findByDate(LocalDate date)`
- 步骤：
  1. 导出工具夹 `limit` 到 1..500。
  2. 速递日期解析失败返回错误文本，不抛。
  3. 测试：描述符 + 日期错误。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-knowledge,paideia-ai,paideia-app -am test`

### TASK-007 | done | 前端工具组与 admin 权限范围

- 对应：REQ-012、AC-011
- 依赖：TASK-002~006
- 修改：`frontend/apps/app/src/pages/McpTokensPage.tsx`（工具组清单补 7 条 + `sql`；权限范围加 `admin`，仅管理员可见）
- 步骤：
  1. 组清单与后端工具组一一对应；标签用 design 的表。
  2. `admin` 权限范围选项只在账号是管理员时渲染；服务端仍独立校验。
  3. 跑 `mcp-tokens` e2e。
- 验证：`cd frontend && pnpm --filter @paideia/app test:e2e -- mcp-tokens.spec.ts`

### TASK-008 | done | 全量验证、文档与提交

- 对应：AC-012、REQ-013~014、REQ-011
- 依赖：TASK-001~007
- 步骤：
  1. 后端：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` 与集成套件切片。
  2. 前端：`pnpm -r typecheck` + `mcp-tokens` e2e。
  3. 更新 `.agent/history/updates.md` 与 WORK-038 的验证报告。
  4. 分切片本地提交（不 push）。
- 验证：见验证报告的五列证据表。
