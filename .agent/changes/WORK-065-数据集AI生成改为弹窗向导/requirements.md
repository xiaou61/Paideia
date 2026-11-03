---
artifact: requirements
work_id: WORK-065
work: 数据集 AI 生成改为弹窗向导
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 16:20:00 +0800
approver_role: owner
---

# 需求

- **REQ-001**：点「让 AI 生成」不打开助手；在弹窗里出现四组选项（主题 / 练什么 / 几张表 / 每表行数），每组选中项下方有说明。
- **REQ-002**：点「生成」调 `POST /api/v1/sql-datasets/draft`，拿到 `{name, sql}` 后**填进「写 SQL」与名字框**，不落库。
- **REQ-003**：保存仍由用户点「确认保存」，走既有白名单 + 沙箱干跑 + 结构快照。
- **REQ-004**：助手不再承载数据集向导（`guide` API 删除）；聊天保留「插入编辑器 / 继续编辑 / 存为数据集」。
- **REQ-005**：生成失败在弹窗内显示原因；生成中显示"约 10–30 秒"。

## 验收标准

- **AC-001**：点入口后弹窗仍在、选项在弹窗内、`conversation-panel` 不存在（助手没被打开）。
- **AC-002**：点「生成」后 SQL 框有值、名字框有值，且没有保存请求。
- **AC-003**：点「确认保存」才发 `POST /sql-datasets`，成功即关闭弹窗。
- **AC-004**：生成失败时弹窗内出现 `sql-dataset-draft-error`。
