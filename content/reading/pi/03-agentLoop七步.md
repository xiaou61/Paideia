---
title: 03 · agentLoop：一次请求走完七步
category: pi
order: 3
minutes: 20
level: intermediate
status: published
---

> **版本**：pi `v1.5.0`（`f9a2636`，2026-11-09 核对）。路径与行号以该 tag 为准。

## 现在的坑

你已经知道一次提问会发 `agent_start → turn_start → … → turn_end`，但**"一个 turn 里到底发生几件事"**还是糊的。

打开 `packages/agent/src/agent-loop.ts`，`runLoop` 从 `:172` 到大约 `:330`，一百多行，中间夹着 `prepareNextTurn`、`prepareRequest`、`finishTurn`、两个队列的轮询。你能认出每个名字，却说不清谁是必须的、谁只是钩子。

这一讲把它压成**七步**，再加**三个提前退出的口子**。记住这两组，`runLoop` 就不再是迷宫。

## 一页地图

一个 turn 的七步，按代码顺序：

| # | 做什么 | 位置 |
| --- | --- | --- |
| 1 | 让宿主有机会换掉整个上下文（压缩、注入） | `config.prepareRequest`，`:228` |
| 2 | 把 `AgentMessage[]` 变成 LLM 能吃的消息，发请求 | `streamAssistantResponse`，`:251`（内部 `:404` 转换、`:412` 发请求） |
| 3 | 如果模型这次是报错或被打断，收尾退出 | `:253-264` |
| 4 | 有工具调用就执行；**输出被 token 上限截断时，一个都不执行** | `:276-290` |
| 5 | 让宿主有机会决定"还要不要继续" | `config.finishTurn`，`:295` |
| 6 | 发 `turn_end`，带上助手消息与工具结果 | `:296` |
| 7 | 轮询 steering（`:304`）；没有后续就尝试 follow-up（`:311`） | `:304`、`:311` |

第 1 步和第 5 步是**钩子**，不实现也能跑；第 2、4、6 步是骨架，缺一个循环就不成立。第 3 步和第 7 步容易被忽略，但它们是"为什么有时只跑一轮就结束"的答案。

三个提前退出的口子：

- **模型报错 / 被中断**（`:253`）：`finishTurn` 照跑、`turn_end` 照发，然后直接 `agent_end` 返回。所以"出错"不是抛异常，是**一次正常的收尾**。
- **输出被截断**（`:276`）：`stopReason === "length"` 时，工具参数可能是半截的，pi 会给每个工具发一次 start 再全部报错（`failToolCallsFromTruncatedMessage`，`:488`），**故意不执行**。
- **本批工具全部要求终止**（`:284`）：`terminate` 为真时不再继续下一轮（`:281`）；混合批次（有的要停有的不要）仍然继续。

## 一个 turn 里，队列是怎么排上的

第 7 步之后有个不显眼但很关键的分支：

```ts
explicitContinuation = decision?.action === "continue"
pendingMessages = (await config.getSteeringMessages?.()) || []
if (hasMoreToolCalls || pendingMessages.length > 0) {
  explicitContinuation = false
}
```

意思是：**只要还有工具结果没消化、或者有人插话，就不需要"额外再问一次"**。只有当这三者都没有、而 `finishTurn` 又明确说"继续"时，循环才会造一个**只有上下文、没有新用户消息**的 turn（`:319-324`）——这是"让模型再想一次"的机制，也是很多 agent 框架里没有的东西。

`agent_end` 在这里会发两次吗？不会：正常路径在 `:329` 统一发一次，提前退出路径各自发一次然后 `return`。

## 和真实现的差异

- **钩子的顺序不能换。** `prepareRequest` 在请求前、`finishTurn` 在工具结果之后 `turn_end` 之前。前者能重写整个消息数组（pi 用它做会话恢复后的上下文重建），后者只能决定"继续 / 结束"，改不了已经定稿的消息。
- **`prepareNextTurn` 不是 `prepareRequest`。** 前者在两轮之间跑（适合做压缩，`agent.ts:495-501` 还提供了带 context 的版本），后者在每次请求前跑。压缩放错位置，就会压缩到"这次请求已经发出去的消息"。
- **工具执行是"并发执行、串行叙述"。** 第 4 步的并行模式下，执行是并发的，但工具结果消息**按助手消息里的原始顺序**落盘（`executeToolCallsParallel` 的 `orderedFinalizedCalls`，`:656-660`）。所以事件顺序和会话顺序不是一回事——第 05 讲细讲。
- **`agent_end` 不是终点。** follow-up 队列里还有消息时，循环**不会**另起一个 run，而是在同一个 run 里再走一个 turn（`agent-loop.ts:311-317`）；真正另起 run 的是上层会话（重试、压缩），那才会再发一次 `agent_start`。所以要判断"真的安静了"，看 `agent_settled`。

## 小结

`runLoop` = 七步（准备上下文 → 请求 → 错误出口 → 工具 → 继续判定 → `turn_end` → 队列）+ 三个提前退出（报错、截断、全部 terminate）。

如果重来一次，我会先画这张七步表，再读源码：读的时候每一行都能归到某一步，遇到不认识的钩子就知道它属于"可选"。

下一讲拆第 2 步里最常被跳过的一环：`AgentMessage` 为什么要和 LLM 消息分成两套，以及 `convertToLlm` 到底在挡什么。
