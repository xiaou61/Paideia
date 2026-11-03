---
artifact: proposal
work_id: WORK-066
work: 移除学习者端 AI 助手，入口改成专用流程
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 17:20:00 +0800
approver_role: owner
---

# 移除学习者端 AI 助手

## 背景（用户 2026-11-03 原话要点）

- 把**学习者端的 AI 助手**去掉；原来用到它的地方，全部改成"类似数据集向导那种"专用流程。
- 不要再做一个通用可视化助手，嫌麻烦；**管理员端可以保留一个东西**。

## 现状盘点（代码核实）

助手面板 `AssistantShell` 挂在 `frontend/apps/app/src/main.tsx`，四个页签：

| 页签 | 能力 | 现在唯一入口 |
| --- | --- | --- |
| 对话 | 多轮聊天 + 工具 + 附件 | 全局面板（Ctrl+J / 页头按钮） |
| 精简 | 条目 AI 摘要 | 面板页签（`summary` API） |
| 记忆 | 记忆列表/开关/候选确认 | 面板页签（`memories` API） |
| 积分 | 余额与流水 | 面板页签（`credits` API） |

依赖面板的入口（`ask()` 预填一句话后切面板）：

1. `LearnPage` 一条推荐理由；
2. `ProgressPage` 三处（卡住/挑复习/为什么推这几条）；
3. `ResumeOptimizePage` JD 差距；
4. `SqlPracticePage` 三处（追加行 / 看 SQL / 让 AI 出题的低配版）；
5. 源码块 `code-tour` / `source-walk` 上的「问 AI」（`AskButton`）。

已有**专用接口**、不依赖聊天的能力：SQL 出题（`generateSqlQuestion`）、面试点评（`reviewInterview`）、
简历 JD 分析（`versions/{id}/analysis`）、摘要（`summary`）、记忆（`memories`）、积分（`credits`）。

## 用户裁决（2026-11-03，按推荐执行）

1. **面板四块**：对话删除；精简搬到条目页（按钮 + 卡片）；记忆、积分搬到设置页（`/#/settings`）。
2. **八处「问 AI」按钮**：全删。
3. **管理端**：不新建助手，只保留已有 AI 调用统计/大屏。

## 不做什么

- 不删后端接口与数据（记忆、积分、摘要的 API 与表都保留；只是前端不再有入口）。
- 不做"把聊天改造成向导"的通用框架：每个用途单独做，避免再造一个助手。
