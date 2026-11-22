---
artifact: tasks
work_id: WORK-063
work: SQL 数据集 AI 引导式生成
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 12:05:00 +0800
approver_role: owner
---

# 任务

- [x] TASK-001 context：`AssistantGuide` 类型、`openGuide`/`clearGuide`、`appendSql`/`clearPendingAppendSql`。
- [x] TASK-002 向导卡：`guide-card.tsx`（三组单选、默认第一项、生成/先不用）+ `composeGuideMessage`。
- [x] TASK-003 面板：渲染卡片、`send(text?)`、SQL 回答的「插入编辑器 / 继续编辑 / 存为数据集」。
- [x] TASK-004 SQL 页 + 编辑器：`focusSignal`、追加 effect、`askAiToDesign` 改 `openGuide`。
- [x] TASK-005 验证：前端 typecheck + 相关单测；`sql-practice` e2e 改版后跑绿；记录报告。
