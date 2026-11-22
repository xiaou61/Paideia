---
artifact: requirements
work_id: WORK-022
work: AI 调用追踪与运营统计
status: approved
mode: strict
mode_reason: 新增两张表与一个迁移（数据边界）；新增公共采集端口与写入队列（公共行为）；新增管理端只读端点与管理端页面；注销级联表清单变更；改动计费结算函数的返回值
workflow: full
depends_on: []
related_to: [WORK-006, WORK-009, WORK-010, WORK-013, WORK-019]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 17:54:59 +0800
approver_role: CTO
---

# AI 调用追踪与运营统计需求

## 本项状态与本轮变化

- **2026-10-19 起草**。用户原话：「关于Agent每次对话的一个追踪，现在主流的方案是traceid吗，还是什么呢，我们现在打算要实现一个这个，就是最后放到后台来统计各种东西」；随后追加：「就是能做的复杂一点吗，因为要可扩展啊，你要为之后考虑啊」。
- 两轮讨论已确认：**不做 Langfuse / OpenTelemetry 全家桶**，自建采集端口 + MySQL 落地；**trace id 是"一轮"的关联键，不是方案本身**；复杂度只能花在扩展点上（采集端口、sink 列表、冷字段 JSON、字符串枚举），不能花在多建表上。
- 本项**尚未实施**。四件工件均为 draft，等待签署。

## 已确认事实

### 仓库事实（逐文件核对）

| 项 | 事实 |
| --- | --- |
| 现有追踪 | 只有 HTTP 级 `TraceIdFilter`（`backend/paideia-web/.../web/TraceIdFilter.java`）：读/生成 `X-Trace-Id`，写请求属性、响应头与 MDC key `traceId`，请求结束即 `MDC.remove` |
| MDC 传播 | **没有**。`runTurn` 提交到 `applicationTaskExecutor`（`AssistantConversationController.java:124`），全仓无 `TaskDecorator`；`%X{traceId}` 也没有进任何日志格式（无 `logback-spring.xml`，`application.yml` 未配 `logging.pattern`） |
| 追踪依赖 | **没有** OTel / Micrometer Tracing / Sleuth；`paideia-app/pom.xml` 只有 `spring-boot-starter-actuator`，暴露 `health,info,metrics` |
| Actuator 覆盖 | **覆盖不到真实时长**：`SseEmitter` 让 HTTP handler 立即返回，`http.server.requests` 只量到 emitter 返回那一刻，量不到 5–60 秒的流式生成 |
| 一轮的边界 | `AssistantConversationController.send`（同步 prepare + 建 emitter）与 `runTurn`（异步消费上游流）构成一轮；`AssistantConversation.Listener` 的 `onUsage` / `onCompleted` / `onFailed` 已是明确的"用量与结果分离"语义 |
| 上游调用 | `AgentScopeConversation.stream` 里工具循环最多 4 轮，每轮一次真实上游调用（`runRound`）；用量**逐轮累加后只上报一个总数** |
| 工具调用 | `AgentToolBridge.run` 是唯一收口；异常被吞成文本结果（`:166-169`），**没有错误计数、没有耗时** |
| 上下文规模 | `ConversationService.prepare` 手上有历史条数、记忆召回数、条目正文字数、学情摘要，**全部没有记录** |
| 计费 | `ai_credit_ledger`（V11）有 `model` / `input_tokens` / `output_tokens` / `ref_message_id`，**没有会话或轮次关联**；`reserve` 与 `refund` **不写流水**（`KIND_RESERVE` / `KIND_REFUND` 定义了未使用） |
| 计费返回值 | `CreditService.settle` / `settleAs` / `settleExternalAsk` 都是 `void`，**算出来的实扣金额不外传**，调用方只知道自己预留了多少 |
| 消息表 | `ai_messages.token_usage` 只有一份聚合 JSON `{input,output,total}`；思考内容明确不落库 |
| AI 入口共七条 | 对话（`ConversationService` + `AgentScopeConversation`）、记忆整理（`MemoryExtractionService` → `AgentScopeMemoryExtractor`）、面试点评（`InterviewReviewService` → `AgentScopeInterviewReviewer`）、外部问答（`AskTool` → `AgentScopeAssistantAnswerer`）、简历导入（`ResumeImportService` → `ResumeModelCaller`）、简历优化（`ResumeOptimizationService`）、条目精简（`SummaryService` → `AgentScopeEntrySummarizer`） |
| 注销级联 | `AiUserDeletionListener` 是一张**手写表清单**，由 `AccountDeletionCascadeIntegrationTest` 逐表断言；新增用户私有表忘了登记，用例会红 |
| 管理端鉴权 | `/api/v1/ai/admin/**` 已有 `hasRole("ADMIN")` 规则，**且排在 `/api/v1/ai/**` 之前**（`SecurityConfiguration`）；`AiCreditAdminController` 类上有 `@ApiAccess(ApiAccess.Access.ADMIN)` |
| 分页设施 | `paideia-platform` 已有 `PageQuery`（归一化 + `offset()` / `limit()`）与 `PageResult.of(total, query, items)` |
| 定时任务 | `@EnableScheduling` 在 `paideia-account` 的 `AccountSupportConfiguration`；`@Scheduled` 已有先例（`ExpiredStatePurger`） |
| 迁移编号 | 已用到 **V20**（条目读索引占用），下一个是 V21 |
| 管理端菜单 | `AdminShell.tsx` 的侧栏是层级表；`/ai-credits` 已有"父项只分组、子项为页面"的先例 |

