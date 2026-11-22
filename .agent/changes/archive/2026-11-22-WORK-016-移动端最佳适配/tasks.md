---
artifact: tasks
work_id: WORK-016
work: 移动端最佳适配
status: approved
mode: strict
workflow: full
depends_on: [design]
related_to: [WORK-002, WORK-011, WORK-012, WORK-014, WORK-015]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 09:02:42 +0800
approver_role: CTO
---

# 任务 · 移动端最佳适配

**排序原则**：先做**共享层**（基础设施 → `Dialog` → 外壳），让后面所有页面天然受益；再做页面级细节；最后补 PWA 与测试矩阵。这样中途随时可停，已完成的切片都能独立验证。

**全局约束**：不引新运行时依赖（PWA 插件是 devDependency）；不改后端；不改现有桌面断点与类名；每完成一个切片提交一次。

## 任务

### TASK-001 | done | 视口、安全区与动态视口基线

- 对应：REQ-001、REQ-002、AC-008
- 修改：`frontend/apps/app/index.html`、`frontend/apps/admin/index.html`、`frontend/apps/public/index.html`、`frontend/apps/ui-kit/index.html`、`frontend/packages/ui/src/styles/globals.css`
- 步骤：
  1. 四个 `index.html` 的 viewport 加 `viewport-fit=cover`；`app` 与 `admin` 加 `theme-color`（浅色值）。
  2. `globals.css`：`body` 的 `min-height: 100vh` → `100dvh`。
  3. `@theme` 加 `--touch-min: 2.75rem`；`@layer utilities` 加 `.safe-x` / `.safe-b` / `.safe-t` / `.touch-target`。
  4. 确认 `@source` 无需新增（没有新包）。
- 验证：`pnpm --filter @paideia/ui test`；`pnpm -r typecheck`；`rg -n "100vh" frontend` 只在非全高用途出现。

### TASK-002 | done | `Dialog` 窄屏形态

- 对应：REQ-003、REQ-018、REQ-023、AC-013
- 修改：`frontend/packages/ui/src/components/dialog.tsx`
- 步骤：
  1. `DialogContent` 基础类按设计分支：`<sm` 贴底全屏面板、`≥sm` 居中浮层。
  2. `DialogFooter` 在 `<sm` 改 `sticky bottom-0` + `safe-b`。
  3. 加 `data-[slot=dialog-handle]` 装饰把手（`<sm` 可见，`aria-hidden`）。
  4. 动画：`<sm` 底部滑入、`≥sm` 保持 zoom。
- 验证：新增单测断言类名；手工核对 6 个调用点（管理端编辑器、条目删除、助手记忆清空、账号注销、举报、新建条目选择）。

### TASK-003 | done | 学习者端导航抽屉

- 对应：REQ-004、REQ-019、REQ-021、AC-002
- 修改：`frontend/apps/app/src/components/LearnerHeader.tsx`
- 步骤：
  1. 加 `☰` 按钮（`md:hidden`），打开 `Dialog` 抽屉。
  2. 抽屉内容：`NAV` 三项（整行 44px、`aria-current`）+ 搜索框 + 主题开关。
  3. 点击项后关闭；关闭后焦点回触发按钮（Base UI Dialog 默认行为，需确认）。
  4. `≥md` 保持现有横排导航与搜索框。
- 验证：移动 e2e 断言打开、三项可见、点击跳转、焦点还原；桌面 e2e 现有 44 条不受影响。

### TASK-004 | done | 学习者端页面窄屏收敛

- 对应：REQ-005..012、AC-001、AC-003、AC-009
- 修改：`app-shell.tsx`、`HomePage`、`KnowledgeCategoriesPage`、`CategoryEntriesPage`、`KnowledgeEntryPage`、`NotesPage`、`NoteNewPage`、`MyAssessmentsPage`、`SharedNotePage`、`packages/auth/src/auth-layout.tsx`
- 步骤：
  1. `AppShell` 页头/主内容 `px-6` → `px-4 sm:px-6`。
  2. 条目页 `<lg` 目录改为可展开（`<details>` 或 `Dialog`），不直接消失。
  3. 各页按设计表收紧内边距、字号与操作按钮命中区。
  4. 长 URL / 表格 / 图片补 `break-words` 与 `max-w-full`。
- 验证：360px 无溢出冒烟；条目页目录可展开；交互块命中区断言。

### TASK-005 | done | 交互块窄屏

- 对应：REQ-008、AC-004
- 修改：`frontend/packages/ui/src/markdown/renderers/{code-tour,code-map,code-challenge,quiz,learning-path}.tsx`
- 步骤：
  1. 按钮/选项加 `touch-target` 或 `min-h-11`。
  2. 代码区 `max-h-[60dvh] overflow-y-auto`。
  3. `code-map` 横向滚动提示。
- 注意：`code-challenge.tsx` 可能与 WORK-015 冲突，实施前确认那项是否已合并；未合并则本任务只做纯样式行，不碰逻辑。
- 验证：WORK-014 的 360px 用例继续通过；补命中区断言。

