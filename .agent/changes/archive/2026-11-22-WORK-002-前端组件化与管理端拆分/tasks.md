---
artifact: tasks
work_id: WORK-002
work: 前端组件化与管理端拆分
status: approved
created: 2026-10-09
updated: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 14:45:36 +0800
approver_role: CTO
---

# 前端组件化与管理端拆分实施任务

## 依据

- `.agent/changes/WORK-002-前端组件化与管理端拆分/requirements.md`（已批准，签署人 xiaou61 / CTO）
- `.agent/changes/WORK-002-前端组件化与管理端拆分/proposal.md`、`design.md`（本次一并提交审阅）
- `.agent/rules/always.md`：已裁决技术栈、平台端口纪律、公开仓库保密规则

## 全局约束

- **令牌单一来源**：颜色值只出现在 `packages/ui/src/styles/globals.css`；应用侧不得写硬编码颜色字面量。
- **Tailwind 扫描路径必须显式登记**：`@import "tailwindcss" source(none)` + `@source` 写 `packages/...`/`apps/...` 物理路径，不得写包名。
- **`packages/ui` 仍是纯展示层**：不得引用 `@paideia/core`、`@paideia/platform-*`、`react-router`、`electron`、`@tauri-apps/*`。
- `base: './'` 不得改成 `'/'`；路由保持 Hash 路由；不引 Next.js；不引 webfont；CJK 走系统字体栈。
- 共享包继续直出 TypeScript 源码，不新增独立构建步骤。
- 每个任务完成后同步 `tasks.md` 的任务状态，并追加 `.agent/history/updates.md`。

## 任务

### TASK-001 | done | 样式管线与令牌层

- 对应：`REQ-001`、`AC-002`、`AC-008`
- 依赖：无
- 修改：`frontend/packages/ui/package.json`（新增 `./styles.css` 导出与 `tailwindcss` 开发依赖）、`frontend/packages/ui/src/styles/globals.css`（新建）、`frontend/packages/ui/src/lib/cn.ts`（新建）、`frontend/apps/app/vite.config.ts`、`frontend/apps/app/src/main.tsx`、`frontend/apps/public/vite.config.ts`、`frontend/apps/public/src/entry-client.tsx`、`frontend/apps/public/src/entry-server.tsx`
- 步骤：
  1. `packages/ui` 加 `tailwindcss@4.3.3` 开发依赖，并加 `"react"`/`"react-dom"` 之外的既有 peer 不动；加 `./styles.css` → `./src/styles/globals.css` 导出映射。
  2. 写 `globals.css`：`source(none)` + 三行 `@source`（ui 自身、`apps/app/src`、`apps/public/src`；`apps/admin/src` 在 TASK-009 补、`apps/ui-kit/src` 在 TASK-005 补）、`:root`/`.dark` 双套变量、`@theme inline` 映射、`@custom-variant dark`、`.glass` 与 `@supports` 增强、`prefers-reduced-transparency` 与 `prefers-reduced-motion` 分支。
  3. `apps/app` 与 `apps/public` 加 `@tailwindcss/vite` 插件，并在入口引入 `@paideia/ui/styles.css`（public 的客户端与服务端入口都要，SSR 下由 Vite 处理）。
  4. 给 `apps/app` 的 `HomePage` 临时只做一件事：把外层容器换成令牌着色（`bg-background text-foreground`），确认管线通了；完整重写留给 TASK-006。
- 验证：
  - `cd frontend && pnpm -r typecheck` 通过。
  - `pnpm --filter @paideia/app build` 通过；在 `apps/app/dist/assets/*.css` 里能检索到仅 `packages/ui` 才有的类名（例如 `.glass`）与 `.dark` —— **这一步是"扫描路径登记正确"的证据，不能省**。
  - `pnpm --filter @paideia/public build` 通过，产物 `dist/index.html` 里有预渲染正文与样式链接。

### TASK-002 | done | 主题状态、切换与首屏不闪烁

