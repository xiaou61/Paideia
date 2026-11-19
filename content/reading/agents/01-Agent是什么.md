---
title: Agent 到底是什么：一条界线，和它的代价
category: agents
order: 1
minutes: 18
level: beginner
status: published
---

## 我漏了一条消息，接口直接 400

第一次手写 Agent 循环时，我以为逻辑是这样的：

```text
调模型 → 它说要调工具 → 我执行 → 把工具结果塞回去 → 再调模型
```

跑起来第二圈就报了：

```text
openai.BadRequestError: Error code: 400
{'error': {'message': "Invalid parameter: messages with role 'tool' must be a
 response to a preceding message with 'tool_calls'.",
 'type': 'invalid_request_error', 'param': 'messages'}}
```

我漏掉的是模型自己那条回复。

模型的回复不是只有 `content`，它还带着 `tool_calls`。这一条 assistant 消息必须被**原样 append 进 `messages`**，然后才是我执行的 tool 消息。两者靠 `tool_call_id` 对上：

```text
messages = [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": null, "tool_calls": [{"id": "call_1", ...}]},   ← 我漏了这个
    {"role": "tool", "tool_call_id": "call_1", "content": "...结果..."},
]
```

这个错误本身很浅，但它把 Agent 的本质暴露得很清楚：**模型是无状态的、没有记忆的**。所谓"它记得自己调过工具"，全部来自你每轮把完整对话重新发过去——包括它自己说过什么。

这一篇就讲清这条循环，以及它带来的四个必须提前付的代价。

## 判据只有一条：控制流归谁

**Agent 是"由模型自己决定下一步做什么"的程序。**

不是"用了大模型"，不是"能多轮对话"，也不是"有工具"。判据是：**调哪个工具、要不要继续、什么时候停，是不是模型在运行时决定的**。

同一件事，两种做法的区别只在这一点：

```text
固定流程：
  1. 拉 Issue 正文
  2. 塞进提示词问模型「给修复方案」
  3. 存库
  → 调几次、什么时候调，都是你写死的

Agent：
  给模型 Issue 正文 + 工具（读仓库、搜代码、看历史提交）
  → 它先搜相关代码 → 读两个文件 → 发现关键函数在另一个模块 → 再读
  → 判断信息够了 → 输出方案
  → 调用次数不确定，顺序也不确定
```

差别不在"用不用模型"，在**控制流在你的 `if/else` 里，还是在模型每一轮输出的那个"下一步"里**。

## 那个循环长什么样

```code-flow
{
  "id": "ag-01-agent-loop",
  "title": "Agent 的核心就是一个循环",
  "nodes": [
    { "id": "model", "label": "调模型", "sub": "带上完整 messages", "layer": 0, "order": 0 },
    { "id": "check", "label": "有 tool_calls 吗", "sub": "模型的回复里看", "layer": 1, "order": 0 },
    { "id": "appendreply", "label": "先 append 模型回复", "sub": "连着 tool_calls 一起", "layer": 2, "order": 0 },
    { "id": "exec", "label": "执行每个工具", "sub": "你的代码真的执行", "layer": 3, "order": 0 },
    { "id": "append", "label": "append tool 结果", "sub": "tool_call_id 对应", "layer": 4, "order": 0 }
  ],
  "edges": [
    { "from": "model", "to": "check", "label": "看 finish_reason" },
    { "from": "check", "to": "appendreply", "label": "有" },
    { "from": "appendreply", "to": "exec", "label": "逐个调" },
    { "from": "exec", "to": "append", "label": "拿到 result" },
    { "from": "append", "to": "model", "label": "下一轮" }
  ],
  "steps": [
    {
      "title": "每轮先调模型",
      "desc": "历史越滚越长",
      "file": "agent.py",
      "code": "for step in range(MAX_STEPS):\n    reply = call_model(messages, tools=TOOLS)",
      "focus": [1, 2],
      "nodes": ["model"],
      "edges": [],
      "log": [{ "k": "轮次", "v": "受 MAX_STEPS 约束" }],
      "explain": "硬上限必须有，否则就是一个不封顶的账单。"
    },
    {
      "title": "看模型想不想动手",
      "desc": "它只输出结构化意图",
      "file": "agent.py",
      "code": "if not reply.tool_calls:\n    return reply.content   # 收工",
      "focus": [1, 2],
      "nodes": ["model", "check"],
      "edges": ["model>check"],
      "log": [{ "k": "finish_reason", "v": "stop / tool_calls" }],
      "explain": "没有工具调用就是正常结束，这是唯一的收工条件。"
    },
    {
      "title": "先 append 模型这一条回复",
      "desc": "漏了它就是 400",
      "file": "agent.py",
      "code": "messages.append(reply)   # content + tool_calls 一起",
      "focus": [1],
      "nodes": ["check", "appendreply"],
      "edges": ["check>appendreply"],
      "log": [{ "k": "顺序", "v": "assistant 必须先于 tool" }],
      "explain": "tool 消息必须对应前面某条 assistant 的 tool_calls。"
    },
    {
      "title": "执行工具，再 append 结果",
      "desc": "执行权始终在你手里",
      "file": "agent.py",
      "code": "for call in reply.tool_calls:\n    out = execute(call)\n    messages.append({\"role\": \"tool\",\n                     \"tool_call_id\": call.id,\n                     \"content\": out})",
      "focus": [1, 2],
      "nodes": ["appendreply", "exec", "append"],
      "edges": ["appendreply>exec", "exec>append"],
      "log": [{ "k": "执行方", "v": "宿主程序" }],
      "explain": "模型从头到尾没碰系统，只是「说要调什么」。"
    }
  ]
}
```

