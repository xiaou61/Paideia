---
title: Agent 的可观测性与回放：出了问题怎么查
category: agents
order: 14
minutes: 22
level: advanced
status: published
---

## 33.7 秒、$0.104、最后一句是「已转人工」

```text
33.7 秒
$0.104
最后一句：「查不到订单，请确认订单号，已转人工」
```

这算成功吗？

只看这三个数，答不了。33.7 秒和 $0.104 是成本与延迟；「已转人工」是业务结果。技术状态可能全是 `ok`，业务目标却没达成。要回答这个问题，得把一次任务拆成 trace 和 span，并且把「技术状态」和「业务 outcome」分开记。

可观测性不是多打几行日志；它是把一次任务拆成一棵有根、有调用 ID、有业务 outcome 的树。这一篇只做四件事：把树搭对、把 token 算对、把回放分清、把采样和告警落到能行动的地方。

- **trace**：一次用户任务的全过程，从收到请求到最终答复。一个 `trace_id` 贯穿所有 span。
- **span**：trace 里的一个操作。模型调用、工具调用、检索、校验、重试，各是一个 span；有 `span_id`、`parent_span_id`、开始/结束时间、状态和属性。
- **根 span**：`parent_span_id` 为空的 span，名字可以叫 `invoke_agent` 或 `task`（OTel GenAI 约定里 `gen_ai.operation.name=invoke_agent`）。它收口总时长、总成本、`status` 和 `outcome`。

没有根 span 的「树」是森林：子 span 各自有耗时，但没有一个总时长可以对齐，`outcome` 也没地方挂。

这条 trace 的树：

```text
trace 8f3a…  33.7s  $0.104（旧估算）  status=ok  outcome=handoff
└─ span invoke_agent              root   33.7s  status=ok  outcome=handoff
   ├─ span chat.plan             model   1.8s  in=200  cache_write=3,000  out=300
   ├─ span execute_tool.search_logs tool 18.4s  tool_call_id=call_a1  args_hash=9f2c
   │  result 48,000 chars / 18,182 tok
   ├─ span chat.summarize        model   6.1s  in=18,732  cache_read=3,000  out=600
   ├─ span execute_tool.get_order tool  0.3s  tool_call_id=call_b7  args_hash=7d10  403
   ├─ span execute_tool.get_order tool  0.4s  tool_call_id=call_b8  args_hash=7d10  403
   ├─ span chat.final            model   3.2s  in=1,000  cache_read=3,000  out=400
   ├─ span guard.validate        guard   0.01s status=error  missing refund_eligible
   └─ span chat.final_retry      model   3.5s  in=1,050  cache_read=3,000  out=420
```

模型 span 的耗时只算模型调用本身；工具 span 挂在根下，不挂在模型 span 下。父子关系表示执行层级，调用关系用 `tool_call_id` 表示——否则模型 span 的 `duration_ms` 会把工具时间算进去，延迟拆解就失真了。

`in` 是计价输入，不含 cache write/read。plan 的 3,000 是 system+tools 第一次写缓存，200 是用户问题；summarize 的 18,732 是「18,182 日志 + 200 用户问题 + 300 plan 输出 + 50 工具调用消息」，另外 3,000 从缓存读。这个口径后面还会用到。

根 span 的 `status=ok` 只说明编排没有崩；`outcome=handoff` 才是业务结果。`get_order` 两次 403 被最后一句翻译成「查不到订单」，这是错误归因：403 是权限不足，不是订单不存在。根 span 没有未处理异常，不等于业务解决；子 span 的 403 和校验失败还在。

`status` 怎么定是团队约定：可以「有未处理异常才 error」，也可以「任一关键子 span error 就 error」。不管哪种，`outcome` 单独记。

## 工具调用靠 tool_call_id 串，不靠 args_hash

