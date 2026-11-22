---
artifact: test-plan
work_id: WORK-002
work: 前端组件化与管理端拆分
status: ready
created: 2026-10-09
---

# 前端组件化与管理端拆分测试计划

## 测试范围

覆盖设计系统底座（令牌层、主题、组件基元）与组件展览应用。管理端应用、桌面端缓存修正、跨应用迁移不在本轮（TASK-006..011 未实施），相关验收标准记入「已知缺口」。

## 环境与前置条件

- Node 与 pnpm 12.10.1；工作目录 `F:\Paideia\frontend`。
- 单元测试与构建**不需要后端**：设计系统与展览页都不调接口。
- Playwright 需要本机已装 Chromium（`@playwright/test` 的既有依赖）。
- 下列用例需要后端与 SSH 隧道，本轮不涉及：`apps/app` 的 e2e、`apps/desktop` 的 e2e。

## 验收矩阵

| 验收标准 | 检查方式 | 位置或命令 |
| --- | --- | --- |
| AC-001 类型检查与单测全绿、新包在覆盖内 | 命令 | `pnpm -r typecheck`、`pnpm -r test` |
| AC-002 应用侧无硬编码颜色，且有检查防回退 | 自动检查 + 违反复核 | `packages/ui/src/tokens.test.ts` |
| AC-003 浅深可切换、刷新保持、首屏无闪烁、对比度达标 | 自动检查 + 浏览器检查 | `packages/ui/src/theme/theme-context.test.tsx`、`packages/ui/src/theme/contrast.test.ts`、`apps/ui-kit/e2e/ui-kit.spec.ts` |
| AC-004 两个应用渲染后端真实数据 | 命令（需后端 jar 与到服务器 MySQL 的 3307 隧道） | `pnpm --filter @paideia/app test:e2e`、`pnpm --filter @paideia/admin test:e2e` |
| AC-005 桌面产物与 Web 产物都不含管理端与展览页 | 命令 + 人工核对 | 构建后检索 `apps/app/dist` 与 `apps/desktop/renderer`，并用 `apps/admin/dist` 作对照组 |
| AC-006 非管理员进不了管理端路由 | 自动检查 | `packages/core/src/session.test.ts` + `apps/admin/e2e/admin.spec.ts` |
| AC-007 边界检查覆盖新增包且违规必失败 | 自动检查 + 违反复核 | `packages/core/src/workspace-boundaries.test.ts` |
| AC-008 玻璃层在无 `backdrop-filter` 时有降级 | 构建产物结构核对 + 浏览器检查 | 产物 CSS 的 `.glass` 三条规则（默认不透明底、`@supports` 增强、减少透明） |

## 自动化检查

| 检查 | 命令 |
| --- | --- |
| 全量类型检查 | `pnpm -r typecheck` |
| 单元测试（含令牌单一来源、对比度、主题） | `pnpm -r test` |
| 展览页端到端（渲染、键盘、主题持久化） | `pnpm --filter @paideia/ui-kit test:e2e` |
| 三个应用的构建 | `pnpm --filter @paideia/app build`、`--filter @paideia/public build`、`--filter @paideia/ui-kit build` |

## 人工检查

- 在浏览器里逐节浏览展览页，确认玻璃层观感、深浅两色下组件都正常（截图或口头结论记入报告）。
- 用开发者工具禁用 `backdrop-filter`，确认玻璃卡片退回到不透明底后仍可读。
- 检查 `apps/app` 与 `apps/public` 的产物 `index.html` 里首屏脚本位于 `<head>` 且早于模块脚本。

## 回归范围

- `packages/core` 的既有边界检查与 API 客户端用例（`pnpm -r test` 已覆盖）。
- 公开页的预渲染流程：`pnpm --filter @paideia/public build` 必须仍注入正文并输出相对路径的样式链接。
- 桌面端：本轮未改 `apps/desktop`，不做回归；桌面打包依赖网络拉取二进制，不在本轮范围内。

## 已知缺口

- 桌面端 e2e（`apps/desktop/e2e/desktop.spec.ts`）本机不可运行（Playwright 经 `cmd.exe` 启动 Electron 的 spawn 被限制）；桌面侧结论由 `packages/platform-desktop/src/index.test.ts` 承担，并在报告中标注"非端到端"。
- 桌面安装包打包需联网拉 electron-builder 二进制，本轮未执行；AC-005 的证据取自 `prepare-renderer` 之后的渲染产物。
- 静止状态的 `input`/`border` 边框对比度低于 3:1（刻意的观感取舍，Geist 亦然）；WCAG 1.4.11 要求的焦点指示已由 `ring` 合成后 ≥3:1 覆盖。