### TASK-006 | done | 助手底部抽屉

- 对应：REQ-013..015、AC-005
- 修改：`frontend/packages/assistant/src/assistant-shell.tsx`
- 步骤：
  1. `matchMedia('(max-width: 639px)')` 订阅 `narrow`。
  2. 抽出内部内容为局部组件，浮动窗与抽屉共用。
  3. 抽屉：`inset-x-0 bottom-0 max-h-[85dvh] rounded-t-2xl`，隐藏拖拽/调宽把手。
  4. 窄屏不写 `x/y/w/h` 到 localStorage。
  5. 遮罩/`Esc`/按钮三种关闭；焦点回启动按钮。
  6. `clampToViewport` 用 `visualViewport?.height ?? innerHeight`。
- 验证：`assistant.spec.ts` 的 700px 用例仍过；新增窄屏抽屉用例；单测覆盖 `narrow` 分支。

### TASK-007 | done | 管理端抽屉侧栏

- 对应：REQ-016、AC-006
- 修改：`frontend/apps/admin/src/components/AdminShell.tsx`
- 步骤：
  1. `<md` 侧栏改 `fixed` 抽屉 + 遮罩 + `☰`；选中后关闭。
  2. `h-screen` → `h-dvh`，`min-h-screen` → `min-h-dvh`。
  3. `≥md` 现有行为不变（`collapsed` 只在桌面生效）。
- 验证：375px 下打开抽屉、到达全部菜单、选中后关闭；桌面 9 条 e2e 全绿。

### TASK-008 | done | 管理端表格转卡片

- 对应：REQ-017、AC-007
- 修改：`frontend/apps/admin/src/pages/{KnowledgeEntriesPage,KnowledgeCategoriesPage,NoteReportsPage,AiCreditsAuditPage}.tsx`
- 步骤：
  1. 每个页面加 `<md` 的卡片列表，与表格共用 `rows`。
  2. 现有 `data-testid` 保留在桌面分支；卡片用 `-card` 后缀。
  3. 操作按钮 44px。
- 验证：375px 六页面无溢出、卡片含全部关键字段与操作；桌面 e2e 全绿。

### TASK-009 | done | PWA

- 对应：REQ-024..028、AC-014、AC-015
- 修改：`frontend/apps/app/vite.config.ts`、`package.json`、`public/` 三份图标、`index.html`
- 步骤：
  1. `pnpm --filter @paideia/app add -D vite-plugin-pwa`。失败则记录原因，退回到 `vite build` 后置脚本手写最小 SW（不进本项验收，另开项）。
  2. 按设计配置 `VitePWA`，`devOptions.enabled = false`。
  3. Pillow 生成 192/512/512-maskable 图标。
  4. `index.html` 加 `theme-color`。
  5. 生产构建后核对产物：`sw.js`、`manifest.webmanifest`、图标。
- 验证：`pnpm --filter @paideia/app build` 成功；体积增量记录；`preview` 下 SW 注册成功、`caches.keys()` 不含 API；现有 e2e（dev 模式）不受影响。

### TASK-010 | done | 桌面壳最小窗口

- 对应：REQ-022、AC-012
- 修改：`frontend/apps/desktop/electron/main.cjs`
- 步骤：`BrowserWindow` 加 `minWidth: 360, minHeight: 480`。
- 验证：`pnpm -w build:desktop` 后手测拖窄；或仅核对配置（桌面打包耗时，按需）。

### TASK-011 | done | 移动测试矩阵

- 对应：AC-010、AC-011
- 修改：`frontend/apps/app/playwright.config.ts`、`frontend/apps/admin/playwright.config.ts`、新增 `e2e/*.mobile.spec.ts`
- 步骤：
  1. 两个 config 各加 `mobile` project（`Pixel 7`），`testMatch: '**/*.mobile.spec.ts'`；桌面 project 加 `testIgnore`。
  2. app：11 路由 360px 冒烟 + 导航抽屉 + 助手抽屉 + 条目目录 + 交互块命中区。
  3. admin：6 页面 375px 冒烟 + 抽屉 + 卡片。
  4. PWA spec：仅在生产预览下跑，用环境变量开关，默认跳过。
- 验证：`pnpm --filter @paideia/app test:e2e` 与 `--filter @paideia/admin test:e2e` 全绿；移动 project 全绿。

### TASK-012 | done | 验收与报告

- 对应：AC-001..015
- 步骤：跑完整验证矩阵、记录体积与真机人工走查结果、写 `testing/plan.md` 与 `testing/report.md`。

## 依赖图

```text
T-001 ─┬─ T-002 ─┬─ T-004 ── T-005
       │         └─ T-007 ── T-008
       ├─ T-003 ─┘
       ├─ T-006
       └─ T-009 ── T-011 ── T-012
T-010 ─────────────────────┘
```

T-005 需在 WORK-015 的 `code-challenge` 改动合并后执行。
