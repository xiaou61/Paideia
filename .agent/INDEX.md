---
artifact: project_index
status: active
updated: 2026-11-05
---

# 项目总索引

本页只负责按模块导航，帮助定位“代码改在哪里、依据和验证到哪里看”。工作项阶段、批准、任务进度和验证结果仍以对应工件及 `project-lifecycle.ps1 status` 输出为准，不在这里重复维护。状态、恢复和严格校验脚本属于已安装 Skill 的内部实现，不在目标项目 `.agent/scripts/` 中查找。

> 最近核对：2026-11-06。后端 12 个 Maven 模块、前端 16 个 workspace 包（11 个库 + 5 个可运行端）、18 篇 HTML 专题。

## 内容在哪里

| 内容 | 位置 |
| --- | --- |
| 项目常驻规范 | `.agent/rules/always.md` |
| 当前稳定规格 | `.agent/specs/`（目前为 `platform-contracts.md`） |
| 需求与变更依据 | `.agent/changes/WORK-编号-中文名/`（当前 WORK-001…WORK-075，共 74 项；无 WORK-008） |
| 决策说明与共享资料 | `.agent/notes/`、`.agent/references/` |
| 长期记忆 | `.agent/memory.md` |
| 代码审查报告 | `.agent/reviews/`（按 ref 区间归档） |
| 接口测试标准与长期台账 | `.agent/testing/`（每次运行一个文件）、`.agent/notes/api-testing-standard.md`（标准草案） |
| 项目理解型 HTML | `.agent/html/index.html`（入口，含 `nav.js`；18 篇专题） |
| 架构与流程图 | `docs/architecture/`（mermaid；四张结构图由 `scripts/gen-arch-diagram.mjs` 生成，见该目录 `README.md`） |
| 课程与阅读内容 | `content/courses/`、`content/reading/`、`content/source/` |

## 模块索引

只登记已经从仓库确认的模块和路径；发现新模块或路径发生实质变化时更新对应行。后端模块以 `backend/pom.xml` 为准，前端包以 `frontend/pnpm-workspace.yaml` 为准。工作项列只列代表，完整清单以 `.agent/changes/` 为准。

### 后端（12 个 Maven 模块）

| 模块 | 源码 | 关键内容 | 测试 | 代表工作项 |
| --- | --- | --- | --- | --- |
| 后端聚合工程 | `backend/pom.xml` | 12 个模块的依赖与构建编排 | — | WORK-001 |
| 通用契约 `paideia-platform` | `backend/paideia-platform/` | 跨模块共享契约与基础类型 | `src/test/java/io/github/xiaou61/platform/PlatformContractTest.java` | WORK-001 |
| Web 基础设施 `paideia-web` | `backend/paideia-web/` | Web 配置、CORS、静态资源转发 | `src/test/java/io/github/xiaou61/web/` | WORK-001 |
| 数据访问 `paideia-persistence` | `backend/paideia-persistence/` | 手写 MyBatis 数据访问、分页、通用持久化支撑 | `src/test/java/io/github/xiaou61/persistence/` | WORK-001 |
| 认证授权 `paideia-security` | `backend/paideia-security/` | JWT 认证、权限判定与安全配置（不认识账号表） | `src/test/java/io/github/xiaou61/security/` | WORK-001、WORK-003 |
| 账号与认证 `paideia-account` | `backend/paideia-account/`（`internal/{credential,login,mail,oauth,profile,registration,token,tools,user}`） | 注册登录、图形/邮箱验证码、OAuth 第三方登录与账号绑定、refresh 轮换 | 模块内测试；集成测试 `paideia-app`：`AccountAuthIntegrationTest`、`AccountDeletionCascadeIntegrationTest` | WORK-003、WORK-037 |
| 知识库 `paideia-knowledge` | `backend/paideia-knowledge/`（`internal/{admin,assessment,cache,category,concept,entry,file,learning,plan,question,relation,track,user,web,tools}`） | 分类/条目/附件、知识点图谱与掌握度、题库与练习选择、学习进度与复习计划、学习轨道 | 模块内 25 个测试类；集成测试 `paideia-app`：`KnowledgeReadIntegrationTest`、`KnowledgeAdminIntegrationTest`、`KnowledgeFileIntegrationTest`、`KnowledgeSelfAssessmentIntegrationTest` | WORK-004、WORK-021、WORK-033、WORK-034、WORK-046、WORK-053、WORK-057 |
| 个人笔记 `paideia-note` | `backend/paideia-note/`（`internal/{api,note,share,tools,user,web}`） | 个人笔记、分享链接 | 模块内 `internal/tools` 测试；集成测试 `paideia-app`：`NoteIntegrationTest`、`NoteShareIntegrationTest`、`NoteReportIntegrationTest` | WORK-006 |
| AI 与助手 `paideia-ai` | `backend/paideia-ai/`（对外契约在包根：`AssistantConversation`、`EntrySummarizer`、`MemoryExtractor` 等；实现分 `internal/{conversation,memory,settings,summary,billing,credential,provider,camp,decision,diagnosis,digest,essay,feedback,interview,prompt,question,rag,resume,review,sql,telemetry,tools,user}`） | AI 对话与流式、记忆两路与自动整理、摘要、积分计费、AI 训练营、每日速递、面试/简历/作文/题解等能力、AI 观测 | 模块内 71 个测试类；集成测试 `paideia-app`：`MemoryAutoExtractionIntegrationTest`、`LearnerMemoryIntegrationTest`、`AssistantConversationIntegrationTest`、`AiCreditIntegrationTest`、`AiSummaryIntegrationTest`、`MemoryExtractionJobsIntegrationTest` 等 | WORK-005、WORK-009、WORK-013、WORK-018、WORK-022、WORK-029、WORK-031、WORK-050、WORK-067 |
| 代码执行沙箱 `paideia-sandbox` | `backend/paideia-sandbox/`（`internal/{audit,dataset,run,trace,tools,user,web}`） | 代码执行（Docker/Shell）、SQL 容器池、SQL 数据集与 AI 生成、执行轨迹 | 模块内 `internal/{dataset,run,web}` 测试（含 `SqlContainerPoolTest`、`SqlStatementGuardTest`） | WORK-015、WORK-023、WORK-032、WORK-052、WORK-055、WORK-060 |
| MCP 网关 `paideia-mcp` | `backend/paideia-mcp/`（`internal/{security,token,usage,web}`） | MCP 工具面与视图注册、个人访问令牌（PAT）、调用用量记录 | 模块内测试：`McpDispatcherServletTest`、`McpToolInvokerTest`、`PatServiceTest` 等 | WORK-019、WORK-039、WORK-044 |
| 运维与 AIOps `paideia-ops` | `backend/paideia-ops/` | 运维摘要、Analytics、事件与自诊断、Loki/Prometheus/Tempo/Grafana 代理 | `paideia-app` 的 `ops` 相关测试（`OpsDiagnosisIntegrationTest`、`OpsFeedbackIntegrationTest` 等） | WORK-028、WORK-042、WORK-043、WORK-045 |
| 启动模块 `paideia-app` | `backend/paideia-app/`（`console` 应用装配；`src/main/resources/db/migration` 生产结构迁移、`db/devdata` 测试/开发数据） | 主应用与集成装配、迁移与种子隔离、模块边界与授权守卫 | `src/test` 87 个测试类（含 `ModularityTest`、迁移三件、`ApiContractTest`、`AuthorizationIsolationTest`） | WORK-001、WORK-003、WORK-017、WORK-061 |

