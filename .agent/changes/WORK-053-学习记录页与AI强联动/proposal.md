---
artifact: proposal
work_id: WORK-053
work: 学习记录页与 AI 强联动（试点）
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 14:03:13 +0800
approver_role: CTO
---

# 学习记录页与 AI 强联动提案

## 摘要

本项**一个工作项做完全部**（用户 2026-10-29 决定）：学习记录页、统一的"页面 → 助手 → 工具 → 回到页面"链路、
计划落库、以及把 6 组"AI 生成"入口（精简 / SQL 造数据集 / SQL 出题 / 训练营与复习 / 简历提案 / 推荐理由）逐条迁过来。

内部按阶段交付，**阶段只影响实施顺序，不拆工作项**：阶段一先立链路（公共设施 + 页面 + SQL 造数据集作样本），
之后每个阶段迁一组入口并独立验证。

## 推荐方案

**复用已有的三条原语，只补一处缺口。**

| 方向 | 现成的东西 | 本期怎么做 |
| --- | --- | --- |
| 上行 | `setPageContext` 只有一个 `sql-workbench` 变体 | 加 `study-log` 变体（四块数据的截断快照），沿用 `serializePageContext` |
| 请求 | `ask(text)` 打开并预填、不发送 | 三个入口都用它 |
| 下行 | `applySql` 的"待消费一次"通道 | 生成类工具**只返回草稿**，页面用同形通道消费 |
| 场景 | `Harness` 四档 + `toolsetsOf(harness)` | 加 `PLANNER` 一档，只加一行组清单 |

**核心机制：内部助手工具保持只读。** `ToolQuery.internal(...)` 把作用域硬编码成 `Set.of(ToolScope.READ)`（WORK-006 的裁决：助手不替用户写）。所以：
- `sql_generate_dataset` 是一个**只读工具**：返回生成好的 DDL/INSERT 文本，**不落库**；
- 落库仍走页面现有的"粘贴 SQL → 白名单校验 → 沙箱干跑 → 保存"那条路，校验一点不放松；
- 计划项同理：助手给候选，"采用"是用户在页面上的动作。

## 范围

**做**（全部在本项内，按阶段推进）：
1. 公共设施：`PLANNER` 场景、`study-log` 页面上下文、回填通道、降级兜底；
2. `/progress` 页面 + 导航入口 + 三个 AI 入口（在路线上才有意义）；
3. 入口迁移：SQL 造数据集（样本）、SQL 出题、精简、训练营与复习、简历按岗位优化、推荐理由；
4. 计划落库（表 + 采用 + 勾选），接在 2 之后。

**不做**：简历识别（没法引导 OCR）、面试点评（结构化评分一轮更稳更省）、后台任务型 AI（速递 / AIOps / 记忆提取）、前端内容线。

## 备选方案

| 方案 | 最强理由 | 没选的原因 |
| --- | --- | --- |
| 给 `PLANNER` 开写权限，让工具直接落库 | 一步到位，"工具生成"闭环最顺 | 推翻平台级只读约束（`ToolQuery.internal`），等于让模型替用户写数据；本项只需生成文本，"落库"用页面已有路径更安全 |
| 不复用 `PageContext`，页面自己拼一段上下文文本塞进 `ask()` | 不动共享包 | 前端拼自由文本会绕过 `PAGE_CONTEXT_LIMITS` 截断，也把"哪些内容会进模型"变成散落在页面里的判断 |
| 新开独立的工作项做整条链 | 与试点页解耦 | 试点页正是这条链的落地场景；分开会先造基础设施再找用法 |

## 仓库影响

| 路径 | 改动 |
| --- | --- |
| `frontend/packages/assistant/src/context.tsx` | `PageContext` 加 `study-log` 变体；`AssistantHarness` 加 `planner` |
| `frontend/packages/assistant/src/page-context.ts` | 序列化与长度上限 |
| `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/Harness.java` | 加 `PLANNER("planner")` |
| `backend/paideia-ai/.../conversation/HarnessPrompts.java` | planner 提示词（先问、别伪造结果） |
| `backend/paideia-ai/.../tools/AgentToolBridge.java` | `toolsetsOf` 加一档 |
| `backend/paideia-sandbox/.../dataset/SqlDatasetTools.java` | 加 `sql_generate_dataset`（只读，返回 SQL 草稿） |
| `frontend/apps/app/src/pages/ProgressPage.tsx` | 新建页面 |
| `frontend/apps/app/src/pages/SqlPracticePage.tsx` | 「让 AI 造」改成 `ask()` |
| `frontend/apps/app/src/router.tsx`、`components/LearnerHeader.tsx` | 路由 + 导航入口 |

无迁移、无接口形状变化。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| 多轮引导让积分消耗翻倍 | 提示词约束"能用默认值就用默认值、最多问三个问题"；入口文案标明消耗积分 |
| 模型漏调工具 → 页面拿不到结果 | 页面保留原"直接生成"按钮作为降级；生成失败时给可点的重试 |
| `planner` 场景看到不该看的工具 | 只给只读组（knowledge / learning / assessment / assistant），不给 admin |
| 四块数据来源不一致，页面数字与 /learn 打架 | 全部走既有客户端方法，不在前端二次计算 |

## 验收映射

| 需求 | 落点 |
| --- | --- |
| REQ-001 / REQ-002 | 页面 + 四块 + 导航 |
| REQ-003 | 三个入口 |
| REQ-004 | `study-log` 变体 + `PAGE_CONTEXT_LIMITS` |
| REQ-005 | `PLANNER` 提示词 |
| REQ-009 | SQL「让 AI 造」改造 |
| REQ-010 | 只读工具 + 页面落库 |
| REQ-006 / REQ-007 | 计划表 + 采用 + 勾选（阶段四） |

## 待决定事项

- **Q-001 计划表形态**：随阶段四开工时定（新增最小表 / 复用训练营计划 / localStorage），我倾向新增最小表；不影响阶段一至三。
- Q-002 已决定新增 `planner`；Q-003 页面驱动不做。
