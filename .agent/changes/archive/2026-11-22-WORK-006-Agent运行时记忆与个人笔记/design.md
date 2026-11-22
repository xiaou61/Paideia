---
artifact: design
work_id: WORK-006
work: Agent 运行时、记忆与个人笔记
status: approved
mode: strict
mode_reason: 首次引入 agent 运行时；首次出现平台的大宗用户私有数据及其生命周期；新增第二处免鉴权读端点
workflow: full
depends_on: [WORK-004, WORK-005]
related_to: [WORK-007]
created: 2026-10-12
updated: 2026-10-12
approved_by: xiaou61
approved_at: 2026-10-12 15:19:53 +0800
approver_role: CTO
---

# Agent 运行时、记忆与个人笔记设计

## 系统上下文

- **学习者端 / 桌面端**（同一份产物）：助手是全站常驻入口，任意页可开；条目页把当前条目作为**可选**上下文交给助手。
- **公开分享页**：一个新增的**免鉴权**读路径，与 `GET /api/v1/knowledge/files/**` 同构——id 不可枚举即凭据。
- **管理端**：新增举报处理与下架。
- **后端模块**：`paideia-ai`（扩展）+ 新模块 `paideia-note` + `paideia-app`（迁移、安全规则、模块注册）。
- **外部**：用户自带的 OpenAI 兼容模型端点（默认 DeepSeek），经现有 `GuardedHttpTransport` 出站。

## 组件与职责

### `paideia-ai`（扩展，包根 `io.github.xiaou61.ai`）

端口（包根，业务代码只依赖这些）：

- 现有 `EntrySummarizer`、`ModelAccess`、`SummaryCard`、`SummaryVisibility`、`TokenUsage` 保持**签名不变**（WORK-005 的稳定契约）。
- 新增 `AssistantConversation`：发起一轮对话并返回流（多轮上下文、可选条目上下文）。
- 新增 `LearnerMemory`：写入与召回记忆（两路），以及列出／删除本人记忆。
- 新增 `NoteDrafting`：助手为本人创建／修改笔记的入口（内部转发到 `paideia-note` 的 API）。

内部：

- `internal/conversation`：会话与消息表、仓库、上下文装配（最近 N 轮 + 记忆召回结果 + 可选条目正文）。
- `internal/memory`：结构化记忆与全文记忆两路；写入提炼与召回检索。
- `internal/tools`：只读工具（检索条目、查分类、读本人标记与记忆）与写工具（写本人私有数据、写笔记）；**工具轮与结构化轮的分流规则**在此收口。
- `internal/suggestion`：基于记忆与对话产出建议（不自动动作）。
- `internal/web`：新增控制器；对话走 SSE。

### `paideia-note`（新模块）

- 端口（包根）：`NoteApi`——笔记 CRUD、分享的创建／撤回／读取、举报的提交与处理。
- 内部：`internal/note`（笔记）、`internal/share`（分享与读取时判失效）、`internal/report`（举报与下架）、`internal/web`（管理端与学习者端控制器、免鉴权分享控制器）。
- 提供 `NoteApiImplementation`，供 `paideia-ai` 的 `NoteDrafting` 调用（跨模块只经 API 包）。

### `paideia-app`

Flyway 迁移、`/api/v1/**` 的安全规则、新模块注册。**迁移编号不写死**（现有到 `V4`，取下一个可用号）。

### 前端

- 扩展 `frontend/packages/assistant/`：对话面板、流式渲染、思考折叠区、工具过程提示、建议条、记忆管理入口。
- 学习者端新增：笔记页（本人笔记读写）、分享入口与公开分享页、设置内的记忆查看与逐条删除。
- 管理端新增：举报列表与下架操作。

## 请求或事件流程

### 一轮对话（含工具与结构化输出分流）

