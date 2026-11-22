---
artifact: tasks
work_id: WORK-019
work: MCP 网关与工具目录
status: approved
workflow: full
depends_on: [requirements, proposal, design]
created: 2026-10-18
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-18 23:17:03 +0800
approver_role: CTO
---

# 实施任务 · MCP 网关与工具目录

## 依据

- `requirements.md`（27 REQ / 14 AC，draft）
- `proposal.md`（推荐方案：一份目录 + 内部/外部两个适配器，draft）
- `design.md`（契约、过滤链、视图、PAT、安全链，draft）
- `research.md`（仓库事实、MCP 规范、SDK 版本、GitHub inventory 设计）

## 全局约束

- Boot 4 / JDK 25 / 手写 MyBatis（禁 Plus）/ 仅 MySQL / Flyway；Java 不拼 SQL。
- 归属一律 `WHERE user_id = :currentUser`；拿不到统一 404。
- 时间由应用按 UTC 写；迁移号取当时实际最大号 +1，不写死。
- 新增 `/api/v1/**` 端点必须 `@ApiAccess(AUTHENTICATED)`，否则契约测试红。
- 日志不打印 token、工具参数正文、用户数据。
- 每次测试都核对 Skipped 列：无数据库口令时集成测试跳过而非失败。
- `paideia-mcp` 只依赖 `paideia-platform`；工具实现留在数据所属模块。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 两个工具重名被静默覆盖 | TASK-001 | `ToolCatalogTest.rejectsDuplicateNames` |
| 非法工具名（大写/空格/无点号前缀）上到生产 | TASK-001 | `ToolCatalogTest.rejectsInvalidNames` |
| 只读视图里混进写工具 | TASK-001/006 | `ToolCatalogTest.readOnlyViewExcludesWrites`、`McpToolsIntegrationTest.defaultViewHasNoWriteTools` |
| 视图过滤顺序不确定导致缓存错乱 | TASK-001 | `ToolCatalogTest.viewOrderIsDeterministic` |
| 无 token / 错 token / 过期 / 已吊销仍能调 | TASK-007 | `McpAuthenticationTest.rejects{Missing,Bad,Expired,Revoked}` |
| 只读 token 调写工具 | TASK-007/008 | `McpAuthenticationTest.readTokenCannotWrite` |
| 用别人 id 读别人数据 | TASK-008 | `McpToolsIntegrationTest.foreignIdIs404` |
| 参数里传 userId 改变归属 | TASK-008 | `McpToolsIntegrationTest.userIdArgumentIgnored` |
| 工具抛异常把堆栈/表名泄露给客户端 | TASK-008 | `McpToolInvokerTest.internalErrorIsRedacted` |
| 调用清单外的工具 | TASK-006/008 | `McpToolsIntegrationTest.toolOutsideViewIsUnknown` |
| 超限调用打穿上游 | TASK-008 | `McpToolInvokerTest.rateLimitRejectsBeforeInvoke` |
| 余额 0 仍调上游 | TASK-012 | `McpAiToolTest.zeroBalanceNoUpstreamCall` |
| find_tools 命中后下一轮仍没有 schema | TASK-003 | `AgentToolBridgeTest.discoveredToolJoinsNextRound` |
| 工具数低于阈值却注入 find_tools | TASK-003 | `AgentToolBridgeTest.noFindToolBelowThreshold` |
| PAT 明文落库 | TASK-005 | `PatServiceTest.storesOnlyHash` |
| 注销后 token 仍可用 | TASK-005 | `AccountDeletionCascadeIntegrationTest` + `McpTokenLifecycleTest.tokenInvalidAfterDelete` |
| 视图实例并发构建重复或泄漏 | TASK-006 | `McpViewRegistryTest.concurrentBuildIsSingleInstance` |
| 审计日志缺字段或含正文 | TASK-008 | `McpAuditLoggerTest.recordsRequiredFields` |

## 任务

### TASK-001 | done | platform 工具契约与目录

- 对应：`REQ-001`、`REQ-002`、`REQ-003`、`REQ-004`、`REQ-005`、`AC-001`、`AC-002`
- 依赖：无
- 新建/修改：
  - `backend/paideia-platform/src/main/java/io/github/xiaou61/platform/tools/ToolDescriptor.java`
  - `.../PaideiaTool.java`、`.../ToolContext.java`、`.../ToolResult.java`、`.../ToolScope.java`、`.../ToolQuery.java`
  - `.../ToolCatalog.java`、`.../ToolNaming.java`
  - `backend/paideia-platform/src/test/java/io/github/xiaou61/platform/tools/ToolCatalogTest.java`
