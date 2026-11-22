---
artifact: requirements
work_id: WORK-079
work: 全站换肤与多皮肤体系
status: approved
created: 2026-11-13
updated: 2026-11-13
mode: managed
mode_reason: 改动集中在 frontend/packages/ui 的样式令牌层与主题运行时，不碰后端接口、数据库、部署与安全面；但令牌契约被四个前端应用（app/admin/public/ui-kit）共用，且要新增路由与入口，跨会话追踪必要，故 managed（compact）而不是 lite；也不构成 strict 所要求的公共接口/数据/迁移变更。
workflow: compact
depends_on: []
related_to: [WORK-018, WORK-021, WORK-051, WORK-075]
approved_by: xiaou61
approved_at: 2026-11-13 10:25:12 +0800
approver_role: owner
---

# 需求：全站换肤与多皮肤体系

## 背景

用户 2026-11-13 的原话：「我想弄一个全站换肤，支持很多很多的皮肤……就是支持各种各样的风格的。」

现状（仓库事实）：整站视觉已经完全走令牌，`packages/ui/src/styles/globals.css` 里 `:root` / `.dark` 两套 CSS 变量是唯一颜色来源，`tokens.test.ts` 禁止组件里出现硬编码颜色，`contrast.test.ts` 保证这两套的 WCAG 对比度。主题运行时只有"亮/暗"二元：`theme-context.tsx` 只切换 `<html class="dark">`，首屏脚本 `vite-plugin.ts` 在首次绘制前落这个类。

也就是说，**"很多皮肤"缺的不是组件改造，而是令牌层与主题运行时的结构**：现在只有两个主题，且第二个只能靠 `.dark` 类表达。要支持几十种风格，必须让"皮肤"成为一等公民（可注册、可枚举、可测试、可持久化、首屏不闪），同时不把组件改乱。

用户 2026-11-13 已选择的方向：先做 12–16 个精选皮肤、架构按"轴"组织以便以后无限加；入口放在账号菜单 + 独立外观页；一期只做预设、架构留自定义扩展位；首批要覆盖八个风格族（极简黑白 / 暖纸复古 / 霓虹暗夜 / 护眼自然 / 玻璃拟态 / 终端像素 / 粉彩 / 高对比无障碍）。

## 已确认事实

1. 令牌唯一定义处：`frontend/packages/ui/src/styles/globals.css:80`（`:root`）与 `:183`（`.dark`）；`@theme inline`（`:243`）把令牌映射成 Tailwind 工具类；`--radius` 派生 `--radius-sm/md/lg/xl`（`:299-302`）；`--font-sans` / `--font-mono` 在 `:335-340`，构建产物里落在 `@layer theme` 的 `:root` 上（已核对 `apps/app/dist/assets/index-BJAg3Gc0.css`）。
2. 四个应用都 `import '@paideia/ui/styles.css'`（exports 里映射到同一个 globals.css）：`apps/app/src/main.tsx:3`、`apps/admin/src/main.tsx`、`apps/ui-kit/src/main.tsx`、`apps/public/src/entry-client.tsx:1`（public 是预渲染 + hydrate）。
3. 主题运行时：`theme-context.tsx`（ThemeProvider/useTheme，只有 `theme: 'light'|'dark'`）、`keys.ts`（`THEME_KEY='theme'`、`THEME_STORAGE_KEY='paideia:theme'`）、`theme-toggle.tsx`、`vite-plugin.ts`（首屏防闪脚本，来源优先级：桌面壳 `window.paideiaDesktop.cacheSnapshot` → localStorage → 系统偏好）。
4. 持久化通道：`platform.cache`（`packages/platform-web/src/index.ts` 加 `paideia:` 前缀；`packages/platform-desktop` 的 `cacheSnapshot` 是**通用** `Record<string,string>`，Rust 侧 `main.rs:240` 把整张小表注入页面，新增键不需要改 Rust）。
5. 已有的"刻意不跟随主题"的令牌必须保留：`--paper*`（简历纸张，WORK-018）、`--sk-*`（手绘 rough.js 配色，WORK-051）、`--tier-*`（掌握度档位，WORK-021，图谱画布用 `getComputedStyle` 读取，见 `apps/app/src/components/graph/tier-style.ts:56-64`）。
6. 守卫测试：`tokens.test.ts`（组件里禁止硬编码颜色，扫描 ui 包与全部 app 的 src，豁免 `styles/` 目录与 `.test.` 文件）、`contrast.test.ts`（解析 globals.css 的 `:root` 与 `.dark` 两个块算 WCAG）、`styles/source-coverage.test.ts`（校验 `@source` 目录真实存在）、`styles/streamdown-tokens.test.ts`。
7. e2e 依赖现有主题行为，不能破坏：`apps/ui-kit/e2e/ui-kit.spec.ts:71-79`（点 `theme-toggle` → `html.dark` → 刷新后仍是深色）、`apps/app/e2e/markdown-render.spec.ts:97-103`（切深色后代码块底色/文字色变化）。
8. 账号菜单在 `apps/app/src/components/LearnerHeader.tsx:372-416`（已有「目标与阶段」`/profile`、「MCP 接入」`/settings/mcp`、注销、登出）；`/settings/*` 路由已存在（`router.tsx:362`）。
9. CodeMirror 主题 `packages/ui/src/code-editor/theme.ts` 全部用 `var(--*)`，换肤理论上自动跟随；`streamdown` 代码块与 `--sdm-*` 由它的 dist 生成，需验证。
10. `globals.css` 目前**没有** `@media print` 规则；`.glass` 类定义了但**零处使用**（本仓库内没有组件引用它）。

