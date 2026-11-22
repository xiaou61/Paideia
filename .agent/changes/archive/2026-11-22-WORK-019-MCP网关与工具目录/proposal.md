---
artifact: proposal
work_id: WORK-019
work: MCP 网关与工具目录
status: approved
workflow: full
depends_on: [requirements]
related_to: [WORK-006, WORK-009, WORK-010, WORK-013]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 23:17:03 +0800
approver_role: CTO
---

# 提案 · MCP 网关与工具目录

## 摘要

**做一份工具目录，接两个适配器**：

```
业务模块（knowledge / note / account / ai）
   │  注册 PaideiaTool（描述符 + 处理函数）
   ▼
ToolCatalog（唯一真源：命名、分组、schema、注解、scope、只读）
   ├── 内部适配器：AgentScope harness 每轮取「核心包 + 已发现」，超过阈值时挂 find_tools
   └── 外部适配器：paideia-mcp 模块，Streamable HTTP + PAT，
                   按 URL 视图（/mcp、/mcp/x/{toolset}、/write）返回静态清单
```

这套形状照 GitHub 官方 MCP Server 的 `pkg/inventory`：一份 Inventory、多层过滤（只读 → 工具组 → 显式工具 → 权限）、确定性排序、多视图 URL。它已经被用来暴露约 90 个工具，是我们能验证过的最大规模同类设计。

**不是**「把 83 个端点全量转成 83 个工具」。一期只策展 10–14 个工具（知识读 4、笔记读 2 + 写 2、账号读 1、自评 2、草稿 1），用目录与按需加载保证「以后加工具不涨首轮成本」。

## 推荐方案

### 1. 契约放共享内核

在 `paideia-platform` 增加 `io.github.xiaou61.platform.tools` 包：

- `ToolDescriptor`：名称、工具组、标题、描述、输入/输出 schema、只读/破坏性/幂等、所需 scope、是否默认组。
- `PaideiaTool`：`descriptor()` + `call(ToolContext, Map<String,Object>)`。
- `ToolContext`：`userId`、`role`、`scopes`、`origin`（INTERNAL/EXTERNAL）。**归属只有这一个来源。**
- `ToolResult`：`error`、`text`、`structured`。
- `ToolCatalog`：收集全部 `PaideiaTool` Bean，启动时校验重名/非法名，提供 `view(ToolQuery)` 与 `find(name)`。

业务模块只依赖 `paideia-platform`，不接触 MCP SDK；工具实现留在拥有数据的模块里（知识工具在 knowledge、笔记工具在 note），符合 Modulith。

### 2. 过滤链只有一条

`ToolQuery(toolsets, tools, readOnly, role, scopes, defaultsOnly)` → 有序 `List<ToolDescriptor>`。顺序固定：

1. 工具自身 `enabled`（一期没有动态开关，留字段但不实现规则引擎）
2. `readOnly`：排除 `readOnly=false`
3. 工具组 / 显式工具（显式工具可越过工具组过滤）
4. scope：token 缺少所需 scope 即排除
5. 角色：管理组仅 `ADMIN`

内部与外部都调这一个方法。测试只测一次，两个适配器共享正确性。

### 3. 外部用**已有**的 SDK，不新增依赖

- **复用 AgentScope 已传递引入的 `io.modelcontextprotocol.sdk:mcp:0.17.0`**（实测 `mvn dependency:tree -pl paideia-ai` 确认它在 classpath 上，由 `agentscope-core:2.0.0` 带入）。它含 `HttpServletStreamableServerTransportProvider`、`McpServer.sync(streamableTransport)`、`contextExtractor`，协议到 2025-06-18——够用。
- **不升到 2.x**：那是与 AgentScope 自带版本冲突的升级，且 2026-07-28 只在 2.2.0-M1 里。等 SDK 统一升级时再换适配器。
- 每个视图在第一次请求时构建一个 `McpSyncServer` 并缓存：视图 = 工具组集合 × 是否含写。
- 推荐端点：`/mcp`（默认组只读）、`/mcp/write`、`/mcp/x/{toolset}`、`/mcp/x/{toolset}/write`、`/mcp/x/all`。
- 视图是路径的一部分，所以「清单里有」与「能调用」天然一致。
- 请求上下文：PAT 过滤器把 `userId`/`scopes`/`tokenId` 写进 request attribute → transport 的 `contextExtractor` 放进 `McpTransportContext` → 工具处理函数取出。
- `/mcp/**` 用**独立的安全链**（`@Order(1)`，`securityMatcher("/mcp/**")`），只跑 PAT 过滤器；不改动现有 JWT 链的顺序，避免「路径规则写反」这类静默失效。

### 4. 外部凭据用 PAT

