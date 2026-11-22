---
artifact: design
work_id: WORK-019
work: MCP 网关与工具目录
status: approved
workflow: full
depends_on: [requirements, proposal]
related_to: [WORK-006, WORK-009, WORK-010, WORK-013]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 23:17:03 +0800
approver_role: CTO
---

# 设计 · MCP 网关与工具目录

## 系统上下文

```
                    ┌──────────────── 平台内部 ────────────────┐
学习者浏览器 ──JWT──▶ /api/v1/assistant/stream ─▶ AgentScope harness │
                    │        │                                      │
                    │        └─ ToolCatalog（唯一真源）◀── 业务模块工具 │
                    │               ▲                              │
外部助手 ──PAT──▶ /mcp/x/{toolset} ─────┘                              │
（ZCode / Claude）    paideia-mcp 适配器                                │
                    └──────────────────────────────────────────┘
                                   │
                              MySQL / DeepSeek
```

两个入口、一份目录；`paideia-mcp` 与 `paideia-ai` 互不依赖。

## 组件与职责

### 1. `paideia-platform`：`io.github.xiaou61.platform.tools`

| 类型 | 职责 | 关键点 |
| --- | --- | --- |
| `ToolDescriptor` | 工具的只读元数据 record | 名称、`toolset`、`title`、`description`、`inputSchema`、`outputSchema`、`readOnly`、`destructive`、`idempotent`、`scope`、`defaultGroup` |
| `PaideiaTool` | 可执行工具的接口 | `ToolDescriptor descriptor()`；`ToolResult call(ToolContext, Map<String,Object> args)` |
| `ToolContext` | 调用上下文 | `long userId()`、`String role()`、`Set<String> scopes()`、`Origin origin()`（`INTERNAL`/`EXTERNAL`） |
| `ToolResult` | 统一结果 | `error`、`text`、`structured`（可空）；静态工厂 `ok(...)` / `fail(...)` |
| `ToolScope` | scope 常量 | `READ`、`WRITE`、`ADMIN`（字符串 `"read"`/`"write"`/`"admin"`） |
| `ToolQuery` | 过滤输入 | `toolsets`、`tools`、`readOnly`、`role`、`scopes`、`defaultsOnly`；静态工厂 `internal(...)`、`view(...)` |
| `ToolCatalog` | 收集 + 校验 + 过滤 + 检索 | 构造注入 `List<PaideiaTool>`；`validate()` 在 `@PostConstruct` 执行；`view(ToolQuery)`、`find(String)`、`search(String,int)`、`snapshot()` |
| `ToolNaming` | 名称规则 | `^[a-z][a-z0-9]*(\.[a-z][a-z0-9_]*)+$`，1–128 字符 |

**`ToolCatalog.validate()` 的失败清单**（启动期抛 `IllegalStateException`，消息含工具名）：

1. 名称不匹配 `ToolNaming` 或长度越界
2. 名称重复（同一名称两个 Bean）
3. `inputSchema` 的 `type` 不是 `"object"`
4. `defaultGroup=true` 但 `toolset` 不在任何已注册工具组中（工具组由 `ToolGroup` 枚举或注册表维护）
5. `readOnly=false` 却声明 `scope=read`
6. `destructive=true` 却 `readOnly=true`

**过滤顺序**（`ToolCatalog.view`，写死并注释）：

```java
tools.stream()
  .filter(t -> !query.readOnly() || t.descriptor().readOnly())          // 1 只读
  .filter(t -> query.toolsets().isEmpty()
            || query.toolsets().contains(t.descriptor().toolset())
            || query.tools().contains(t.descriptor().name()))            // 2 工具组/显式
  .filter(t -> !query.defaultsOnly() || t.descriptor().defaultGroup())   // 3 默认组
  .filter(t -> query.scopes().contains(t.descriptor().scope()))          // 4 scope
  .filter(t -> !"admin".equals(t.descriptor().scope())
            || "ADMIN".equalsIgnoreCase(query.role()))                   // 5 角色
  .sorted(comparing(toolset).thenComparing(name))                        // 6 确定性
```

### 2. 业务模块：工具实现

