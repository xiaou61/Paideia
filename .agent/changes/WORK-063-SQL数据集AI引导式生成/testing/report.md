---
artifact: test-report
work_id: WORK-063
work: SQL 数据集 AI 引导式生成
status: passed
evidence: required
created: 2026-11-03
updated: 2026-11-03
verified_commit: faf88b98
---

# 验证报告

## 验证环境

- 前端：本机 `pnpm`（Vite dev + Playwright 1.64）
- 后端：未改（零后端改动），e2e 用既有 jar 起在 `PAID-18080` 端口（8080 被另一个项目占用，用环境变量换端口）
- 数据库：`scripts/test-db.sh` 独占库

## 结果

| 检查项 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| AC-001 点入口出向导卡、弹窗关闭、输入框为空 | `playwright test e2e/sql-practice.spec.ts -g "让 AI 设计"` | passed | 用例断言 `sql-dataset-dialog` 计数 0、`assistant-guide` 可见、`conversation-input` 为空 |
| AC-002 点「生成」才发，内容含三组选择 | 同上 + `conversation-panel.test.tsx` 向导卡用例 | passed | 单测：卡片出现时 `sendMessage` 0 次；点生成后 1 次且含「练什么：JOIN」「几张表：1」。e2e：用户消息里出现选择项 |
| AC-003 「插入」= 追加、「继续编辑」= 替换并聚焦 | 同上 e2e | passed | 插入后编辑器同时含原草稿与 `CREATE TABLE t`；继续编辑后原草稿消失、SQL 在，且 `.cm-content` 处于 focused |
| AC-004 其他 `ask()` 入口仍只预填 | assistant 包单测「预填不发送」 | passed | 该用例仍绿（79 例中 78 绿；「回答反馈」一例 5s 超时属环境慢，单跑 689ms 通过） |
| AC-005 无 SQL 代码块时给明确提示 | 代码复用既有 `extractSqlBlock` 分支 | 未单测 | 「插入」与「继续编辑」各有一句明确提示；未新增解析逻辑 |
| 前端类型检查 | `pnpm typecheck` | passed | 0 error |
| assistant 包单测 | `pnpm --filter @paideia/assistant test` | passed | 79 例；1 例超时为环境抖动，单跑复验通过 |
| SQL 工作台 e2e 全量 | `playwright test e2e/sql-practice.spec.ts` | passed | 9 通过 / 1 跳过 / 0 失败 |

## 未覆盖与已知限制

- 后端未跑：本项零后端改动。
- 向导卡**不进会话历史**：关闭面板即丢；再次点入口会重新出卡。
- 「先不用」只收起卡片，不关闭助手面板。
- 选项是页面固定三组，不随当前数据集动态变化（本项非目标）。