新表 `mcp_access_tokens`（名称、前缀、SHA-256 哈希、scopes、过期、最后使用、吊销、创建时间）。token 格式 `pd_mcp_<32 字节 base64url>`，明文只在创建响应里出现一次。

不选 OAuth 2.1 的理由不是「麻烦」，而是**收益不成立**：OAuth 的价值在于第三方客户端在没有预共享凭据时完成授权；我们的目标客户端（ZCode/Claude Desktop/Cursor）都支持在配置里填 Bearer。为它们建授权服务器 + 动态注册 + 资源元数据，只是多一个可被攻击的组件。

代价：将来若要让陌生客户端「一键授权」，需要再补 OAuth 前置（PAT 可以作为其最终凭证），这是一次明确的返工点。

### 5. 内部按需加载

- 阈值 `paideia.ai.tools.eager-limit`（默认 12）。
- 目录视图 ≤ 阈值：全量下发，**不出现** `find_tools`，行为与现在一致。
- 超过阈值：核心包 = 默认组；其余延迟。`find_tools(query, limit)` 用词面加权打分（名称命中 3、标题 2、描述 1、工具组 1），返回命中的描述符与完整 schema。
- `AgentScopeConversation` 的工具循环从「循环外算一次 schema」改为「每轮用 `core + discovered` 重算」，`find_tools` 的返回把新工具加进本请求的 `discovered` 集合。
- 不依赖 provider 端 tool search（DeepSeek 没有）；将来接入支持的 provider 时，这个机制仍可保留为兜底。

### 6. 一期工具清单（15 个）

| 工具组 | 工具 | 读/写 |
| --- | --- | --- |
| knowledge | `knowledge_search`、`knowledge_get_entry`、`knowledge_list_categories`、`knowledge_my_marks` | 读 |
| note | `note_list`、`note_get`、`note_create`、`note_append` | 后两个写 |
| account | `account_me` | 读 |
| assessment | `assessment_list_marks`、`assessment_mark_entry` | 后者写 |
| assistant | `assistant_draft_note`、`assistant_ask` | 读（前者只产草稿，后者复用积分） |

外部默认视图 = knowledge + note(读) + account + assessment(读) + `assistant_ask`；`write` 视图再加 3 个写工具（`note_create`、`note_append`、`assessment_mark_entry`）。`assistant_draft_note` 只在内部视图出现——它的产物是界面草稿，外部客户端没有确认 UI。

**用户裁决（2026-10-18）**：写工具就这三个，其余写操作不暴露；`assistant_ask` 进第一期；PAT 默认 90 天、可选永不过期。

## 备选方案

### A. 全量 OpenAPI → MCP 自动转换（Unla / Higress / openapi-mcp-generator）

**最强理由**：一次配置就能把 83 个端点全部暴露，开发量最小；第三方网关自带管理 UI 与审计。
**不选的原因**：① 仓库没有 OpenAPI，第一步就得引入 springdoc 并维护规格；② 一操作一工具会把工具数量打到 83，首轮 schema 约一万多 token，积分与准确率双输；③ 网关的工具级 header 是静态模板，接不住每用户凭据；④ 流式对话、附件上传、删除确认这类端点转出来是坏工具；⑤ 多一个 Go/Python 进程要部署、要升级、要与本项目的注销级联和计费打通。
**它仍适合的场景**：将来要接别人的 API（我们作为 MCP 客户端去消费），或者要多租户控制台与集中审计。

### B. 直接在 `paideia-ai` 里加 MCP 端点

**最强理由**：不新增 Maven 模块，装配最少，复用 ai 的 Spring 配置。
**不选的原因**：`paideia-ai` 目前只依赖 knowledge；要暴露 note/account 工具就得让 ai 依赖它们，而 note 也不该依赖 ai（已有 ReportController 与 AI 的边界讨论）。新模块只依赖 platform，谁都不绑死；MCP 是传输适配，不是 AI 领域能力。

### C. 用 2.2.0-M1 + 2026-07-28 无状态传输

**最强理由**：无状态与本项目「无 session」完全一致，且 `McpSyncToolRepository.list(ctx)` 原生支持每请求裁剪，客户端缓存与提示词缓存友好。
**不选的原因**：2.2.0-M1 是里程碑版本，2026-07-28 刚发布不到三个月，主流客户端仍以 2025-06/2025-11 为主；现在只支持新协议会直接降低「接得上」这个首要目标。目录与传输解耦，升级只是多一个适配器。
**何时改**：当目标客户端普遍声明 2026-07-28，或我们需要每请求动态裁剪（如按订阅等级给工具）时，加第二个 transport provider，不改工具。

### C2. 升级到 mcp 2.1.0（稳定版）