| 模块 | 类 | 工具 |
| --- | --- | --- |
| `paideia-knowledge` | `KnowledgeTools` | `knowledge_search`、`knowledge_get_entry`、`knowledge_list_categories`、`knowledge_my_marks` |
| `paideia-note` | `NoteTools` | `note_list`、`note_get`、`note_create`、`note_append` |
| `paideia-account` | `AccountTools` | `account_me` |
| `paideia-knowledge` | `AssessmentTools` | `assessment_list_marks`、`assessment_mark_entry` |
| `paideia-ai` | `NoteDraftTool` + `AssistantAskTool` | `assistant_draft_note`（只产草稿，内部分组）、`assistant_ask`（外部问答，复用积分） |

每个实现只注入本模块的 API/用例，不跨模块调用。`note` 目前没有包根 API（只有 `internal`），TASK-003 需要同时在 `paideia-note` 增加最小 `NoteApi`（`find/list/create/append` 四个方法）——这是本项**唯一**对既有模块公共面的扩展。

### 3. `paideia-ai`：内部适配器

`AssistantTools` 从「硬编码 4 个 schema + switch」改为 `AgentToolBridge`：

```java
List<ToolSchema> core = bridge.schemas(ToolQuery.internal(harnessGroups, eagerLimit));
// 每轮：
List<ToolSchema> schemas = bridge.concat(core, discovered);
```

- `AgentToolBridge` 把 `ToolDescriptor` 转成 AgentScope `ToolSchema`，把 `ToolResult` 转成工具输出文本。
- `find_tools` 由桥接层注入，不注册进 `ToolCatalog`（它不是平台能力，而是内部发现机制）。
- `AgentScopeConversation` 的工具循环增加 `Set<String> discovered`，`find_tools` 的返回解析出工具名后加入；`tools.run(...)` 改为 `bridge.run(ctx, name, args)`。
- 阈值 `paideia.ai.tools.eager-limit` 默认 12；工具总数 ≤ 阈值时不注入 `find_tools`。

### `assistant_ask` 的形状（REQ-028）

外部工具不能直接复用 `AssistantConversation.stream`——那是**流式**接口、按会话组织、需要 `Listener`，而 MCP 工具调用是一问一答。做法：

- 不复用 `EntrySummarizer`（那是"输入正文 → 结构化卡片"的单次调用，不挂工具），新增一个非流式端口 `AssistantAnswerer`（`ai` 包根），实现复用 `AgentScopeConversation` 的模型构建与工具循环，但：
  - 不带历史（外部调用没有会话）；
  - 不落 `ai_conversations` / `ai_messages`（REQ-028）；
  - 结果返回 `String` + `TokenUsage`。
- 计费：`McpToolInvoker` 在调用前 `reserve`、拿到用量后 `settle`，流水 `kind` 新增 `MCP_ASK`。

这是本项**第二个**对既有模块公共面的扩展（第一个是 `NoteApi`），必须在 design 与 tasks 里同时写明，避免执行时被当成"顺手加了"。`ponytail:` 上限——`AssistantAnswerer` 一期只支持"单问单答 + 最多 4 轮工具"，不做多会话、不做流式。

### 4. `paideia-mcp`：外部适配器

新 Maven 模块 `backend/paideia-mcp`。**依赖复用 AgentScope 已带入的 `io.modelcontextprotocol.sdk:mcp:0.17.0`**（显式声明同版本，避免被依赖调解到别的版本；`mvn dependency:tree` 已确认它在 `paideia-ai` 链路上）。0.17.0 提供 `HttpServletStreamableServerTransportProvider`、`McpServer.sync(McpStreamableServerTransportProvider)`、`contextExtractor`——够用，且不新增依赖坐标。

> 版本事实：0.17.0 的协议版本常量到 **2025-06-18**（`javap` 实测）。2025-11-25 与 2026-07-28 都不可用；这是选 0.17.0 的已知代价，登记在 `research.md`。

| 类 | 职责 |
| --- | --- |
| `McpModuleConfiguration` | 注册 Servlet、按视图惰性构建 `McpSyncServer` |
| `McpViewRegistry` | 把路径段解析成 `ToolQuery`；视图名 → 查询的映射表 |
| `McpServerFactory` | 用 `ToolCatalog.view(query)` 构建一个 `McpSyncServer`（工具名直接取描述符名称与 schema） |
| `McpToolInvoker` | `ToolCatalog.find(name)` + 上下文 + 审计 + 限流 + 计费桥 |
| `McpAuditLogger` | 结构化日志（`mcp.audit` logger） |
| `PatAuthenticationFilter` | 解析 `Authorization: Bearer` → `PatPrincipal(userId, tokenId, scopes, role)`；失败 401 |
| `PatService` | 创建/校验/吊销/列表；SHA-256 哈希 + `MessageDigest.isEqual` |
| `PatMapper` | 手写 MyBatis |
| `PatController` | `/api/v1/ai/mcp-tokens` 五个端点（POST/GET/DELETE） |

