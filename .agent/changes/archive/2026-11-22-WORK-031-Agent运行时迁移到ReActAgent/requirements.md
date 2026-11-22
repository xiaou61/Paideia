---
artifact: requirements
work_id: WORK-031
work: Agent运行时迁移到ReActAgent
status: approved
mode: strict
mode_reason: 重写全系统对话链路（所有 AI 场景共用的执行器）；改变工具装配、计费记账与遥测写入的位置；需要升公共依赖版本并连带升级 MCP SDK pin；失败会静默影响每一次 AI 调用
workflow: full
related_to: [WORK-005, WORK-006, WORK-009, WORK-019, WORK-022, WORK-030]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 14:59:57 +0800
approver_role: CTO
---

# Agent 运行时迁移到 ReActAgent 需求

## 本项状态与本轮变化

- **2026-10-21 建项起草（draft）**。用户原话：「我们为什么不用 Agent Scope 提供的 ReActAgent 呢？我有点好奇呢。他们的应该是更加好用的吧？我们来讨论一下这个。如果真的是的话，我们是不是可以新开一个 work 来专门做这个事情呢」。
- **触发前提已完成**：Agent 用 `agentscope-core:2.0.4` + 真实 DeepSeek 跑过三轮尖刺，全部通过。证据与命令输出见 [`references/agentscope-and-deepseek-2026-10.md`](../../references/agentscope-and-deepseek-2026-10.md) 第三节。
- **签字之前不动代码。**

## 本项与 WORK-030 的顺序（2026-10-21 用户裁决：本项先做）

用户决定「WORK-031 要先做」。核对后确认依赖是**单向且部分**的：

- **WORK-030 的遥测簇**（步骤级首字 / 模型 / 工具参数 / 工具返回 / 完整 prompt 与模型返回）**等本项完成**。理由：那些采集点正好落在本项要整段替换的代码上——`runRound` 与 `execute()` 会被 `Middleware.onModelCall` 与 `AgentTool.callAsync` 取代；先在旧循环建一遍再搬一次，等于同一件事做两遍。
- **WORK-030 的其余部分**（用户管理、各页筛选与分页、总览、删积分调整页）**不依赖本项**，不碰 `conversation` 包，可以并行。

因此本项要**尽快把「遥测采集点」稳定下来**——`Middleware.onModelCall` 与 `AgentTool.callAsync` 是 WORK-030 阶段 B 的落点，本项交付后它们才算定型。

## 已确认事实

| 问题 | 实测结果 |
| --- | --- |
| 程序化工具（非注解）能否注册 | **能**。`Toolkit.registerAgentTool(AgentTool)`，接口是 `getName/getDescription/getParameters/callAsync`，与项目 `PaideiaTool` 形状几乎一致 |
| 是否需要 Schema-Only + `TOOL_SUSPENDED` 往返 | **不需要**。这条推翻了建项前的判断 |
| `streamEvents` 是否给全事件 | **给全**，17 种事件、两轮工具调用、5.9 秒完成 |
| 完整请求体能否拿到 | **能**。`MiddlewareBase.onModelCall` 的 `ModelCallInput` 有 `messages() / tools() / options() / model()` |
| 用量字段 | `ChatUsage` 有 `getInputTokens / getOutputTokens / getCachedTokens / getReasoningTokens / getTotalTokens / getTime` |
| `hook` 包状态 | **已标 `removal`（待删除）**。新代码只能用 `MiddlewareBase` |
| `ToolResultEndEvent` 含结果正文吗 | **不含**，只有 `state`。正文要在 `AgentTool.callAsync` 里自己记 |
| DeepSeek 上工具 + 格式化 | 正常。`DeepSeekFormatter` + `nativeStructuredOutputWithTools(false)` |

### 当前实现的规模

| 项 | 值 |
| --- | --- |
| `AgentScopeConversation.java` | 379 行，手写工具循环（`for (int round = 0; round < MAX_TOOL_ROUNDS; round++)`） |
| `AgentScopeAssistantAnswerer.java` | 173 行，非流式问答（MCP `ask` 那条） |
| 现有版本 | `agentscope-core:2.0.0` |
| 目标版本 | `2.0.4`（稳定；`2.1.0-BETA1` 不选） |
| MCP SDK pin | 现在 `0.17.0`，2.0.4 传递带 `0.17.2`——**必须一起升** |