- 对应：`REQ-004`、`AC-003`
- 依赖：TASK-001
- 修改：`frontend/packages/ui/src/theme/theme-context.tsx`、`frontend/packages/ui/src/theme/theme-toggle.tsx`、`frontend/packages/ui/src/theme/vite-plugin.ts`（均新建）、`frontend/packages/ui/src/index.ts`、`frontend/packages/ui/package.json`（加 `./vite` 导出）、`frontend/apps/app/{index.html,vite.config.ts,src/theme.ts}`、`frontend/apps/public/vite.config.ts`
- 步骤：
  1. 写 `ThemeProvider`（存储从参数注入，形状只有 `get`/`set`）、`useTheme`、`ThemeToggle`；初始值取自 `<html>` 的类，切换时改类并写存储；无已保存偏好时订阅 `matchMedia` 跟随系统。
  2. 写 `themeInitPlugin`：`transformIndexHtml` 注入首屏脚本，依次读桌面快照、`localStorage['paideia:theme']`、系统偏好。
  3. 三个入口（app、admin 在 TASK-009、public）加该插件；`apps/app` 的 `src/theme.ts` 把 `platform.cache` 传给 `ThemeProvider`。
  4. 写 `theme-context.test.tsx`：初始值取自 DOM、切换写入存储、无存储时按系统偏好。`packages/ui` 加 `vitest`、`jsdom`、`@testing-library/react` 开发依赖与 `test` 脚本。
- 验证：
  - `pnpm --filter @paideia/ui test` 通过（三条用例）。
  - `apps/app` 的 e2e 增加断言：切换主题 → 刷新 → 主题保持；首屏不出现浅→深跳变（断言 `html` 类在首帧即为预期值）。
  - 人工核对产物 `index.html` 里已注入首屏脚本且在 `<head>` 内、位于模块脚本之前。

### TASK-003 | done | 展示与表单基元 + AppShell

- 对应：`REQ-002`、`AC-001`
- 依赖：TASK-001
- 修改：`frontend/packages/ui/components.json`（新建）、`frontend/packages/ui/src/components/**`（新建）、`frontend/packages/ui/src/layout/app-shell.tsx`（新建）、`frontend/packages/ui/src/lib/cn.ts`、`frontend/packages/ui/src/index.ts`、`frontend/packages/ui/package.json`（新增运行时依赖）
- 步骤：
  1. 用 `pnpm dlx shadcn@latest`（当前 4.21.4）以 `--base base`（Base UI）、`rsc: false`、`iconLibrary: lucide` 生成组件到 `packages/ui`；确认生成的 `components.json` 里 `aliases.ui` 指向本包。
  2. 落地 Button、Input、Label、Checkbox、Switch、Select、Card、Badge、Separator、Table、Spinner、Skeleton、EmptyState、ErrorState；把颜色/圆角全部换成令牌变量，Card 使用 `.glass`。
  3. 写 `AppShell`（页头 / 侧栏 / 内容区插槽，纯展示、不含路由与角色判断）。
  4. 记录实际安装的依赖版本到任务结果。
- 验证：
  - `pnpm -r typecheck` 通过；`pnpm --filter @paideia/ui test` 仍通过。
  - 抽查 `packages/ui/package.json` 的 dependencies：不含 `@paideia/core`、`react-router`、`electron`（这条也会被 TASK-007 的检查覆盖）。

### TASK-004 | done | 交互基元（Dialog / DropdownMenu / Tabs / Toast）

- 对应：`REQ-002`、`AC-001`
- 依赖：TASK-003
- 修改：`frontend/packages/ui/src/components/{dialog,dropdown-menu,tabs,toast}.tsx`（新建）、`frontend/packages/ui/src/index.ts`
- 步骤：
  1. 生成并适配这四个组件；Toast 用 sonner 并在 `packages/ui` 导出其 Provider 包装。
  2. 确保焦点管理、`Esc` 关闭、`aria-*` 属性来自 Base UI 原语，不做手工实现。
- 验证：
  - `pnpm -r typecheck` 通过。
  - 在 TASK-005 的展览页里，键盘 `Tab` 能进入、`Esc` 能关闭（在 TASK-005 的验证中一并记录）。

