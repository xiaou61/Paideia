---
artifact: proposal
work_id: WORK-031
work: Agent运行时迁移到ReActAgent
status: approved
depends_on: []
related_to: [WORK-009, WORK-019, WORK-022, WORK-030]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 14:59:57 +0800
approver_role: CTO
---

# 提案 · Agent 运行时迁移到 ReActAgent

## 摘要

把「自己写的工具循环」换成 AgentScope 的 `ReActAgent` + `MiddlewareBase`，**分三步、每步可独立验收**。第一步只升版本（不改架构），先把最确定的收益拿到：修掉 `cache_read_tokens` 恒为 0 的真缺陷，并拿到框架已经做好的两个中间件。

## 推荐方案

### 为什么值得换（尖刺实测，非推测）

2026-10-21 用 `agentscope-core:2.0.4` + 真实 DeepSeek 跑了三个尖刺，结论：

1. **程序化工具直接能接**：`Toolkit.registerAgentTool(AgentTool)`，接口是 `getName/getDescription/getParameters/callAsync`，与项目 `PaideiaTool` 几乎同形。不需要 `@Tool` 注解，也不需要 Schema-Only + `TOOL_SUSPENDED` 的挂起往返。
2. **完整请求体拿得到**：`MiddlewareBase.onModelCall` 的 `ModelCallInput` 有 `messages() / tools() / options() / model()`，正是"实际发送"那一刻的内容。
3. **用量字段更全**：`ChatUsage` 有 `getCachedTokens()` 与 `getReasoningTokens()`，我们现在两个都丢。

### 不选的路

| 方案 | 为什么不选 |
| --- | --- |
| 继续维护手写循环 | 三处实测补丁里有两处（最终轮才推正文、每轮一条 `ToolResultMessage`）在 2.0.4 已由框架承担；我们在维护别人已经修好的东西 |
| 直接升到 `2.1.0-BETA1` | BETA，不值得为不确定的收益承担迁移风险 |
| 一次性重写（不升版本、直接换架构） | 版本升级与架构迁移的风险混在一个提交里，出问题分不清是谁造成的 |

### 三步走

| 步 | 内容 | 风险 | 收益 |
| --- | --- | --- | --- |
| **1** | 升 `2.0.0 → 2.0.4`，MCP pin `0.17.0 → 0.17.2`，修缓存 token | 低（不改架构） | `FinalAnswerFilterMiddleware`、`OtelTracingMiddleware`、`cache_read_tokens` 有值 |
| **2** | `ReActAgent` 替换 379 行手写循环 | 高（对话链路） | 不再维护框架已解决的问题 |
| **3** | `MiddlewareBase` 成为唯一观测钩子 | 中 | WORK-030 阶段 B 的采集点定型 |

**本次用户只批准了第一步。**

## 关键决策

| 决策 | 理由 |
| --- | --- |
| MCP SDK pin 必须与 AgentScope 传递版本一致 | 2.0.4 传递 `mcp:0.17.2`；不同步升会在**运行期** `NoSuchMethodError`，编译期查不出来 |
| 工具保持程序化注册，不给业务类加注解 | 工具目录是公共契约（`paideia-platform`），不能为适配某个 SDK 给业务类加 `@Tool` |
| 记忆不交给框架 | `AgentStateStore` 是 `(userId, sessionId, key)` KV，装不下"结构化 + 全文两路 + 按轮召回" |
| 缓存 token 用 `TokenUsage` 加一个分量、保留三参构造 | 现有几十处调用点不改；只新增 4 参构造与访问器 |