## 用户决定

1. 首批规模：先做 12–16 个精选皮肤，架构按"轴组合"组织，以后加皮肤基本只加数据。
2. 入口：账号菜单加「外观」入口 + 独立外观页（不是塞进弹层）。
3. 自定义程度：一期只做预设，但架构留好自定义（色相/圆角/字体/质感）的扩展位，二期再加滑块。
4. 风格覆盖：八个族都要来一点——极简黑白 / 暖纸复古 / 霓虹暗夜 / 护眼自然 / 玻璃拟态 / 终端像素 / 粉彩 / 高对比无障碍。
5. **分两步交付**：先做骨架 + 3 个样板皮肤 + 外观页，看过效果之后再补齐其余皮肤（避免 17 个皮肤一次堆完才发现风格不合口味）。

## 目标

1. **皮肤成为一等公民**：新增一个皮肤 = 一份令牌覆盖（CSS）+ 一条注册信息（TS），不改任何组件、不改 `@theme inline` 映射。
2. **全站一致生效**：四个前端应用共用同一套皮肤，切换即时生效、不刷新、不闪烁。
3. **可枚举、可选、可持久化**：外观页能浏览/筛选/预览全部皮肤；选择跨刷新与跨重启保持。
4. **质量不随皮肤数量下滑**：每个（皮肤 × 明暗）组合都由测试强制满足 WCAG AA 与令牌契约完整性，而不是靠人工抽查。
5. **架构可扩张**：以后加到 50 个皮肤时，成本是"加数据 + 跑测试"，不是"再改一次架构"。

## 非目标

- 不做第二个主题系统（不引 next-themes / shadcn theme generator / CSS-in-JS）。
- 不做用户自定义滑块（一期只留扩展位）。
- 不下载 webfont（CJK 字体整包不可接受，字体轴只用系统字体栈；沿用 `globals.css:323-327` 的既有结论）。
- 不做服务端同步（皮肤只存本地，换设备不跟随）。
- 不改后端任何接口、数据库、`--paper*` 纸张语义、简历打印外观。
- 不改 `tokens.test.ts` 的"颜色只能来自令牌"这条纪律来给皮肤开例外通道。
- 不做按页面/按内容区的局部皮肤。

## 功能需求

