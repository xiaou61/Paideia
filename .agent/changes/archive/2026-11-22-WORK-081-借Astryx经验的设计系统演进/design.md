---
artifact: design
work_id: WORK-081
work: 借 Astryx 经验的设计系统演进
status: approved
created: 2026-11-17
updated: 2026-11-17
approved_by: xiaou61
approved_at: 2026-11-17 10:41:26 +0800
approver_role: CTO
---

# 借 Astryx 经验的设计系统演进 — 设计

## 变更清单

| # | 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `frontend/packages/ui/src/component-meta.json` + `frontend/packages/ui/src/component-meta.ts` + `frontend/packages/ui/src/index.ts` | 新增/修改 | 无组件元数据 | `component-meta.json` 存权威数据（按源模块 key：category/description/source）；`component-meta.ts` 类型化再导出 `COMPONENT_META`/`ComponentMeta`；`index.ts` 增一行 `export { COMPONENT_META, type ComponentMeta }` | REQ-001 |
| 2 | `frontend/scripts/gen-ui-manifest.mjs` | 新增 | 无 | 解析 `packages/ui/src/index.ts` 的 `export {…} from '…'`，合并 `COMPONENT_META`，生成 `frontend/docs/ui-manifest.md`；`--check` 时只校验不写、不一致则退出码 1 | REQ-001 / AC-001 |
| 3 | `frontend/docs/ui-manifest.md` + `frontend/packages/ui/src/ui-manifest.json` + `frontend/packages/ui/package.json` | 新增/修改 | 无结构化清单 | md 供人读（分类分组表格）、json 供程序读（`moduleCount/exportCount/exports[]`）；package.json 增 `"./ui-manifest.json"` 导出 | AC-001 |
| 4 | `frontend/package.json` | 修改 | `scripts` 无 ui 清单命令 | 增加 `"ui:manifest": "node scripts/gen-ui-manifest.mjs"`、`"ui:manifest:check": "node scripts/gen-ui-manifest.mjs --check"` | REQ-001 |
| 5 | `frontend/apps/ui-kit/src/pages/UiKitPage.tsx` | 修改 | 只有手工变体分区 | 顶部新增由 `@paideia/ui/ui-manifest.json` 渲染的组件索引表（导出名/分类/用途/源文件）；变体分区仍手工 | REQ-001 |
| 6 | `frontend/packages/patterns/package.json` | 新增 | 无 | `@paideia/patterns` 包清单，`exports "." → ./src/index.ts`，`dependencies` 含 `@paideia/ui: workspace:*` | REQ-002 |
| 7 | `frontend/packages/patterns/tsconfig.json` | 新增 | 无 | 继承共享 tsconfig（与 `packages/ui` 同款） | REQ-002 |
| 8 | `frontend/packages/patterns/src/index.ts` | 新增 | 无 | 导出 `ListPage` / `DetailPage` / `FormWizard` 及各自 props 类型 | REQ-002 |
| 9 | `frontend/packages/patterns/src/list-page.tsx` | 新增 | 无 | 纯 props 的列表页模板（标题 + 筛选槽 + 表格 + 分页 + 空/加载/错误态），只组合 `@paideia/ui` | REQ-002 / AC-003 |
| 10 | `frontend/packages/patterns/src/detail-page.tsx` | 新增 | 无 | 纯 props 的详情页模板（PageHeader + 字段区 + 操作槽） | REQ-002 |
| 11 | `frontend/packages/patterns/src/form-wizard.tsx` | 新增 | 无 | 纯 props 的分步表单模板（步骤条 + 内容槽 + 上一步/下一步/提交） | REQ-002 |
| 12 | `frontend/packages/ui/src/styles/globals.css` | 修改 | 未登记 `packages/patterns` 的源码路径 | 增加一行 `@source "../../packages/patterns/src"` | REQ-002（`always.md` @source 登记约束） |
| 13 | `frontend/packages/ui/src/styles/source-coverage.test.ts` | 修改 | 允许清单不含 `patterns` | 加入 `packages/patterns` 的断言 | REQ-002 |
| 14 | `frontend/apps/ui-kit/package.json` | 修改 | 未依赖 patterns | `dependencies` 增加 `@paideia/patterns: workspace:*` | REQ-002 |
| 15 | `frontend/apps/ui-kit/src/pages/UiKitPage.tsx` | 修改 | 无 patterns 展示 | 增加一节渲染三个模板的静态示例 | REQ-002 |
| 16 | `frontend/apps/admin/package.json` | 修改 | 未依赖 patterns | `dependencies` 增加 `@paideia/patterns: workspace:*` | REQ-002 |
| 17 | `frontend/apps/admin/src/pages/AiRunsListPage.tsx` | 修改 | 手写列表区域 | 改用 `ListPage` 承载（保留原有取数、筛选与列定义，行为不变） | REQ-002 / AC-003 |
| 18 | `frontend/docs/ui-api-principles.md` | 新增 | 无 | 记录 open internals / customize without wrapping / guidance over enforcement 三原则，及"`@paideia/ui/primitives` 双出口暂缓"的结论与理由 | REQ-003 / AC-002 |