### TASK-005 | done | 组件展览应用 `apps/ui-kit` 与设计系统核验

- 对应：`REQ-002`、`AC-001`、`AC-002`、`AC-003`、`AC-008`
- 依赖：TASK-004、TASK-002
- 修改：`frontend/apps/ui-kit/`（新建：`package.json`、`index.html`、`vite.config.ts`、`tsconfig.json`、`src/main.tsx`、`src/pages/UiKitPage.tsx`）、`frontend/packages/ui/src/styles/globals.css`（补 `@source "../../../../apps/ui-kit/src"`）、`frontend/packages/ui/src/tokens.test.ts`（新建）、`frontend/package.json`（加一条便捷脚本 `dev:ui-kit`）
- 步骤：
  1. 建 `apps/ui-kit`：`base: './'`、开发端口 5175、三个插件（`@vitejs/plugin-react`、`@tailwindcss/vite`、`themeInitPlugin`）；`pnpm-workspace.yaml` 的 `apps/*` 已覆盖它，不需要改。
  2. `main.tsx` 只做组合根：`createWebPlatform()` → `ThemeProvider` → `UiKitPage`；**不引 `react-router`、不调任何后端接口**。
  3. `UiKitPage.tsx` 按组件分节渲染全部变体与状态：Button 的 variant × size × disabled × loading；Input/Label/Checkbox/Switch/Select 的正常、聚焦、禁用、校验错误；Card 的普通态与玻璃层上叠正文、叠次要文本；Table 的有数据与空表体；Dialog/DropdownMenu/Tabs/Toast 由页面上的触发按钮唤起；Spinner/Skeleton/EmptyState/ErrorState；`AppShell` 的布局骨架；顶部放 `ThemeToggle` 与当前主题显示。
  4. 在 `globals.css` 补 `@source "../../../../apps/ui-kit/src"`。**漏这一行，展览页里 ui 包独有的类名会静默丢失**，所以下面专门查产物。
  5. 写 `tokens.test.ts`：扫 `apps/*/src` 与 `packages/ui/src`（排除 `styles/globals.css` 与测试自身），出现十六进制或 `oklch(...)`/`rgb(...)`/`hsl(...)` 颜色字面量即失败。
  6. 浅深两色下逐项核验对比度（正文 ≥ 4.5:1、大文本 ≥ 3:1，半透明层按合成后颜色算），把结果与所用工具记入 `.agent/changes/WORK-002-前端组件化与管理端拆分/testing/`。
  7. 核验 `.glass` 降级：用开发者工具令 `@supports` 条件不成立（或临时禁用 `backdrop-filter`），确认玻璃层仍可读，记录结论。
- 验证：
  - `pnpm --filter @paideia/ui-kit build` 通过；产物 CSS 里能检索到 `.glass` 与 `.dark`（这两个只在 `packages/ui` 里定义，是"扫描路径登记正确"的证据）。
  - `pnpm --filter @paideia/ui-kit dev` 起得来且全部组件可见；对话框键盘可进入、`Esc` 可关闭（记录结论）。
  - `pnpm --filter @paideia/ui test` 通过；故意在某个 app 里写一个 `#fff` 使令牌检查失败再撤销，复核记录写进测试报告。
  - 浅深两色对比度与玻璃降级的核验结论记入 `testing/`。

### TASK-006 | done | 迁移学习者端首页与公开页，删除占位组件

- 对应：`REQ-006`、`AC-001`、`AC-002`、`AC-004`
- 依赖：TASK-005
- 修改：`frontend/apps/app/src/pages/HomePage.tsx`、`frontend/apps/public/src/PublicPage.tsx`、`frontend/packages/ui/src/Panel.tsx`（删除）、`frontend/packages/ui/src/index.ts`
- 步骤：
  1. 用 `AppShell` + `Card` + `Badge` + `Button` + `Spinner` + `ErrorState` 重写 `HomePage`，**保留既有 `data-testid`**（`platform-kind`、`api-base`、`health-state`、`health-status`、`health-raw`、`identity-subject`），使既有 e2e 断言继续有效。
  2. 删掉页面内联 `style`；`PublicPage` 改用 `Card` 与令牌类。
  3. 删除 `Panel.tsx` 与它的导出，确认全仓无引用。
