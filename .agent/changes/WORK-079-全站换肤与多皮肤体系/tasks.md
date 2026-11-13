---
artifact: tasks
work_id: WORK-079
work: 全站换肤与多皮肤体系
status: draft
created: 2026-11-13
updated: 2026-11-13
mode: managed
workflow: compact
---

# 实施任务：全站换肤与多皮肤体系

## 依据

- 需求：`requirements.md`（REQ-001…REQ-012 / AC-001…AC-010）。
- `compact` 模式不写 `design.md`，变更清单按 `references/design.md` 的四条硬规则放在下面。
- 用户 2026-11-13 决定分两步走：**先做骨架 + 3 个样板皮肤 + 外观页看效果，确认后再补齐其余皮肤**。

### 变更清单

| # | 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `frontend/packages/ui/src/styles/globals.css:1-4` | 修改 | 只有 `@import 'tailwindcss'` 与 `@import 'tw-animate-css'` | 追加 `@import './theme/skins.css'`、`@import './theme/decor.css'` | REQ-001 |
| 2 | `frontend/packages/ui/src/styles/globals.css` 的 `:root` | 新增 | 无装饰层、无标题字体令牌 | `--decor-image/size/opacity/blend`（默认 0 关闭）、`--font-display: var(--font-sans)` | REQ-009 |
| 3 | `frontend/packages/ui/src/styles/globals.css` 的 `@layer base` h1/h2/h3 | 修改 | 只有 `font-weight` 与 `letter-spacing` | 增加 `font-family: var(--font-display)`（默认等价于正文，零变化） | REQ-009 |
| 4 | `frontend/packages/ui/src/styles/theme/skins.css` | 新增 | 无（只有亮/暗两套令牌） | 皮肤层入口，逐族 `@import` | REQ-001 |
| 5 | `frontend/packages/ui/src/styles/theme/skins/{paper-tone,night,terminal}.css` | 新增 | 无 | `paper` / `neon` / `terminal` 三个皮肤，每个 `:root[data-skin='x']` + `:root[data-skin='x'].dark` | REQ-008 |
| 6 | `frontend/packages/ui/src/styles/theme/decor.css` | 新增 | 无装饰层；无 `@media print` 规则 | `body::before` 装饰层（默认透明）+ 打印时把令牌强制回纸张语义 | REQ-009 / REQ-010 |
| 7 | `frontend/packages/ui/src/theme/keys.ts` | 修改 | 只有 `Theme` / `THEME_KEY` / `THEME_STORAGE_KEY` / `isTheme` | 增加 `SkinId`、`SKIN_KEY='skin'`、`SKIN_STORAGE_KEY='paideia:skin'`、`DEFAULT_SKIN='default'` | REQ-002 / REQ-006 |
| 8 | `frontend/packages/ui/src/theme/skins.ts` + `theme/skins/*.ts` | 新增 | 无（皮肤概念不存在） | 注册表 `SKINS`/`SKIN_FAMILIES`/`isSkin`/`skinById`，每族一个文件 | REQ-002 |
| 9 | `frontend/packages/ui/src/theme/theme-context.tsx` | 修改 | `ThemeContextValue` 只有 `theme/setTheme/toggleTheme`，只切 `.dark` | 增加 `skin`/`setSkin`；同时写 `data-skin`；换肤与明暗都派发主题变更事件 | REQ-003 / REQ-011 |
| 10 | `frontend/packages/ui/src/theme/theme-events.ts` | 新增 | 无 | `THEME_CHANGE_EVENT` + `subscribeToThemeChange()` | REQ-011 |
| 11 | `frontend/packages/ui/src/theme/vite-plugin.ts` | 修改 | 首屏脚本只落 `dark` 类 | 同时落 `data-skin`（桌面快照 → localStorage → 默认皮肤） | REQ-006 |
| 12 | `frontend/packages/ui/src/theme/theme-picker.tsx` | 新增 | 无 | 皮肤网格 + 族分组 + 关键词过滤；卡内自带 `data-skin` 做活预览 | REQ-004 |
| 13 | `frontend/packages/ui/src/theme/theme-css.ts` | 新增 | 无（没有解析 CSS 级联的测试工具） | 顶层规则解析 + 皮肤块收集，供契约与对比度测试共用 | AC-002 / AC-003 |
| 14 | `frontend/packages/ui/src/theme/skin-contract.test.ts` | 新增 | 无 | 四类错误的守卫 + 首屏脚本执行验证 | AC-001 / AC-002 |
| 15 | `frontend/packages/ui/src/theme/contrast.test.ts` | 修改 | 只解析并断言 `:root` 与 `.dark` 两套（2 组 × 5 条） | 遍历（皮肤 × 明暗）共 8 组 × 5 条，失败信息带皮肤 id | AC-003 |
| 16 | `frontend/packages/ui/src/theme/theme-context.test.tsx` | 修改 | 5 条用例，只覆盖明暗 | 增加 4 条皮肤用例（初始值/脏值回退/写属性+存储/事件） | REQ-003 |
| 17 | `frontend/packages/ui/src/index.ts` | 修改 | 只导出主题三件套 | 增加 `SkinPicker`、注册表、`subscribeToThemeChange` | REQ-004 |
| 18 | `frontend/apps/app/src/pages/AppearancePage.tsx` | 新增 | 无 | 外观页 `AppShell wide` + `PageHeader` + `SkinPicker` | REQ-004 |
| 19 | `frontend/apps/app/src/router.tsx` | 修改 | 无 `/settings/appearance` 路由 | 新增路由（紧挨 `/settings/mcp`） | REQ-004 |
| 20 | `frontend/apps/app/src/components/LearnerHeader.tsx` | 修改 | 账号菜单没有「外观」项 | 增加「外观」项（`data-testid="account-appearance"`） | REQ-005 |
| 21 | `frontend/apps/ui-kit/src/pages/UiKitPage.tsx` | 修改 | 组件陈列窗里没有皮肤一栏 | 追加「皮肤」Section（设计系统陈列窗的一部分） | REQ-004（附赠：让维护者一处就能验皮肤） |

