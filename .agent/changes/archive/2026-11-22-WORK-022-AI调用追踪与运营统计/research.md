---
artifact: research
work_id: WORK-022
work: AI 调用追踪与运营统计
status: draft
created: 2026-10-19
updated: 2026-10-19
---

# AI 调用追踪的调研事实

2026-10-19 联网核实。本文件只记证据，不重复需求里的决定。

## 事实一：主流方案用三层 id，不是"一个 trace id 走天下"

- **OTel GenAI 语义约定**（`open-telemetry/semantic-conventions-genai`）把标识分成三层：
  - `gen_ai.conversation.id`：会话 / 线程，**跨多轮**；
  - trace id：**一轮**请求；
  - span：步骤，按 `gen_ai.operation.name` 分为 `chat` / `invoke_agent` / `execute_tool` / `retrieval` / `plan` 等。
- 规范明文写着：conversation id 在客户端框架自己管会话时（LlamaIndex chat store、LangChain `session_id`、Google ADK sessions）才填；**没有时不得用新 UUID、trace id 或内容哈希兜底**。
- 用量拆成 `gen_ai.usage.input_tokens` / `output_tokens` / `cache_read.input_tokens` / `cache_write.input_tokens`；缓存命中单独一列，不并入输入。
- prompt、输出正文、系统提示词、工具定义都是 **Opt-In**：默认不采集，因为「可能含敏感信息与 PII」。
- 该规范当前状态是 **Development**（还不是 Stable），字段名未来可能调整。
- 来源：`opentelemetry.io/docs/specs/semconv/gen-ai/`、`raw.githubusercontent.com/open-telemetry/semantic-conventions-genai/main/docs/gen-ai/gen-ai-agent-spans.md`

## 事实二：三家产品是同一种形状

- **Langfuse**：Session → Trace → Observation。一条 trace 是"一次请求或一次操作"，session 把同一交互的多条 trace 串起来；`user_id` / `session_id` / `tags` / `metadata` **复制到每一条 observation 行上**（用冗余换查询速度）。它底层就是 OTel，可以把同一份 trace 同时发给 Langfuse 与 Datadog。
- **OpenInference（Arize）**：`openinference.span.kind` 取 `LLM` / `TOOL` / `AGENT` / `CHAIN` / `RETRIEVER` / `RERANKER` / `GUARDRAIL` / `EVALUATOR`；会话用 `session.id`，用户用 `user.id`，标签用 `tag.tags`。
- 两家都没有把统计建立在"只有一个 trace id"上，也没有把对话轮次当会话。

来源：`langfuse.com/docs/observability/data-model`、`raw.githubusercontent.com/Arize-ai/openinference/main/spec/semantic_conventions.md`

## 事实三：自托管 Langfuse 的存储成本

- 需要四个存储：**Postgres**（事务）、**ClickHouse**（trace / observation / score）、**Redis 或 Valkey**（队列与缓存）、**S3 或 Blob**（事件与多模态附件）。
- 另需两个容器：`langfuse/langfuse`（Web 与 API）与 `langfuse/worker`（异步处理）。
- 官方自身的表述：ClickHouse 存观测数据、S3 先落事件再入库以抗尖峰。
- 本机生产环境是 4 核 16G，已跑 MySQL、RustFS 与 Firecrawl；再加四个有状态服务是实打实的风险。

来源：`langfuse.com/self-hosting`

## 事实四：trace 数据量级的分水岭

- Langfuse 对外称处理 90B+ observations/月，因此它必须用 ClickHouse + S3 前置落盘那一套。
- 本项目的量级按需求里的假设是每天数百到数千轮，一年百万行以内，MySQL 单表 + 合适索引完全够用。
- 这条差距解释了"为什么别人用 ClickHouse 而我们不用"——不是他们更先进，是量级不同。

## 对设计的直接影响

| 事实 | 落到的设计选择 |
| --- | --- |
| 三层 id | `ai_runs.trace_id` 是一轮；`conversation_id` 是会话；`ai_run_spans` 是步骤。三者不混用 |
| conversation id 不能用 trace 兜底 | 无会话的路径（`memory_extract` 等）该列为 NULL，不填 trace |
| 缓存命中单列 | `cache_read_tokens` 独立成列 |
| 正文 Opt-In、默认不采 | `attrs` 只放长度与计数；正文留在 `ai_messages` |
| 规范仍是 Development | 字段名照它取，但现在不引 SDK，避免被未稳定版本绑住 |
| 各家都是 Session→Trace→Span | 表结构按同一形状，将来接任一平台都是映射 |
| 自托管要四个存储 | 不引入，自建两张表 |
| 量级差三个数量级 | 不做 ClickHouse、不做分区、不做日汇总表（有触发条件） |