- 接口：
  - 产出 `record ToolDescriptor(String name, String toolset, String title, String description, Map<String,Object> inputSchema, Map<String,Object> outputSchema, boolean readOnly, boolean destructive, boolean idempotent, String scope, boolean defaultGroup)`
  - 产出 `interface PaideiaTool { ToolDescriptor descriptor(); ToolResult call(ToolContext, Map<String,Object>); }`
  - 产出 `record ToolContext(long userId, String role, Set<String> scopes, Origin origin)`
  - 产出 `record ToolResult(boolean error, String text, Map<String,Object> structured)`
  - 产出 `record ToolQuery(List<String> toolsets, List<String> tools, boolean readOnly, String role, Set<String> scopes, boolean defaultsOnly)`
  - 产出 `ToolCatalog.view(ToolQuery): List<ToolDescriptor>`、`find(String): Optional<PaideiaTool>`、`search(String,int): List<ToolDescriptor>`、`snapshot(): List<ToolDescriptor>`、`toolsets(): Set<String>`
- 步骤：
  1. 写 `ToolCatalogTest` 六条失败用例（重名、非法名、非 object schema、defaultGroup 不存在、readOnly=false 却 scope=read、destructive=true 却 readOnly=true）→ 跑，全红。
  2. 写 `ToolDescriptor`/`ToolNaming`/`PaideiaTool`/`ToolContext`/`ToolResult`/`ToolQuery`，编译通过。
  3. 写 `ToolCatalog` 校验逻辑（`@PostConstruct`）→ 跑，六条绿。
  4. 写过滤与排序测试（只读排除写、工具组过滤、显式工具越过组、scope 过滤、admin 只给 ADMIN、顺序稳定）→ 跑红 → 实现 → 绿。
  5. 写 `search` 打分测试（名称命中 > 标题 > 描述；无命中返回空）→ 实现 → 绿。
- 验证：`cd backend && mvn -B -pl paideia-platform test -Dtest=ToolCatalogTest` → 期望全部通过，Skipped=0。

### TASK-002 | done | 知识库与评测工具迁移到目录

- 对应：`REQ-001`、`REQ-024`、`AC-001`、`AC-005`
- 依赖：TASK-001
- 新建/修改：
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/tools/KnowledgeTools.java`（新建：把现有助手工具的能力搬成目录工具）
  - `.../AssessmentTools.java`（新建：自评读/写）
  - `backend/paideia-knowledge/src/test/java/.../tools/KnowledgeToolsTest.java`
- 接口：
  - 实现 `PaideiaTool`；名称 `knowledge_search`、`knowledge_get_entry`、`knowledge_list_categories`、`knowledge_my_marks`、`assessment_list_marks`、`assessment_mark_entry`
  - 消费 `KnowledgeApi.searchPublished(String,int)`、`findPublishedContent(long)`、`listCategories()`、`findMarks(long,int)`
- 步骤：
  1. 先查 `KnowledgeApi` 是否有写入自评标记的方法；没有则在本任务内只做 `assessment_list_marks`，`mark_entry` 记为 blocked 并在 tasks 里注明原因（不臆造接口）。
  2. 为四个知识工具写映射测试（参数 → 调用 → 文本），用桩 `KnowledgeApi` → 跑红。
  3. 实现 `KnowledgeTools` → 绿。
  4. 写「工具参数里传 userId 被忽略」的测试：`my_marks` 永远用 `ToolContext.userId`。
- 验证：`mvn -B -pl paideia-knowledge test -Dtest='KnowledgeToolsTest*'` → 全绿；若 `mark_entry` blocked，测试只覆盖 `list_marks` 且 tasks 注明。

### TASK-003 | done | 内部桥接与 find_tools

- 对应：`REQ-006`、`REQ-007`、`REQ-008`、`REQ-009`、`REQ-010`、`AC-006`
- 依赖：TASK-001
- 新建/修改：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/tools/AgentToolBridge.java`（新建）
  - `AgentScopeConversation.java`（改：工具循环每轮重算 schema，增加 `discovered`）
  - `AssistantTools.java`（改：委托桥接或删除，二选一，保留 `draft_note` 行为）
  - 测试：`AgentToolBridgeTest.java`、`ConversationUsageAccumulationTest` 回归