**最强理由**：协议支持到 2025-11-25，比现用的 0.17.0 新一档。
**不选的原因**：`agentscope-core:2.0.0` 已经把 `mcp:0.17.0` 带进 classpath；升到 2.1.0 等于与 AgentScope 期望的版本对抗，一旦它用了变化过的 API 就会 `NoSuchMethodError`，且是用到某条路径才发现的那种。工具代码与传输解耦，等 SDK 统一升级时再换。
**何时改**：AgentScope 升级并携带 2.x，或我们决定为 MCP 引入独立类加载隔离（不值得，除非确实需要新协议特性）。

### D. 只扩 `AssistantTools`，不做 MCP

**最强理由**：零新依赖、零新攻击面，够内部用。
**不选的原因**：用户明确要外部助手接入，这条路线做不到；而且工具一多，硬编码 switch 与首轮全量 schema 两个问题都会回来。

## 仓库影响

| 区域 | 变化 |
| --- | --- |
| `paideia-platform` | 新增 `tools` 契约包（无外部依赖） |
| `paideia-knowledge` / `paideia-note` / `paideia-account` | 各新增 1 个工具实现类，只依赖本模块 API |
| `paideia-ai` | `AssistantTools` 改为目录适配器；工具循环支持增量 schema；新增 `find_tools` |
| **新模块** `paideia-mcp` | MCP 传输、视图、PAT 过滤器/服务、安全链、审计与限流 |
| `paideia-app` | 依赖新模块；新增迁移；注销级联登记 |
| 前端 `apps/app` | 设置页新增 PAT 管理 |
| 构建 | `backend/pom.xml` 增加模块；新增一个依赖 |

## 交付拆分

| 阶段 | 内容 | 可独立验证 |
| --- | --- | --- |
| P1 目录 | 契约 + 目录 + 过滤链 + 知识工具迁移 | 单测；内部助手行为不变 |
| P2 内部按需 | 阈值、`find_tools`、增量 schema | 合成目录单测；现有 e2e 回归 |
| P3 凭据 | PAT 表、服务、注销级联、管理端点 | 集成测试 + 管理页 |
| P4 外部服务 | `paideia-mcp`、视图、安全链、执行器 | 协议集成测试 + conformance |
| P5 收尾 | 文档、接入示例、限流与审计断言 | 文档审查 + 冒烟 |

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| SDK 里程碑/稳定版与 Boot 4、Jackson 3 不兼容 | 复用已有 0.17.0（它在当前构建里已经工作），启动冒烟确认 `McpServer.sync` + Servlet 注册 + `tools/list` 回包 |
| 升级 MCP SDK 引入与 AgentScope 的版本冲突 | 不升级；隔离在 `paideia-mcp` 适配器内，工具代码不依赖 SDK 类型 |
| Modulith 因新模块依赖报边界错误 | `paideia-mcp` 只依赖 `paideia-platform`；工具通过 Bean 收集，不 import 业务包 |
| 每视图一个 `McpSyncServer` 带来内存增长 | 视图数量 ≤ 12，按需惰性构建；单视图工具 ≤ 20，压测记录内存 |
| PAT 泄露 | 只存哈希、只显示一次、可吊销、可选过期、按 token 限流；日志只记 tokenId |
| 工具名/描述变更破坏外部客户端 | REQ-026 别名规则；目录快照测试在 CI 里锁住清单 |
| 写工具被误用 | 默认视图只读；写视图需 write scope；破坏性标记 + 描述；二期再加逐工具开关 |
| 按需加载让模型「看不见」能力 | `find_tools` 描述里列出可用工具组；阈值以下不启用；命中结果带完整 schema |

## 验收映射

| 验收 | 落点 |
| --- | --- |
| AC-001 目录一致性 | TASK-001（导出接口）+ TASK-006（外部清单同源测试） |
| AC-002 视图正确 | TASK-001、TASK-006 |
| AC-003 鉴权矩阵 | TASK-005、TASK-007 |
| AC-004 清单与调用一致 | TASK-006、TASK-008 |
| AC-005 归属隔离 | TASK-002、TASK-008 |
| AC-006 内部按需加载 | TASK-003 |
| AC-007 零积分 | TASK-008 |
| AC-008 协议一致性 | TASK-010 |
| AC-009 错误语义 | TASK-008 |
| AC-010 审计日志 | TASK-008 |
| AC-011 注销级联 | TASK-005 |
| AC-012 迁移与守卫 | TASK-005 |
| AC-013 PAT 管理页 | TASK-009 |
| AC-014 接入文档 | TASK-011 |
| AC-015 `assistant_ask` 计费 | TASK-012 |

## 待决定事项

需求文档末尾的三条（写工具范围、是否暴露 `assistant_ask`、PAT 默认过期）在批准需求时一并裁决；它们都不阻塞目录与内部阶段的实现，但决定 P4 的工具清单。