- **REQ-001 令牌分层与皮肤契约**：在 `styles/` 下新增皮肤层（`[data-skin="<id>"]` / `[data-skin="<id>"].dark` 两组选择器），皮肤只覆盖既有令牌；`globals.css` 现有 `:root`/`.dark` 即"默认皮肤"（黑白），不搬家、不改名。
- **REQ-002 注册表与轴标签**：每个皮肤在 `packages/ui/src/theme/skins/` 下声明 `id`、名称、一句话说明、风格族、标签、是否内置；注册表与 CSS 块双向一致（多了是死代码、少了是点了没反应，都要能被测试挡住）。
- **REQ-003 运行时**：`ThemeProvider` 同时管理明暗与皮肤，落到 `<html class="dark">` + `<html data-skin="…">`；提供 `useTheme()` 的 `skin` / `setSkin`；换肤后派发一个全局事件供画布类组件重读令牌。
- **REQ-004 外观页**：`/settings/appearance` 按风格族分组展示全部皮肤，支持关键词过滤；每张卡是**活的迷你预览**（卡内自带 `data-skin`，用真实令牌渲染色块/模拟文字/模拟按钮），点一下立即全站生效。
- **REQ-005 入口**：账号菜单（`LearnerHeader`）新增「外观」项，testid `account-appearance`，跳到 `/settings/appearance`。
- **REQ-006 持久化与首屏**：选择写入 `platform.cache`（键 `skin`，落盘为 `paideia:skin`）；首屏脚本在首次绘制前同时落 `dark` 类与 `data-skin`，来源顺序沿用现有的 桌面快照 → localStorage → 默认皮肤；刷新/重开不得闪一下旧皮肤。
- **REQ-007 明暗正交**：明暗继续独立切换并保留"用户没显式选过就跟随系统"的现有逻辑；每个皮肤在亮/暗两种模式下都必须成立（不允许"某个皮肤只在深色下能看"）。
- **REQ-008 首批 17 个皮肤（含默认）**，覆盖八族：
  - 极简黑白：`default`（现有黑白）、`contrast`（高对比无障碍）
  - 暖纸复古：`paper`（暖纸米色 + 衬线标题）、`sepia`（旧书深米）、`newsprint`（报纸灰白 + 细纹）
  - 霓虹暗夜：`neon`（黑底青/品红）、`midnight`（深蓝 + 电光蓝）
  - 终端像素：`terminal`（纯黑荧光绿等宽）、`pixel`（硬边框 + 零圆角）
  - 护眼自然：`forest`（低饱和绿）、`sand`（暖灰陶土）、`ocean`（浅蓝灰 + 青）
  - 粉彩：`pastel`（柔和粉紫）、`sakura`（樱花粉 + 低饱和红）、`candy`（明快 + 渐变）
  - 玻璃拟态：`glass`（冷调半透明 + 装饰层）、`glass-warm`（暖调半透明）
- **REQ-009 质感层**：皮肤可选的"质感"（噪点/纸纹/渐变/边框风格）通过一个 `body::before` 装饰层 + 装饰令牌实现，默认关闭时与今天**逐像素一致**；不得用图片资源（用 inline SVG/CSS 渐变），不得影响 `prefers-reduced-motion`。
- **REQ-010 跟随限制**：`--paper*` 不随皮肤变（简历/打印永远是纸）；`@media print` 下强制浅色令牌（含装饰层关闭），避免深色皮肤打印出黑底。
- **REQ-011 联动**：换肤后 `--tier-*`（图谱 2D/3D 节点色）、CodeMirror 编辑区、markdown 代码块、手绘图配色必须跟着变，不需要用户刷新页面。
- **REQ-012 可访问性**：每个（皮肤 × 明暗）组合都满足 WCAG AA——正文与次要文本 ≥4.5:1（页面底、卡面、玻璃层上）、按钮文字 ≥4.5:1、危险色当文字 ≥4.5:1、焦点环 ≥3:1；不新增键盘陷阱，`aria` 语义不因换肤改变。

## 质量要求

- `pnpm --filter @paideia/ui test`、`pnpm --filter @paideia/app typecheck`、`pnpm --filter @paideia/ui typecheck` 全绿；`tokens.test.ts` / `contrast.test.ts` / `source-coverage.test.ts` 保持绿且**不降低**原有断言强度。
- 皮肤令牌值必须是字面量 `hsl(...)`（便于测试解析与 devtools 排查），不写成 `var()` 链条式间接引用。
- 新增令牌（装饰层、可能的字体/边框令牌）必须在 `@theme inline` 里有映射或明确说明不需要映射。
- 每个皮肤都必须同时定义它所覆盖令牌的亮/暗两份值（漏了就由契约测试判红）。
- 装饰层默认关闭时，`apps/ui-kit` 与首页的视觉必须与今天一致（不引入零散的观感变化）。
- 不引入新的运行时依赖；皮肤 CSS 打包体积目标 <30KB 未压缩（令牌文本，gzip 后个位数 KB）。

## 约束与依赖

- 遵守 `AGENTS.md`：新增页面必须同时加入口；颜色只能来自令牌；改共享包要跨端考虑。
- 遵守 `.agent/rules/always.md`（若与本次冲突，以该文件为准）。
- 依赖既有的 `theme-context` / `vite-plugin` / `tokens.test.ts` 结构，不重写它们；扩展而不是替换。

## 关联工作项

- `WORK-018`（简历纸张令牌 `--paper*`）：本次明确皮肤不可改动它，REQ-010 保护其语义。
- `WORK-021`（掌握度档位 `--tier-*` + 图谱画布读变量）：REQ-011 要求换肤后画布重读，避免"图不变色"。
- `WORK-051`（手绘图 `--sk-*`）：REQ-011 覆盖。
- `WORK-075`（模拟面试页 UI 重做）：同属"视觉层"工作，但对象不同（页面布局 vs 全站令牌），无硬依赖，软关联。

## 验收标准