- 接口：
  - `AgentToolBridge.core(ToolQuery): List<ToolSchema>`、`concat(List<ToolSchema>, Collection<ToolSchema>): List<ToolSchema>`、`findTool(): ToolSchema`、`run(long userId, Harness, String, Map<String,Object>): Outcome`
  - `AgentScopeConversation` 内部：`Set<String> discovered = new HashSet<>()`
- 步骤：
  1. 写 `AgentToolBridgeTest`：目录 3 个工具（≤阈值）时不注入 `find_tools`；20 个工具时注入，且首轮只含默认组。
  2. 实现 `AgentToolBridge` → 跑，绿。
  3. 改 `AgentScopeConversation` 循环：每轮 `bridge.concat(core, discovered)` → 跑 `ConversationUsageAccumulationTest`，绿。
  4. 写 `find_tools` 命中后 `discovered` 增加、下一轮 schema 含该工具的测试 → 实现 → 绿。
  5. 写无关查询返回「没有匹配」且不抛异常的测试。
- 验证：`mvn -B -pl paideia-ai test` → 全绿；`pnpm --filter @paideia/app test:e2e` 助手相关用例通过（工具路径未回归）。

### TASK-004 | done | NoteApi 与笔记/账号工具

- 对应：`REQ-001`、`REQ-017`、`REQ-024`、`AC-005`
- 依赖：TASK-001
- 新建/修改：
  - `backend/paideia-note/src/main/java/io/github/xiaou61/note/NoteApi.java`（新建：包根公共接口）
  - `.../internal/NoteApiImplementation.java`（新建或复用现有 service）
  - `.../internal/tools/NoteTools.java`
  - `backend/paideia-account/src/main/java/io/github/xiaou61/account/internal/tools/AccountTools.java`
- 接口：
  - 产出 `interface NoteApi { List<NoteBrief> list(long,int); Optional<NoteContent> find(long,long); long create(long,String,String); long append(long,long,String); }`
  - 工具名 `note_list`、`note_get`、`note_create`、`note_append`、`account_me`
- 步骤：
  1. 写 `NoteApi` 与实现，先让现有 `NoteController` 的行为测试通过（不改行为）。
  2. 写 `NoteToolsTest`（create 归属、append 到别人笔记 → 404）→ 跑红 → 实现 → 绿。
  3. 写 `AccountToolsTest`（只返回本人基础信息，不含邮箱验证状态等敏感字段）。
- 验证：`mvn -B -pl paideia-note,paideia-account test` → 全绿。

### TASK-005 | done | PAT 表、服务与注销级联

- 对应：`REQ-015`、`REQ-016`、`REQ-022`、`AC-011`、`AC-012`、`AC-013`（后端部分）
- 依赖：无（可与 TASK-001 并行）
- 新建/修改：
  - 迁移 `backend/paideia-app/src/main/resources/db/migration/V{next}__create_mcp_access_tokens.sql`
  - `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/token/{McpAccessToken.java,McpTokenMapper.java,PatService.java}`（模块骨架同 TASK-006 一起建，可先建最小骨架）
  - `AiUserDeletionListener`（改：登记新表）
  - 测试：`PatServiceTest`、`McpTokenLifecycleTest`
- 接口：
  - `PatService.create(long userId, String name, Set<String> scopes, Integer expiresInDays): CreatedToken`，`CreatedToken(long id, String plaintext, String prefix, Instant expiresAt)`
  - `PatService.authenticate(String bearerToken): Optional<PatPrincipal>`，`PatPrincipal(long userId, long tokenId, Set<String> scopes, String role)`
  - `PatService.list(long userId): List<PatView>`、`PatService.revoke(long userId, long id): boolean`
- 步骤：
  1. 实查当前最大迁移号（`ls db/migration | sort -V | tail`）→ 新建 SQL；`mvn -B -pl paideia-app test` 迁移守卫通过。
  2. 写 `PatServiceTest`（哈希不入明文、过期、吊销、恒时比较）→ 红 → 实现 → 绿。
  3. 在 `AiUserDeletionListener` 加表名与断言；跑 `AccountDeletionCascadeIntegrationTest`，确认逐表 0 行。
- 验证：`mvn -B -pl paideia-mcp,paideia-app test -Dtest='PatServiceTest,AccountDeletionCascadeIntegrationTest,McpTokenLifecycleTest'` → 全绿，Skipped 记录在报告里。

### TASK-006 | done | paideia-mcp 骨架与视图

