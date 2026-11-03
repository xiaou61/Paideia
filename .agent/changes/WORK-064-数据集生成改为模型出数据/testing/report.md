---
artifact: test-report
work_id: WORK-064
work: 数据集生成改为模型出数据
status: passed
evidence: required
created: 2026-11-03
updated: 2026-11-03
verified_commit: (提交后填)
---

# 验证报告

## 结果

| 检查项 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| AC-001 模型给的数据原样进 SQL 且过白名单 | `mvn -pl paideia-sandbox,paideia-ai -am -Dtest=SqlDatasetBuildToolsTest,...` | passed | `SqlDatasetBuildToolsTest` 6/6：含 `'alice'`、`240`、`130.5`、`NULL`、`'2025-03-02'`，`SqlStatementGuard.check` 为空 |
| AC-002 单引号转义 | 同上 | passed | `O'Brien` → `'O''Brien'` |
| AC-003 数据错误点名到行/列 | 同上 | passed | 缺列、多余列、非数字、小数当 INT、数字当 VARCHAR、非法日期、51 行、超 3 表、非法标识符、非白名单类型，全部返回可读失败 |
| 既有工具路径未回归 | 同上 | passed | `SqlDatasetToolsTest` 9/9 |
| 提示词仍含工具约定 | 同上 | passed | `PlannerPromptTest` 4/4 |
| AC-004 前端不受影响 | `pnpm typecheck` + `pnpm --filter @paideia/assistant test` + 单条 e2e | passed | typecheck 0 错；assistant 79/79；`让 AI 设计` e2e 1/1 |
| 后端构建 | 同上 | passed | BUILD SUCCESS（1 分 06 秒，独占库已自动删除） |

## 未覆盖与已知限制

- **工具不校验外键一致性**：生成的 DDL 没有 `FOREIGN KEY`，沙箱干跑也查不到引用完整性；数据连贯
  现在靠提示词要求（"外键指向真实 id、日期不超今天、可故意留坑并说明"），是**提示词级**约束，
  不是机制级。模型硬不遵守时，用户仍可能拿到 JOIN 不上的数据。
- **未用真模型跑端到端**：工具契约由单测覆盖，端到端仍只有打桩的 e2e。
- 每表行数上限从 200 降到 50：一次工具调用的 token 随行数线性涨，200 行不现实。
- 含换行或反斜杠的字符串直接拒绝（方言差异，练习数据用不到）。