## 一个更准的追问

网上流传最广的判据是三个问题，但我见过的版本方向是反的——它把答案说成"三个都是是"，实际前两条的答案都是**否**才说明是 Agent。

用这三条更准：

1. **同一份输入跑两次，工具调用序列会一样吗？** ——**不一样**，说明控制流在模型手里。
2. **你能在代码里预先画出完整流程图吗？** ——**画不出来**，说明它确实是 Agent。
3. **加一种新情况，你要改代码还是改提示词？** ——**改提示词就够**，说明决策权在模型那边。

三条都在问同一件事：**这份逻辑能不能提前写死。**

第三条最实用，因为它能抓出"伪 Agent"：代码里写着 `while` 循环配工具，但每轮调用的工具和顺序完全固定，新增情况全部靠改代码。这种程序付出了不确定性，没换到任何收益——它其实是个固定流程，只是穿了件 Agent 的衣服。

## 为什么这条界线值钱

因为有些活的步骤**在事前说不出来**。

修一个「订单列表加载慢」的 bug 就是典型：

```text
固定流程能写的（在设计时就定死）：
  拉慢日志 → 提取慢 SQL → 让模型输出建议

Agent 能做的：
  拉慢日志 → 看到 selectByUser 慢
           → 去代码里搜它的定义
           → 发现它动态拼了三个条件
           → 去查表结构与执行计划
           → 判断是哪一段导致的
           → 给建议 + 迁移脚本
```

第 2 到第 5 步，设计时写不出来——**要看第 1 步的结果才知道下一步查什么**。

这里有个必须诚实的地方：Agent 走到第 4 步时，它读代码**不能**断定"没走索引"。索引用不用是数据库在运行时决定的，只能靠 `EXPLAIN` 看。所以真实工具清单里必须有"执行 EXPLAIN"这一项，否则 Agent 会给出一个听起来很对、但属于推断的结论。这也正是下面要讲的"失败是静默的"。

反过来：**一件事的步骤你完全知道，就别用 Agent**。用 Agent 做「先 A 再 B 再 C」，你付出了不确定性和更高的成本，换来零收益。

## 代价一：成本随轮次累积

这是最容易被低估的一项，因为大多数人只算了「调用次数」，没算「每次要发多少」。

同一份任务，固定流程调一次，Agent 可能调八次。**但贵的不只是次数，是每次都要重发全部历史**：

```text
系统提示词 + 工具描述           3000
每轮新增（模型那条回复 + 工具结果） 约 2000

第 1 轮输入   3000 + 2000 =  5000
第 2 轮输入   3000 + 4000 =  7000
第 3 轮输入   3000 + 6000 =  9000
...
第 8 轮输入   3000 + 16000 = 19000
────────────────────────────────
合计输入              ≈ 96000 token
```

而单次调用只要 5000。

**累计输入是 19 倍左右**，这就是为什么"多轮 Agent 很贵"——不是因为它调用了八次，是因为第 8 次把前 7 次的内容又付了一遍钱。

而且**同一个任务跑两次，花的钱不一样**（轮次不同），这也是为什么做 Agent 必须有硬上限：最多几轮、最多几次工具调用、最多多少 token。没有上限的 Agent 等于一张不封顶的账单。

## 代价二：结果不可复现

温度调成 0 让模型更倾向确定，但不等于幂等——同一个输入，这次可能搜 3 个文件，下次搜 2 个。

这直接决定了测试怎么写：

```text
不能这样断言：
  output == "给 (user_id, created_at) 加联合索引"

可以这样断言：
  - 输出里提到了 user_id 与索引
  - 必须调用过 search_code
  - 工具调用次数 ≤ 10
```

**只能断言性质，不能断言字面。** 这是 Agent 测试跟普通单元测试最大的差别，也是很多人第一次写 Agent 的测试时最不适应的地方。

## 代价三：失败是静默的

固定流程挂了会抛异常。Agent 挂了往往**正常返回一段看起来很对的话**：

```text
用户问：这个方法为什么慢
Agent 搜代码 → 命中同名方法，但那是另一个模块的
Agent 读那个方法 → 分析得头头是道
结论：完全正确，只是分析的是另一个方法
```

