---
artifact: testing_report
work_id: WORK-080
work: 图版皮肤：结构层与排版层（三个激进样板）
status: in_progress
updated: 2026-11-15
commit: 本分支首个提交 work/WORK-080（含本文件）
---

# 验证报告

阶段：**三个结构型皮肤（粗野主义 / 杂志排版 / 九八）已实现、已测、已截图**；批量扩到其余候选皮肤不在本项内。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-003 全皮肤对比度（20 皮肤 × 2 模式 × 5 项 = 200 条）+ 令牌契约 + 结构层白名单 + 主题上下文 | `pnpm --filter @paideia/ui exec vitest run src/theme` | 0 | passed | `Test Files 4 passed (4)`、`Tests 228 passed (228)`（contrast 200 / skin-contract 12 / skin-structure 7 / theme-context 9） |
| 共享包全量回归（确认改样式没碰坏其它模块） | `pnpm --filter @paideia/ui test` | 0 | passed | `Test Files 23 passed (23)`、`Tests 476 passed (476)`，26.82s |
| 共享包类型检查（`SkinFamily` 联合字面量扩了三项） | `pnpm --filter @paideia/ui typecheck` | 0 | passed | 无诊断输出 |
| 应用类型检查（`preferredNav` 等用到族/皮肤类型的调用点） | `pnpm --filter @paideia/app typecheck` | 0 | passed | 无诊断输出 |
| AC-006 结构规则进了生产产物 | `pnpm --filter @paideia/ui-kit build` | 0 | passed | CSS `dist/assets/index-BKmcm7Yy.css` 167 629 字节（WORK-079 时 156 149）；`data-skin=` 出现 109 次、`data-slot=` 44 次、含 `--decor2-image` 与 `outset`；grep 到 `[data-skin=brutal] [data-slot=card],…{border-width:2px;border-color:var(--foreground);box-shadow…` |
| AC-001 白名单测试能拦住布局属性 | 见「反向验证」R1 | 0 | passed | 临时插入 `position: relative; width: 999px` → 1 failed，两条信息逐属性点名；还原后 228 全绿 |
| AC-004 默认主题未变 | `skin-contract.test.ts` 断言 + `globals.css` 评审 | 0 | passed | `--decor2-opacity` 默认 `0`、`--decor2-image` 默认 `none`；`default` 皮肤不含任何 CSS 块 |
| AC-005 打印时两层装饰都关 | `decor.css` 的 `@media print` + CDP `Emulation.setEmulatedMedia({media:'print'})`（WORK-079 同法实测） | 0 | passed | 打印块内 `--decor2-opacity: 0` 且 `body::before, body::after { display: none }`；WORK-079 实测打印下 `body` 背景 `rgb(255, 255, 255)` |
| AC-002 三个皮肤的运行时可读铁证 | 系统 Chrome + CDP（`shot-graphic.mjs`，点真实皮肤卡与真实明暗按钮） | 0 | passed | `graphic-report.json`：brutal `radiusLg=0rem` / `cardRadius=0px` / `cardShadow=rgb(13, 13, 13) 4px 4px 0px 0px` / `stored=brutal`；editorial `cardRadius=2px` / `bodyAfter=repeating-linear-gradient(rgba(122, 101, 82, 0.06) …)`；win98 `cardRadius=0px` / `cardShadow=none`；暗色 `--background` 分别为 hsl(0 0% 6%) / hsl(28 14% 9%) / hsl(240 25% 12%)；console 错误 0 |
| AC-007 文档已更新 | 评审 `.agent/specs/platform-contracts.md` | 人工 | passed | 三层结构表加「结构」行与 `body::after`；新增「结构层白名单（WORK-080）」；补「`@theme inline` 把字号内联成字面量，改 `--text-3xl` 无效」这条坑 |
| AC-002 观感抽查（不只看数字） | Chrome headless 截图 1440×1000 | 人工 | passed | `F:\Paideia\.worktrees\WORK-080-shots\`：`{brutal,editorial,win98}-{light,dark}-{top,controls,table}.png` 共 18 张 |

## 反向验证（证明守卫真的会红）

| # | 怎么弄坏 | 结果 |
| --- | --- | --- |
| R1 | 在 `graphic.css` 末尾追加 `[data-skin='brutal'] [data-slot='card'] { position: relative; width: 999px; }` | `× 只改形状，不改布局（布局属性一律拒绝）`；断言信息两条：`graphic.css「[data-skin='brutal'] [data-slot='card']」改了布局属性 position——布局必须在所有皮肤下一致，否则某些皮肤下功能会消失`、同句的 `width`；从 `$env:TEMP\graphic.bak` 还原后确认无残留、228 全绿 |
| R2 | `border-radius` 一开始不在 BORDER 正则里（`/^border(-(top|right|bottom|left))?-(width|style|color)$/`） | 首次运行即红（合法属性被误判成非法）→ 正则改成 `/^border(-(top|right|bottom|left|inline|block)){0,2}-(width|style|color|radius)$/` 后转绿。这条记录的是**测试自己的 bug**，留档是因为它说明白名单必须自带反向用例 |

## 运行时与截图证据

- 驱动方式：脚本点 `[data-testid="skin-card-brutal"]` 这类真实卡片、点真实 `[data-testid="theme-toggle"]`，不直接写 `data-skin` 或 `class`——这样 React 那条状态路也被覆盖。
- `graphic-report.json` 同时记录了每张截图对应的皮肤、明暗、`--background`、卡片圆角与阴影、`body::after` 的 `background-image`、localStorage `paideia:skin` 与 console 错误（0 条）。
- 一次视觉返工（截图抓出来的）：`editorial` 的网格原为 24px 间距 / 7% 线色 / `opacity 0.6`，格线落在正文行间反复切字 → 改 32px 间距 / 6% 线色 / `opacity 0.5`（实际可见度约 3%），暗色同步，重跑截图确认。这条正是"白名单证明不了好看，必须人眼过截图"的实例。

## 未覆盖与遗留

1. **Playwright e2e 未执行**：`apps/app/e2e/appearance.spec.ts`（WORK-079 写的五条）在本机跑不了——`chromium_headless_shell-1248` 缺失（`playwright install chromium-headless-shell` 只下到 ffmpeg）、`scripts/test-db.sh` 拒绝 `backend/config/remote-db.local.env` 里的远程库（远端账号建不了派生库）。本项的运行时证据全部来自 CDP，属于脚本证据，不等同于 e2e 证据。
2. **其余候选皮肤未做**：`mac-classic` / `swiss` / `notebook` / `cel` / `cyber` / `typewriter` / `bauhaus` / `highlighter` / `compact` 九个已列出但未实现（本项只证明机制可行）。
3. **`apps/admin` / `apps/public` 未逐个截图**：两者共用同一份 `@paideia/ui/styles.css`，样式代码同源，风险来自"某组件在管理端排得更密"——留到批量扩皮肤时连同 `admin` 一起抽查。
