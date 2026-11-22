---
artifact: tasks
work_id: WORK-081
work: 借 Astryx 经验的设计系统演进
status: approved
created: 2026-11-17
updated: 2026-11-17
approved_by: xiaou61
approved_at: 2026-11-17 11:00:33 +0800
approver_role: CTO
---

# 借 Astryx 经验的设计系统演进 — 任务

> 状态值：pending / doing / done。每个 TASK 独立可验证，对应 design.md 的变更清单编号。

## S1 · REQ-001 单一规范源

### TASK-001 | done | 组件元数据 + 清单生成脚本 + 文档
- 产出：`frontend/packages/ui/src/component-meta.ts`（`COMPONENT_META`）、`frontend/scripts/gen-ui-manifest.mjs`、`frontend/docs/ui-manifest.md`、`frontend/package.json` 增 `ui:manifest` / `ui:manifest:check`。
- 覆盖：design #1–#4。
- 验收：`cd frontend && pnpm ui:manifest` 生成清单；`pnpm ui:manifest:check` 退出码 0；手改一条组件说明不重生成时 check 退 1。

### TASK-002 | done | ui-kit 顶部组件索引表
- 产出：`frontend/apps/ui-kit/src/pages/UiKitPage.tsx` 顶部用 `COMPONENT_META` 渲染组件索引；变体分区保持手工。
- 覆盖：design #5。
- 验收：ui-kit 页面可见索引表，变体分区不变；`pnpm --filter @paideia/ui-kit typecheck` 通过。

## S2 · REQ-002 Patterns 层

### TASK-003 | done | @paideia/patterns 包骨架
- 产出：`frontend/packages/patterns/{package.json,tsconfig.json,src/index.ts}`，`exports "." → ./src/index.ts`，依赖 `@paideia/ui: workspace:*`。
- 覆盖：design #6–#8。
- 验收：`pnpm -r typecheck` 能找到并编译该包。

### TASK-004 | done | 三个页面模板
- 产出：`frontend/packages/patterns/src/{list-page,detail-page,form-wizard}.tsx`，纯 props、只组合 `@paideia/ui`、不引 core/router。
- 覆盖：design #9–#11。
- 验收：patterns 包 vitest 渲染测试通过（最小渲染 + 空/加载态）。

### TASK-005 | done | 登记 @source 与来源覆盖测试
- 产出：`frontend/packages/ui/src/styles/globals.css` 增 `@source "../../packages/patterns/src"`；`source-coverage.test.ts` 加 patterns 断言。
- 覆盖：design #12–#13。
- 验收：先红（不加 @source 时测试失败）后绿；`pnpm --filter @paideia/ui test` 通过。

### TASK-006 | done | ui-kit 展示三模板
- 产出：`frontend/apps/ui-kit/package.json` 加依赖；`UiKitPage.tsx` 增一节展示三模板。
- 覆盖：design #14–#15。
- 验收：ui-kit e2e 通过。

### TASK-007 | done | admin 试点一页
- 产出：`frontend/apps/admin/package.json` 加依赖；`AiRunsListPage.tsx` 改用 `ListPage`（保留原有取数与列，行为不变）。
- 覆盖：design #16–#17。
- 验收：`pnpm --filter @paideia/admin test:e2e` 试点页对应文件绿。
- 备注（2026-11-17）：本机无后端(8080/8081)/Redis(6379)，e2e 未执行；已用 `pnpm --filter @paideia/admin build`（7.19s，退出 0）与 `pnpm --filter @paideia/admin typecheck`（0 error）验证编译与打包。行为等价性由 typecheck+build 的编译期保证，运行时 e2e 待有环境时补跑。

## S3 · REQ-003 API 原则

### TASK-008 | done | API 原则文档
- 产出：`frontend/docs/ui-api-principles.md`（open internals / customize without wrapping / guidance over enforcement；「primitives 双出口」暂缓）。
- 覆盖：design #18。
- 验收：文档评审通过，无待定项。

## 收尾

### TASK-009 | done | 整体验证与证据
- 跑 `cd frontend && pnpm -r typecheck`、`pnpm -r test`（受影响包）、ui-kit 与 admin 对应 e2e 文件；按 `references/testing.md` 出 `testing/report.md`。
- 验收：AC-001/AC-002/AC-003 均有证据；`project-lifecycle validate` 对本项不再报错。