输出格式完美、逻辑通顺、结论错误。**错误伪装成成功**，是 Agent 最危险的地方。

所以可观测性要落在"依据"上，而不是"结论"上：让它把用了哪几个文件、哪几条检索结果一起输出，然后抽查依据本身对不对。人看结论看不出来，看依据一眼就发现不对。

## 代价四：动作会真的发生

如果工具里有"写文件""发消息""提交代码"，模型的一次误判就是一次真实副作用。

一个容易犯的错，是觉得"只读工具随便给"。**只读不等于安全**：

- 只读工具也能读到密钥文件、`.env`、生产库里的用户数据；
- 只读也能被提示注入利用——网页/Issue 里藏一句指令，让它去读别的目录再把内容发出去；
- 读到的内容会进入上下文，也就进入了日志与下一次请求。

所以规则是**最小权限**：每个工具只给完成当前任务真正需要的那部分范围（限定目录、限定表、只读视图），而不是"反正是只读"。

写入类工具的护栏是"让人确认"，但要说清它做到什么：**它降低误操作的概率，不是消除风险**。人会点错、会确认疲劳，一模一样的弹窗点十次之后第十一次就不看了。

## 判断表

| 你的需求 | 用什么 | 为什么 |
| --- | --- | --- |
| 一段文本进、一段文本出（翻译、摘要、改写） | 直接调模型 | 步骤数为零 |
| 步骤固定，中间用模型处理 | 固定流程 + 模型 | 控制流可控，好测、好算钱 |
| 步骤不确定，但每一步都能验证 | Agent | 它的主场 |
| 不可逆的写操作 | Agent 建议、人来执行 | 概率上把风险压低，不是清零 |

## 最小 Agent 的完整代码

抛开所有框架，核心就这么多：

```python
messages = [
    {"role": "system", "content": SYSTEM_PROMPT},
    {"role": "user", "content": task},
]

for step in range(MAX_STEPS):
    reply = call_model(messages, tools=TOOLS)

    if not reply.tool_calls:            # 没有工具调用 = 结束
        return reply.content

    messages.append(reply)              # ① 先塞模型这条回复（漏了就是 400）

    for call in reply.tool_calls:
        result = execute(call)          # ② 你的代码真的执行
        messages.append({
            "role": "tool",
            "tool_call_id": call.id,    # ③ 靠这个 id 对上
            "content": result,
        })

# ④ 走到这里说明用完了 MAX_STEPS
return force_final_answer(messages)     # 不给工具，让它基于现有信息收尾
```

四处编号是这一篇的全部技术内容。其中 ① 和 ③ 是最容易漏的，而它们的报错都很直接（400），所以算不上难；真正难的是 ④。

**`MAX_STEPS` 用完怎么办，必须提前定。** 两个选择：

- 直接返回"信息不完整"的当前结果——快，但用户体验差；
- 再调一次模型让它收尾（这次不给工具）——体验好，但多一次调用。

我第一版选了第二种，然后发现这条路径**只在异常时才会走到，所以从来没被测过**，上线后第一次撞到就是一段奇怪的话。现在我给这种情况专门造一个必超轮次的任务，把它纳进回归。

## 检查一下

```quiz
{"id":"ag-01-quiz","title":"Agent 界线三问","questions":[
 {"question":"判断一个程序是不是 Agent，最关键的依据是？","options":["它用了大模型","下一步做什么由模型在运行时决定","它支持多轮对话","它调用了外部 API"],"answer":1,"explain":"用了模型、能多轮都不够，关键是控制流归谁。固定流程里模型只是其中一环。"},
 {"question":"append 工具结果之前，必须先 append 什么？","options":["用户的新消息","模型那条带 tool_calls 的回复","系统提示词"],"answer":1,"explain":"tool 消息必须对应前面某条 assistant 消息里的 tool_calls，靠 tool_call_id 关联，否则接口直接 400。"},
 {"question":"下面哪件事最不该让 Agent 自主完成？","options":["检索相关文档","读几个代码文件","直接删除生产库里的记录","总结检索结果"],"answer":2,"explain":"不可逆的写操作要人确认。Agent 可以建议删哪几条，执行权留给人。"}
]}
```

## 这一篇之后

Agent 的判据只有一条：**下一步由谁决定**。

它擅长"步骤不确定、但每步可验证"的活，代价是成本随轮次累积、结果不可复现、失败静默、动作会真的发生。核心是二十来行循环，其中真正容易写错的地方只有"先 append 模型回复"这一条。

下一篇看系统提示词：Agent 跑偏时，改提示词通常比换模型有效得多——但它能修的东西，比大多数人以为的少。

**延伸阅读**：[Datawhale · hello-agents](https://github.com/datawhalechina/hello-agents) 第一章从智能体定义讲到符号主义到 LLM 驱动的演进，比本篇更完整；本线只取了「接功能前必须想清楚」的那部分。
