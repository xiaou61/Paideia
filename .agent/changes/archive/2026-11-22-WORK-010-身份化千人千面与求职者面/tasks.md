---
artifact: tasks
work_id: WORK-010
work: 身份化千人千面与求职者面
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-004, WORK-006, WORK-009]
created: 2026-10-15
updated: 2026-10-15
approved_by: xiaou61
approved_at: 2026-10-15 17:57:35 +0800
approver_role: CTO
---

# 任务 · 身份化千人千面与求职者面

**排序原则**：先把"身份"作为数据立起来并能在界面上切换（此时对既有功能零影响），再把 AI 行为扩成可切换的 harness（此时仍是通用助手行为，只是路径支持多套），再补"自评 → 记忆"这座桥（与面试无关、可独立验收），最后做面试（面试官 → 点评 → 评估落库），收尾做推荐配置与部署。**每一步都保证系统可用**，且前三步不依赖面试能跑通。

> **开工前置（硬）**：本项 `depends_on: WORK-009`，**`WORK-009` 未实施完成前不得开工**（`validate` 会拦）。另：`WORK-009` 落地后普通用户积分为 0 且不做充值，**验收前必须由管理员给验收账号发放积分**。

| # | 任务 | 依赖 | 验证 |
| --- | --- | --- | --- |
| T-01 | `learner_profiles` 迁移（身份 + 求职者画像字段，编号取仓库当前最大号的下一个）；身份枚举（一期只有 `job_seeker`，其余留位） | — | 迁移在空库与既有库上都能跑；枚举单测 |
| T-02 | `AccountApi` 加读当前身份的方法 + 实现；账号不存在时返回空 | T-01 | 单测/集成：新建用户读到默认身份；不存在返回空 |
| T-03 | 身份读写端点（读画像 / 改身份） + 学习者端设置页入口 | T-02 | e2e：改身份后重新进设置页读回新值；**改动前后该用户的 `role` 与权限判定不变**（AC-001） |
| T-04 | harness 注册表（key → 提示词 + 工具集）；新增 `InterviewPrompt` / `ReviewPrompt`，`ConversationPrompt` 不动 | — | 单测：注册表三套齐全；`assistant` 那份提示词与既有逐字一致（回归） |
| T-05 | `AssistantConversation.Request` 加 `harness`；`AgentScopeConversation` 按 harness 选提示词 | T-04 | 集成：同一句输入在 `assistant` 与 `interview` 下命中不同提示词（以请求构造断言） |
| T-06 | 工具分层：`AssistantTools` 按 harness 返回工具子集（通用组给全部；`review` 给空） | T-05 | 单测：`review` 的 schema 名称集合为空；`assistant` 与 `interview` 为通用组四个（AC-006） |
| T-07 | `ai_conversations` 加可空 `harness` 列；创建会话时写入；恢复会话时按它选行为 | T-05 | 集成：既有会话（NULL）行为不变；面试会话从通用入口继续仍走面试行为 |
| T-08 | **自评 → 结构化记忆的桥**：`ai_memories_structured` 加唯一键 `(user_id, kind, ref_id)`；PUT 时 upsert、DELETE 时清理 | — | 集成：改自评 → 出现一条记忆；**反复改动后该条目仍只有一条且内容是最后一次**（AC-008）；取消自评 → 记忆消失 |
| T-09 | 面试官：面试页 + 多轮追问（复用流式与思考折叠）；**题源为知识库已发布条目**；**用户笔记默认不参与、需显式勾选**；开场白走身份配置包；会话与消息复用既有表 | T-06, T-07 | e2e：能完成"开场 → 至少一轮追问 → 用户作答"；**未勾选时请求里不含任何笔记内容**；中途退出不留脏数据（AC-009 前半） |
| T-10 | 点评官：`harness=review` 的独立调用 + 结构化评分解析 | T-06 | 集成：点评请求的工具集为空、返回可解析的结构化评分、字段齐全（AC-007） |
| T-11 | `ai_interview_evaluations` 迁移 + 落库 + 结果展示（本人可见） | T-10 | e2e：完整走完面试 → 评分落库且界面上能看到（AC-009 完整闭环） |
| T-12 | 推荐配置包（identity → 分类/首页卡片/导航顺序/助手开场白）+ 首页与导航按身份重排 | T-03 | e2e：改身份后首页卡片与导航顺序变化（AC-002）；**记忆/笔记/自评条目数不变**（AC-003/AC-004） |
| T-13 | 注销级联登记 `learner_profiles` 与 `ai_interview_evaluations` | T-01, T-11 | 既有"注销后逐表断言行数 0"用例扩展后通过（AC-011） |
| T-14 | 部署：线上启用身份与面试；**先由管理员给验收账号发积分** | T-09, T-11, T-12, T-13 | 线上真跑一次完整面试并看到扣减与评分；0 积分账号看到明确提示而非报错（AC-010） |
| T-15 | 文档：身份与 harness 的模型（一次对话 = 公共行为 × 私有数据）、注册表留位的身份、两处已知缺口（单身份不可端到端验收、`identityDefault` 恒为 assistant）写进 `.agent/notes/dev-workflow.md` 与 `always.md` | T-14 | 照文档跑一遍，命令与实际一致 |