## 全局约束

- 颜色只能出现在 `frontend/packages/ui/src/styles/` 下（`tokens.test.ts` 拦）；TS/TSX 一律用令牌类，预览卡也是。
- 皮肤令牌值写字面量 `hsl(h s% l%)`，不写 `var()` 间接链（测试要能直接解析）。
- 选择器固定两种形态：`:root[data-skin="<id>"]`（0,2,0）与 `:root[data-skin="<id>"].dark`（0,3,0），不依赖 `@import` 顺序。
- 每个皮肤只要覆盖了某个令牌，就必须在亮/暗两块里都给值（契约测试强制）。
- `--paper*` 禁止被任何皮肤覆盖（契约测试的保留名单）。
- 不新增运行时依赖；不引 webfont；不改 `@theme inline` 的既有映射。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 皮肤只写浅色块、暗色下继承默认令牌导致深底深字 | TASK-005 | `skin-contract.test.ts` → `每个皮肤都有亮色块与暗色块`、`每个皮肤亮暗两套覆盖的令牌完全相同` |
| 令牌名拼错，静默不生效 | TASK-005 | `skin-contract.test.ts` → `皮肤只覆盖已知令牌名` |
| CSS 有块但注册表没登记 / 注册表登记了没 CSS | TASK-002 | `skin-contract.test.ts` → `注册表与皮肤 CSS 双向一致` |
| 某个皮肤某个模式下对比度掉到 4.5:1 以下 | TASK-005 | `contrast.test.ts` → `$skinName（$skinId）· $label 的对比度`（5 条/组） |
| 首屏闪一帧默认主题 | TASK-003 | `skin-contract.test.ts` → `已保存的皮肤在首屏就写到 <html> 上` |
| 存储里是脏皮肤 id，界面无解释地回退 | TASK-003 | `theme-context.test.tsx` → `DOM 上是没登记过的脏值时回退默认皮肤` |
| 换肤后图谱节点色仍是旧的（`resolveVar` 只读一次） | TASK-012（待做） | e2e `appearance.spec.ts` → 换肤后图谱节点颜色变化 |
| 深色皮肤打印出黑底 | TASK-006 | e2e `appearance.spec.ts` → print 媒体下背景回到纸色（待做） |
| 皮肤顺手改了 `--paper*`，简历 PDF 变色 | TASK-005 | `skin-contract.test.ts` → `纸张令牌不在任何皮肤的覆盖范围` |
| 装饰层挡住点击 | TASK-006 | 已用 `pointer-events: none` + `z-index: -1`；e2e 待补 |

