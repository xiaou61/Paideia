---
artifact: tasks
work_id: WORK-053
work: 学习记录页与 AI 强联动（试点）
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 14:03:13 +0800
approver_role: CTO
---

# 学习记录页与 AI 强联动实施任务

## 依据

- `requirements.md`（REQ-001…010 / AC-001…009）、`proposal.md`（复用三条原语、工具保持只读）、`design.md`（阶段划分与落点）。
- 用户 2026-10-29 决定：**全部放在本工作项内**，阶段只影响顺序，不拆项。

## 全局约束

- 内部助手工具**只读**（`ToolQuery.internal` 固定 `ToolScope.READ`）。生成 = 返回草稿；落库 = 页面按既有校验路径做。
- 助手不得伪造学习结果（不改进度、不改掌握度、不代交卷）。
- 新增上下文快照必须有长度上限，且不含邮箱/令牌/简历内容。
- 每个入口都要能被"不启用助手"的场景降级（保留直接生成或手动路径）。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| `study-log` 快照把敏感字段带进提示词 | TASK-002 | `page-context.test.ts`（断言不含 `@` 邮箱形态与 token 前缀） |
| 上下文超长把模型上下文挤爆 | TASK-002 | 同上（按上限截断并有标注） |
| `PLANNER` 拿到管理工具或写工具 | TASK-003 | `AgentToolBridgeTest` |
| 生成工具偷偷落库 | TASK-004 | `SqlDatasetToolsTest`（只返回文本、库里无新数据集） |
| 助手不可用时页面无法生成 | TASK-007 | e2e：关掉助手仍能直接造 |
| 点入口自动发送消息（未经用户确认就花积分） | TASK-006 | e2e：输入框有内容、消息列表没新增 |
| 计划项被助手直接写入 | 阶段二 | e2e：助手自称"已加入"但列表为空 |

## 任务

### TASK-001 | done | 前端上下文：`study-log` 与 `planner`

- 对应：`REQ-004`
- 依赖：无
- 修改：`frontend/packages/assistant/src/context.tsx`、`page-context.ts`
- 接口：`PageContext` 加 `{ kind: 'study-log'; lines; failing; dueReviews; weakConcepts }`；`AssistantHarness` 加 `'planner'`；`PAGE_CONTEXT_LIMITS` 加上限
- 测试：`page-context.test.ts`
- 验证：`pnpm --filter @paideia/assistant test` → 全绿

### TASK-002 | done | 上下文序列化与截断

- 对应：`REQ-004`
- 依赖：TASK-001
- 修改：`page-context.ts` 的 `serializePageContext`
- 测试：`page-context.test.ts`（四块都在、超长截断、无敏感字段）
- 验证：同上

### TASK-003 | done | 后端 `PLANNER` 场景与提示词

- 对应：`REQ-005`
- 依赖：无
- 修改：`Harness.java`（加 `PLANNER("planner")`）、`HarnessPrompts.java`、`AgentToolBridge.toolsetsOf`
- 接口：`PLANNER → Set.of("knowledge","learning","assessment","assistant","sql")`（只读，无 admin）
- 测试：`AgentToolBridgeTest`、`HarnessPromptsTest`
- 验证：`mvn -pl paideia-ai test` → 0 failed

### TASK-004 | pending | 只读工具 `sql_generate_dataset`

- 对应：`REQ-009`、`REQ-010`
- 依赖：无
- 修改：`backend/paideia-sandbox/.../dataset/SqlDatasetTools.java`
- 接口：`sql_generate_dataset(topic, tables, rows)` → 返回 DDL/INSERT 草稿文本，`ToolScope.READ`，`group="sql"`
- 测试：`SqlDatasetToolsTest`
- 验证：`mvn -pl paideia-sandbox test` → 0 failed

### TASK-005 | done | `/progress` 页面

- 对应：`REQ-001`、`REQ-002`
- 依赖：TASK-001
- 新建：`frontend/apps/app/src/pages/ProgressPage.tsx`
- 接口：读 `/learn/tracks`、`/learning/progress`、`/learning/reviews`、`/learning/graph`；每块独立加载与错误态
- 测试：`e2e/progress.spec.ts`
- 验证：`e2e/progress.spec.ts` 已写；**本轮用 ego lite 线上手验**（四块有真实数据、无横向溢出），
  Playwright 浏览器在本机重装中，未跑 spec

### TASK-006 | done | 三个 AI 入口

- 对应：`REQ-003`
- 依赖：TASK-002、TASK-005
- 修改：`ProgressPage` 三个按钮走 `ask()`，注册 `setPageContext`
- 测试：`e2e/progress.spec.ts`（预填不发送、上下文注册）
- 验证：ego lite 线上实测——点「问问」后助手打开、输入框已含关卡名、消息数为 0（没有自动发送）

### TASK-007 | done | 路由与导航入口

- 对应：`REQ-001`
- 依赖：TASK-005
- 修改：`router.tsx`、`components/LearnerHeader.tsx`（NAV）、`@paideia/identity` 的 `preferredNav`（联合字面量，不加编译不过）
- 测试：`identity.spec.ts`（导航可达）
- 验证：`pnpm --filter @paideia/app build` 通过 + e2e

### TASK-008 | pending | SQL 工作台「让 AI 造」改走助手

- 对应：`REQ-009`、`AC-008`
- 依赖：TASK-003、TASK-004
- 修改：`SqlPracticePage.tsx`（按钮 → `ask()`；保留原直接生成作为降级）
- 测试：`e2e/sql-practice.spec.ts`
- 验证：e2e + 线上点一次

### TASK-009 | pending | 计划落库（阶段二）

- 对应：`REQ-006`、`REQ-007`
- 依赖：TASK-005
- 修改：Flyway 迁移（14 位时间戳）+ `paideia-app` 或 `paideia-ai` 的计划端点 + 页面"今天要做的事"
- 测试：后端切片 + `e2e/progress.spec.ts`（采用后刷新仍在；助手自称已加入但列表为空）
- 验证：待开工

### TASK-010 | pending | 迁移 #1 精简

- 对应：`REQ-009`
- 依赖：TASK-001
- 修改：`AiSummary` 侧加只读工具或复用对话；助手面板「精简」改为对话引导
- 验证：e2e + 线上

### TASK-011 | pending | 迁移 #3 SQL 出题

- 对应：`REQ-009`
- 依赖：TASK-004
- 修改：`SqlPracticePage` 出题入口
- 验证：e2e

### TASK-012 | pending | 迁移 #10 推荐理由 / #7 简历提案 / #4-5 训练营与复习

- 对应：`REQ-009`
- 依赖：TASK-001
- 修改：三处入口 + 各自的只读工具/提示
- 验证：各自的 e2e

## 完成条件

- 阶段一：`/progress` 上线且有导航入口；四块数据与 `/learn`、`/review` 一致；三个入口预填不发送；SQL 造数据集走助手且校验仍生效；`assistant` / `sql-practice` / `progress` 三套 e2e 全绿。
- 阶段二至三：每个迁移入口有独立 e2e，且"助手不直接写用户数据"这条边界在测试里被钉住。
