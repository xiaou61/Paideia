---
artifact: tasks
work_id: WORK-006
work: Agent 运行时、记忆与个人笔记
status: approved
mode: strict
workflow: full
depends_on: [WORK-004, WORK-005]
related_to: [WORK-007]
created: 2026-10-12
updated: 2026-10-12
approved_by: xiaou61
approved_at: 2026-10-12 15:19:54 +0800
approver_role: CTO
---

# Agent 运行时、记忆与个人笔记实施任务

## 依据

- `requirements.md`（14 条用户决定、REQ-001…016、AC-001…016）
- `proposal.md`（推荐方案：扩展 `paideia-ai` 薄端口 + 新建 `paideia-note` 模块；三条硬约束的落地方式）
- `design.md`（组件、流程、表设计、安全规则、实施顺序）
- 前置已完成：WORK-004、WORK-005

## 全局约束

- **迁移编号不写死**：现有 `V1`–`V4`，实施时取下一个可用号；并发会话会撞号。
- **框架类型不出端口**：业务代码不直接依赖 AgentScope；只用模型与工具编排面。
- **写操作必须用户确认**：助手不得直接执行写；写入走独立端点并校验归属。
- **免鉴权面只有一处新增**：`GET /share/{shareId}`，只允许 `GET`、id 不可枚举、产物不含记忆与学习痕迹。
- **无定时任务**：会话有效期与分享到期一律读取时判。
- **注销即级联硬删**：所有用户私有表的 `user_id` 外键指向 `users.id` 且 `ON DELETE CASCADE`。
- **测试前置**：集成用例需 `PAIDEIA_TEST_DB_PASSWORD`；e2e 串行共用一个后端；不用 `pnpm -r build`。
- **公开仓库**：不新增任何凭据入库。

## 任务

### TASK-001 | done | 会话与消息持久化

- 对应：`REQ-001`、`REQ-003`、`AC-001`、`AC-003`
- 依赖：无
- 修改：`backend/paideia-app/src/main/resources/db/migration/V5__create_assistant_tables.sql`（编号取下一个可用号，实施时确认未撞号）、`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/conversation/`
- 测试：`backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/conversation/ConversationWindowTest.java`
- 步骤：
  1. 建 `ai_conversations`、`ai_messages` 两表。**不建外键**（理由见 `design.md`「注销级联硬删」的纠正）。
  2. 写 mapper 与"最近 N 轮 + 可选条目正文 + 记忆召回结果"的上下文装配；裁剪规则写成纯函数。
  3. 实现会话有效期与条数上限的**读取时判定**（过期会话视为不存在），并支持滑动续期。
- 验证：`mvn -B -o -pl paideia-ai -am test -Dtest=ConversationWindowTest -Dsurefire.failIfNoSpecifiedTests=false` → **5 条通过**（已跑）。
  - **SQL 层的覆盖落在 TASK-002 的集成用例**（本地调整，理由）：那个用例在完整上下文里跑真实的 `V5` 迁移并按 API 断言读写与过期。在 `paideia-ai` 里另起一套 DB 装配只会重复它，且会撞 Flyway 迁移顺序——`paideia-ai` 在反应堆里早于 `paideia-app`，那时 `V5` 还没建。


### TASK-002 | done | 对话端口与 SSE 端点

- 对应：`REQ-001`、`REQ-002`、`REQ-003`、`AC-001`、`AC-002`、`AC-003`
- 依赖：TASK-001
- 修改：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/AssistantConversation.java`、`.../ai/internal/provider/`、`.../ai/internal/web/`
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/AssistantConversationIntegrationTest.java`
- 步骤：
  1. 在包根新增端口 `AssistantConversation`，内部实现走 AgentScope 模型面；**不暴露框架类型**。
  2. `POST /api/v1/ai/conversations`、`GET /api/v1/ai/conversations`、`POST /api/v1/ai/conversations/{id}/messages`（SSE）。
  3. SSE 事件分 `thinking` 与 `content` 两类，结束事件带 `tokenUsage`；**思考不入库**。
  4. `max_tokens` 按"思考 + 正文"给足；"只有思考、正文为空"时返回可见提示而非空白。
- 验证：`mvn -B verify`（匿名 401、他人 403、本人 200；SSE 两类事件；空正文提示）。