## 背景

对话链路是自己写的循环，里面塞着三处「实测算出来」的补丁：

1. 最终轮才推正文（否则工具轮旁白与答案重复两遍，2026-10-19）
2. 每轮各发一条 `ToolResultMessage`（打包会 400，2026-10-19）
3. 思考内容不进历史（2026-10-19）

其中第 1、2 条在 2.0.4 里已经由框架承担（`FinalAnswerFilterMiddleware` 与框架内协议处理）。也就是说**我们在维护框架已经解决的问题**。

更直接的一点：现在的 `toUsage()` 只读三种 token，`step.tokens(..., 0)` 把 cacheRead 写死 0——**`ai_runs.cache_read_tokens` 与 `ai_run_spans.cache_read_tokens` 永远是 0**，而 DeepSeek 实测会报 `cached=256`。这个缺陷与迁移无关，是本项顺带要修的。

## 用户决定

| 编号 | 决定 | 说明 |
| --- | --- | --- |
| U-01 | 采用 AgentScope 的 `ReActAgent`，不只停在手写循环 | 原话「我们难道不可以深度绑定这个 agentscope吗，就是用他们的这一套react 因为我们自己写的肯定是很简单的啊」 |
| U-02 | 新开独立工作项，不并入 WORK-030 | 原话「我们是不是可以新开一个 work 来专门做这个事情呢」 |
| U-03 | **本项先于 WORK-030 的遥测部分** | 原话「那么既然这样 work30是不是要依赖这个31 这个31要先做了」。因此 WORK-030 拆两阶段，阶段 B 等本项 |
| U-04 | **本次只批准第一步（升版本）** | 对「只做第一步 / 三步一次做完 / 先不写代码」三选一，用户选「只做第一步」 |
| U-05 | 开独立 worktree 干活 | 对「开 worktree / 就在主树改」二选一，用户选「开 worktree」 |

## 实现选择

以下由 Agent 依既有边界与仓库模式决定，已在 proposal 与 design 中记录理由：

1. **MCP SDK pin 与 AgentScope 传递版本必须一致**——不同步升是运行期 `NoSuchMethodError`，编译期查不出。
2. **工具保持程序化注册**，不给业务类加 `@Tool` 注解：工具目录是公共契约。
3. **记忆不交给框架**：`AgentStateStore` 是 `(userId, sessionId, key)` KV，装不下两路记忆 + 按轮召回。
4. **`TokenUsage` 用加分量 + 保留三参构造**的方式承载缓存量，几十处调用点一行不改。
5. **缓存不计入 `totalTokens`**：那是上游原始值，动它会让账实相符的断言失去意义。
6. **不用已标 `removal` 的 `hook` 包**，新代码只用 `MiddlewareBase`。

## 目标

- 对话链路改由 `ReActAgent` 驱动，手写循环退役。
- 三处实测补丁的**行为不变**（用对照套件证明，不是"看起来还好"）。
- 拿回现在丢掉的两种用量：`cached` 与 `reasoning` tokens，并让 `cache_read_tokens` 两列真的有值。
- `MiddlewareBase` 成为唯一的观测钩子：完整请求体、工具入参、工具返回、每轮用量。
- 工具装配仍然按 `harness` 与用户权限决定可见集合。

## 非目标

- **不做多 Agent 协作 / 子 Agent**。`SubagentEventBus` 存在但本项不用。
- **不做运行中人工审批 / 打断**。`RequestStopEvent` 与权限门存在，本项不接。
- **不换掉自己的记忆层**。会话历史与两路记忆仍在 MySQL，只是以适配器形式喂给 `AgentStateStore`（或干脆不让框架管记忆，见实现选择）。
- **不改计费规则**。只是把 `cached` / `reasoning` 这两种用量补进记录，单价表与预留结算逻辑不动。
- **不升到 `2.1.0-BETA1`**。
- **不动 WORK-030 的 payload 表结构**。本项只保证"迁移后仍能填满那张表"，表本身归 WORK-030。

## 用户与工作流程

用户可见的变化**应当为零**：同样的提问、同样的流式、同样的草稿确认、同样的积分扣减。唯一可见的差异是管理端能看到更细的用量（缓存命中与思考 token）。

## 功能需求

### 第一步：升版本（可独立验收）