**视图路由**（单 Servlet，按 `pathInfo` 分发）：

| 路径 | 工具组 | 写 |
| --- | --- | --- |
| `/mcp` | 默认组（knowledge、note-read、account、assessment-read） | 否 |
| `/mcp/write` | 默认组 + 写工具 | 是 |
| `/mcp/x/all` | 全部 | 否 |
| `/mcp/x/{toolset}` | 单组 | 否 |
| `/mcp/x/{toolset}/write` | 单组 | 是 |

未知工具组 → 404（不是 400，避免探测存在性）。视图实例缓存在 `ConcurrentHashMap<String, McpSyncServer>`。

**请求上下文传递**：

```
PatAuthenticationFilter
   → request.setAttribute("pd.pat.userId"/"pd.pat.tokenId"/"pd.pat.scopes"/"pd.pat.role")
   → transport.contextExtractor.extract(request) 写入 McpTransportContext
   → McpToolInvoker 从 exchange.transportContext() 取
```

### 5. 安全链

新增 `McpSecurityConfiguration`（`@Order(1)`）：

```java
http.securityMatcher("/mcp/**")
    .cors(Customizer.withDefaults())
    .csrf(csrf -> csrf.disable())
    .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
    .authorizeHttpRequests(a -> a.anyRequest().authenticated())
    .addFilterBefore(patFilter, AnonymousAuthenticationFilter.class);
```

现有 JWT 链保持 `@Order(2)` 且不加 `/mcp/**` 规则——**两条链按 matcher 互斥**，不存在顺序写反的静默失效。

PAT 端点 `/api/v1/ai/mcp-tokens` 走现有 JWT 链 + `@ApiAccess(AUTHENTICATED)`。

## 请求或事件流程

### 外部 `tools/list`

```
POST /mcp/knowledge  Authorization: Bearer pd_mcp_xxx
 → PatAuthenticationFilter：哈希查询、过期/吊销/scope 校验 → 401 或 principal
 → McpViewRegistry：解析 "knowledge" → ToolQuery(toolsets=[knowledge], readOnly=true)
 → McpServerFactory：缓存命中/构建 → server.handle
 → 返回 tools/list（name/title/description/inputSchema/annotations）
```

### 外部 `tools/call`

```
POST /mcp/write  {"method":"tools/call","params":{"name":"note_create",...}}
 → PAT 校验（必须含 write scope，否则 403）
 → ToolCatalog.find("note_create")；不在当前视图 → 协议错误 -32602 "Unknown tool"
 → schema 校验参数；失败 → 协议错误
 → 限流检查（每 token 每分钟 N 次）→ 超限返回 JSON-RPC error（code -32000）
 → ToolContext(userId=principal, scopes, role, origin=EXTERNAL)
 → tool.call(ctx, args)（内部事务由工具自己所在模块负责）
 → 审计一行日志
 → ToolResult → CallToolResult（text + structuredContent；error → isError=true）
```

### 内部一轮对话

```
POST /api/v1/assistant/stream
 → harness 选择工具组
 → bridge.core = catalog.view(internal(...))；若总数 > 阈值 → 加 find_tools
 → 第 1 轮：model(tools = core + discovered)
 → 模型调用 find_tools("知识 分类")
      → 本地检索目录 → 返回命中的描述符+schema → discovered += 命中
 → 第 2 轮：tools = core + discovered（含新工具）→ 模型调用 knowledge_list_categories
 → bridge.run(...) 执行 → ToolResult 文本回填
```

### PAT 创建

```
POST /api/v1/ai/mcp-tokens {name, scopes:["read"], expiresInDays:90}
 → 校验用户已登录、scopes 子集合法
 → 生成 32 字节随机 → base64url → "pd_mcp_" + 明文
 → 存 sha256(明文) + 前 12 字符前缀
 → 返回 {id, token: 明文, prefix, scopes, expiresAt}（明文仅此一次）
```

## 接口与数据

### 契约签名（`paideia-platform`）