1. 客户端 `POST /api/v1/ai/conversations/{id}/messages`（或新建会话），带可选 `entryId`。
2. `internal/conversation` 装配上下文：最近 N 轮消息 + `internal/memory` 的召回结果 +（有 `entryId` 时）该条目的已发布正文。
3. **分流**：判断本轮是否需要结构化取回（例如"用户要求把结论记成笔记/记忆"）。
   - **不需要结构化**：直接进入**工具轮**——把只读工具交给模型，模型可多轮调用工具，最终产出正文。
   - **需要结构化**：**先**调用一次"无工具、要求只回 JSON"的模型调用取回结构化结果并校验；**再**单独跑工具轮。两次调用互不交叠，绕开"工具与结构化输出不可并用"的限制。
4. 响应以 **SSE** 下发，事件分两类：`thinking`（思考片段）与 `content`（正文片段）；结束事件带 `tokenUsage`。
5. **任何写操作都不在流里直接执行**：模型只能给出"要写什么"，写入必须由用户在前端点击确认后走独立端点（见「安全与权限」）。

### 记忆的写入与召回

1. **写入**：一轮对话结束后（或用户点击"记住这条"），`internal/memory` 做两件事——把结构化事实（条目、标记、偏好）写 `ai_memories_structured`；把"值得记的原话"写 `ai_memories_text`（带中文全文索引）。
2. **召回**：生成前按同一 `user_id` 两路各取一批：结构化按条件筛（分类／时间／标记），全文按关键词检索；两路结果合并后进上下文。
3. **删除**：设置内逐条删除即按 id 删对应行（两路各一条），删除后不再进入召回。

### 分享的创建与访问

1. 本人对某条笔记点"分享" → 生成 `note_shares` 行：`share_id` 为 **UUID**（不可枚举）、`expires_at`、`revoked_at`（可空）。
2. 访问 `GET /share/{shareId}`：**先判** `revoked_at IS NULL` 且 `expires_at > now`，**再**取笔记正文。过期或撤回一律按"不存在"处理（404），不区分原因。
3. 响应只含笔记正文与其归属条目的可公开部分；**过滤掉**作者身份、记忆、标记、学习痕迹。
4. 撤回即写 `revoked_at`；下架即写 `revoked_at`（与举报处理同一效果）。

### 注销级联硬删

1. **机制：应用层显式删除，不用数据库外键。**
   - **纠正（2026-10-12，实施中发现，属局部事实订正）**：本节初稿写的是"各表 `user_id` 外键 → `users.id` + `ON DELETE CASCADE`"。实施时核实到两点使其不可行：① `V4` 迁移明文定了"不建跨模块外键"的约定，且集成测试用**任意数字身份**造数据（`"91" + nanoTime`，库里没有对应 `users` 行）——加外键会要求每个用例先造真实用户；② 要让级联覆盖 `ai_model_credentials`（用户加密的 API Key，注销必须删），就得给**已有表**加外键，那会直接把 `AiSummaryIntegrationTest` 打红。**需求与可见行为不变**（注销后全删），只换机制。
2. **表清单本身是护栏**：AC-014 的用例在注销后**逐表断言行数为 0**，清单从该用例反推；新增用户私有表忘了登记就会红——这是"删干净"能长期成立的唯一保证。清单：`ai_conversations`、`ai_messages`、`ai_memories_structured`、`ai_memories_text`、`notes`、`note_shares`、`note_reports`，**外加本项之前已有的** `ai_model_credentials`、`ai_entry_summaries`、`refresh_tokens`。
3. **跨模块互不引用对方的表**：注销由 `paideia-account` 发起，各模块经自己的 API 包提供删除入口，账号模块不需要知道别人的表结构。
4. **不引入定时任务**，删除同步完成；注销后本人凭旧令牌也不可再访问。

## 接口与数据

### 学习者端（`/api/v1/ai/**` 已 `authenticated()`）

- `POST /api/v1/ai/conversations`：新建会话（可带 `entryId`）。
- `GET /api/v1/ai/conversations`：本人会话列表。
- `POST /api/v1/ai/conversations/{id}/messages`：一轮对话，返回 **SSE**。
- `GET /api/v1/ai/memories`、`DELETE /api/v1/ai/memories/{id}`：本人记忆的查看与逐条删除。
- 现有 `/api/v1/ai/credentials`、`/api/v1/ai/summaries/**` 不变。

### 笔记与分享