- **REQ-001 升到 2.0.4**：`agentscope-core` 与 `agentscope-extensions-model-openai` 同时升到 `2.0.4`。
- **REQ-002 连带升 MCP SDK pin**：`backend/pom.xml` 的 `mcp-sdk.version` 从 `0.17.0` 改为 `0.17.2`。不升会在运行期 `NoSuchMethodError`（该 pin 上方的注释已经写明这条纪律）。
- **REQ-003 升版本后先跑全绿**：`McpStartupSmokeTest`、`AiTelemetryIntegrationTest`、对话与工具相关集成测试全过，**再动架构**。这一步必须是独立、可回滚的提交。

### 第二步：换运行时

- **REQ-010 `ReActAgent` 驱动对话**：流式对话由 `ReActAgent` 承担，手写 `for` 循环删除。
- **REQ-011 工具以程序化方式注册**：平台目录里的工具经 `registerAgentTool(AgentTool)` 注册，**不引入 `@Tool` 注解**——目录是公共契约，不能为了适配某个 SDK 给业务类加注解。
- **REQ-012 工具可见集合仍按 harness 与权限**：不同 harness 看到不同工具集，`REVIEW` 仍为空集（DeepSeek 上工具与结构化输出不能同一次调用）。
- **REQ-013 `find_tools` 语义保留**：工具数超过阈值时仍提供发现元工具，命中的工具在**同一请求的下一轮**可用。
- **REQ-014 草稿旁路保留**：`NoteDraft` 仍在流中途推到前端，用户不等模型说完就能看到「要不要存」。
- **REQ-015 思考与正文分离**：`ThinkingBlock` 的内容仍然单独通道，不混进正文、不进历史。
- **REQ-016 记忆不变**：会话历史与记忆仍由项目自己的服务读写，框架只拿到本轮要用的 messages。
- **REQ-017 计费逐轮落账**：工具循环里每一轮都是一次真实上游调用，每一轮都必须计入用量——**这条在 WORK-009 修过一次，迁移不许把它退回去**。

### 第三步：观测补齐

- **REQ-020 用 `MiddlewareBase` 记录**：`onModelCall` 记录本轮 messages/tools/options/model，`onActing` 记录工具调用；**不使用已弃用的 `hook` 包**。
- **REQ-021 缓存与思考 token 入库**：`ChatUsage.getCachedTokens()` 与 `getReasoningTokens()` 写进既有列（`cache_read_tokens` 已有；reasoning 若无处落，则进 `attrs`）。
- **REQ-022 `cache_read_tokens` 不再恒为 0**：一次命中缓存的真实调用后，`ai_runs.cache_read_tokens > 0`。
- **REQ-023 工具参数与返回可记录**：在 `AgentTool.callAsync` 实现里留下入参与返回（供 WORK-030 的 REQ-052/053 使用）。
- **REQ-024 完整请求体可记录**：`ModelCallInput` 能支撑 WORK-030 的 `ai_run_payloads`；本项只需保证**接口可达**，落库实现归 WORK-030。

## 质量要求

- **对照套件是硬门**：同一批固定输入在旧循环与新 Agent 下逐条比对，至少覆盖：单轮无工具、单轮带工具、多轮工具、工具失败、上游 401、上游超时、达到最大轮次、带草稿的对话。**输出正文与总用量必须一致或差异可解释**。
- **行为不变的负面清单**：以下三件事必须逐条验证没有退化——① 工具轮旁白不重复；② 每轮 `ToolResultMessage` 条数正确（否则上游 400）；③ 思考内容不在正文里。
- **计费不许少记**：多轮对话的总扣分与迁移前对得上；用真实调用核对，不靠桩。
- **流式不退化**：首字延迟不比迁移前明显变长（同一问题各跑 3 次取中位）。
- **可回滚**：第二步与第三步分两次提交，出问题能只回第二步。

## 约束与依赖

- **MCP SDK 版本必须与 AgentScope 传递版本一致**：`backend/pom.xml` 已有注释写明这条，违反的表现是运行期 `NoSuchMethodError` 而非编译错误。
- **`hook` 包不许用**：已标 `removal`。
- **深层边界**：`Msg` / `ChatResponse` / `ChatUsage` / `AgentEvent` / `ReActAgent.Builder` 仍不得渗透进业务模块（`.agent/rules/always.md` 的薄端口纪律）。
- **依赖 WORK-030 的信息口径**：payload 表由 WORK-030 建，本项只保证不挡路。
- **不动 `paideia-platform` 的工具契约**：`PaideiaTool` 不改签名。