- 验证：
  - `grep -rn "Panel" packages/ui/src apps/*/src` 无命中。
  - `pnpm --filter @paideia/app test:e2e` 通过（需后端 jar 与 3307 隧道，未就绪时记录为未运行并说明缺口）。
  - `pnpm --filter @paideia/public build` 通过。
  - `pnpm -r typecheck && pnpm -r test` 全绿。

### TASK-007 | done | 扩展共享包边界检查

- 对应：`REQ-007`、`AC-007`
- 依赖：TASK-003
- 修改：`frontend/packages/core/src/workspace-boundaries.test.ts`
- 步骤：
  1. 在既有"共享包不得引用桌面壳专有 API"之外，增加规则：`packages/ui` 不得引用 `@paideia/core`、`@paideia/platform-web`、`@paideia/platform-desktop`、`react-router`。
  2. 让扫描覆盖新增子目录（现在是递归的，确认 `components/`、`theme/`、`layout/` 都在覆盖内）并保留"只看 import/require 语句"的既有做法，避免注释误报。
- 验证：
  - `pnpm --filter @paideia/core test` 通过。
  - 在 `packages/ui` 某个文件里临时 `import '@paideia/core'`，检查必须失败；撤销并记录这次复核。

### TASK-008 | done | 会话与角色端口

- 对应：`REQ-005`、`AC-006`
- 依赖：无
- 修改：`frontend/packages/core/src/session.ts`（新建）、`frontend/packages/core/src/index.ts`
- 步骤：
  1. 定义 `Role`、`Session`、`SessionReader`、`createSessionReader({ getToken })`、`canAccessAdmin`；角色不可识别时按 `learner` 处理（失败即拒绝）。
  2. 在注释里写明：客户端解析载荷**不校验签名**，只用于界面分流，不是授权边界；后端角色契约落地后只用改 `getToken` 的来源。
  3. 写 `session.test.ts`：无令牌 → `null`；载荷畸形 → `null`；无 `role` 字段 → `learner`；`role: admin` → 放行且 `canAccessAdmin` 为真。
- 验证：
  - `pnpm --filter @paideia/core test` 通过（含既有边界检查）。

### TASK-009 | done | 管理端应用与路由守卫

- 对应：`REQ-003`、`REQ-005`、`AC-004`、`AC-005`、`AC-006`
- 依赖：TASK-008、TASK-005
- 修改：`frontend/apps/admin/**`（新建：`package.json`、`index.html`、`vite.config.ts`、`tsconfig.json`、`components.json`、`src/{main,env,api,platform,theme,session,router,guard}.tsx|ts`、`src/pages/{AdminConsolePage,AdminDenied}.tsx`、`e2e/admin.spec.ts`）、`frontend/packages/ui/src/styles/globals.css`（补 `@source "../../../../apps/admin/src"`）、`frontend/pnpm-workspace.yaml`（确认 `apps/*` 已覆盖，无需改）
- 步骤：
  1. 按 `apps/app` 的结构建应用：`base: './'`、Hash 路由、开发端口 5174、`/api` 与 `/actuator` 代理、`createWebPlatform()`、`api.ts` 沿用 `getToken: () => null` 的接缝。
  2. `guard.tsx` 的 `RequireAdmin` 只调 `canAccessAdmin`；`#/` 为受保护的管理区，被拒时渲染 `AdminDenied` 并显示当前身份。
  3. `AppShell` 页头放后端健康徽标（真实 `/actuator/health` 数据），使被拒页面上也能证明接口连通。
  4. 写 `e2e/admin.spec.ts`：访问 `/` 断言显示拒绝且身份为学习者；断言健康徽标显示真实 `UP`（不是硬编码）。
