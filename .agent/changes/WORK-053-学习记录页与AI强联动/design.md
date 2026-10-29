---
artifact: design
work_id: WORK-053
work: 学习记录页与 AI 强联动（试点）
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 14:03:13 +0800
approver_role: CTO
---

# 学习记录页与 AI 强联动设计

## 系统上下文

`/progress` 页面（学习者端）读四个既有接口拿数据，通过 `useAssistantContext()` 把四块快照注册成页面上下文；
用户点入口时 `ask()` 打开助手，助手按 `PLANNER` 场景的系统提示词反问，需要生成时调用只读工具
`sql_generate_dataset`，产物以 ```sql 块回到对话，页面用 `pendingSql` 那条既有通道回填。

## 组件与职责

| 组件 | 职责 | 改动 |
| --- | --- | --- |
| `ProgressPage` | 四块数据的展示与三个入口 | 新建 |
| `assistant/context.tsx` | `PageContext` 变体、`AssistantHarness` 取值 | 加 `study-log` / `planner` |
| `assistant/page-context.ts` | 把 `study-log` 快照序列化进提示词，按上限截断 | 加一个分支 |
| `Harness.PLANNER` | 服务端场景枚举 | 新增 |
| `HarnessPrompts` | planner 的系统提示词 | 新增文案 |
| `AgentToolBridge.toolsetsOf` | `PLANNER → 只读组` | 加一行 |
| `SqlDatasetTools` | `sql_generate_dataset`（只读，返回 SQL 草稿） | 新增工具 |
| `SqlPracticePage` | 「让 AI 造」从直调接口改成 `ask()` | 改按钮与提示 |

## 请求或事件流程

**页面注册上下文**

1. `ProgressPage` 四个查询就绪后 `setPageContext({ kind: 'study-log', ... })`；
2. 卸载时 `setPageContext(null)`——与 `setEntry(null)` 分开，互不清理（`entryId` 是"正在读哪条"）。

**入口 → 助手 → 工具 → 回填（以 SQL 造数据集为例）**

1. 页面点「让 AI 造」→ `ask('帮我造一份 SQL 数据集，先问我几个问题')`；
2. 助手（`PLANNER`）按提示词反问主题/字段/规模（最多三个问题，能用默认值就用默认值）；
3. 用户答完，助手调 `sql_generate_dataset(topic, tables, rows)`，工具**只返回 DDL/INSERT 文本**；
4. 助手把 SQL 放进 ```sql 块回复；页面已有 `applySql`/`extractSqlBlock`，用户点「填回」→ 落进上传框；
5. 用户点保存 → 走原有的白名单 + 沙箱干跑 → 落库。（校验不在对话链路里，绕不过去）

## 接口与数据

- 读：`/api/v1/learn/tracks`（进度）、`/api/v1/learning/progress`（最近未满分块）、`/api/v1/learning/reviews`（到期）、`/api/v1/learning/graph`（掌握度最低 5）。
- 新增接口：无。
- 新工具：`sql_generate_dataset`，`ToolScope.READ`，`group = "sql"`，只进 `PLANNER` 的工具组。

## 持久化与迁移

无迁移。一期不落库计划项（二期再做表）。

## 失败处理与恢复

| 失败 | 处理 |
| --- | --- |
| 某块数据接口失败 | 该块显示可重试的错误，其余块照常；页面不整页失败 |
| 助手不可用（余额不足 / 上游 5xx） | 页面提示"助手暂不可用"，保留原有"直接生成"按钮作为降级 |
| 模型没调工具 | 用户看到的是普通回答，页面上的"直接生成"仍可点——不把人卡死 |
| 快照过长 | `PAGE_CONTEXT_LIMITS` 截断并标注"后面还有 N 字符" |

## 安全与权限

- `study-log` 快照只含进度数字与标题，**不含**邮箱、令牌、简历内容；上限 1200 字符。
- 工具只读；落库一律由页面按既有校验路径完成。
- `PLANNER` 不给 admin 组、不给写工具。

## 可观测性

- 复用现有对话追踪（`ai_runs`）：每轮记录 harness、工具名与耗时，`planner` 与 `sql_generate_dataset` 可单独筛。
- 不新增指标。

## 实施顺序（同一工作项内的阶段）

**阶段一：立链路**

1. 后端枚举与提示词（`Harness.PLANNER`、`HarnessPrompts`、`toolsetsOf`）。
2. 前端上下文（`study-log`、`planner`、序列化与上限）。
3. 只读工具 `sql_generate_dataset`。
4. `/progress` 页面 + 路由 + 导航入口。
5. SQL 工作台「让 AI 造」改造。
6. e2e 与线上复看。

**阶段二：计划落库**（REQ-006/007）——表 + 采用 + 勾选，接在页面之后。

**阶段三：其余入口迁移**——按"用户会不会追问"排序：精简 → 推荐理由 → 简历提案 → SQL 出题 → 训练营与复习出题/诊断/计划。
每迁一个：加只读工具（或复用）、加场景提示，改按钮，补一条 e2e。

## 测试策略

- 后端：`HarnessPromptsTest` 断言 planner 文案含"最多三个问题"与"不得伪造学习结果"；`AgentToolBridgeTest` 断言 `PLANNER` 的工具组只读且不含 admin；工具单测断言只返回文本、不写库。
- 前端：`page-context.test.ts` 断言 `study-log` 序列化含四块、按上限截断、不含敏感字段。
- e2e：`progress.spec.ts` 断言四块渲染 + 点入口后输入框预填且不自动发送 + 空数据空状态。
- 回归：`assistant.spec`、`sql-practice.spec`。

## 需求追踪

| 需求 | 落点 |
| --- | --- |
| REQ-001 / 002 | ProgressPage + 路由 + NAV |
| REQ-003 | 三个入口按钮 |
| REQ-004 | `study-log` + `PAGE_CONTEXT_LIMITS` |
| REQ-005 | `PLANNER` 提示词 |
| REQ-009 | SQL 页改造 |
| REQ-010 | 只读工具 + 页面落库 |
| REQ-006 / 007 | 阶段二（计划表 + 采用） |

## 待决定事项

- 计划表形态在阶段二开工时定（见提案 Q-001）。