### 前端（11 个库包 + 5 个可运行端）

| 包/端 | 路径 | 关键内容 | 测试 | 代表工作项 |
| --- | --- | --- | --- | --- |
| 设计系统 `@paideia/ui` | `frontend/packages/ui/` | `styles/globals.css` 是令牌唯一来源；`components/` 基元、`theme/` 主题与首屏脚本、`layout/` 外壳、`markdown/` 是 markdown 渲染的唯一实现 | `src/tokens.test.ts`、`src/theme/contrast.test.ts`、`src/theme/theme-context.test.tsx`、`src/markdown/markdown.test.tsx`、`src/styles/source-coverage.test.ts`、`src/styles/streamdown-tokens.test.ts` | WORK-001、WORK-002、WORK-012、WORK-016 |
| 端口与 API 客户端 `@paideia/core` | `frontend/packages/core/` | 平台端口、API 客户端、通用格式化；`session.ts` 是角色判断的唯一入口 | `src/api.test.ts`、`src/workspace-boundaries.test.ts`、`src/session.test.ts` | WORK-001、WORK-002 |
| 共享认证 `@paideia/auth` | `frontend/packages/auth/` | 令牌存储、401 刷新重放、登录/注册表单、OAuth 按钮、登录门 | `src/auth.test.ts`、`src/auth-context.test.tsx` | WORK-003、WORK-037 |
| 身份化配置 `@paideia/identity` | `frontend/packages/identity/` | 按身份决定首页推荐分类、`preferredNav` 导航顺序、助手开场白；**不决定任何权限** | `src/config.test.ts` | WORK-010 |
| AI 能力共享包 `@paideia/assistant` | `frontend/packages/assistant/` | 条目摘要卡、积分面板、数据集引导卡（`GuideCard`）、SSE 客户端；**通用对话面板与记忆面板已下线** | `src/summary-card.test.tsx`、`src/sse.test.ts` | WORK-005、WORK-006、WORK-011、WORK-013、WORK-058、WORK-066 |
| 知识库共享包 `@paideia/knowledge` | `frontend/packages/knowledge/` | 类型、学习者侧 `client.ts` 与管理侧 `admin-client.ts`、`markdown-body.tsx` 薄封装（渲染器在 `@paideia/ui`）、正文目录、关系邻域、图谱布局、学习进度、习题种子与推荐 | `src/*.test.ts`（13 个，含 `category-tree`、`concept-graph-layout`、`learning-progress`、`source-reading-progress`） | WORK-004、WORK-021、WORK-024、WORK-033、WORK-034、WORK-053、WORK-057 |
| 训练营包 `@paideia/camp` | `frontend/packages/camp/` | 类型与客户端 `client.ts`、薄弱点/主题表/并排对比 `weakness.tsx`、每日计划 `plan.tsx` | `src/components.test.tsx`；e2e `apps/app/e2e/camp.spec.ts` | WORK-029 |
| 简历包 `@paideia/resume` | `frontend/packages/resume/` | 简历 DTO/API、自动保存状态机、模板与差异渲染 | `src/autosave.test.ts`、`src/diff.test.tsx`、`src/templates.test.tsx` | WORK-018 |
| SQL 工作台包 `@paideia/sql-workbench` | `frontend/packages/sql-workbench/` | SQL 编辑器、结果表/图、EXPLAIN 树、数据集设计与 schema 视图 | `src/*.test.ts(x)`（6 个，含 `explain`、`result-tables`、`dataset-designer`） | WORK-032、WORK-035、WORK-056、WORK-060 |
| Web 平台适配 `@paideia/platform-web` | `frontend/packages/platform-web/` | Web 端本地存储等平台能力 | — | WORK-001、WORK-002 |
| 桌面平台适配 `@paideia/platform-desktop` | `frontend/packages/platform-desktop/` | 桌面端平台能力；`createBridgeCache` 由启动快照初始化同步镜像 | `src/index.test.ts` | WORK-001、WORK-002 |
| 学习者端 `@paideia/app` | `frontend/apps/app/` | Web 与桌面共用产物，端口 5173；35 个页面级组件（学习、进度、复习、知识库、笔记、训练营、面试、简历、图谱、速递、SQL 练习、源码精读等） | `e2e/` 23 个 spec（home、knowledge、notes、graph、assistant、markdown-render、resume、identity、navigation、layout.mobile、learn、pwa、books、camp、digest、oauth、progress、question-attempt、review、sketch、source、source-reading、sql-practice） | WORK-001、WORK-002 及绝大多数功能工作项 |
| 管理端 `@paideia/admin` | `frontend/apps/admin/` | 仅 Web，端口 5174；Hash 路由 + `components/AdminShell.tsx`；知识库分类/条目/概念、用户、报表、SQL 数据集、AI 运行与积分、运维、Analytics | `e2e/` 10 个 spec（admin、knowledge、users、concepts、ai-runs、analytics、feedback、incidents、ops、layout.mobile） | WORK-002、WORK-004、WORK-030、WORK-039、WORK-044、WORK-045 |
| 公开页 `@paideia/public` | `frontend/apps/public/` | 预渲染静态页（SEO surface），不引 `core` 的 API 客户端、不用 `AppShell` | — | WORK-001、WORK-002 |
| 组件展览 `@paideia/ui-kit` | `frontend/apps/ui-kit/` | 仅开发与内审，端口 5175，不进任何产品产物 | `e2e/ui-kit.spec.ts` | WORK-002 |
| 桌面壳 `@paideia/desktop` | `frontend/apps/desktop/` | Electron 主进程与 preload 在 `electron/` | `e2e/desktop.spec.ts`（本机环境未运行） | WORK-001、WORK-002 |