- 验证：
  - `pnpm --filter @paideia/admin typecheck`、`test:e2e` 通过。
  - `pnpm --filter @paideia/app build` 后检索 `apps/app/dist` 无 `admin`、无 `ui-kit` 入口；`pnpm -w build:desktop` 后检索 `apps/desktop/renderer` 同样无命中（**这是 AC-005 的证据**；桌面打包若因网络受限，至少完成 `prepare-renderer` 后的检索并如实记录）。

### TASK-010 | done | 修正桌面端缓存的同步读缺陷

- 对应：`REQ-004`（桌面端成立的条件下）、`AC-003`
- 依赖：TASK-002
- 修改：`frontend/apps/desktop/electron/main.cjs`、`frontend/apps/desktop/electron/preload.cjs`、`frontend/packages/platform-desktop/src/index.ts`、`frontend/packages/platform-desktop/src/index.test.ts`（新建）、`frontend/packages/platform-desktop/package.json`（加 vitest 与 `test` 脚本）
- 步骤：
  1. 主进程读 `cache.json`，把内容 base64 后作为 `--paideia-cache-snapshot=` 启动参数传入；preload 解析并作为 `cacheSnapshot` 暴露（`sandbox: true` 下 preload 不能读文件，所以走启动参数）。
  2. 拆出纯函数 `createBridgeCache(bridge, snapshot)`：`get` 读由快照初始化的同步镜像，`set`/`remove` 同步改镜像并异步落盘；`createDesktopPlatform()` 改为调用它。
  3. 写 `index.test.ts`：快照里的键不经过 `set` 就能 `get` 到；`set` 后立即 `get` 可见且桥被异步调用；`remove` 生效；快照非对象时不抛错。
- 验证：
  - `pnpm --filter @paideia/platform-desktop test` 通过。
  - 桌面 e2e 本机仍不可运行（WORK-001 已记录限制）：在测试报告中记为**未运行**并写明缺口，不得写成已验证。

### TASK-011 | done | 长期约定、索引与验证报告

- 对应：全部（沉淀）
- 依赖：TASK-005、TASK-006、TASK-007、TASK-009、TASK-010
- 修改：`.agent/rules/always.md`、`.agent/INDEX.md`、`.agent/history/updates.md`、`.agent/changes/WORK-002-前端组件化与管理端拆分/testing/{plan.md,report.md}`
- 步骤：
  1. `always.md` 增补本项确立的长期约定：前端四个面（`app` 学习者端，同时出 Web 与桌面产物；`admin` 管理端，仅 Web；`public` 公开页；`ui-kit` 组件展览，仅开发与内审、不进任何产品产物）、组件库与样式方案（shadcn/ui + Tailwind v4，令牌唯一来源、`@source` 必须写物理路径）、角色只有管理员与学习者、主题为浅深双主题。
  2. `.agent/INDEX.md` 增加 `apps/admin`、`apps/ui-kit` 与 `packages/ui` 的新行。
  3. 写 `testing/plan.md` 与 `testing/report.md`，逐条对应 AC-001..AC-008，未运行项如实标注。
  4. 追加 `updates.md` 记录，写明本地提交边界。
- 验证：
  - `project-lifecycle.ps1 validate F:\Paideia --json` 无 error。
  - 报告里每条 AC 都指向具体命令与输出，未验证项写明原因。

## 完成条件

- TASK-001..TASK-011 全部 `done`；任何 `blocked` 必须在报告中说明阻塞点。
- `cd frontend && pnpm -r typecheck && pnpm -r test` 全绿。
- `pnpm --filter @paideia/ui-kit build` 与 `pnpm --filter @paideia/public build` 通过，`apps/app` 构建通过。
- AC-001..AC-008 各有可复查证据记入 `testing/report.md`；未运行的检查（桌面 e2e）单独列出并说明缺口。
- `packages/ui` 的占位 `Panel` 已删除且全仓无引用。
- `.agent/rules/always.md` 与 `.agent/INDEX.md` 已更新，`updates.md` 已追加。
