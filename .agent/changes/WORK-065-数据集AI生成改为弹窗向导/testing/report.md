---
artifact: test-report
work_id: WORK-065
work: 数据集 AI 生成改为弹窗向导
status: passed
evidence: required
created: 2026-11-03
updated: 2026-11-03
verified_commit: d3c7a7c9
---

# 验证报告

| 检查项 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| AC-001 选项在弹窗里、助手没被打开 | `playwright test e2e/sql-practice.spec.ts -g "让 AI 生成"` | passed | 弹窗可见、`assistant-guide` 可见、`conversation-panel` 计数 0 |
| AC-002 生成后 SQL/名字回填且未保存 | 同上（`/sql-datasets/draft` 打桩） | passed | `sql-dataset-sql` 含 `CREATE TABLE books`、`sql-dataset-name` 为「AI 图书库」；保存请求发生在点击之后 |
| AC-003 确认保存才提交 | 同上（`POST /sql-datasets` 打桩） | passed | 点「确认保存」后弹窗关闭 |
| AC-004 生成失败在弹窗内提示 | 代码路径 `sql-dataset-draft-error` | 未单测 | 复用 `errorMessage`；未写失败分支的 e2e |
| 后端：只生成不保存 | `mvn -pl paideia-sandbox -am -Dtest=SqlAiServiceTest` | passed | 8/8，新增「只生成不保存」断言 `verifyNoInteractions(uploads)` |
| 前端类型与单测 | `pnpm typecheck` + assistant 包 | passed | typecheck 0 错；assistant 78/78（移除了向导卡的会话面板用例） |
| 线上端到端（ego） | 线上真实浏览器：选「酒店预订 + JOIN + 3 表 + 10 行」→ 生成 → 保存 | passed | 生成 **15 秒**，SQL 与名字（酒店预订JOIN聚合练习）回填；保存后右侧显示 **共 3 张表 · 15 列**（bookings 7 / hotels 4 / rooms 4，主键齐全）；截图 `F:/caches/tmp/ego-sql-wizard-2.png` |

## 未覆盖与已知限制

- 生成失败分支只有代码，没有 e2e/单测。
- 生成走真实上游时才会验证模型输出质量；本条只验接线与回填。
- 助手聊天本身未改（插入编辑器/继续编辑仍在），本轮没有回归跑全量 e2e。