## 任务

### TASK-001 | done | 皮肤层骨架 + `paper` 样板皮肤

- 对应：`REQ-001`、`AC-001`
- 依赖：无
- 新建/修改：`styles/globals.css`（两条 `@import` + 饰品令牌）、`styles/theme/skins.css`、`styles/theme/skins/paper-tone.css`
- 步骤：1) 顶部加 `@import`；2) 建 `skins.css` 入口；3) 写 `paper` 亮/暗两块。
- 验证：`pnpm exec vitest run src/theme` → 通过（见 `testing/report.md`）

### TASK-002 | done | 皮肤注册表与轴元数据

- 对应：`REQ-002`、`AC-001`
- 依赖：TASK-001
- 新建/修改：`theme/keys.ts`、`theme/skins.ts`、`theme/skins/{paper-tone,night,terminal}.ts`
- 验证：`skin-contract.test.ts` → `注册表与皮肤 CSS 双向一致` 通过

### TASK-003 | done | 主题运行时（上下文 / 存储 / 首屏 / 事件）

- 对应：`REQ-003`、`REQ-006`、`REQ-011`
- 依赖：TASK-002
- 新建/修改：`theme/theme-context.tsx`、`theme/theme-events.ts`、`theme/vite-plugin.ts`
- 验证：`theme-context.test.tsx` 9 条 + `skin-contract.test.ts` 的两条首屏脚本用例通过

### TASK-004 | done | 外观页 + 账号菜单入口 + 预览卡

- 对应：`REQ-004`、`REQ-005`、`AC-004`
- 依赖：TASK-003
- 新建/修改：`theme/theme-picker.tsx`、`apps/app/src/pages/AppearancePage.tsx`、`apps/app/src/router.tsx`、`LearnerHeader.tsx`
- 验证：`pnpm --filter @paideia/app typecheck` 0 error；`pnpm --filter @paideia/ui-kit build` 成功；截图见 `testing/report.md`

### TASK-005 | done | 契约测试与对比度测试升级（全皮肤遍历）

- 对应：`REQ-012`、`AC-002`、`AC-003`
- 依赖：TASK-002
- 新建/修改：`theme/skin-contract.test.ts`、`theme/contrast.test.ts`、`theme/theme-css.ts`
- 验证：8 组（皮肤 × 明暗）× 5 条对比度断言全绿；两类反向验证（拼错令牌名、删暗色块）均判红（见 report）

### TASK-006 | done | 质感装饰层 + 打印强制浅色

- 对应：`REQ-009`、`REQ-010`、`AC-008`
- 依赖：TASK-001
- 新建/修改：`styles/theme/decor.css`、`globals.css` 的 `:root` 装饰令牌
- 验证：`skin-contract.test.ts` → `装饰层默认关闭` 通过；`paper`/`neon` 的装饰层在截图中可见
- 未完成：print 媒体的 e2e 断言（并入 TASK-013）

### TASK-007 | done | 复古纸质族：`paper`

- 对应：`REQ-008`
- 依赖：TASK-001、TASK-002
- 验证：对比度 `暖纸 · 亮色/暗色` 两组 10 条断言通过

### TASK-008 | done | 霓虹暗夜族：`neon`

