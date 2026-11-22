---
artifact: research
work_id: WORK-019
work: MCP 网关与工具目录
created: 2026-10-18
updated: 2026-10-18
---

# 调研 · MCP 网关与工具目录

本文件只记录可复查的外部事实与本仓库事实；判断与取舍写在 `proposal.md`。

## 一、本仓库现状（2026-10-18 逐条核对）

| 项 | 事实 | 核对方式 |
| --- | --- | --- |
| 生产端点 | **83 个** / 26 个 controller | `rg -o "@(Get\|Post\|Put\|Delete\|Patch)Mapping" backend --glob "*.java" -g "!**/src/test/**"` |
| 分布 | ai 40、knowledge 19、note 12、account 9、sandbox 2、security 1 | 同上，按模块目录统计 |
| OpenAPI | **没有** springdoc/swagger 依赖，也没有任何规格文件 | `rg -n "springdoc\|swagger" pom.xml backend/*/pom.xml` → 0 命中 |
| 助手工具 | 4 个：`search_entries`、`list_categories`、`my_marks`、`draft_note` | `AssistantTools.java` 第 29–32 行 |
| 工具装配点 | harness 在循环外算一次 schema：`List<ToolSchema> schemas = tools.schemas(request.harness())` | `AgentScopeConversation.java:86` |
| 模块依赖 | `ai → knowledge`；note/account 互不依赖；app 聚合全部 | 各 `pom.xml` |
| 模块边界 | Spring Modulith 构建期校验，跨模块只能碰包根 API | `backend/paideia-app/src/test/java/io/github/xiaou61/ModularityTest.java` |
| 认证 | 自签 JWT（HS256），无 Cookie，`SecurityFilterChain` 单链按路径顺序匹配 | `paideia-security/SecurityConfiguration.java` |
| 端点声明 | `/api/v1/**` 必须 `@ApiAccess`，否则契约测试红 | `paideia-platform/ApiAccess.java` |
| 注销级联 | 私有表要在 `AiUserDeletionListener` 表清单里登记 | `.agent/memory.md`、WORK-018 事实表 |
| 计费 | `CreditService.reserve/settle/refund`，余额不足 402 且不发上游请求 | WORK-009 |
| 迁移 | 全部集中在 `paideia-app/src/main/resources/db/migration`，现有最大号 V15 | 目录实查 |

## 二、MCP 规范事实（2026-07-28）

来源：<https://modelcontextprotocol.io/specification/latest>（本页指向 2026-07-28），2026-10-18 读取。

- **传输**：标准列表只剩两种——stdio 与 **Streamable HTTP**（单端点 POST，回复是 JSON 或请求级 SSE）。旧 HTTP+SSE 不再列在标准传输里。
- **无状态**：2026-07-28 取消了 `initialize` 握手与连接级 session，元数据放每请求 `_meta`；`server/discover` 供客户端发现能力。规范原文：「nothing here is ever cached across requests; the protocol is stateless」。
- **鉴权是可选**：HTTP 传输下若做鉴权 **SHOULD** 走 OAuth 2.1；完整要求包括 RFC 9728（受保护资源元数据）、RFC 8707（resource 参数）、客户端注册。规范没有禁止「已有的 Bearer 凭据」，但严格按发现流程走的客户端需要 OAuth 元数据。
- **tools/list 可以随请求授权变化**：原文「The set **MAY** vary by the authorization presented on the request — for example, returning only the tools the caller's granted scopes permit — since credentials are per-request input, not connection state」。
- **确定性顺序**：规范建议按稳定顺序返回工具，以便客户端缓存与提示词缓存命中。
- **工具注解**：`readOnlyHint`、`destructiveHint`、`idempotentHint`、`openWorldHint`；客户端必须把注解当作不可信（除非 server 可信）。
- **输出 schema**：`outputSchema` + `structuredContent`；有 schema 时 server **MUST** 返回符合 schema 的结构化结果。
- **命名**：1–128 字符，允许字母数字、`_`、`-`、`.`；规范明确提醒聚合网关会撞名，建议加 server 前缀。
- **没有原生工具搜索**：`tools/list` 只有 cursor 分页。SEP-1821（给 `tools/list` 加 `query` 与 `tools.toolsets.filtering` 能力位）到 2026-10-18 仍是 **Draft**，讨论中的反对意见是「客户端在拉全量之前不知道搜什么」。→ 不能依赖协议层按需发现。

## 三、官方 Java SDK 事实

来源：<https://github.com/modelcontextprotocol/java-sdk>、Maven Central 元数据、v2.1.0 / v2.2.0-M1 源码，2026-10-18 读取。

| 版本 | 状态 | 关键事实 |
| --- | --- | --- |
| `2.1.0` | 稳定（2026-10-16 发布） | 支持协议到 **2025-11-25**；`mcp-core` 含 Servlet 传输：`HttpServletStreamableServerTransportProvider`；`McpSyncServerExchange.transportContext()`；provider builder 有 `contextExtractor`、`mcpEndpoint` |
| `2.2.0-M1` | 里程碑（2026-10-16 发布） | 唯一包含 `io.modelcontextprotocol.modern`（2026-07-28 无状态实现）的发布；`ProtocolVersions.LATEST_PROTOCOL_VERSION = "2026-07-28"`；`McpSyncToolRepository.list(McpRequestContext ctx, cursor)` 支持**每请求**工具列表 |