## HTML 理解材料

将用于解释架构、流程、状态机、数据流或交互的独立 HTML 放在 `.agent/html/`，但必须先取得用户明确同意。新增保留文件后，在这里补充名称、用途和相对路径；这类材料不替代源码、测试或批准工件。

当前入口为 `.agent/html/index.html`（含 `nav.js` 导航），共 18 篇专题：

- **系统怎么跑（01–14）**：全景、一次请求的一生、AI 对话与积分、记忆的写与读、学习闭环与掌握度、内容生产线、SQL 工作台与代码沙箱、前端与流式渲染、可观测与发版、AIOps 自诊断、信任边界、简历与求职、交互式内容与可视化调试、MCP 工具面。
- **代码怎么写（15–18）**：代码规范、测试规范、提交评审与发布、文档与决策纪律。

## 查看一次变更

| 想确认什么 | 查看位置 |
| --- | --- |
| 为什么要改、验收什么 | 对应工作项的 `requirements.md` |
| 为什么选择这种方案 | `proposal.md`、`design.md` |
| 实际改哪些步骤 | `tasks.md` |
| 当前未提交文件有哪些 | `git status` 与 `git diff` |
| 如何证明改对 | `testing/plan.md`、`testing/report.md` |
| 交付后项目应保持什么行为 | `.agent/specs/` |
| 这批改动审出什么问题 | `.agent/reviews/`（按 ref 区间归档） |
| 模块依赖、请求链路、部署拓扑、表关系、接口面 | `docs/architecture/README.md`（六张 mermaid 图，其中四张结构图由脚本生成） |