- 对应：`REQ-008`
- 依赖：TASK-001、TASK-002
- 验证：对比度 `霓虹夜 · 亮色/暗色` 通过；截图确认光晕与发光描边

### TASK-009 | done | 终端像素族：`terminal`

- 对应：`REQ-008`
- 依赖：TASK-001、TASK-002
- 验证：对比度 `终端 · 亮色/暗色` 通过；截图确认等宽字体与零圆角

### TASK-010 | done | 补齐其余 13 个皮肤（首批共 17 个）

- 对应：`REQ-008`
- 依赖：TASK-001、TASK-002、TASK-006
- 实际做法：按族分文件写（每族一个 CSS + 一个注册 TS），互不抢文件：
  复古纸质追加 `sepia`、`newsprint`；暗夜追加 `midnight`；终端族追加 `pixel`；
  新建 `nature.css`（`forest`/`sand`/`ocean`）、`pastel.css`（`pastel`/`sakura`/`candy`）、
  `glass.css`（`glass`/`glass-warm`）、`contrast.css`（`contrast`）
- 同期补上对比度测试的一个漏洞：`--card` 带 alpha 时（玻璃族）按"卡面与页面底色的合成色"算，
  否则半透明卡面会高估对比度
- 验证：`pnpm exec vitest run src/theme` → `Tests 191 passed`（17 皮肤 × 2 模式 × 5 条 = 170 条对比度断言）；`pnpm --filter @paideia/ui-kit build` → CSS 156 149 字节、`data-skin` 规则 64 条

### TASK-011 | pending | 换肤联动：图谱重读、编辑器与代码块跟随

- 对应：`REQ-011`、`AC-007`
- 依赖：TASK-003
- 新建/修改：`apps/app/src/components/graph/tier-style.ts`、`GraphCanvas2D.tsx`、`GraphCanvas3D.tsx`
- 步骤：1) `tier-style.ts` 加 `useTierColors()`（订阅 `subscribeToThemeChange`）；2) 两个画布改为依赖该 hook 的返回值并重建画布；3) 手动核查 `/practice/sql` 编辑器与 markdown 代码块在 `neon`/`paper` 下的跟随。
- 验证：e2e 图谱用例通过 + 人工截图记录

### TASK-012 | pending | e2e：入口 / 切换 / 持久化 / 首屏不闪 / print

- 对应：`AC-004`、`AC-005`、`AC-006`、`AC-008`、`AC-009`
- 依赖：TASK-004、TASK-006、TASK-011
- 新建：`apps/app/e2e/appearance.spec.ts`
- 步骤：1) 账号菜单 → 外观 → 选 `paper` → 断言 `html[data-skin]` 与 `--background` 变化；2) reload 后属性仍在且无需交互；3) 关键词筛选与空状态；4) `emulateMedia({media:'print'})` 下背景回到纸色；5) 跑既有的 `ui-kit.spec.ts` 与 `markdown-render.spec.ts` 主题用例。
- 验证：`bash ../scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/appearance.spec.ts` 全绿

### TASK-013 | pending | 规格文档与收尾

- 对应：`AC-001`、`AC-009`
- 依赖：TASK-010、TASK-012
- 新建/修改：`.agent/specs/platform-contracts.md` 增加「设计令牌与皮肤契约」小节
- 步骤：写清"加一个皮肤的三步"（加 CSS 块 → 加注册条 → 跑两个测试）与禁止事项（不改 `--paper*`、不把颜色写进 TS、不覆盖未知令牌）
- 验证：文档提交后 `pnpm --filter @paideia/ui test` 与两个 typecheck 全绿

## 完成条件

- AC-001…AC-010 全部有命令与输出摘要（`testing/report.md`）。
- 17 个皮肤 × 2 种模式全部过对比度断言。
- 外观页可从账号菜单进入；切换即时生效、刷新保持、首屏不闪。
- 图谱/编辑器/代码块换肤后跟随；打印与纸张语义未被破坏。