- `mcp` 聚合件 = `mcp-core` + `mcp-json-jackson3`，本项目 Boot 4 用 Jackson 3，版本对齐。
- `mcp-core` 直接依赖只有：`slf4j-api`、`jackson-annotations`、`reactor-core`、`jakarta.servlet-api`(provided)。**不需要 Spring AI**（Spring 集成在 `org.springframework.ai`，D-09 不冲突）。
- README 的 conformance 结果：Server 40/40；Auth（Spring）12/14 场景。

## 四、可参考的成熟设计

### GitHub MCP Server（MIT，33.7k star，2026-10-15 仍在更新）

来源：<https://github.com/github/github-mcp-server>，重点读 `pkg/inventory/`。

- **`Inventory` + `Builder`**：工具注册后由 Builder 一次性构建，携带 `readOnly`、`enabledToolsets`、`additionalTools`、`featureChecker`、任意 `ToolFilter` 函数。
- **过滤顺序写死在注释里**：① 工具自身 `Enabled` → ② 只读与 Builder 过滤器 → ③ toolset/显式工具 → ④ 特性开关。任一失败即排除。
- **确定性排序**：先按 toolset ID、再按工具名。
- **`ForMCPRequest(method, itemName)`**：为每个请求只注册该请求需要的条目（源码注释：不必注册全部约 90 个工具）。说明「每请求视图」在生产规模下扛得住。
- **远程多视图**：`/mcp`（默认）、`/mcp/x/all`、`/mcp/x/{toolset}`，任一 URL 末尾加 `/readonly`；也可用请求头 `X-MCP-Toolsets`、`X-MCP-Tools`、`X-MCP-Readonly`。
- **鉴权**：OAuth 或 PAT 两种都支持；PAT 走 `Authorization: Bearer`。
- **工具集是元数据**：ID、描述、`Default` 标记、图标；文档由代码生成。

### 其他方案（作为备选记录，未采用）

| 方案 | 机制 | 不适配点 |
| --- | --- | --- |
| Unla（Go，2.2k star） | YAML 把 REST 映射成 MCP；自带 OpenAPI 转换器 `pkg/openapi/converter.go` | 工具级 header 是静态模板，接不住每用户 JWT；需先补 OpenAPI |
| Higress（Wasm `mcp-server` 插件） | 网关侧把 REST 暴露成工具，支持 bearer 透传 | 等于把整个 API 面暴露，无 curation；仍是配置驱动 |
| IBM ContextForge（Python，4.6k） | MCP/REST/gRPC 联邦、注册中心、管理 UI | 单服务场景过重；运行时是另一个进程 |
| MCPHub（Node，2.5k） | 多 server 控制面、语义路由 | 面向「聚合别人的 server」，不是「把自己的能力暴露出去」 |
| executor（Node，4.2k） | OpenAPI/GraphQL 工具目录 + 策略 | 同上，且策略模型与我们不同 |

## 五、按需加载（deferred tool loading）事实

| 提供方 | 机制 | 依据 |
| --- | --- | --- |
| Anthropic | 工具标 `defer_loading: true`；内置 `tool_search_tool_bm25_20251119` / `tool_search_tool_regex_20251119`；命中后以 `tool_reference` 把 schema 拉进同一轮 | anthropic-sdk-ruby `lib/anthropic/models/tool_search_tool_*.rb`（字段注释逐字核对） |
| OpenAI | hosted `tool_search` + deferred function loading（Responses 接口） | 开源实现 VT Code `docs/tools/TOOL_SEARCH.md` |
| **DeepSeek（本项目所用）** | 无端侧工具搜索；不能依赖 provider 把 schema 延迟注入 | 由上面两条反推 + 现有调用形态；**TASK-003 以一次真实请求确认不支持的表现为「忽略未知字段」** |

开源实现（VT Code）的阈值经验：非核心工具默认全部 defer；工具 schema 合计超过约 4k tokens 也强制 defer。

## 六、本项目 MCP 相关的既有裁决

- **WORK-006 记过「现在不做 MCP」**（`.agent/memory.md`、`.agent/changes/WORK-006-*`）。本项就是那次决策的**重新裁决**：当时结论的前提是「50 端点 + 4 工具远在安全区」，现在端点 83、跨端接入成为需求，前提变了。
- **D-09**：只用 AgentScope Java，不叠加 Spring AI。官方 MCP SDK 不是 Spring AI，不冲突。
- **不做多租户**、**不做向量检索** 仍然有效：工具搜索用词面打分，不引向量。

## 七、未核实事项（不写进结论）

- 官方 conformance 套件对 2025-11-25 + Servlet 传输的组合是否仍全绿：由 TASK-010 实跑记录，不预设结果。
- Boot 4.1.1 + `mcp 2.1.0` 的 Jackson 3 版本是否完全对齐：由 TASK-006 的启动冒烟确认。
- 桌面端 / 移动端是否需要 MCP 客户端能力：本项不做（见非目标）。