## 关联工作项

- **WORK-005 / WORK-006**：对话与工具链的来源。
- **WORK-009**：逐轮计费的修复就在这里，迁移不许倒退。
- **WORK-019**：工具目录与 `find_tools`，本项要接住它的语义。
- **WORK-022**：遥测与步骤表，本项改变写入位置。
- **WORK-030**：payload 表由它建；本项只保证 `ModelCallInput` 可达。

## 验收标准

**2026-10-21 用户追加批准第二、三步**（原话「然后继续 第二步第三步开始吧，你就一直开始就行了」），因此下面 13 条全部生效。

- **AC-001 升版本不炸**：升 2.0.4 + MCP pin 0.17.2 后，编译通过，且 `McpStartupSmokeTest` 与全部对话/工具集成测试通过。
- **AC-002 缓存 token 有值**：`TokenUsage` 能承载上游的缓存命中量；一次命中缓存的真实调用后，`ai_runs.cache_read_tokens > 0`。
- **AC-003 契约只增不改**：`TokenUsage` 新增字段后，`api-shape` 快照的 diff **只有新增**，无删除、无类型变更。
- **AC-004 既有行为不退化**：后端内环与全量的执行条数不低于基线，且 Skipped 与基线一致（逐条核对是什么被跳过，不是只看总数）。
- **AC-005 反向验证**：把"缓存量"的映射改坏后，新增的用例必须变红——证明它抓得住这类回归。
- **AC-006 可回滚**：版本升级与缓存修复是两笔独立提交，可各自单独回滚。

## 第二步与第三步的验收（已批准）

- **AC-007 工具循环仍工作**：真实上游下一次对话里模型连续调用两个工具，两个都执行、结果都回到模型。
- **AC-008 旁白不重复**：带工具的那轮对话，用户看到的正文里工具轮旁白只出现一次。
- **AC-009 无 400**：多工具同轮调用不触发 `insufficient tool messages`。
- **AC-010 思考不混正文**：开思考的模型，正文里不含思考内容。
- **AC-011 草稿仍中途到达**：说「帮我记一条笔记」时，草稿在回答结束之前出现在界面上。
- **AC-012 逐轮计费**：一次两轮工具调用的对话，用量是各轮之和，不是只有最后一轮。
- **AC-013 `find_tools` 语义保留**：发现之前看不到该工具；发现之后**同一请求的下一轮**即可调用。
- **AC-014 观测采集点定型**：`Middleware.onModelCall` 提供 messages/tools/options/model；`AgentTool.callAsync` 提供工具入参与返回（WORK-030 阶段 B 的落点）。
- **AC-015 边界未破**：SDK 类型没有渗出 `ai` 模块，模块边界校验仍绿。
- **AC-016 可回滚**：第二、三步与第一步分三笔提交，可各自回滚。

## 假设

- 2.0.4 的 `FinalAnswerFilterMiddleware` 行为与我们的手写补丁一致。**这条要用 AC-003 验证**，不能只看名字相信。
- 记忆不需要交给框架：`AgentStateStore` 的 KV 形状表达不了"结构化 + 全文两路 + 按轮召回"，本项按"框架不管记忆"实现。
- `ToolResultEndEvent` 不含正文不是问题——工具返回在我们的 `AgentTool` 实现里本来就有。

## 待确认问题

1. **第一步（升版本）是否单独做、先上线**。我建议单独做并先跑一周，因为它零架构风险且能立刻修掉 `cache_read_tokens` 恒为 0 的缺陷。**若你同意，可以先只批准第一步。**
2. **`reasoning` token 存哪里**。`ai_runs` / `ai_run_spans` 没有这一列。两个选项：加列（改表）或进 `attrs`（不改表）。我倾向先进 `attrs`——加列的收益还不足以动两张写入最频繁的表。
3. **迁移期间要不要保留旧循环作为兜底开关**。一个配置项在两套实现间切换，能在出问题时秒回滚，代价是多维护一份代码直到删除。我倾向**不留**（有 Git 回滚），但如果你担心线上，可以留。
