---
artifact: tasks
work_id: WORK-031
work: Agent运行时迁移到ReActAgent
status: approved
depends_on: []
related_to: [WORK-009, WORK-022, WORK-030]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 14:59:57 +0800
approver_role: CTO
---

# 任务 · Agent 运行时迁移（第一步）

本文件只覆盖**第一步**。第二、三步在第一步通过后追加。

## 计划

### TASK-001 | done | 升 AgentScope 与 MCP SDK 版本

**做什么**

- `backend/pom.xml`：`mcp-sdk.version` `0.17.0` → `0.17.2`
- `backend/paideia-ai/pom.xml`：两个 agentscope 依赖 `2.0.0` → `2.0.4`
- 不改任何 Java 代码

**为什么先做它**：2.0.4 的 API 是否兼容我们现有用法，只有编译才知道。带着架构改动一起升，编译错误会分不清是谁引起的。

**可检查结果**

- `mvn -B -pl paideia-ai -am compile` 通过
- `mvn -B dependency:tree -pl paideia-ai` 里 `io.modelcontextprotocol.sdk:mcp` 是 `0.17.2`

### TASK-002 | done | TokenUsage 承载缓存 token

**做什么**

- `TokenUsage` 加 `cacheReadTokens` 分量，保留三参构造
- `AgentScopeConversation.toUsage()` 传 `usage.getCachedTokens()`

**可检查结果**

- 全模块编译通过（证明三参构造的兼容性保住了）
- 新增一条单测：三参构造得到 `cacheReadTokens == 0`，四参构造得到给定值

### TASK-003 | done | 把缓存 token 写进两处遥测

**做什么**

- `AgentScopeConversation` 的 `step.tokens(input, output, 0)` → `usage.cacheReadTokens()`
- 其余 `run.tokens(input, output, 0)` 调用点同样替换

**可检查结果**

- 全模块编译通过
- 真实调用后 `ai_runs.cache_read_tokens > 0`（同问题连问两次，第二次命中缓存）

### TASK-004 | done | 验证

**做什么**

- 内环：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`
- 全量：`scripts/test-db.sh mvn -B -T 1C test`
- 两处都要**核对 Skipped 列**，不是只看 BUILD SUCCESS

**可检查结果**

- 全绿；Skipped 数与改动前一致
- `AiTelemetryIntegrationTest`、`McpStartupSmokeTest` 在内并通过

### TASK-005 | done | 第二步：ReActAgent 替换手写循环

**做了什么**

- `AgentScopeConversation` 重写：手写 `for (round)` 删除，改由 `ReActAgent` + `RoundMiddleware` 驱动
- 三条实测约束落到代码：每次请求新建 Agent、提示词合成一条 `sysPrompt`、
  `AgentToolBridge.callable()` 一次注册齐 + Middleware 逐轮收窄
- 三处实测补丁逐条对上：最终轮才推正文、tool 消息交框架、思考不进历史
- 顺带修两处迁移中暴露的缺陷：步序号并发撞号、用量累加丢更新

**可检查结果（实测）**

- `AgentTurnBehaviorTest` 7 条全绿（假模型走真实 ReActAgent）
- `AgentLiveConversationTest` 2 条真连 DeepSeek 通过（含真工具循环，答案含工具返回内容）
- 反向验证：去掉 schema 收窄后 `find_tools` 用例立刻红

### TASK-006 | done | 第三步：观测采集点收口到 Middleware

**做了什么**

- 每轮模型调用在 `RoundMiddleware.onModelCall` 里开一步 `SpanSpec.llm(seq)`，记 tokens（含缓存）与成功/失败
- 每次工具调用在 `BridgedTool.callAsync` 里开一步 `SpanSpec.tool(name)`，工具入参与返回都在这一步可达
- `Middleware.onModelCall` 的 `ModelCallInput` 提供 messages/tools/options/model——WORK-030 阶段 B 的采集点就此定型

**可检查结果（实测）**

- 全量 554 条 / 0 失败 / 10 跳过，`AiTelemetryIntegrationTest` 在内通过

## 完成判据

- 四个任务全部 done，且 TASK-004 的输出被记录到 `testing/report.md`
- 版本升级与缓存修复分两笔提交（可各自回滚）
- 不 push（远端推送需单独授权）

## 不在本批

第二步（`ReActAgent` 替换手写循环）与第三步（`MiddlewareBase` 观测）**不在本次实现范围**，等第一步验证通过后追加任务。