- **AC-001** 新增一个皮肤只改 `styles/theme/skins/*.css` 与 `theme/skins/*.ts` 两类文件即可全站生效（评审时以 `git diff --stat` 证明没有组件改动）。
- **AC-002** 契约测试能挡住三类错误：皮肤漏写暗色值、皮肤写了 `:root` 里不存在的令牌名、CSS 有块但注册表没登记（反向验证：故意引入错误 → 测试红 → 撤销 → 绿）。
- **AC-003** 对比度测试遍历全部（皮肤 × 明暗）组合，断言失败信息里包含皮肤 id 与模式（例：`paper/dark 的次要文本在卡面上只有 4.1:1`）。
- **AC-004** 打开 `/settings/appearance` 能看到全部皮肤并可按风格族筛选；点任意皮肤后 `document.documentElement` 的 `data-skin` 立即变化、`getComputedStyle(html).getPropertyValue('--background')` 随之变化，刷新后保持。
- **AC-005** 首屏不闪：刷新后 `data-skin` 在第一次绘制前已经在 `<html>` 上（e2e 用"页面加载完成时属性已存在 + 无交互"断言），且旧皮肤色值不会出现在首帧（以 e2e 断言属性与计算色一致为准）。
- **AC-006** 账号菜单里有「外观」入口（`data-testid="account-appearance"`），点击进入 `/settings/appearance`。
- **AC-007** 换肤后图谱页节点颜色随新皮肤变化（不刷新页面），且 `--tier-*` 语义（未接触/了解/熟悉/掌握）不混淆。
- **AC-008** `emulateMedia({ media: 'print' })` 下页面背景/文字回到纸张语义（深色皮肤也如此）。
- **AC-009** `pnpm --filter @paideia/ui test`、`pnpm --filter @paideia/app typecheck`、`pnpm --filter @paideia/ui typecheck` 全绿；`apps/ui-kit/e2e/ui-kit.spec.ts` 与 `apps/app/e2e/markdown-render.spec.ts` 既有主题用例仍通过。
- **AC-010** 抽取 3 个极端皮肤（`contrast`、`terminal`、`glass`）在真机/浏览器上人工抽查一次首页、知识库、外观页：无不可读文字、无错位、无横向滚动。

## 实现选择

记录理由，不替代用户对范围的决定。

- **皮肤 = 令牌覆盖块，不做运行期"色相+明度"数学组合**：先把候选方案写成 CSS 变量拆分（`--brand-h/s/l` 三段，明度由模式锁定）能天然稳定对比度，但组合结果无法被"每个皮肤 × 明暗"逐条断言，且 var 链在 devtools 里查不出最终值。改为每皮肤写字面量、由测试遍历断言，正确性可验证优先于文本简洁。轴（palette/texture/type/shape）保留为**元数据**用于分组筛选与以后的自定义扩展，不做级联组合。
- **选择器用 `:root[data-skin="x"]` 与 `:root[data-skin="x"].dark`**（特异性 0,2,0 / 0,3,0）：`globals.css` 里 `:root` 与 `.dark` 都是 0,1,0，`@theme` 令牌在 `@layer theme`（低优先级）。这样皮肤块无需依赖文件顺序（`@import` 必须置于文件顶部，顺序不可控），且"漏写暗色值"会表现为明显错误并被测试抓住。
- **装饰层放 `body::before` + 装饰令牌**，默认 `display:none`；不用图片、不用额外请求，避免给默认主题带来任何变化。
- **预览卡自己带 `data-skin`**：卡内直接用标准令牌类渲染，不引入"影子颜色表"，也不需要在 TS 里写颜色字面量（否则会撞 `tokens.test.ts`）。
- **换肤事件**用 `document.dispatchEvent(new CustomEvent('paideia:themechange'))`：画布组件（sigma / 3d-force-graph）不是 React 受控的，靠事件重读 `getComputedStyle` 比把主题塞进画布的 props 更小、更稳。

## 假设

- 假设用户能接受"皮肤全部是预设、且每个都为自己调过值"，不追求"任意颜色都能生成可读主题"（后者需要运行期对比度计算，属于二期自定义）。
- 假设桌面壳不需要额外改动：`cacheSnapshot` 是通用键值表，新增 `skin` 键无需改 Rust 与 TS 接口（已核对 `main.rs:240` 与 `platform-desktop/src/index.ts:25`）。
- 假设 17 个皮肤的 CSS 体积对首屏无实质影响（每条皮肤约 40 个声明，纯文本）。

## 待确认问题

- 无。四项范围决定已由用户 2026-11-13 给出；技术取舍记在「实现选择」，不需要用户裁决。
