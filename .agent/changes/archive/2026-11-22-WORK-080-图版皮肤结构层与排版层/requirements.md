---
artifact: requirements
work_id: WORK-080
work: 图版皮肤：结构层与排版层（三个激进样板）
status: approved
created: 2026-11-13
updated: 2026-11-15
mode: managed
mode_reason: 只改 frontend/packages/ui 的样式层与皮肤测试，不碰后端、数据库、接口与部署；但这次要**放宽 WORK-079 定的"皮肤只能覆盖令牌"契约**（新增结构层白名单），属于约定本身的变化，且十个前端入口共用，跨会话追踪必要，故 managed（compact）而不是 lite。
workflow: compact
depends_on: []
related_to: [WORK-079, WORK-018, WORK-021, WORK-051]
approved_by: xiaou61
approved_at: 2026-11-15 10:37:11 +0800
approver_role: owner
---

# 需求：图版皮肤（结构层与排版层）

## 目标

让皮肤的强度从"换配色"升级到"换骨架与排版"：新增**结构层**（可用 `[data-skin] [data-slot]` 改边框/圆角/阴影/内边距）与**排版层**（标题字体族与字号、正文行高、表头样式）两种能力，并交付三个一眼就能认出来的样板皮肤——粗野主义（`brutal`）、杂志排版（`editorial`）、九八系统（`win98`），每个都带亮/暗两套令牌。

判据是可度量的：20 个皮肤 × 2 种明暗的 200 条对比度断言全绿、结构层由白名单测试锁死不许碰布局属性、默认主题逐像素不变、结构规则确实进了生产 CSS，并且截图人眼确认没有崩坏。

## 背景

WORK-079 交付了 17 个皮肤（`/settings/appearance`，账号菜单「外观」），但它们全部是**同一套骨架换配色与质感**：圆角卡片、模糊阴影、无衬线标题。用户 2026-11-13 的原话是：

> 「还有一些多一点的换肤吗，就是彻底一点的呢」

也就是说，他想要的是**一眼就知道换了站**的强度——粗野主义的硬边块、杂志的衬线排版、90 年代系统的立体边。

这逼出了一个方法上的取舍：WORK-079 的契约是"皮肤**只能**覆盖设计令牌"，好处是 17 个皮肤 × 2 种明暗的对比度能被逐条断言（200 条断言），代价是永远做不出"换骨"的皮肤。

## 已确认事实

1. 组件上已有 **82 处 `data-slot`**（`frontend/packages/ui/src/components/*.tsx`：card/button/input/textarea/select-trigger/table-head/table-container/dialog-content/dialog-title/popover-content/dropdown-menu-content…），这是做作用域结构覆盖的天然锚点，不需要给组件加新属性。
2. `@theme inline` 的**内联**语义决定了两类令牌命运不同（构建产物实测）：`.rounded-lg{border-radius:var(--radius)}` 是 var 引用，皮肤改 `--radius` 有效；`.text-3xl{font-size:2.5rem}` 是**字面量**，皮肤改 `--text-3xl` 无效。所以排版只能走 `[data-skin='x'] :is(h1,h2,h3) { font-size: … }`，覆盖 `globals.css` `@layer base` 里已有的标题规则。
3. `Card` 的类是 `rounded-lg border border-border bg-card shadow-card`、`Button` 是 `rounded-lg border border-transparent`；这些工具类在 `@layer utilities`，**未分层的皮肤规则天然压过它们**，不需要 `!important`（`!important` 反而会让"以后想调"变成事故）。
4. 对比度测试的阈值与配对（`frontend/packages/ui/src/theme/contrast.test.ts`）：正文 × {background, card, card-solid, glass} ≥ 4.5、次要文字 × {background, card, glass} ≥ 4.5、`primary-foreground` × `primary` 与 `secondary-foreground` × `secondary` ≥ 4.5、`destructive` × `card` ≥ 4.5、`ring` × `background` ≥ 3。
5. `decor.css` 现有的装饰层是 `body::before` + `--decor-image/size/opacity/blend`，`z-index:-1` 画在内容**之下**，默认 `--decor-opacity: 0`。

## 用户决定

1. 首批只做 **3 个激进样板**（推荐项，ask_user_question 120 秒超时后按全局规则默认采用）：粗野主义、杂志排版、90 年代系统。先验证"换骨"这条路能走通、且不会把布局搞乱，再批量扩到 12 个。
2. 皮肤入口沿用 WORK-079 的外观页与账号菜单，不新增入口。

## 实现选择