### 调研事实

2026-10-19 联网核实，证据与来源见 `research.md`。四条影响设计的结论：

- 主流用的是**三层 id**：会话（`gen_ai.conversation.id`）→ 一轮（trace）→ 步骤（span），trace id 只是中间那层的关联键。规范明确禁止用 trace id 或新 UUID 冒充会话 id。
- Langfuse（Session → Trace → Observation）与 OpenInference（`openinference.span.kind` + `session.id` + `user.id`）是同一种形状，三家都没把统计压在"一个 trace id"上。
- OTel 规范把 prompt 与输出正文列为 **opt-in**，默认不采集。
- 自托管 Langfuse 要 Postgres + ClickHouse + Redis/Valkey + S3 四个存储加两个容器；本机生产环境 4 核 16G，已跑 MySQL、RustFS 与 Firecrawl。

## 用户决定

| 决定 | 用户原话 / 轮次 | 影响 |
| --- | --- | --- |
| 要做 AI 调用的追踪，落地到后台做统计 | 「我们现在打算要实现一个这个，就是最后放到后台来统计各种东西」 | 需要落库的每轮记录 + 管理端统计面 |
| 要可扩展，要为之后考虑 | 「就是能做的复杂一点吗，因为要可扩展啊，你要为之后考虑啊」 | 采集端必须是一个端口 + 多个落地实现；加维度不改表 |
| 接受自建、不引入 Langfuse/OTel | 第一轮答复后用户未反对「自建两张表、不上全家桶」，直接进入起草 | 不自托管 Langfuse、不加 OTel SDK |

## 实现选择

1. **一行一轮（`ai_runs`）+ 一行一步（`ai_run_spans`）**，不做"一个请求一行把所有步骤塞进 JSON"。理由：工具调用现在是黑盒，只有步级行能回答"哪个工具失败、哪个慢、一轮调了几次"；塞进 JSON 之后就再也聚合不了。
2. **`kind` 与 `span.kind` 用字符串，不用 MySQL 枚举**。理由：加新 AI 功能是常态，枚举每加一个值都要 DDL；本项目已放开 MySQL 特性，但这里的可扩展性比类型严格更值钱。
3. **热字段用列、冷字段用 `attrs` JSON**。理由：用户、场景、模型、状态、耗时、token 这些要 GROUP BY 与索引，必须是列；"历史条数""记忆命中数""条目字数""HTTP trace id"这类只用于下钻的数，加进 JSON 就不用改表。
4. **不存任何正文与提示词**。理由：正文已经在 `ai_messages`，靠 `conversation_id` 可还原；prompt 属于 OTel 明确列为 opt-in 的敏感内容。真需要回放时写对象存储（已有 RustFS），不进 MySQL。
5. **采集端口 `RunRecorder` + 落地接口 `RunSink` 两层，而不是直接写库**。理由：这是"可扩展"的落点。将来接 OTel、Langfuse 或 Micrometer 只加一个 `@Component`，不改任何埋点；关掉统计只需一条配置。
6. **写入走有界队列 + 单写线程 + 批量插入，调用方线程零 IO**。理由：统计系统不许影响回答。队列满就丢并记数，不阻塞。
7. **采样按轮（run）决定，不按步（span）**。理由：按步采样会拼出残缺的 trace，比不留更糟。失败与超时的轮次**永远保留**。
8. **span 用 `run_trace_id` 关联，不用自增 `run_id`**。理由：异步写入下拿不到刚插入的自增 id；用一个唯一的 trace id 做关联键，插入与更新都能独立执行，少一层内存状态。代价是 join 的是字符串，在万行量级可忽略。
9. **不碰 `ai_credit_ledger`**。理由：`ai_runs` 自己记下"这一轮实扣多少"，足以做日级对账；给流水加 `run_trace_id` 属于第二次写同一件事，且要改计费路径。升级触发条件：需要**逐笔**对账时再加。
10. **不改 `ai_messages`**。理由：每轮明细已经在 `ai_runs`，消息表保持"对话内容"的单一职责。