### TASK-003 | done | 助手对话前端（流式 + 思考折叠 + 任意页入口）

- 对应：`REQ-001`、`REQ-002`、`AC-001`、`AC-002`
- 依赖：TASK-002
- 修改：`frontend/packages/assistant/src/`、`frontend/apps/app/src/`（助手挂载点由"仅条目页上下文"改为"全站常驻 + 可选条目上下文"）
- 测试：`frontend/packages/assistant/src/*.test.tsx`、`frontend/apps/app/e2e/assistant.spec.ts`
- 步骤：
  1. 对话面板：输入、消息列表、流式增量渲染。
  2. 思考折叠区：默认收起、可展开；与正文视觉分离。
  3. 全站入口：任意页可开；在条目页自动把当前条目作为**可选**上下文。
- 验证：`pnpm --filter @paideia/app test:e2e`（多轮、流式、思考折叠各有用例）。

### TASK-004 | done | 记忆两路存储与召回

- 对应：`REQ-004`、`AC-004`
- 依赖：TASK-002
- 修改：`.../ai/internal/memory/`、迁移新增 `ai_memories_structured`、`ai_memories_text`（后者建中文 **ngram 全文索引**）
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/LearnerMemoryIntegrationTest.java`
- 步骤：
  1. 结构化记忆：写入（条目、标记、偏好）与按条件召回。
  2. 全文记忆：写入"值得记的原话"与关键词检索召回。
  3. 两路召回结果的合并进上下文。
- 验证：`mvn -B verify`（结构化命中、全文命中**各有一条独立用例**；合并顺序确定）。

### TASK-005 | done | 记忆的查看与逐条删除

- 对应：`REQ-005`、`REQ-006`、`AC-005`、`AC-006`
- 依赖：TASK-004
- 修改：`.../ai/internal/memory/`、`.../ai/internal/web/`、`frontend/apps/app/src/pages/`（设置内的记忆面板）
- 测试：`LearnerMemoryIntegrationTest`、`frontend/apps/app/e2e/settings.spec.ts`
- 步骤：
  1. `GET /api/v1/ai/memories`、`DELETE /api/v1/ai/memories/{id}`，全部按 `user_id` 过滤。
  2. 删除后不再进召回（两路都验）。
  3. 设置界面：列出本人记忆、逐条删除（**不做编辑**）。
- 验证：`mvn -B verify` + e2e（三态：匿名 401 / 他人不可见 / 本人可删）。

### TASK-006 | done | 只读工具与三态越权防护

- 对应：`REQ-007`、`AC-007`
- 依赖：TASK-002
- 修改：`.../ai/internal/tools/`、对 `paideia-knowledge` 的 `KnowledgeApi` 只读调用
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/AssistantToolsIntegrationTest.java`
- 步骤：
  1. 只读工具：检索条目、查分类、读**本人**标记与本人记忆。
  2. 工具参数里的任何 id 都在服务端**重新校验归属**，不信任模型给出的 id。
  3. 工具错误作为可观察结果回到模型，不写记忆。
- 验证：`mvn -B verify`（工具被调用的证据 + 越权 id 被拒的用例）。

### TASK-007 | done | 新建 paideia-note 模块与笔记 CRUD

- 对应：`REQ-011`、`AC-010`
- 依赖：无
- 修改：`backend/paideia-note/`（新模块，含 `NoteApi` 包根与 `internal/note`）、`backend/pom.xml`、`backend/paideia-app/pom.xml`、迁移新增 `notes` 表
- 测试：`backend/paideia-note/src/test/java/`、`backend/paideia-app/src/test/java/io/github/xiaou61/NoteIntegrationTest.java`
- 步骤：
  1. 按既有模块形状建模块（API 包根 + `internal` 子包），注册进聚合工程。
  2. `notes` 表：`user_id` 外键 CASCADE、可空 `entry_id` 挂靠条目。
  3. `/api/v1/notes/**` 的 CRUD，全部按 `user_id` 过滤；安全规则 `authenticated()`。
- 验证：`mvn -B verify`（模块边界 `ModularityTest` 仍通过；他人笔记不可读写）。

### TASK-008 | done | 笔记前端页与 AI 内容落进笔记