## 系统上下文

依赖方向：`apps/* → @paideia/patterns → @paideia/ui`；`@paideia/ui` 不反向依赖 patterns，无环。`patterns` 与 `ui` 一样是**纯展示层**：不引 `@paideia/core` / `react-router` / 平台 API。

## 组件与职责

- `COMPONENT_META`：组件"目录信息"的唯一人工来源（分类、一句话用途、源文件）；新增组件时手写一条。
- `gen-ui-manifest.mjs`：把"源码里有哪些导出"（自动）与"它们是干什么的"（人工 meta）缝成一份清单。
- `ListPage<T>`：入参 `{ title, description?, filters?, columns, rows, getRowKey, loading?, error?, empty?, pagination? }`，纯渲染，取数由调用方在外部完成。
- `DetailPage`：入参 `{ title, sections, actions? }`，字段区由调用方给节点。
- `FormWizard`：入参 `{ steps, current, onNext, onPrev, onSubmit, renderStep }`，不持表单状态。

## 请求或事件流程

不涉及网络/事件；全为编译期与渲染期。

## 接口与数据

- 新增公共 API 仅 `@paideia/patterns` 的 `ListPage` / `DetailPage` / `FormWizard` 及其 props 类型。
- `@paideia/ui` 的对外出口 `index.ts` **不变**（`component-meta.ts` 仅内部使用，不新增组件导出）。这是刻意的：本工作项不改 `ui` 公共 API。

## 持久化与迁移

无数据库/迁移。

## 失败处理与恢复

- 清单生成：`--check` 不一致即失败并打印差集；修复方式是重跑 `pnpm ui:manifest` 或补 `COMPONENT_META`。
- `@source` 漏登记不会报错、只会静默丢样式——因此以 `source-coverage.test.ts` 先红后绿作为硬门。

## 安全与权限

不涉及鉴权、凭据或个人数据。

## 可观测性

不涉及。

## 实施顺序

S1（#1–#5）→ S2（#6–#17）→ S3（#18）。S2 内部先建包与模板、再补 `@source` 与边界测试、最后接 ui-kit 与 admin 试点。

## 测试策略

- S1：`pnpm ui:manifest:check` 绿；反向验证——手改一条组件说明不重生成，check 必须变红。
- S2：`pnpm --filter @paideia/ui test`（`source-coverage` 覆盖 patterns 路径）；patterns 包 vitest 渲染测试（三模板最小渲染 + 空/加载态）；`pnpm --filter @paideia/ui-kit test:e2e`；`pnpm --filter @paideia/admin test:e2e` 只跑试点页对应文件；`cd frontend && pnpm -r typecheck`。
- S3：文档评审，无需运行测试。
- 全部完成后按 `references/testing.md` 的硬门出 `testing/report.md`。

## 需求追踪

| 需求 | 设计元素 | 测试 |
| --- | --- | --- |
| REQ-001 / AC-001 | #1–#5 | `ui:manifest:check` + 反向验证 |
| REQ-002 / AC-003 | #6–#17 | ui typecheck/test、patterns 渲染测试、ui-kit & admin e2e |
| REQ-003 / AC-002 | #18 | 文档评审 |

## 待决定事项

- admin 试点页确认（建议 `AiRunsListPage.tsx`）。