## 背景

平台现在有七条 AI 调用路径，但没有一条留下可查记录。用户能看到一条回答，开发者能看到的只有两行 WARN 日志（上游 HTTP 失败时）。下面这些问题今天一个都答不出来：上周调了多少次？成功率多少？首字延迟多少？哪个工具最常失败？某用户说"回答很慢"怎么定位到他那一次？某次答得差是上下文没给够还是模型的问题？

这些问题的共同点是：**它们都需要"一次调用"这个概念存在**。现在系统里没有它——只有"一条消息"和"一条计费流水"，两者之间还断着。

## 目标

- 让每一次 AI 调用（不只是对话）都留下一条可查的轮次记录，带状态、耗时、用量、成本与上下文规模。
- 让一轮内部的每一步（每次上游调用、每次工具调用）都可下钻。
- 让这些数据能被管理端按时间、场景、模型、用户、状态切片统计。
- 让采集端成为**一个端口**：新增 AI 功能只传一个新的 `kind`，不建表、不改写入路径；将来接外部观测平台只加一个 sink。
- 让日志能对上记录：同一次调用的日志行带同一个标识。

## 非目标

- 不做 trace 树的交互式可视化（逐层展开、瀑布图）。本期是列表 + 聚合 + 单轮步骤表。
- 不采集提示词与回答正文，不做"重放某次对话"。
- 不接 OpenTelemetry SDK、不自托管 Langfuse / Phoenix / Jaeger。
- 不做 LLM-as-judge 评分、人工标注、A/B 对比、数据集回归。
- 不做实时告警与推送。
- 不改计费规则、不改积分余额语义、不给用户看自己的调用明细（本期只给管理员）。
- 不做日汇总表与分区（有明确的升级触发条件，见设计）。
- 不记录流式增量的逐条时间戳（只记首字与结束两个时刻）。

## 用户与工作流程

**管理员（日常）**：进「AI 调用」→ 概览页看今日/近 7 天的调用量、成功率、p50/p95 耗时、总 token、总扣分、错误类型排行、工具失败排行 → 发现工具失败率异常，切到明细页按场景与状态筛选。

**管理员（定位一次问题）**：用户反馈"某次回答很慢" → 按用户 + 时间筛 → 找到那一轮，看 `durationMs` 与 `ttftMs` → 点开步骤表：第 1 轮上游 800ms、工具 `knowledge_search` 4.2s、第 2 轮上游 600ms → 结论是工具慢。

**开发者（查故障）**：日志里搜到 `aiTraceId=...` → 用同一个值在明细页筛出那一轮 → 看到 `errorType` 与各步状态。

**新增 AI 功能时（工程视角）**：调用处 `begin(RunSpec.of(KIND_XXX, userId))`，完成时 `finish(OK, null)`；不建表、不改 sink、不改统计页的筛选维度。

## 功能需求