1. **不改令牌体系，加一层"结构层"**，并用**白名单**而非黑名单约束：只放行"看起来是形状"的属性（边框/圆角/阴影/内边距/字体/背景/透明度），`position`/`display`/`width`/`height`/`z-index`/`overflow`/`transform`/`inset`/`margin` 一律拒绝。理由：皮肤改布局 = 某些皮肤下功能会消失，而 20 个皮肤里没人会逐个点。
2. **白名单由测试强制**（`theme/skin-structure.test.ts`），不写进文档靠人记：文档会腐，测试不会。同时要求每条选择器以 `[data-skin='x']` 打头、`x` 必须是已登记皮肤、不许 `!important`、不许引用 `--paper*`、`var()` 只能指向真实存在的令牌。
3. **排版走 `:is(h1,h2,h3)` 覆盖**而不是新增令牌：`@theme inline` 已把字号内联成字面量（事实 2），且标题的层级语义是全站统一的，逐个组件传入字号会失控。
4. **第二装饰层用 `body::after`**（`--decor2-*`）：第一层在内容之下适合"底噪"（纸纹），而杂志的设计网格、终端扫描线必须**横穿内容**才像排过版。代价是它盖在内容之上，所以只允许极低透明度（editorial 实际可见度约 3%），并强制 `pointer-events: none`。
5. **与 WORK-079 记 related_to 而不是 depends_on**：WORK-079 的代码已在 `b8282996` 合并进 master，它的剩余未完成项只有 TASK-012（app 级 e2e，因本机缺 Playwright headless shell 与测试库而未能执行，单独追踪）。若写成硬依赖，会把"另一个工作项的一条测试没跑"变成"本项不许开工"，那是错的依赖。
6. **三个皮肤各代表一种机制**，不是一个比一个更花：brutal = 结构（硬边 + 实心偏移投影 + 零圆角），editorial = 排版（衬线大标题 + 细线 + 网格），win98 = 拟物（outset/inset 立体边 + 银灰面）。这样后续 12 个候选可以按机制归类复用，而不是每个都重新发明。

## 需求

- REQ-001 结构层：皮肤可用 `[data-skin='x'] [data-slot='…']` 改边框/圆角/阴影/内边距/字体/背景，属性受白名单约束。
- REQ-002 排版层：标题字体族/字号/字重/字距/大小写、正文行高、表格表头样式可被皮肤覆盖。
- REQ-003 第二装饰层：`body::after` + `--decor2-image/size/repeat/opacity/blend`，默认关闭，打印时关闭。
- REQ-004 三个样板皮肤：`brutal`（粗野）、`editorial`（铅印）、`win98`（九八），各带亮/暗两套令牌，并在注册表登记三 个新族（粗野主义/杂志排版/复古系统）。
- REQ-005 默认主题零变化：`default` 皮肤不写 CSS 块、`--decor2-opacity: 0`、`@media print` 里关掉两层装饰。
- REQ-006 既有守卫全绿：对比度测试覆盖新皮肤（20 皮肤 × 2 明暗 × 5 项 = 200 条）、契约测试仍强制亮暗令牌集合相等与未知令牌拦截。
- REQ-007 契约文档更新：`.agent/specs/platform-contracts.md` 写明结构层白名单、"字号被内联"这个坑、以及"加结构型皮肤必须人眼过截图"。
- REQ-008 截图抽查：三个皮肤 × 明暗 × {首屏 / 控件 / 表格} 的可读证据，人眼确认没有布局崩坏。

## 验收标准

- AC-001 白名单测试能拦住布局属性（反向验证：临时插入 `position: relative` 必红）。
- AC-002 三个皮肤的铁证可从运行时读到：`--radius-lg` 为 0、卡片 `border-radius: 0px` 且阴影是实心偏移（brutal）、`body::after` 的 `background-image` 是网格渐变（editorial）、卡片无阴影且边框为 outset（win98）。
- AC-003 暗色两套令牌齐全，深底不出现深字（对比度 200 条全绿）。
- AC-004 默认主题未被改变（`--decor2-opacity` 默认 0；默认皮肤无 CSS 块）。
- AC-005 打印下两层装饰都不出现、令牌回纸张语义。
- AC-006 结构规则进入生产产物（`apps/ui-kit/dist` 的 CSS 里能 grep 到 `[data-skin=brutal] [data-slot=card]` 与 `outset`）。
- AC-007 文档已更新，含白名单与"字号 inline"那条坑。

## 待确认问题

1. **批量扩到其余候选皮肤的顺序**：还剩 `mac-classic` / `swiss` / `notebook` / `cel` / `cyber` / `typewriter` / `bauhaus` / `highlighter` / `compact` 九个。是继续按机制分组一次做完（结构→排版→拟物，各 3 个），还是先看这三个的线上观感再决定？（不阻塞本轮交付。）
2. **结构层要不要下放到"用户自定义皮肤"**：一期的自定义扩展位只在令牌层面（WORK-079 留的），而结构层本质是写 CSS，下放给用户意味着允许注入样式。默认答案是不下放（安全优先），若要下放必须先单独评审注入面。
3. **`apps/admin` / `apps/public` 的观感抽查**：两者共用同一份样式，本轮未逐个截图，是否要在批量扩皮肤时补一次管理端抽查？（我倾向要，管理端表格更密，最容易被结构层撑破。）