OpenAI Chat Completions 里，模型返回的 assistant 消息带 `tool_calls`，每个调用有 `id`；工具结果消息用 `tool_call_id` 回指。少一条 assistant 消息，或者 `tool_call_id` 对不上，接口会 400；第 04 篇排过这个错（[OpenAI Chat Completions API 参考](https://platform.openai.com/docs/api-reference/chat/create)，2026-11-19 查）。

```text
assistant: tool_calls=[{id: call_b7, name: get_order, arguments: {"order_id": "12345"}}]
tool:      tool_call_id=call_b7  content=403 permission denied
```

trace 里用同一个值：`gen_ai.tool.call.id=call_b7`。OpenTelemetry 的 GenAI 语义约定里有这个属性（[OTel GenAI 属性](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/)），但它还在实验/迁移阶段，字段名以你用的版本为准；没有 OTel，也要在自己的 span 里存一个稳定的调用 ID。

`args_hash` 不能当调用身份。两次并行调用参数完全相同，哈希就相同；工具结果如果乱序返回，你分不清哪个结果属于哪次调用。手机号、订单号这类低熵参数还能被枚举，哈希不等于脱敏。

`args_hash` 只做三件事：去重、缓存、发现重复调用。`tool_call_id` 才做关联。重试也要新的 `tool_call_id`，不要复用旧 ID，否则两次结果会串。

工具 span 至少记这些：

| 字段 | 记什么 | 注意 |
| --- | --- | --- |
| `tool_call_id` | provider 返回的调用 ID | 每次调用唯一；重试也新 ID |
| 工具名与版本 | `lookup_order@v3` | 工具行为变了要能区分 |
| 参数 | 结构、类型、关键 id；敏感值脱敏 | 不记完整密钥、密码、身份证号 |
| 参数哈希 | `args_hash` | 只用于去重/缓存；不当身份；低熵值不只哈希 |
| 结果摘要 | 行数、字节数、状态码、前几条脱敏摘要 | 不把完整结果塞进 trace |
| 结果指针 | 对象存储地址或内部 ID | 需要时去受控存储取 |
| 耗时 | 工具执行时间；网关排队单独测 | 区分工具慢和模型慢 |
| 重试 | 第几次、为什么重试 | 403 重试不会变好 |
| 错误 | `error_type`、上游错误码、是否可重试 | 不只写 `FAILED` |
| 权限决策 | 允许/拒绝、依据 | 越权排查的关键 |
| 幂等键 | 写操作的 `idempotency_key` | 回放和重试都靠它防重复副作用 |

错误分类和重试策略在下一节，一张表说完，不在工具字段里重复。

## token 与成本：48,000 字符不是 12k token

把 48,000 字符按 4 字符/token 算成 12k token，这个换算不成立。我用 `tiktoken 0.13.0` 的 `o200k_base`，在 Python 3.11.5 / Windows 11 上量了四段文本：

```text
48,000 chars 英文 JSONL 日志   -> 17,204 tok  (2.79 chars/tok)
48,000 chars 英文日志（另一组样本） -> 18,182 tok  (2.64 chars/tok)
44,255 chars JSON 结果         -> 16,764 tok  (2.64 chars/tok)
 6,060 chars 中文段落           ->  3,960 tok  (1.53 chars/tok)
```

同样 48,000 字符的英文日志，落在 17.2k–18.2k token，不是 12k；低估 43%–52%。中文更贵，1 个字符约 0.65 token，不是 0.25。比值随内容变：英文日志 2.64–2.79，JSON 2.2–2.64，中文 1.53–1.6；这些区间来自两组不同的文本，所以只能对实际负载量，不能套一个固定除数。下面按 18,182 算，取英文日志里较大的那个。

量法：

```python
import tiktoken
enc = tiktoken.get_encoding("o200k_base")
text = open("search_logs.jsonl", encoding="utf-8").read()
print(len(text), len(enc.encode(text)), round(len(text) / len(enc.encode(text)), 3))
```

```text
48000 17204 2.79
```

这是 tokenizer 估算，不等于供应商的计费 token；换 encoding、换模型、换版本，数会变。但「4 字符/token」这个拍法不能用。

价格用公开价，不是我的账单：Anthropic Claude Sonnet 4.5，2026-11-19 查[官方定价页](https://platform.claude.com/docs/en/about-claude/pricing)，输入 $3/百万、输出 $15/百万、5 分钟 cache write $3.75/百万、cache read $0.30/百万。换模型就换单价，别把这里的数当通用价格。

成本表：

| span | 类型 | 耗时 | 计价输入 | cache write | cache read | 输出 | 成本 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| plan | model | 1.80s | 200 | 3,000 | 0 | 300 | $0.01635 | ok |
| search_logs | tool | 18.40s | 0 | 0 | 0 | 0 | $0.00000 | ok，48,000 chars / 18,182 tok |
| summarize | model | 6.10s | 18,732 | 0 | 3,000 | 600 | $0.06610 | ok |
| get_order | tool | 0.30s | 0 | 0 | 0 | 0 | $0.00000 | 403 |
| get_order retry | tool | 0.40s | 0 | 0 | 0 | 0 | $0.00000 | 403 again |
| final | model | 3.20s | 1,000 | 0 | 3,000 | 400 | $0.00990 | ok |
| validate | guard | 0.01s | 0 | 0 | 0 | 0 | $0.00000 | missing `refund_eligible` |
| final retry | model | 3.50s | 1,050 | 0 | 3,000 | 420 | $0.01035 | ok |
| **模型小计** | | **14.60s** | **20,982** | **3,000** | **9,000** | **1,720** | **$0.10270** | |
| 日志检索费 | 外部 | | | | | | **$0.00960** | 假设价 |
| **任务总计** | | **33.71s** | | | | | **$0.11230** | |

如果按 4 字符/token 估，summarize 会记成 16,500 总输入（其中 3,000 缓存）、$0.0486；按 48,000 字符 / 18,182 token 重算，summarize 的总 prompt 是 21,732（18,732 计价输入 + 3,000 cache read），成本 $0.0661，单项涨 36%。这 36% 里，token 重算约 +32%（如果单价仍按 $12/百万输出，summarize 是 $0.0643）；输出单价从 $12 换到 $15 再约 +3%。这条 trace 记的 $0.104 是旧估算；按真实 token 重算是 $0.1123，只涨 8%——因为 plan/final 的输入按实际消息量记，比 4 字符/token 的估算小；只看总账会以为问题不大。

成本公式要按 provider 的 usage 口径写，不要自己乘：

```text
cost = 计价输入 × 输入单价
     + cache_write × 缓存写入单价
     + cache_read × 缓存读取单价
     + 输出 × 输出单价
```

这里至少有三个坑：

- **cache write 是成本。** Anthropic 的 5 分钟 cache write 是 1.25× 输入；OpenAI 部分模型也有 cache write，按 1.25× 未缓存输入计费（[OpenAI pricing](https://platform.openai.com/docs/pricing)）。只写 cache read，成本会少算。
- **字段同名不等于口径相同。** Anthropic 的 `input_tokens` 不含 cache read/creation，总 prompt = `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`；OpenAI 的 `prompt_tokens` 含缓存命中，部分模型还单列 `cache_write_tokens`。存 provider 的原始 usage 子集，按 provider 的规则算，不要跨家套一个减法。
- **reasoning token 不要重复乘。** OpenAI 的 `completion_tokens` 含 reasoning token；有的 provider 把 thinking 单列。存下来可以，别在 output 之外再加一遍。

重试也是成本。`final retry` 多花 3.5 秒和 $0.01035；403 重试多花 0.4 秒。按 span 记账时，失败和重试的 span 也要保留，并在任务汇总里标出「其中重试成本多少」。只记成功那一次，会低估真实账单。

任务成本 = 模型 span 成本 + 工具、检索、存储等外部成本。表里的 $0.0096 日志检索费是假设价，按你的供应商替换；不要把它塞进 token 单价，否则换一个日志平台，整个成本模型就错了。

## 延迟：TTFT 已经包含排队

总时长 33.71 秒，拆开是：

```text
模型 1.8 + 6.1 + 3.2 + 3.5 = 14.6s
工具 18.4 + 0.3 + 0.4      = 19.1s
校验 0.01s
合计 33.71s
```

`search_logs` 18.4 秒，占总时长 54.6%；模型只占 43.3%。这不是「模型太慢」，是一个日志搜索工具在慢。串行执行时这些时间相加；并行工具调用时，同一层取最大值，不是相加。

延迟记录要区分 `ttft_ms` 和 `total_ms`，但**不要再加一个排队时间**：

```text
模型段 = TTFT + 生成时间
TTFT  = 请求发出 → 收到第一个流式 chunk
      （含网络、供应商排队、预填充）
```

TTFT 已经包含排队与预填充；再加 `queue_ms` 就是双计。托管 API 不暴露服务端排队，你只能测客户端 TTFT；如果请求先经过自己的网关，网关排队单独记 `gateway_queue_ms`，并明确它是 `client_ttft` 的一部分，不要重复相加。

几个判据要分开写：

- **单条 trace 没有 p95。** p95 是同一总体、同一时间窗内 N 条任务的集合统计。写「p95 < 15 秒」时，至少要补：总体（哪个入口/任务类型）、时间窗（发版后 24h）、样本量（N≥300）、分母（只算完成，还是把超时/取消/转人工也算进去）。
- **超时和取消单列。** 如果把 30 秒超时截断值混进已完成任务的延迟，p95 会被截断值污染；如果排除，p95 只描述完成的任务。两种都可以，但要写清。
- **样本少于 100 时别只看 p95。** 看最大值、错误样本和瀑布图；样本不够时，p95 本身就不稳。

回归卡至少分两层：

```text
任务级：outcome=resolved 的比例；单任务成本（含重试）
集合级：同一入口、同一时间窗、N≥300 的 p50/p95；超时/取消单列
安全：越权/泄漏 = 0
```

通过率要带基线、样本量、标签来源；没有就标假设，不要写一个没有出处的通过率。这篇没有真实评测数据，所以不写具体通过率。

## 失败分类与 outcome 是两张表

「任务失败」不是一个可行动的结论。至少按下面分，并把错误打在失败发生的那个 span 上：

| 类别 | 典型信号 | 先看哪里 | 重试 |
| --- | --- | --- | --- |
| 模型/输出 | schema 不合法、缺字段、空回答、选错工具 | prompt/模型版本、输出预算 | 带错误重试一次；再改提示词/模型 |
| 工具/参数 | 422、`invalid_args` | 调用参数、schema | 不重试；改参数 |
| 工具/权限 | 401/403、owner/tenant 不匹配 | 凭据、归属校验、策略 | 不重试 |
| 工具/上游 | 429、5xx、timeout | 上游状态、最近发布 | 有限重试，指数退避 |
| 工具/空结果 | 200 但 0 行 | query、过滤条件、索引 | 谨慎；可能确实没有 |
| 编排 | 超轮次、上下文溢出、重复调用 | 停止条件、去重、摘要 | 不重试；改编排 |
| 输入 | 缺信息、歧义、注入、超长 | 产品/交互、护栏 | 追问/拒绝，不重试 |
| 超时/取消 | `deadline exceeded`、client cancel | 预算、并行、队列 | 超时可有限重试；取消不重试 |

403 不是「再试一次就会好」的错误。示例里 `get_order` 403 之后又重试一次，是策略 bug；如果这是写操作，还可能造成重复副作用。重试的调用 ID 和幂等键见上一节。

技术状态和业务结果要分开打标：

| outcome | 含义 | 例子 |
| --- | --- | --- |
| `resolved` | 用户目标达成，有工具结果/用户确认支持 | 查到订单并给出可执行的补发/退款方案 |
| `fallback` | 给出替代答案，但目标未完全达成 | 让用户自助查单、稍后再试 |
| `handoff` | 转人工，Agent 未完成 | 权限不足、需要人工审核 |

`status=ok/error` 是 span 的技术状态；`outcome` 是任务级业务结果，挂在根 span 上。没有最终答复时 `outcome=null`，不要用 `resolved` 兜底；`resolved` 要有工具结果、用户确认或业务系统状态作证据，不能只看模型说「已解决」。

开头的 trace 就是 `status=ok`、`outcome=handoff`。最后一句「查不到订单」把 403 说成「订单不存在」，这是错误归因；即便改成「我无法访问订单，已转人工」，业务结果仍然是 `handoff`，不是 `resolved`。

## 脱敏：结构保留，值脱敏

排查需要细节，合规要求少存。原则是**结构保留，值脱敏**：

```json
{
  "order_id": "ord_8f3a****",
  "email": "u***@example.com",
  "phone": "138****5678",
  "card_last4": "****"
}
```

你仍然能看出参数类型对不对、有没有传错字段，但看不到完整敏感值。

具体做法：

1. **在源头脱敏**：trace SDK 在采集时过滤，不要先写原文再事后清洗。
2. **按字段白名单**：只允许已知安全的字段进入 span 属性，其他字段一律丢弃、加密或令牌化。
3. **原文分离存储**：完整请求/响应存到加密、受控、有审计的对象存储，trace 里只放指针。**摘要进 trace、原文进受控存储、用指针关联**。
4. **用户 ID 带盐哈希**；手机号、订单号这类低熵值不要只哈希，能被枚举；用加密/令牌化，或只存后四位。
5. **保留期限**：trace 保留期按合规要求定；一个起点是 7–30 天，敏感原文更短；支持按用户删除。
6. **访问控制**：不是所有工程师都能看完整 prompt；调试模式要审批、要记录谁看过。

提示词本身也可能含敏感信息。默认不要把完整 prompt 写进 trace；需要时记哈希、长度、模板版本，全文进受控存储或受控调试模式。工具结果可能带注入文本，原文进通用日志索引又是新的风险；摘要和指针进 trace，原文留在受控存储。

## 回放：三个开关，不是三个口号

回放不是再跑一遍碰运气；它是把现场固定到你可以重复实验的程度。把同样的输入再发给模型，只是三种模式里的「真模型重跑」，它不确定，也不能单独证明编排正确。

| 模式 | 固定什么 | 能回答 | 不能回答 | 确定性 |
| --- | --- | --- | --- | --- |
| 固定模型输出回放 | 模型响应（按 response id / 轮次） | 编排、解析、工具路由是否走错 | 换模型/改提示词的效果 | 高 |
| 真模型重跑 | 输入消息、工具结果（可选） | 换模型/提示词/参数的效果分布 | 逐字复现 | 低 |
| 工具打桩 | 工具返回 | 只测编排和模型决策 | 工具真实行为、延迟、权限 | 高（桩固定） |

三者不是互斥菜单。固定模型输出 + 工具打桩 = 只测编排；真模型 + 工具打桩 = 只测模型在固定工具结果下的决策；固定模型输出 + 真实沙箱工具 = 测工具契约。**工具打桩是工具轴，不是模型轴**；沙箱是工具侧的执行环境，不是第四种模型模式。把 trace 画成时间线或瀑布图是查看，不是回放，它不重新执行任何东西。

回放要记录的东西：

```text
trace/span 树：root、parent_span_id、tool_call_id
模型请求：model snapshot、temperature/top_p/seed/max_tokens、messages manifest
工具：name/version、args（脱敏或指针）、tool_call_id、result pointer、status
上下文事件：context.truncated / context.summarized（before/after token、消息区间、原文指针）
环境：时间、feature flag、权限、索引版本
```

「记录完整消息序列」和「默认不记录完整 prompt」并不矛盾，前提是把清单和全文分开：

```text
trace 里：message_id、role、tool_call_id、content_hash、token_count、storage_pointer
受控存储：完整文本
回放时：按权限取全文；没权限只能看清单和摘要
```

如果只存摘要，回放出来的消息不等于模型实际输入；如果只存全文，又会把敏感内容散进日志平台。清单 + 指针是折中：能对齐、能取回、能审计、能删除。

压缩和截断也要记事件（名字可以叫 `context.truncated` / `context.summarized`）。没有它们，你回放的是压缩前的消息，不是模型实际看到的输入。事件至少要有：触发原因、before/after token、消息区间、策略、摘要指针。

排查时找**第一个分歧点**：从根往下，按 `tool_call_id` 对齐，不按消息顺序——并行调用可能乱序返回。对比每一步的输入、模型输出、工具结果、上下文事件；找到第一处与线上不同的地方，只改那一层，不要一上来重写整个提示词。

**回放写操作是红线。** 退款、发消息、删数据这类工具，在回放时必须替换成桩，或者用只读沙箱；在工具网关拦截，不要靠模型自觉。写操作带幂等键，回放后检查副作用为零。

`temperature=0` 不保证逐字一致。模型可能已经升级、provider 可能换了别名背后的快照、工具和索引也会变。需要确定性时用固定模型输出回放；需要比较效果时用真模型重跑，跑多次看分布；模型快照变了要在结果上标注「模型已变」。

## 采样：头部和尾部不是并列选项

| | 头部采样 | 尾部采样 |
| --- | --- | --- |
| **什么时候决定** | 请求开始/第一个 span 时，按 trace_id 哈希或比例 | trace 结束后，或决策窗口到期时，看结果、时长、错误 |
| **成本在哪** | 采样率低，存储便宜；但决策时不知道结果 | 必须缓冲全部 span；同一 trace 的 span 要进同一个 collector；长任务占内存和延迟 |
| **会漏什么** | 稀有错误、慢请求；固定比例可能一条失败都没采到 | 超过缓冲窗口的长任务、晚到的 span、collector 重启丢缓冲 |

「错误和慢请求全采」是尾部策略，不是第三个并列选项；「成功 5–10%」是头部比例，也不是尾部策略的替代品。两者解决的是不同问题：头部决定「要不要开始记」，尾部决定「记完了留不留」。

尾部采样的账要算清：所有 span 先缓冲，等根 span 结束再等一个决策窗口，防止晚到 span；窗口越长，内存越高。同一 trace 的 span 必须路由到同一个 collector（[OTel Tail Sampling Processor](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/processor/tailsamplingprocessor/README.md)），否则决策时只看到半条 trace。collector 重启，缓冲区里的 trace 就丢了；长任务还会把缓冲占满。

「5–10% 够」没有样本量依据。先定你要估多大的变化，再算样本量：

```text
n ≈ 1.96² × p(1-p) / e²
p=5%，e=±2pp  -> n≈457 条 trace；按 5% 采样，要观察约 9,140 条任务
p=5%，e=±1pp  -> n≈1,825 条；按 5% 采样，要观察约 36,500 条
```

这是正态近似；p 很小或样本很小时用 Wilson 区间。5% 不是魔法数字，它只决定你一年看到多少条；先算 n，再定采样率。

## 告警：能点进 trace 才算告警

| 告警 | 触发（示例） | 先看 |
| --- | --- | --- |
| 任务失败率 | 5 分钟技术失败率 >10%，样本≥100 | 失败分类 Pareto，最大类别 |
| 工具错误率 | 某工具 5xx/超时 >5% | 上游状态、最近发布 |
| p95 延迟 | 10 分钟、同入口、N≥100，p95 >阈值 | 瀑布图，最大 span |
| 单任务成本 | 日均比基线高 30% | 按 span/模型/工具拆 |
| 越权/注入 | 出现即告警 | 安全事件流程 |

阈值是示例，不是行业标准。样本少于 100 时别只看 p95，看最大值和错误样本；发版回归用 300 条起，告警窗口用 100 条起。

告警必须带 `trace_id` 和跳转链接；只告诉你「失败率升高」而没有样本，是看板，不是告警。**告警能点进 trace**，才算把排查入口交到了下一个人手里。

缓解动作提前写进 runbook：回滚提示词/模型、禁用某个工具、调高超时、降级到小模型、切人工。**先止血，再定位**，不要在现场一边查一边改。runbook 还要写谁有权执行、多久生效、怎么验证。

## 怎么验证

1. 拿一条真实失败任务，只打开现有日志和看板，回答四个问题：第几轮、调了哪个工具、`tool_call_id` 对应的结果是什么、重试了几次。答不上来，就是不可观测；先补根 span 和 `tool_call_id`，不要先买 APM。
2. 在 trace 存储里搜 `@`、手机号、`Bearer`、`sk-`；再执行一次按用户删除，确认 trace、摘要、原文指针都删掉。搜到明文或删不干净，就先缩小采集范围。

## 检查一下

```quiz
{
  "id": "ag-14-quiz",
  "title": "可观测性与回放三问",
  "questions": [
    {
      "question": "回放三种模式里，哪一种固定模型输出、用于确定性排查编排？",
      "options": [
        "真模型重跑",
        "工具打桩",
        "固定模型输出回放",
        "沙箱重放"
      ],
      "answer": 2,
      "explain": "固定模型输出回放把模型响应打桩，编排可重复；真模型重跑不确定；工具打桩只固定工具侧；沙箱是工具执行环境，不是第四种模型模式。"
    },
    {
      "question": "`args_hash` 能不能当工具调用身份？",
      "options": [
        "不能；同参数并行会碰撞，低熵值还可枚举，要用 `tool_call_id`",
        "能；参数相同就是同一次调用",
        "能；加盐哈希就不会碰撞",
        "不能；但可以用 `trace_id` 代替"
      ],
      "answer": 0,
      "explain": "同参数并行调用哈希相同，结果乱序时无法区分；哈希也不等于脱敏。args_hash 只用于去重/缓存，调用身份用 tool_call_id。"
    },
    {
      "question": "写「p95 延迟 < 15 秒」时，最少要补清什么？",
      "options": [
        "单条 trace 的 p95",
        "只看平均值",
        "只要模型版本相同",
        "总体、时间窗、样本量、分母是否含超时/取消"
      ],
      "answer": 3,
      "explain": "p95 是集合统计，单条 trace 没有 p95；必须写清总体、时间窗、样本量和分母，否则这个数不可复现。"
    }
  ]
}
```

## 还差的

- 没有真实模型 API key，也没有线上 trace：33.7 秒、$0.104、18,182 token 的例子是手写的；18,182 是按 2.64 字符/token 折算的，我本机量到的 JSONL 样本是 17,204；耗时和 usage 不是实测。
- 没有部署 OTel collector：尾部采样的缓冲内存、决策窗口、同一 trace 路由到同一 collector 都没实测；文中给的是机制和成本，不是压测结果。
- 回放三模式只到设计：没有在真实模型上跑固定输出回放、真模型重跑、工具打桩的对比，分歧点定位和确定性没验证。
- 没有评测集：不写通过率；基线、样本量、标签来源都没有，没有出处的通过率不能出现。
- 脱敏和按用户删除没有在真实存储上演练；字段白名单、访问审计、保留期只给了规则。