- REQ-001：每一轮 AI 调用都产出一条轮次记录，包含：轮次标识、用户、可选会话、场景（`kind`）、行为（`harness`）、模型、提示词版本（可空）、状态、开始/结束时间、耗时、首字延迟、上游调用轮数、工具调用次数、输入/输出/缓存命中 token、实扣积分、上下文规模。
- REQ-002：轮次记录在调用**开始时**先以「进行中」写入，结束时更新为终态；进程崩溃或线程卡死留下的「进行中」行必须能在统计里被看见。
- REQ-003：轮次内部每一次真实上游调用与每一次工具调用各留下一条步骤记录，包含步骤类型、名称、状态、开始时间、耗时、该步的 token 用量；步骤可以带父步骤（用于表达"这一轮工具里的子调用"）。
- REQ-004：`kind` 覆盖全部七条 AI 路径：对话、记忆整理、面试点评、外部问答、简历导入、简历优化、条目精简。新增路径只需新增一个 `kind` 字符串。
- REQ-005：采集端是一个端口，调用方只依赖它；落地实现可以有一个或多个并列，也可以是"什么都不做"。关闭采集不改变任何业务行为。
- REQ-006：写入不得阻塞调用方线程；队列满时丢弃并计数，丢弃不影响回答。写入失败只记日志，不影响回答与计费。
- REQ-007：失败、超时、被取消的轮次可以被单独筛出并统计；它们的 `errorType` 是可枚举的低基数标识，不是异常栈。
- REQ-008：提供管理端概览统计：指定时间窗内的调用量、成功/失败/超时/进行中计数与成功率、耗时 p50 与 p95、首字延迟 p50 与 p95、输入/输出 token 合计、实扣积分合计、按 `kind` 与按模型的分布、错误类型排行。
- REQ-009：提供管理端轮次列表：按时间窗、`kind`、状态、用户、模型筛选，分页，按开始时间倒序。
- REQ-010：提供管理端轮次详情：轮次全部字段 + 该轮的步骤表（按序号）。
- REQ-011：上下文规模作为可下钻字段记录：历史消息条数、记忆召回条数、条目正文字数、是否带学情摘要、HTTP 请求的 `X-Trace-Id`。
- REQ-012：日志能与记录对上：一次调用的日志行携带轮次标识，且与 HTTP 请求的追踪标识互不覆盖。
- REQ-013：注销账号时，该用户的轮次记录与其步骤记录一并删除；步骤记录没有用户列，按该用户的轮次标识删除。
- REQ-014：统计接口只对管理员开放，只读。
- REQ-015：轮次记录保留期可配置（默认 90 天），过期数据由后台任务分批清理。
- REQ-016：采样率可配置（默认全量）；采样只影响成功轮次，失败与超时轮次永远保留。

## 质量要求

- 采集端在调用方线程上**不做任何 IO**；单测用假的 sink 断言"调用方线程内没有写库调用"。
- 队列有界，满时丢弃并累加计数（该计数在日志里可见）；写入失败只打日志，**不得**变成用户可见的错误。
- 统计查询必须有时间窗上限（默认 90 天），不得无界扫描；分位数在 MySQL 内算，不把明细拉进应用内存。
- 列表与详情都不得返回提示词、回答正文或工具参数原文；步骤表按 `trace_id` + `seq` 建索引。
- 管理端页面在无数据时显示空状态，不白屏。

## 约束与依赖

- 迁移编号取执行时的下一个（当前下一个是 V21），**不写死**；新增迁移后按 `MigrationChecksumTest` 提示用 `-Dmigration.checksums.update=true` 登记校验和。
- 时间全部由应用传 `Instant`（UTC），不写 `DEFAULT CURRENT_TIMESTAMP`；SQL 只写 mapper；手写 MyBatis，不引 MyBatis-Plus。
- 表不建跨模块外键（与 V4/V5/V11 一致）；注销级联由 `AiUserDeletionListener` 的表清单负责。
- 新管理端点放在 `/api/v1/ai/admin/**` 前缀下（既有 `hasRole("ADMIN")` 规则已覆盖且顺序正确），类上标 `@ApiAccess(ApiAccess.Access.ADMIN)`。
- 采集端口放在 `paideia-ai` 包根，签名里不出现任何框架类型（D-09 纪律，与 `AssistantConversation` 同一套）。
- 不新增第三方依赖。
- 公开仓库：`attrs` 与日志都不得写入凭据、服务器地址或提示词内容。

