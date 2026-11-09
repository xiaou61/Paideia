---
title: 07 · pi-ai：五十一家的适配矩阵
category: pi
order: 7
minutes: 20
level: intermediate
status: published
---

> **版本**：pi `v1.5.0`（`f9a2636`，2026-11-09 核对）。路径与行号以该 tag 为准。

## 现在的坑

"统一多 provider"听起来像一段 `switch`：OpenAI 走这个 URL，Anthropic 走那个。看到 `packages/ai/src/providers/` 下 **51 个 provider**、`src/api/` 下十几种请求形态，你才会意识到这不是 if-else 能解决的事。

真正麻烦的从来不是 URL，而是**同一件事各家做法不同**：工具调用怎么流式拼、思考预算怎么表达、缓存命中怎么计费、错误怎么重试。这一讲看 pi-ai 怎么把这些差异关进一个包里。

## 一页地图

pi-ai 的设计只有一句话：**把"是谁"和"怎么说"分开。**

- **provider**（`src/providers/*.ts`）：这家服务是谁——endpoint、鉴权、模型清单。比如 `anthropic.ts`、`deepseek.ts`、`github-copilot.ts`。
- **api**（`src/api/*.ts`）：这类接口怎么说——请求体、流式事件、工具调用格式。比如 `anthropic-messages.ts`、`openai-responses.ts`、`google-generative-ai.ts`、`bedrock-converse-stream.ts`。

一个 provider 指向一种 api 实现；同一个 api 可以被多家复用。所以加一家新厂商，通常是"写一个 provider 文件 + 复用已有 api"，而不是从头写一遍流式解析。

还有三样东西让这个矩阵能维护：

- **模型清单是数据，不是代码。** `models.generated.ts` 是构建时生成的；运行时会从 `https://pi.dev` 拉一份更新的目录做 overlay（`packages/coding-agent/src/core/remote-catalog-provider.ts:14`、`:103`），只在 `Last-Modified` 更新时才覆盖。所以新模型上线不必等发版。
- **按需加载。** `*.lazy.ts` 让不用的 api 实现不进冷启动路径——51 个 provider 全量加载会明显拖慢启动。
- **鉴权单独成层。** OAuth（`oauth.ts`、`bun-oauth.ts`）和各家专有的（`github-copilot`、`cloudflare-auth`）都不混进请求实现。

## 三个真难题

**一、流式工具调用怎么拼。** OpenAI 的 tool call 是增量 JSON 字符串片段，Anthropic 是独立的 content block，Google 又是另一种结构。pi 的做法是每个 api 自己把流拼成统一事件（`message_update` 里的 `assistantMessageEvent`），上层只看到一个形状。代价是：**每个 api 实现都得自己写一遍拼接**，pi 没有强行抽象成"通用增量协议"。

**二、思考预算怎么表达。** 上层只有 `thinkingLevel`（off/minimal/low/medium/high/xhigh/max），但各家字段不同：有的传 token 数，有的传枚举，有的根本不支持。映射在 provider/api 层做，`thinkingBudgets` 只是给"按 token 计"的那类用的换算表。

**三、用量与钱怎么算。** 这是最容易糊弄过去、也最容易坑用户的一块。pi 的 usage 结构把缓存拆得很细（`packages/ai/src/types.ts:462-484`）：

```ts
cacheRead: number
cacheWrite: number
/** Subset of `cacheWrite` written with 1h retention. Only Anthropic reports this split. */
cacheWrite1h?: number
```

成本字段同时保留两种口径：**provider 报的账单**（有就用）和**按目录价估算的**（没有就用）。差额不是小数目——缓存命中价通常是输入价的十分之一，写错一个字段，账单就会差一个数量级。

顺带一个容易被忽略的设计：**重试不在 agent 层**。`pi-agent-core` 只提供一个"重试延迟上限"（`agent.ts:231` 的 `maxRetryDelayMs`），真正的重试策略在每家 api 实现里（`api/anthropic-messages.ts:661`），底层是 `utils/provider-retry.ts`——它明确写着"对齐 OpenAI/Anthropic SDK 的重试策略"，还会读 `x-should-retry` 响应头决定要不要重试。为什么要这样？因为**哪些错误可重试是厂商语义**，agent 不该猜。

## 和真实现的差异

- **pi-ai 不负责"选模型"。** 它给你一个模型目录和一个统一的 `stream()`，选哪个、怎么降级、怎么按任务路由，都是上层的事（`pi-coding-agent` 里才有 model selector 和 refresh）。
- **工具参数的 schema 不在这里校验。** 那是 `pi-schema` 的活（它把 JSON Schema 编译成闭包，不用 `eval`），pi-ai 只负责把 schema 塞进请求。
- **没有统一的"能力探测"。** 某个模型支不支持工具调用、支不支持图片，靠目录里的字段描述，而不是运行时探测。
- **版本提醒**：`providers/` 和 `api/` 是这条线里变动最快的目录。`v1.5.0` 之后新增或改名是常态，引用具体文件名前先对 tag。

## 小结

pi-ai 的结构是 **provider × api 的矩阵**：provider 说"是谁"，api 说"怎么说"，模型清单做成可远端覆盖的数据，鉴权与重试各自成层。

如果重来一次，我会先写一个最小 provider（复用 `openai-completions`），跑通一次流式请求，再去看 `anthropic-messages` 的实现——两相对比，"哪些差异必须留在 api 层"就一目了然了。

下一讲回到能力边界：pi 没有内置权限系统，那"确认一下再执行"该怎么做？答案是写 30 行扩展。
