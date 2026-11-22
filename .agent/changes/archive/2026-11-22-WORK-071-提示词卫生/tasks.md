---
artifact: tasks
work_id: WORK-071
work: 提示词卫生：复用、版本守卫、按版本可见
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 15:28:11 +0800
approver_role: owner
---

# 任务

- [x] TASK-001 `PromptFences.DATA_ONLY` + 五处引用（`DigestPrompt`、`SqlQuestionGenerator`、`SqlDatasetGenerator`、`QuestionTaggingService`、`SummaryPrompt`）。
- [x] TASK-002 `PromptVersionGuardTest`（扫源码、白名单 `HarnessPrompts`）+ 反向验证。
- [x] TASK-003 `SummaryPrompt.VERSION` → `summary-card-v2`。
- [x] TASK-004 `TelemetrySummaryMapper.countByPromptVersion` + `Overview` 增字段 + 服务装配。
- [x] TASK-005 管理端 `AiRunsOverviewPage`：类型字段 + 「按提示词版本」排行卡。
- [x] TASK-006 测试与验证：`paideia-ai` 全绿、守卫反向验证、admin typecheck。
- [x] TASK-007 报告与更新历史（`testing/report.md`、`.agent/history/updates.md`）。