## 关联工作项

- **WORK-006（Agent 运行时）— related_to**：本项为它的七条调用路径补观测；不改它的端口签名，只在实现侧埋点。
- **WORK-009（AI 网关与积分）— related_to**：本项记录"这一轮实扣多少"，与积分流水做**日级**对账；不改流水表结构，不改扣费规则。需要 `settle` 系列返回实扣金额（向后兼容的返回值改动）。
- **WORK-010（身份化）— related_to**：`harness` 作为独立列记录，将来按身份统计只需加一个筛选维度。
- **WORK-013（记忆自动提取）— related_to**：记忆整理是本项要覆盖的一条 `kind`；它自身的开关语义不变。
- **WORK-019（MCP 网关）— related_to**：外部问答（`AskTool`）是本项要覆盖的一条 `kind`。

## 验收标准

- AC-001：发起一次带工具的对话后，`ai_runs` 恰好新增 1 行，`status = ok`，`llm_rounds >= 2`，`tool_calls >= 1`，`duration_ms` 与 `ttft_ms` 均非空。
- AC-002：同一次对话在 `ai_run_spans` 里至少留下 2 条 `llm` 步骤与 1 条 `tool` 步骤，序号连续且该轮的 token 合计等于各 `llm` 步骤之和。
- AC-003：上游失败（桩掉模型返回 500）的一轮记为 `status = failed` 且 `error_type` 非空，同时仍记录已烧掉的 token；该轮不被采样丢弃。
- AC-004：一轮结束后，`ai_runs.credits` 等于同一次结算在积分流水里写入的差额绝对值。
- AC-005：调用开始到结束之间（模拟：在 `begin` 之后打断点前查库）能查到 `status = running` 的行。
- AC-006：七条 AI 路径各触发一次后，`SELECT kind, COUNT(*) ... GROUP BY kind` 返回七个不同的 `kind`。
- AC-007：把采集开关关掉后，同样的对话不产生任何轮次记录，且对话行为、消息落库、扣费与原来完全一致。
- AC-008：让 sink 的写入抛异常，对话仍然正常完成、消息落库、扣费正确，只有一条 WARN 日志。
- AC-009：概览接口返回的调用量等于该时间窗内 `ai_runs` 的实际行数；成功率等于 `ok` 行数除以终态行数。
- AC-010：概览接口的 p50/p95 与把同一批 `duration_ms` 排序后手算的结果一致。
- AC-011：列表接口按 `kind + status + 时间窗` 组合筛选时只返回匹配行，分页总数正确。
- AC-012：详情接口返回的步骤表按 `seq` 升序，且响应里不出现任何提示词、回答正文或工具参数原文（用一次带长上下文的调用做断言）。
- AC-013：注销账号后，该用户的 `ai_runs` 行数为 0，其 `ai_run_spans` 行数为 0，其他用户的行数不变。
- AC-014：把保留期设为 1 天并造一条 2 天前的记录，清理任务执行后该行与其步骤一起消失，1 天内的行保留。
- AC-015：采样率设为 0 时成功轮次不留记录，失败轮次仍被记录。
- AC-016：一次流式对话的日志里能同时看到 `traceId`（HTTP）与 `aiTraceId`（轮次）两个字段，且不能用同一个值冒充另一个。
- AC-017：管理端两个页面在无数据时显示空状态；有数据时概览卡片与列表数字来自同一次查询窗口。

## 假设

- 单实例部署（现状）：队列与丢弃计数在内存里，重启即清零。多实例时汇总需要在 sink 层另想办法，本期不做。
- 调用量级在每天数百到数千轮；`ai_runs` 一年不超过百万行，不需要分区或汇总表。
- 管理端读的是过去的数据，允许秒级延迟（异步写入），不要求实时。
- 平台凭据与模型选择是全局配置，因此"按模型统计"在现阶段等价于"按时间统计"；列仍保留，因为将来可能允许用户选模型。

## 待确认问题

无阻断需求批准的问题。以下两条只影响实现，已按实现选择记录：`kind` 用字符串常量集中在 `RunKind` 一处定义（库里不约束）；本期不给用户看自己的调用明细（积分页已能查流水，那是另一件事）。