- 对应：`REQ-011`、`AC-010`
- 依赖：TASK-007、TASK-003
- 修改：`frontend/packages/`（笔记客户端）、`frontend/apps/app/src/pages/`（笔记页与入口）
- 测试：`frontend/apps/app/e2e/notes.spec.ts`
- 步骤：
  1. 笔记列表、创建、编辑、保存；挂靠条目的显示。
  2. 助手生成的正文可一键作为一段落进笔记（走用户点击确认）。
- 验证：`pnpm --filter @paideia/app test:e2e`（录入 → 编辑 → 刷新仍在 → 落进笔记）。

### TASK-009 | done | 写本人私有数据（确认端点）

- 对应：`REQ-008`、`AC-008`
- 依赖：TASK-004、TASK-002
- 修改：`.../ai/internal/tools/`（写工具只产出"草案"）、`.../ai/internal/web/`（确认端点）
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/AssistantWriteIntegrationTest.java`
- 步骤：
  1. 模型只产出"要写什么"的草案，**不直接执行**。
  2. 新增确认端点：用户点击后调它，服务端校验归属再写；记录来源（用户／助手）。
  3. 写本人标记与记忆；不能写他人数据。
- 验证：`mvn -B verify`（未确认时**零写入**的断言 + 确认后写入成功 + 越权被拒）。

### TASK-010 | done | 助手写笔记与"工具 × 结构化输出"分流

- 对应：`REQ-009`、`REQ-016`、`AC-008`、`AC-016`
- 依赖：TASK-007、TASK-009
- 修改：`.../ai/internal/tools/`（分流规则）、`.../ai/NoteDrafting.java`（端口）、跨模块调用 `NoteApi`
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/AssistantNoteDraftingIntegrationTest.java`
- 步骤：
  1. 在 `internal/tools` 固定**分流规则**：需要结构化取回时，**先**跑"无工具、只回 JSON"的一次调用并校验，**再**单独跑工具轮；两次调用不交叠。
  2. 助手写笔记经 `NoteApi`，只写本人笔记。
  3. 把这套分流写成可单测的纯判定 + 两种调用的集成用例。
- 验证：`mvn -B verify`（AC-016 核心证据：**同时使用工具**且需要结构化输出的场景能正确取回并校验）。

### TASK-011 | done | 分享：创建、撤回、免鉴权读取