- `/api/v1/notes/**`：`authenticated()`，全部按 `user_id` 过滤。
- `/api/v1/reports`：提交举报，`authenticated()`。
- `/api/v1/notes/admin/reports/**`：`hasRole("ADMIN")`，**写在更宽的 `/api/v1/notes/**` 规则之前**（安全规则按顺序匹配，沿用既有约定）。
- `GET /share/{shareId}`：**`permitAll()`**，且限定 `GET`；需加入静态放行列表与 view controller（分享页是前端静态产物）。

### 表（编号不写死）

| 表 | 关键列 | 说明 |
| --- | --- | --- |
| `ai_conversations` | `id`、`user_id`、`entry_id`(可空)、`expires_at`、`created_at`、`updated_at` | 会话；`user_id` 与 `entry_id` **都不建外键**（见「注销级联硬删」的纠正） |
| `ai_messages` | `id`、`conversation_id`、`role`、`content`、`token_usage`(可空)、`created_at` | 消息；**思考不入库**；随会话一起删 |
| `ai_memories_structured` | `id`、`user_id`、`kind`、`ref_id`(可空)、`value`、`created_at` | 结构化记忆 |
| `ai_memories_text` | `id`、`user_id`、`text`、`created_at`，**中文全文索引** | "说过的话"那一路 |
| `notes` | `id`、`user_id`、`entry_id`(可空)、`title`、`body`、`created_at`、`updated_at` | 笔记 |
| `note_shares` | `share_id`(UUID, PK)、`note_id`、`user_id`、`expires_at`、`revoked_at` | 分享 |
| `note_reports` | `id`、`share_id`、`reporter_user_id`、`reason`、`status`、`created_at` | 举报 |

全文索引用 MySQL 的 **ngram** 解析器建（与 WORK-004 的中文检索同一套做法）。

## 持久化与迁移

- 新增迁移取**下一个可用编号**（现有 `V1`–`V4`）；**不写死**——本仓库有并发会话，写死编号会撞车（WORK-004 已踩过，两个 `V2` 会让 Flyway 起不来）。
- `paideia` 与 `paideia_test` 两库共用迁移，`paideia_test` 另有夹具编号（`V900+`）与乱序容忍，沿用既有配置，不改。
- 分享读取是**读取时判失效**，无清理任务；过期行留在表里（可接受：体积小，且注销会随用户级联删）。

## 失败处理与恢复

- **模型失败**：错误对用户可见，且**不透出上游原始报文**（沿用 WORK-005）；失败轮次不入记忆。
- **只有思考、正文为空**：`max_tokens` 按"思考 + 正文"给足以留余量；仍为空时前端给明确提示，不显示空白气泡。
- **流中断**：SSE 断开即结束本轮；已收到的正文保留在当前视图，不冒充完整结果；未确认的写操作一律不执行。
- **工具失败**：工具错误作为可观察结果回到模型，允许其改用其他方式回答；工具错误不写记忆。
- **分享失效**：过期／撤回／下架统一 404，不泄漏"为什么失效"。
- **并发写**：记忆与笔记的写入都是"用户确认后单次写入"，不做并发合并；同一会话并发轮次按会话加锁，超出则让后到的一轮排队或明确失败。

## 安全与权限

- **三态校验**（每一类用户私有数据都要）：匿名 → 401；他人 → 403 或空结果（**不泄漏存在性**）；本人 → 放行。会话、消息、记忆、笔记、分享、举报全部覆盖，各有自动化用例。
- **免鉴权面**：`GET /share/{shareId}` 是全项目**第二处**免鉴权读端点。纪律与 `GET /api/v1/knowledge/files/**` 一致：① `shareId` 为 UUID，不可枚举；② 只允许 `GET`；③ 产物过滤掉记忆、标记、作者身份与任何学习痕迹；④ 失效统一 404。
- **写路径必须用户确认**：助手产出的写操作先给"草案"，用户点击后才调独立端点执行；端点本身校验归属，不能靠"前端只显示自己的"来保证。每条写入记录来源（用户／助手），以便分辨。
- **工具越权**：工具只接收 `user_id` 作用域的查询；任何工具参数里的 id 都会在服务端重新校验归属（不信任模型给的 id）。
- **凭据**：沿用 WORK-005 的加密落库与"不进日志、不出响应"；本项**不新增任何凭据**。