- 对应：`REQ-011`、`REQ-012`、`AC-002`、`AC-004`
- 依赖：TASK-001
- 新建/修改：
  - `backend/paideia-mcp/pom.xml`、`paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/{McpModuleConfiguration,McpViewRegistry,McpServerFactory}.java`
  - `backend/pom.xml`（加模块）、`paideia-app/pom.xml`（依赖）
  - `McpViewRegistryTest`、`McpStartupSmokeTest`
- 接口：
  - 依赖 `io.modelcontextprotocol.sdk:mcp:0.17.0`（与 AgentScope 带入的版本一致，显式 pin 防调解）
  - 产出 `McpViewRegistry.server(String view): McpSyncServer`（惰性 + 缓存）
- 步骤：
  1. 加依赖与模块，写最小 `@SpringBootTest` 冒烟：上下文启动、`ToolCatalog` Bean 就绪 → 绿（先证明 Boot 4 + Jackson 3 + SDK 能共存）。
  2. 写 `McpViewRegistryTest`（路径解析、未知组 404、并发只构建一次）→ 红 → 实现 → 绿。
  3. 用 `McpSyncServer` 注册一个假工具，用 SDK 客户端或 JSON-RPC 原始请求调 `tools/list`，断言只有视图内工具。
- 验证：`mvn -B -pl paideia-mcp test` → 全绿；`mvn -B -pl paideia-app test -Dtest=ModularityTest` → 边界不报错。

### TASK-007 | done | PAT 过滤器与 MCP 安全链

- 对应：`REQ-013`、`REQ-014`、`AC-003`、`AC-004`
- 依赖：TASK-005、TASK-006
- 新建/修改：
  - `.../mcp/internal/security/{PatAuthenticationFilter,McpSecurityConfiguration}.java`
  - `backend/paideia-app/src/test/java/io/github/xiaou61/McpAuthenticationTest.java`
- 接口：消费 `PatService.authenticate(String): Optional<PatPrincipal>`；产出 request attribute 键 `pd.pat.userId`、`pd.pat.tokenId`、`pd.pat.scopes`、`pd.pat.role`
- 步骤：
  1. 写鉴权矩阵测试（无/错/过期/吊销 → 401；读 token → 写视图 403；有效 → 200）→ 红。
  2. 实现过滤器与独立安全链（`@Order(1)`，`securityMatcher("/mcp/**")`）→ 绿。
  3. 断言现有 JWT 链行为未变（跑 `ApiSurfaceTest` 与一个既有集成用例）。
- 验证：`mvn -B -pl paideia-app test -Dtest=McpAuthenticationTest,ApiSurfaceTest` → 全绿。

### TASK-008 | done | 工具执行、错误语义、审计与限流

- 对应：`REQ-017`、`REQ-018`、`REQ-019`、`REQ-020`、`REQ-021`、`REQ-023`、`AC-005`、`AC-007`、`AC-009`、`AC-010`
- 依赖：TASK-002、TASK-004、TASK-006、TASK-007
- 新建/修改：
  - `.../mcp/internal/{McpToolInvoker,McpAuditLogger,McpRateLimiter}.java`
  - `McpToolInvokerTest`、`McpToolsIntegrationTest`、`McpAiToolTest`
- 接口：
  - `McpToolInvoker.invoke(PatPrincipal, String view, String tool, Map<String,Object> args): ToolResult`
  - `McpAuditLogger.log(tokenId, userId, tool, view, durationMs, isError, bytes)`
- 步骤：
  1. 写错误映射测试（未知工具、schema 违规、内部异常脱敏）→ 红 → 实现 → 绿。
  2. 写归属测试（别人 id → 404、参数带 userId 不越权）→ 红 → 实现 → 绿。
  3. 写限流测试（超过 N 次不调用工具）→ 红 → 实现（基于`UserRateLimiter` 的同一思路，按 token 维度）→ 绿。
  4. 写审计测试（捕获日志，断言七个字段、无正文）→ 绿。
- 验证：`mvn -B -pl paideia-app test -Dtest='McpToolInvokerTest,McpToolsIntegrationTest,McpAuditLoggerTest'` → 全绿。

### TASK-009 | done | PAT 管理端点与前端页

- 对应：`REQ-015`、`REQ-025`、`AC-013`
- 依赖：TASK-005
- 新建/修改：
  - `backend/paideia-mcp/src/main/java/.../PatController.java`
  - `frontend/apps/app/src/.../settings/McpTokensPage.tsx` + 查询钩子
  - 测试：`PatControllerTest`、`mcp-tokens.test.tsx`