- 对应：`REQ-012`、`AC-011`、`AC-012`
- 依赖：TASK-007
- 修改：`backend/paideia-note/internal/share/`、`internal/web/`（含免鉴权控制器）、`backend/paideia-security/src/main/java/io/github/xiaou61/security/SecurityConfiguration.java`（新增 `GET /share/**` permitAll，**限定 GET**）
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/NoteShareIntegrationTest.java`
- 步骤：
  1. `note_shares` 表：`share_id` 为 **UUID**、`expires_at`、`revoked_at`。
  2. 创建／撤回端点（本人）；`GET /share/{shareId}` **免鉴权**，读取时判 `revoked_at IS NULL AND expires_at > now`，失效统一 404。
  3. 产物过滤：只含笔记正文与条目的可公开部分，**不含**记忆、标记、作者身份。
  4. 静态放行列表与 view controller 同步（分享页是前端静态产物）。
- 验证：`mvn -B verify`（免登录可读、UUID 不可枚举、到期与撤回各自失效、产物不含痕迹）。

### TASK-012 | done | 分享前端（公开页 + 分享入口）

- 对应：`REQ-012`、`AC-011`、`AC-012`
- 依赖：TASK-011
- 修改：`frontend/apps/public/`（分享页）、`frontend/apps/app/src/pages/`（分享与撤回入口）
- 测试：`frontend/apps/public/e2e/`、`frontend/apps/app/e2e/notes.spec.ts`
- 步骤：
  1. 公开分享页从 `GET /share/{shareId}` 取内容渲染；失效显示统一的"不存在"。
  2. 学习者端的分享与撤回入口。
- 验证：`pnpm --filter @paideia/app test:e2e`（分享后免登录打开可见 → 撤回后不可见）。

### TASK-013 | done | 举报与管理员下架

- 对应：`REQ-013`、`AC-013`
- 依赖：TASK-011
- 修改：`backend/paideia-note/internal/report/`、`internal/web/`、`SecurityConfiguration.java`（`/api/v1/notes/admin/**` 的 `hasRole("ADMIN")` **写在更宽规则之前**）、`frontend/apps/admin/src/pages/`
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/NoteReportIntegrationTest.java`、`frontend/apps/admin/e2e/`
- 步骤：
  1. `note_reports` 表：举报人外键 CASCADE、`status`。
  2. 提交举报（`authenticated()`）；管理端列表与下架（写入 `revoked_at`，链接立即不可读）。
  3. 管理端页面：举报列表 + 下架操作。
- 验证：`mvn -B verify`（匿名 401 / 学习者 403 / 管理员放行三态）+ 管理端 e2e。

### TASK-014 | done | 注销级联硬删

- 对应：`REQ-014`、`AC-014`
- 依赖：TASK-001、TASK-004、TASK-007、TASK-011
- 修改：`backend/paideia-account/`（注销流程与逐表删除编排）、`paideia-ai` 与 `paideia-note` 的 API 包（各自的删除入口）、`frontend/apps/app/src/pages/`（注销确认文案）
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/AccountDeletionCascadeIntegrationTest.java`
- 步骤：
  1. **不用数据库外键**（理由见 `design.md`「注销级联硬删」的纠正）：注销流程按固定清单**逐表显式删除**。
  2. 清单：`ai_conversations`、`ai_messages`、`ai_memories_structured`、`ai_memories_text`、`notes`、`note_shares`、`note_reports`，**外加本项之前已有的** `ai_model_credentials`、`ai_entry_summaries`、`refresh_tokens`。
  3. 跨模块经各自 API 包提供的删除入口；账号模块不引用别人的表结构。
  4. 注销后本人凭旧令牌不可再访问（会话失效）。
- 验证：`mvn -B verify`（注销后**逐表断言行数为 0** —— 这张断言清单就是"删干净"的长期护栏；另加注销后访问被拒）。

### TASK-015 | done | 用量返还与限流收口

- 对应：`REQ-015`、`AC-015`
- 依赖：TASK-002
- 修改：`.../ai/internal/web/`（SSE 结束事件带用量）、`.../ai/internal/summary/SummaryRateLimiter`（或新增同形态的多轮限流器）
- 测试：`backend/paideia-app/src/test/java/io/github/xiaou61/AssistantUsageIntegrationTest.java`
- 步骤：
  1. 每轮把 `tokenUsage` 返还用户（沿用 WORK-005 的形态）。
  2. 多轮的按用户限流；**保持纯 BYOK**：无凭据用户不能触发生成。
- 验证：`mvn -B verify`（用量数值返还 + 无凭据被拒 + 超限被拒）。

### TASK-016 | done | 回归与整端验收

- 对应：全部 `AC-001`…`AC-016`
- 依赖：TASK-003、TASK-005、TASK-006、TASK-008、TASK-010、TASK-012、TASK-013、TASK-014、TASK-015
- 修改：无源码改动（补齐验证证据）
- 测试：`backend/paideia-app/src/test/java/`、`frontend/apps/*/e2e/`
- 步骤：
  1. **四条反向验证**：临时放行三态越权 → 用例须红；分享端点临时改为需登录 → 免登录用例须红；注销改为不级联 → 删除用例须红；建议流程改为不确认直接写 → "零写"用例须红。验证后 `git checkout --` 还原。
  2. **WORK-005 回归**：精简路径（单次调用、缓存命中、凭据不出响应）不因端口扩展而回归。
  3. 外环：后端全量 + 前端 typecheck/test + 四端 e2e。
- 验证：`mvn -B verify` + `pnpm -r test` + `pnpm -r typecheck` + `pnpm test:e2e:all`，全部退出码 0，并记录证据位置。

## 完成条件

- `AC-001`…`AC-016` 全部有"命令 + 退出码 + 结果 + 证据位置"的结构化证据。
- 四条反向验证都咬住，且临时改动已还原。
- WORK-005 的精简路径无回归。
- 迁移编号在实施时取的是下一个可用号，未与并发会话撞号。
- 安全规则：`/api/v1/notes/admin/**` 在更宽规则之前；`GET /share/**` 只放行 GET。
- 报告写入 `testing/report.md` 并声明 `verified_commit`；`testing/logs/` 证据文件按既有约定不入库。