## 验收要点（对应 AC）

- **AC-001 身份与角色正交**（T-03）——本项最重要的一条护栏。
- **AC-002/003/004 切换身份生效且不丢数据**（T-12）。
- **AC-005/006 harness 选择与工具不外泄**（T-05/T-06）。
- **AC-007 点评是独立无工具调用**（T-10）——同时是对 DeepSeek 那条限制的绕开方式的证明。
- **AC-008 自评桥是覆盖语义**（T-08）。
- **AC-009 面试闭环**（T-09/T-11）、**AC-010 零积分语义**（T-14，依赖 WORK-009）、**AC-011 注销清零**（T-13）。

## 风险与默认处置

- **硬依赖未满足**：`WORK-009` 未完成前**不开工**（`validate` 拦着）。本项工件可先写好，实施等它。
- **一期只有单身份 → 两条验收无法端到端走**（多身份切换、身份默认行为不同）。默认处置：在测试计划里**显式写成已知缺口**，用"人工造第二个身份"覆盖数据层，不用假界面糊过去。
- **面试成本**：多轮带工具 + 一次点评，单次会话 token 成倍。默认处置：`WORK-009` 已提供单次调用积分上限与请求频次限制；本项不额外加轮次上限，**先看线上真实消耗再定**（真出现单次面试吃掉大量积分，再加轮次或题量上限，属 `WORK-009` 的配置而非本项代码）。
- **改 `Request` 会波及既有调用方**：与 `WORK-009` 改 `Listener` 是同一类风险——**先让既有用例全绿再往下**。
- **`ai_memories_structured` 加唯一键**：既有数据理论上都是追加式且 `ref_id` 多为 NULL，唯一键对 NULL 不去重，**加键前要先核一遍线上/测试库有没有冲突行**（有则先清理，见 T-08 的验证）。

## 任务状态（执行记录，2026-10-18 补）

> 计划原文未改。按实际执行结果逐条记账；与计划不一致处就地写明。

### T-01 | done | `learner_profiles` 迁移 + 身份枚举
`V14__create_learner_profiles.sql`（编号取当时最大号 +1）。`Identity` 枚举一期只有 `job_seeker` 实心，其余三个 `selectable()=false`、接口写不进去。

### T-02 | done | `AccountApi` 读身份 + 画像
`findProfile` / `saveProfile`；**有账号没画像行返回默认值**（否则每个新用户都要在注册链路插一行，漏插就是"静默地没有身份"）。