## 可观测性

沿用既有暂缓（D-15）：**不引入监控栈**。本项只做两件事：记日志（含 `traceId`）与把每轮 `tokenUsage` 返还用户。运行时的成本可见性由"用户看得见自己的用量"承担，不由平台指标承担。

## 实施顺序

与提案的八个纵向切片一致（多轮+流式 → 记忆 → 只读工具 → 写路径 → 笔记 CRUD → 只读分享 → 举报与下架 → 注销与计费收口）。每片穿过它实际涉及的数据、接口、界面与测试三层。

## 测试策略

- **内环**：纯函数（分流判定、分享失效判定、记忆合并、上下文裁剪）走单测，不启容器。
- **外环**：后端全量 `mvn -B verify` + 前端 `pnpm -r test`／`typecheck` + **改动那一端**的 e2e。
- **集成用例需要 `PAIDEIA_TEST_DB_PASSWORD`**（否则静默跳过，见 WORK-001 复验记录）。
- **e2e 串行共用一个后端**（并行会让学习者端与管理端抢 8080）。
- **四条反向验证必须咬住**：① 把三态越权校验临时放行 → 对应用例必须红；② 把分享端点临时改成需登录 → 免登录用例必须红；③ 注销改为不级联 → 删除用例必须红；④ 建议流程里写入改为不确认直接执行 → "零写"用例必须红。
- **回归**：WORK-005 的精简路径（单次调用、缓存、凭据）不得因端口扩展而回归。

## 需求追踪

| 需求 | 组件 | 表 / 端点 | 验收 |
| --- | --- | --- | --- |
| REQ-001 多轮对话 | `AssistantConversation`、`internal/conversation` | `ai_conversations`、`ai_messages`；`/api/v1/ai/conversations**` | AC-001 |
| REQ-002 流式与思考分离 | `internal/web`（SSE）、前端 assistant 包 | SSE `thinking` / `content` 事件 | AC-002 |
| REQ-003 会话持久化 | `internal/conversation` | 同上；上限与有效期读取时判 | AC-003 |
| REQ-004 记忆两路 | `LearnerMemory`、`internal/memory` | `ai_memories_structured`、`ai_memories_text` | AC-004 |
| REQ-005 记忆私有 | 三态校验 | `/api/v1/ai/memories**` | AC-005 |
| REQ-006 记忆自我管理 | `LearnerMemory` | 同上（列表 + 单条删） | AC-006 |
| REQ-007 只读工具 | `internal/tools` | `KnowledgeApi` 只读方法 | AC-007 |
| REQ-008 写本人私有数据 | `internal/tools` + 确认端点 | 记忆／标记写入 | AC-008 |
| REQ-009 助手写笔记 | `NoteDrafting` → `NoteApi` | `/api/v1/notes/**` | AC-008 |
| REQ-010 建议不动作 | `internal/suggestion` | 无写端点被调用 | AC-009 |
| REQ-011 笔记 CRUD | `paideia-note` | `notes` | AC-010 |
| REQ-012 只读分享 | `internal/share` | `note_shares`；`GET /share/{id}` | AC-011、AC-012 |
| REQ-013 举报与下架 | `internal/report` | `note_reports`；`hasRole(ADMIN)` | AC-013 |
| REQ-014 注销硬删 | 各表 `user_id` FK CASCADE | `users` 删除级联 | AC-014 |
| REQ-015 BYOK 与用量 | 现有 `ModelAccess`、限流 | SSE 结束事件带用量 | AC-015 |
| REQ-016 工具 × 结构化 | `internal/tools` 分流规则 | 两次独立调用 | AC-016 |

## 待决定事项

均属实现选择，不阻断实现：会话条数上限与会话有效期数值、多轮限流数值、分享固定有效期数值与撤回交互、被合并门槛的签署顺序（见 `proposal.md`「门槛合并说明」）。