- 接口：POST/GET/DELETE `/api/v1/ai/mcp-tokens`；创建响应 `{id, token, prefix, scopes, expiresAt}`
- 步骤：
  1. 写控制器测试（创建只回一次明文、列表无 hash、删除本人 404 统一）→ 红 → 实现 → 绿。
  2. 写前端测试（创建后展示一次、刷新只显示前缀、吊销二次确认、390px 无横向滚动）→ 红 → 实现 → 绿。
- 验证：`mvn -B -pl paideia-app test -Dtest=PatControllerTest`；`pnpm --filter @paideia/app test` → 绿。

### TASK-010 | done | 协议一致性、限流回归与文档

- 对应：`REQ-026`、`REQ-027`、`AC-008`、`AC-014`
- 依赖：TASK-008
- 新建/修改：
  - `docs/` 下新增接入说明（或 `.agent/notes/mcp-access.md`）
  - 报告：`.agent/testing/` 新增一次 conformance 记录
- 步骤：
  1. 跑官方 conformance（`npx @modelcontextprotocol/conformance`）对 `http://localhost:8080/mcp` 一次，记录通过/失败项；不通过项逐条写原因。
  2. 写 ZCode 与 Claude Desktop 的配置片段（URL + Authorization 头 + 三种视图用途），确认无真实 token。
  3. 目录快照测试：`ToolCatalog.snapshot()` 与文档工具表对照（名称、工具组、只读）。
- 验证：conformance 记录文件存在且结果如实；文档里的 JSON 片段可被 JSON 解析；快照测试绿。

### TASK-011 | done | 端到端冒烟与验收报告

- 对应：`AC-001`..`AC-014`
- 依赖：TASK-001..010
- 新建/修改：`.agent/changes/WORK-019-*/verification.md`
- 步骤：
  1. 起本地后端，用 curl 走完：创建 PAT → list → 调读工具 → 写视图 403 → 吊销 → 401。
  2. 用 ZCode 真实连一次（本机配置里加一条，确认 `tools/list` 可见）。
  3. 逐条对照 AC 写入验证表，未达标的写「未达」与原因。
- 验证：`verification.md` 每条 AC 有命令或人工步骤与结果；`project-lifecycle.ps1 validate` 通过。

### TASK-012 | done | assistant_ask 工具与计费

- 对应：`REQ-028`、`AC-015`
- 依赖：TASK-001、TASK-008
- 新建/修改：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/AssistantAnswerer.java`（新建包根端口）
  - `.../internal/conversation/AgentScopeAssistantAnswerer.java`（新建，复用模型构建与工具循环，非流式、无历史、不落库）
  - `.../internal/tools/AssistantAskTool.java`（新建 `PaideiaTool`）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/billing/CreditLedger.java`（加 `KIND_MCP_ASK`）
  - 迁移：`credit_ledger.kind` 若为枚举/CHECK，需要同步（先查 V11 的实际定义，不臆造）
  - 测试：`AssistantAskToolTest`、`McpAiToolTest` 增补
- 接口：
  - `interface AssistantAnswerer { Answer answer(long userId, String question, Long entryId); record Answer(String text, TokenUsage usage) {} }`
  - 工具名 `assistant_ask`，输入 schema `{question: string, entryId?: integer}`
- 步骤：
  1. 查 `credit_ledger.kind` 是否有取值约束；有则迁移加值，无则只加常量。
  2. 写 `AssistantAskToolTest`（无历史、不落库、返回文本）→ 红 → 实现 → 绿。
  3. 写零余额测试：402 且上游 0 次；写结算测试：成功调用产生一条 `MCP_ASK` 流水。
  4. 断言调用后 `ai_conversations` / `ai_messages` 行数不变。
- 验证：`mvn -B -pl paideia-ai,paideia-app test -Dtest='AssistantAskToolTest,McpAiToolTest'` → 全绿。

## 完成条件

- 28 条 REQ 与 15 条 AC 都有归属任务与可复查证据。
- `mvn -B verify` 全绿且 Skipped 列已核对（无口令时的跳过要单独标明）。
- 前端 `pnpm -r typecheck` 与相关单测绿；`app` 端 e2e 不回归。
- 实现与验证结果更新到 `tasks.md` 与 `verification.md`，并追加 `.agent/history/updates.md`。
- 本地提交按切片分笔；`push` 与部署单独授权。