```java
public record ToolDescriptor(
    String name, String toolset, String title, String description,
    Map<String, Object> inputSchema, Map<String, Object> outputSchema,
    boolean readOnly, boolean destructive, boolean idempotent,
    String scope, boolean defaultGroup) {}

public interface PaideiaTool {
    ToolDescriptor descriptor();
    ToolResult call(ToolContext context, Map<String, Object> arguments);
}

public record ToolContext(long userId, String role, Set<String> scopes, ToolContext.Origin origin) {
    public enum Origin { INTERNAL, EXTERNAL }
}

public record ToolResult(boolean error, String text, Map<String, Object> structured) {
    public static ToolResult ok(String text) { ... }
    public static ToolResult ok(String text, Map<String, Object> structured) { ... }
    public static ToolResult fail(String text) { ... }
}

public record ToolQuery(List<String> toolsets, List<String> tools, boolean readOnly,
                        String role, Set<String> scopes, boolean defaultsOnly) {
    public static ToolQuery internal(List<String> toolsets, String role) { ... }
    public static ToolQuery view(List<String> toolsets, boolean write) { ... }
}

public final class ToolCatalog {
    public List<ToolDescriptor> view(ToolQuery query);
    public Optional<PaideiaTool> find(String name);
    public List<ToolDescriptor> search(String query, int limit);
    public List<ToolDescriptor> snapshot();
    public Set<String> toolsets();
}
```

### `paideia-note` 新增 API（唯一既有公共面扩展）

```java
public interface NoteApi {
    List<NoteBrief> list(long userId, int limit);
    Optional<NoteContent> find(long userId, long noteId);
    long create(long userId, String title, String body);
    long append(long userId, long noteId, String text);
    record NoteBrief(long id, String title, Instant updatedAt) {}
    record NoteContent(long id, String title, String body, Instant updatedAt) {}
}
```

### 表 `mcp_access_tokens`（迁移号 TASK-004 实查最大号 +1）

```sql
CREATE TABLE mcp_access_tokens (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(64)     NOT NULL,
  token_prefix  VARCHAR(16)     NOT NULL,          -- 展示用，如 pd_mcp_ab12
  token_hash    CHAR(64)        NOT NULL,          -- sha256 hex
  scopes        VARCHAR(64)     NOT NULL,          -- "read" / "read,write"
  expires_at    DATETIME(3)     NULL,              -- NULL = 不过期
  last_used_at  DATETIME(3)     NULL,
  revoked_at    DATETIME(3)     NULL,
  created_at    DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mcp_token_hash (token_hash),
  KEY idx_mcp_token_user (user_id, created_at)
) ENGINE = InnoDB;
```

- 不加外键（项目既有习惯）；注销级联由 `AiUserDeletionListener` 手写 `DELETE`。
- `token_hash` 唯一索引兼作校验查找与防重。

### HTTP 端点

