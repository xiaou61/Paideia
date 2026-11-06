---
artifact: tasks
work_id: WORK-078
work: 面试自适应出题（按历史弱项与能力定难度）
status: approved
created: 2026-11-06
updated: 2026-11-06
approved_by: xiaou61
approved_at: 2026-11-06 17:32:15 +0800
approver_role: owner
---

# 实施任务

## 依据

`requirements.md`（WORK-078，managed/compact）。

## 全局约束

- 维度名前后端**字面一致**：`技术深度 / 表达结构 / 项目经验 / 应变能力`。
- 能力算法放 `@paideia/assistant` 纯函数并带单测；页面只做展示与拼接。
- 不改接口/库表/鉴权；颜色只用令牌。

## 评审焦点

| 失败模式 | 归属任务 | 验证 |
| --- | --- | --- |
| 维度名仍由模型自造，聚合失效 | TASK-001 | 提示词固定四维；`review-v2` |
| EWMA 权重方向错（旧场压新场） | TASK-002 | 单测：近期低分应拉低能力 |
| 自适应设置被当"参考资料"忽略 | TASK-003 | `InterviewPrompt` 明确"据简历与设置出题" |
| 空历史也注入噪声 | TASK-002/003 | 无历史时 `computeAbility` 返回 null、不注入 |

## 任务

### TASK-001 | done | 固定点评维度
- 对应：REQ-001、AC-001
- 改动：`ReviewPrompt.system()` 固定四项维度名；`VERSION = review-v2`。
- 验证：解析器 `MAX_DIMENSIONS=4` 兼容；编译通过。

### TASK-002 | done | 能力聚合纯函数 + 单测
- 对应：REQ-002、AC-002
- 新建：`frontend/packages/assistant/src/adaptive.ts`、`adaptive.test.ts`；`index.ts` 导出。
- 内容：`INTERVIEW_DIMENSIONS`、`computeAbility(views)`（EWMA 0.65^k、弱项排序、难度分档、旧名归一）、`adaptiveContextText(brief, advice)`。
- 验证：`pnpm --filter @paideia/assistant test` 全绿。

### TASK-003 | done | 面试页接入 + 提示词采用
- 对应：REQ-003、REQ-004、AC-003、AC-004
- 改动：`InterviewPage.tsx` 用 `computeAbility(history.data)` 生成 brief；开场 `pageContext` 拼「自适应设置 + 简历」；起始卡显示"本场重点/难度/场次"。`InterviewPrompt.system()` 增规则并 `interview-v2`。
- 验证：mock 抓 `pageContext` 含"本场自适应设置"；截图可见重点标签。

### TASK-004 | done | 历次面试详情 + 对话回放
- 对应：REQ-005、AC-006
- 改动：后端 `EvaluationView` 增加 `conversationId`（含 `listMine`/`review` 与测试夹具）；前端类型加字段；`HistoryCard` 起始页与侧栏都显示，点条目弹 `HistoryDetailDialog`，含完整报告 + `assistant.messages(conversationId)` 回放。
- 验证：mock 详情弹窗、回放文本可见；截图 08/09。

### TASK-005 | done | 弱项联动知识库复习
- 对应：REQ-006、AC-007
- 改动：`ReviewSuggestions` 取最弱 1–2 维（score<4），按关键词（技术深度→Java、项目经验→项目、表达/应变→面试）调 `knowledgeApi.entries({q,size:3})`，列出可点条目（`/knowledge/entries/:id`）。
- 验证：mock 知识库条目，详情内可见"针对弱项去复习"与条目链接。

## 完成条件

- AC-001…AC-007 有证据。
