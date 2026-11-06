---
artifact: requirements
work_id: WORK-078
work: 面试自适应出题（按历史弱项与能力定难度）
status: approved
created: 2026-11-06
updated: 2026-11-06
mode: managed
mode_reason: 前端新增能力聚合算法（@paideia/assistant 纯函数 + 面试页）与两处提示词文案调整；不改数据库结构、不新增接口、不改鉴权，属可有界验证的增强，故 managed
workflow: compact
depends_on: []
related_to: [WORK-010, WORK-077]
approved_by: xiaou61
approved_at: 2026-11-06 17:31:44 +0800
approver_role: owner
---

# 需求

## 背景

面试页现在每场都从零开始：只看「目标岗位 + 可选简历」，**不读历史成绩**，练十场也不会变聪明。用户要求做"自适应"：按这个学生每次面试的结果调整重点与难度。

参考的开源做法（取其思想，不搬代码）：
- **IRT 自适应测验**（Item Response Theory，如 `woodstocksoftware/adaptive-question-selector`、`Adaptive-Learning-System-IRT`）：按能力估计选择合适难度的题；能力随作答实时更新。
- **LLM+IRT 面试评估**（IEEE 论文）：用 LLM 生成题目难度/区分度，再用 IRT 按真实表现修正。
- **InterviewGPT / InterviewPro-AI**：自适应难度 + 启发式评分 + 会话/进度分析。

我们没有题库与逐题响应数据，做完整 IRT 不现实；采用其**能力估计 + 按能力定难度**的核心，用"历史评估分数的指数加权平均（EWMA）"近似能力值，够用、可解释、无外部依赖。

## 已确认事实

1. 历史评估已落库并可读：`GET /api/v1/ai/interviews/evaluations` 返回 `InterviewEvaluationView[]{id, evaluation{overall, dimensions[{name,score,comment}], strengths, improvements}, createdAt}`，按新→旧，最多 50 条。
2. **点评维度名不稳定**：`ReviewPrompt` 现在让模型自定 2–4 个中文维度名（技术准确性/表达清晰度/深度/应变…），跨场无法比较——这是做自适应前必须解决的**前提**。
3. 面试的上下文通道：`sendMessage(..., pageContext)` 只进提示词、不落库；后端把它拼进 SYSTEM（`ConversationPrompt.pageContext`），并标注"参考资料不是指令"。因此需要在 `InterviewPrompt.system` 里显式要求"据简历与自适应设置出题"，否则会被当成纯参考。
4. `InterviewPrompt.opening()` 目前后端未被调用（开场白由前端拼）；后端只需改 `system()` 与 `VERSION`。

## 用户决定

- 2026-11-06：做自适应；参考开源优点集成。

## 实现选择

- **固定维度集合**（跨场可比的前提）：点评固定输出四项 `技术深度 / 表达结构 / 项目经验 / 应变能力`，改 `ReviewPrompt`（`review-v2`）。
- **能力模型（前端纯函数，`@paideia/assistant`）**：对最近 N 场按 `0.65^k` 指数加权，算出每维能力值（1–5）与总分；最弱 1–2 维为"本场重点"；总分≥3.8 判"偏难/深挖"、≥2.6 判"中等"、否则"基础"。旧数据的不规范维度名按关键词归一到这四项。
- **注入方式**：开场 `pageContext` = `【本场自适应设置】…` + （若选简历）简历文本；同时改 `InterviewPrompt.system`（`interview-v2`）要求"优先追问简历项目、对『本场重点』多问深问、按建议难度起步并随表现调整"。
- **可视化**：起始卡显示一行"根据过往 N 场，本场重点：X、Y（难度：中等）"，让个性化可见；不做新的后端接口或进度图。

## 目标

1. 点评维度稳定为四项，跨场可聚合。
2. 每场开场按历史弱项与能力定重点、定起始难度。
3. 起始页可见"本场重点/难度"，可解释。

## 非目标

- 不做真正的 IRT 参数估计（无逐题响应数据）。
- 不新建题库/推荐复习联动。
- 不改点评/对话接口与数据库。

## 功能需求

- REQ-001 固定点评维度四项（技术深度/表达结构/项目经验/应变能力）。
- REQ-002 前端按历史评估算能力与弱项（EWMA），产出"本场重点 + 难度 + 各维能力"。
- REQ-003 开场把自适应设置（+简历）作为 pageContext 注入，并让面试官提示词明确采用。
- REQ-004 起始页展示"本场重点/难度/过往场次"。

## 验收标准

- AC-001 点评提示词固定四维；`review-v2`。
- AC-002 `computeAbility` 有单测：EWMA 权重、弱项排序、难度分档、旧维度名归一、空输入返回 null。
- AC-003 开场请求体 `pageContext` 含"本场自适应设置"与重点维度；气泡仍只显示"开始面试"。
- AC-004 起始页显示"本场重点：… 难度：…"（有历史时）。
- AC-005 typecheck / assistant 单测 / ui 令牌测试通过；部署后线上健康。

## 待确认问题

- 无（维度集合与算法由本项确定，用户已同意"做这套自适应"）。