| 方法 | 路径 | 访问 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/ai/mcp-tokens` | AUTHENTICATED | 创建，明文只回一次 |
| GET | `/api/v1/ai/mcp-tokens` | AUTHENTICATED | 本人列表（不含 hash） |
| DELETE | `/api/v1/ai/mcp-tokens/{id}` | AUTHENTICATED | 吊销（本人，404 统一） |

`/mcp/**` 不进 `/api/v1/**` 契约清单，但有独立集成测试覆盖鉴权矩阵。

## 持久化与迁移

- 迁移文件放 `paideia-app/src/main/resources/db/migration`，编号取当时实际最大号 +1（当前 V15，TASK-004 执行时再查一次）。
- 时间一律应用侧按 UTC 写入，不用库默认值。
- `last_used_at` 更新策略：每次校验成功写一次；为避免每请求一写，采用「距上次更新 > 60 秒才写」。

## 失败处理与恢复

| 场景 | 行为 |
| --- | --- |
| 未知视图 | 404，日志记视图名，不记 token |
| 未知工具 | JSON-RPC `-32602`，消息含工具名 |
| 参数不符合 schema | JSON-RPC `-32602`，消息含第一个字段错误 |
| 工具内部异常 | `isError: true`，文本为脱敏的一句；完整堆栈只进服务端日志（logger `mcp.error`） |
| 上游 AI 失败（AI 工具） | 按现有语义退款/结算，返回 `isError: true` |
| 限流 | JSON-RPC `-32000`，消息含 `retryAfterSeconds` |
| PAT 校验数据库异常 | 500，不降级放行 |
| 视图构建失败 | 启动期失败（fail fast），不在请求里重试 |

## 安全与权限

- **默认拒绝**：`/mcp/**` 由独立链保护，`anyRequest().authenticated()`；PAT 过滤器失败直接 401 并 `return`。
- **恒定时间比较**：`MessageDigest.isEqual`；即便哈希碰撞也不会因比较耗时泄露。
- **token 前缀不入日志**：审计只记 `tokenId`（数据库主键）。
- **参数无归属字段**：`ToolContext.userId` 是唯一来源；若参数里出现 `userId`/`ownerId`，工具实现忽略。TASK-008 有测试覆盖「传 userId 不越权」。
- **写视图二次闸**：即使 token 有 `write` scope，`/mcp/x/{toolset}`（无 `/write`）仍然只读。
- **管理组**：`scope=admin` 的工具只对 `ROLE_ADMIN` 出现；PAT 的 `admin` scope 不能单独解锁，必须用户本身是 ADMIN。
- **SSRF**：本项不抓取外部 URL（工具只读写本库），无 SSRF 面。

## 可观测性

- logger `mcp.audit`：`tokenId=%d userId=%d tool=%s view=%s durationMs=%d isError=%s bytes=%d`
- logger `mcp.error`：异常堆栈（不含参数正文）
- Micrometer 计数器（若已存在）：`mcp.tools.calls{view,tool,error}`；一期只加日志，指标留到可观测性项。
- 健康检查：`/actuator/health` 不变；MCP 视图不增加额外端点。

## 实施顺序

1. 契约 + 目录 + 校验 + 过滤（platform）
2. 知识工具迁移到目录（内部仍走老路径，但新路径已有测试）
3. 内部桥接 + `find_tools` + 增量 schema（切换 harness）
4. `NoteApi` + note/account 工具
5. PAT 表 + 服务 + 注销级联
6. `paideia-mcp` 骨架 + 启动冒烟（`tools/list` 能回）
7. PAT 过滤器 + 安全链 + 鉴权矩阵
8. 视图路由 + 执行器 + 错误语义 + 审计
9. PAT 管理前端
10. conformance + 文档

## 测试策略

| 层 | 内容 |
| --- | --- |
| 纯单元 | `ToolCatalogTest`（校验 6 条失败、过滤顺序、确定性排序、检索打分）、`PatServiceTest`（哈希、过期、吊销、恒定时间） |
| 切片 | `McpViewRegistryTest`（路径 → 查询）、`McpToolInvokerTest`（错误映射、限流、审计字段） |
| 集成 | `McpAuthenticationTest`（鉴权矩阵）、`McpToolsIntegrationTest`（list 与 call 一致性、归属 404、写视图 403）、`McpTokenLifecycleTest`（创建/吊销/过期/注销级联） |
| 内部 | `AgentToolBridgeTest`（阈值、find_tools、增量 schema、无关查询）、现有 `ConversationUsageAccumulationTest` 回归 |
| 前端 | `mcp-tokens.test.tsx`（创建一次展示、吊销、390px） |
| 协议 | 官方 conformance 套件一次运行，结果入报告 |
| 反向验证 | 去掉只读过滤 → 视图测试红；去掉 SHA-256 比较 → 鉴权测试红；去掉 `write` scope 检查 → 403 测试红 |

## 需求追踪

| REQ | 设计位置 | 任务 |
| --- | --- | --- |
| REQ-001/002/003 | 组件 1 | TASK-001 |
| REQ-004/005 | 组件 1 过滤链 | TASK-001 |
| REQ-006/007/008/009/010 | 组件 3 | TASK-003 |
| REQ-011..019 | 组件 4、5 | TASK-006/007/008 |
| REQ-020/021/022 | 可观测性、失败处理 | TASK-005/008 |
| REQ-023/024 | 组件 4 执行器 | TASK-008 |
| REQ-025 | 前端 | TASK-009 |
| REQ-026/027 | 契约与解耦 | TASK-001、TASK-010 |

## 待决定事项

- 是否需要把 `assistant_draft_note` 暴露给外部（当前设计：不暴露）。
- 写工具一期清单（当前设计：note_create/append、assessment_mark_entry）。
- PAT 默认过期（当前设计：默认 90 天，可选永不过期）。
- 是否为 `tools/list` 配置 `ttlMs`/`cacheScope`（一期用 SDK 默认，不加缓存提示）。