### T-03 | done | 身份读写端点 + 设置页
`GET/PUT /api/v1/account/profile`（`anyRequest().authenticated()` 兜住，路径无角色限制）；`/profile` 页明写"身份不影响你能做什么"。

### T-04 | done | harness 注册表 + 两份新提示词
`Harness` + `HarnessPrompts`（穷尽 switch，**无 default**）+ `InterviewPrompt` / `ReviewPrompt`；`ConversationPrompt` **一字未改**。

### T-05 | done | `Request` 加 harness，按它选提示词
`Harness` 进 `Request`（旧构造自动用 `assistant`）；`AgentScopeConversation` 由它选系统提示词。

### T-06 | done | 工具分层
`AssistantTools.schemas(Harness)`：`ASSISTANT`/`INTERVIEW` 给通用组，`REVIEW` **空集**。

### T-07 | done | 会话记 harness
`ai_conversations.harness` 可空；`create(userId, entryId, harness)`；恢复时 `Harness.ofOrDefault(conversation.getHarness())`——**行为跟着会话走**。

### T-08 | done | 自评 → 结构化记忆的桥
`EntryAssessmentChangedEvent` + `@TransactionalEventListener(BEFORE_COMMIT)`；唯一键 `(user_id, kind, ref_id)` + `ON DUPLICATE KEY UPDATE`。**加键前已核过两个库无冲突行**。

### T-09 | done（界面） | 面试页 + 多轮追问
`/interview`：一次一个问题、思考折叠、会话建时钉死 `harness=interview`。**笔记默认不参与**——当前是"一期根本不送"，比设计更保守；**勾选控件尚未做**（见报告的未验证项 2）。

### T-10 | done | 点评官：独立调用 + 结构化评分
`InterviewReviewer` 端口（非流式、**不经过工具循环**）+ `InterviewEvaluationParser`（7 条单测）。

### T-11 | done | 评估落库 + 展示
`ai_interview_evaluations` + `INTERVIEW_REVIEW` 独立流水种类；前端 `EvaluationCard` 展示总分/维度/改进点。

### T-12 | done | 推荐配置包 + 首页与导航重排
新包 `@paideia/identity` 的 `config.ts`（四套配置，各有真实差异）；首页与页头共用"配置只表达谁排前面、没提到的追加在后面"这条规则。

### T-13 | done | 注销级联
`AiUserDeletionListener` 加 `ai_interview_evaluations`；`AccountDeletionService` 直接删 `learner_profiles`（同库同域，不绕事件）；级联测试的清单与夹具都补了。

### T-14 | done | 部署（线上启用身份与面试）
2026-10-19 完成：发版到 codenest-online（生产库 V16→V19）；线上真人账号走完
"开场 → 追问 → 点评 → 评分落库 → 扣费"，`overall=2`（三维度 3/3/2）、
`ai_interview_evaluations` id=1、流水 `SETTLE -1` + `INTERVIEW_REVIEW -2`。
**顺带修掉一个线上必现缺陷**：`maxTokens=1024` 使点评 JSON 被思考 token 截断
（见 testing/report.md 补验段）。线上学习者端 e2e 60/22/0、管理端 20/0。
**本机验收已完成（2026-10-19）**：在隔离库 `paideia_verify_010` 上由管理员给 `seed-learner` 发 5000 分，
真连 DeepSeek 走完"开场 → 追问 → 点评 → 评分落库 → 看到扣减"，AC-009 不再被"余额 0"卡住
（详见 `testing/report.md` 的补验段）。**剩下的只有"部署到线上并在线上再走一次"**——
本机验证不能替代线上形态（systemd + 8081）的复跑，所以这一行保持 pending，不提前记 done。

### T-15 | done | 文档
`.agent/notes/dev-workflow.md` 新增「身份化与模拟面试」一节（含身份≠角色、事件桥为什么不能直接调用、唯一键要先查冲突行、首页排序别让固定卡被去重丢掉）。
