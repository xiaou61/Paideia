---
artifact: requirements
work_id: WORK-064
work: 数据集生成改为模型出数据
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 15:00:00 +0800
approver_role: owner
---

# 需求

- **REQ-001**：工具入参改为 `tables[].rows`（每行一个对象，键是列名），不再接收全局 `rows` 计数；
  工具只校验与拼装，不 invent 任何值。
- **REQ-002**：校验点名到行与列：缺列、多余列、类型不符（INT 给小数/非数字、VARCHAR 给数字、
  DATE/DATETIME 格式错）、每表 1–50 行、最多 3 表 12 列。
- **REQ-003**：字符串值自行转义单引号；含换行或反斜杠直接拒绝（方言差异，练习数据用不到）。
- **REQ-004**：提示词要求模型逐行想数据（外键指向真实 id、日期不超今天、状态用真实取值，
  可故意留练习坑并说明）；向导"每表行数"选项改为 10/30/50（与工具上限一致）。

## 验收标准

- **AC-001**：给定两表数据，产出 SQL 含模型给的每个值，且 `SqlStatementGuard.check` 为空。
- **AC-002**：`O'Brien` 产出 `'O''Brien'`。
- **AC-003**：缺列/多列/类型不符/日期非法/超 50 行/超 3 表/非法标识符都返回可读失败，不抛异常。
- **AC-004**：前端 typecheck 与 assistant 包单测通过；`sql-practice` e2e 不受影响。
