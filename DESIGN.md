---
name: Paideia
description: 千人千面的 AI 个性化学习平台
colors:
  ink: "hsl(222 25% 12%)"
  page-canvas: "hsl(220 20% 98%)"
  card-white: "hsl(0 0% 100%)"
  indigo-primary: "hsl(243 62% 44%)"
  indigo-wash: "hsl(243 45% 95%)"
  indigo-secondary: "hsl(243 30% 95%)"
  quiet-gray: "hsl(220 16% 95%)"
  muted-text: "hsl(220 12% 38%)"
  hairline: "hsl(220 14% 90%)"
  field-line: "hsl(220 14% 84%)"
  danger: "hsl(0 65% 42%)"
  success: "hsl(152 55% 30%)"
  warning: "hsl(32 85% 34%)"
  info: "hsl(199 75% 34%)"
  tier-unknown: "hsl(215 16% 62%)"
  tier-learning: "hsl(213 85% 62%)"
  tier-familiar: "hsl(152 55% 48%)"
  tier-mastered: "hsl(243 62% 52%)"
typography:
  display:
    fontSize: "2.5rem"
    fontWeight: 600
    lineHeight: 1.1
    letterSpacing: "-0.04em"
  title:
    fontSize: "1.25rem"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "-0.02em"
  body:
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.75
  label:
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  sm: "6px"
  md: "8px"
  lg: "10px"
  xl: "14px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "40px"
components:
  button-primary:
    backgroundColor: "{colors.indigo-primary}"
    textColor: "{colors.card-white}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "40px"
  button-outline:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "40px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "40px"
  card:
    backgroundColor: "{colors.card-white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "16px"
  input:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "40px"
---

# Design System: Paideia

## Overview

**Creative North Star: "写满批注的自习室"**

这是一间灯很白、桌面很干净的自习室：桌面上摊着你的书（正文），书页边是你自己写下的批注（自评、笔记、复习标记），旁边坐着一个你随时能问的助教（AI 助手）。界面本身不该抢戏——它是这张桌子，不是桌上的东西。

这个定位决定了三件事。**第一，密度靠内容决定，不靠装饰。** 知识库条目页可以长到 7000px，首页却必须在一屏内回答"今天做什么"。**第二，颜色是信号不是气氛。** 靖蓝只出现在"可以点"和"被选中"的位置，语义色只出现在状态上，其余全是中性灰阶。**第三，深度靠一条线，不靠阴影堆叠。** 卡片是 1px 描边 + 极低透明度的三层阴影，不是浮在空中的玻璃板。

**Key Characteristics:**
- 微冷白页面底色（不是纯白）+ 纯白卡面，让卡片靠明度差"浮"起来而不是靠投影
- 单一品牌色（靖蓝）承担主按钮、链接、焦点环、选中态四件事
- 中文界面不引 webfont：字体回退逐字符进行，拉丁在前 CJK 在后
- 深浅两套主题是同构的，不是反相

## Colors

一个品牌色撑起全部交互语义，其余是中性灰阶与四个状态色。

### Primary
- **靖蓝 Indigo** (`hsl(243 62% 44%)`): 主按钮、链接、焦点环、选中态、进度条。全站唯一的"品牌"信号。深色模式下提亮到 `hsl(243 72% 74%)`——原值在深底上像"没上色"。
- **靖蓝淡 Indigo Wash** (`hsl(243 45% 95%)`): 悬停与选中的背景、卡片封面（`.tile-surface`）的底色。它带品牌色相，是这套观感与"灰底灰字 demo"的分水岭。

### Neutral
- **墨 Ink** (`hsl(222 25% 12%)`): 正文与标题。不是纯黑，纯黑在浅灰底上过硬。
- **纸 Canvas** (`hsl(220 20% 98%)`): 页面底色。微冷白——比纯白低一档，卡片才有地方"浮"。
- **卡面白 Card** (`hsl(0 0% 100%)`): 卡片、弹层、输入框的底。
- **静灰 Quiet** (`hsl(220 16% 95%)`): 次级区块底色、代码块、引用块。
- **次文本 Muted Text** (`hsl(220 12% 38%)`): 说明文字。已验过 4.5:1。
- **细线 Hairline** (`hsl(220 14% 90%)`): 分隔线与卡片描边。
- **输入线 Field Line** (`hsl(220 14% 84%)`): 输入控件描边，比普通分隔线重一档，否则表单"没有边界"。

### Tertiary（状态与掌握度）
- **危险 Danger** (`hsl(0 65% 42%)`) / **成功 Success** (`hsl(152 55% 30%)`) / **警告 Warning** (`hsl(32 85% 34%)`) / **信息 Info** (`hsl(199 75% 34%)`): 只用于状态，不用于装饰。
- **掌握度四档** (`--tier-unknown` / `learning` / `familiar` / `mastered`): 知识图谱画布专用。画布要的是可解析颜色值而非类名，所以它们必须是令牌——组件运行时用 `getComputedStyle` 读。

### Named Rules
**The One Voice Rule.** 品牌色在一屏上的面积不超过 10%。它的稀有才是重点——一旦每个图标都染成靖蓝，"可以点"这个信号就失效了。

**The No-Gray-On-Color Rule.** 彩色底上的次要文字必须从该色相派生，不许用中性灰。灰字压在彩色块上既不可读也不像做过。

## Typography

**Display Font:** 系统字体栈（`-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, ...`，末尾 `'PingFang SC', 'Microsoft YaHei UI', 'Noto Sans CJK SC'`）
**Body Font:** 同上
**Mono Font:** `'SFMono-Regular', Consolas, 'Liberation Mono', Menlo`

**Character:** 只有中文界面，因此不引 webfont——CJK webfont 整包约 74MB，必须子集化才有意义。代价是"字距收紧"的质感只落在拉丁字母与数字上，中文靠字号与字重建立层级。

### Hierarchy
- **Display** (600, `2.5rem` / 40px, 1.1, `-0.04em`): 页面 H1。正文页也是这个量级，32px 撑不住"这是一篇文章"的第一印象。
- **Title** (600, `1.25rem` / 20px, 1.4, `-0.02em`): 二级标题（`Section` 组件统一出口）。
- **Body** (400, `1rem` / 16px, 1.75): 正文。条目页限宽 46rem（约 65–75ch）。
- **Label** (400, `0.875rem` / 14px): 说明文字与元信息。

### Named Rules
**The −0.04em Floor Rule.** 字距最紧到 `-0.04em`，不再往下。40px 下 `-0.05em` 等于 -2px，中英混排的字会开始粘连。

**The No-Kicker Rule.** 标题上方不放小标签（eyebrow / kicker）。标题自己扛第一眼；"当前位置"由导航的选中态表达。这条曾经违反过 19 处，2026-10-27 全部删除。

## Layout

单列窄档（`max-w-3xl`）与宽档（`max-w-[86rem]`）两种容器，由 `AppShell` 的 `wide` 决定：阅读类页面用窄档（一屏内少打断），列表与总览用宽档（1440 屏上两侧留白太多会像"没做完"）。

页头是 56px 固定横条：品牌 / 导航 / 搜索 / 主题开关。导航按用途分四组下拉（学习、知识、练习、求职），不按身份隐藏——身份只决定组的顺序。

间距节奏：区块之间 `mb-12`（48px），区块内元素 `gap-3`（12px）到 `gap-4`（16px）。**标题上方的空白永远大于下方**——这是分组感的来源，不是美学偏好。

`main` 底部固定留 `pb-24`（96px）：AI 助手是 `fixed` 浮标，不留空间的话最后一行内容会被它盖住。

## Elevation & Depth

混合制：**默认平**，深度只在两种情况下出现——卡片静止时的一层极淡投影，以及悬停时抬升半档。

阴影一律是"三层低透明黑"而不是单层，因为单层阴影在浅灰底上会显脏：

- **Card rest** (`0 1px 2px /4%, 0 2px 6px -1px /5%, 0 10px 20px -6px /6%`): 卡片默认。几乎看不见，只负责让纯白卡面与微冷白底之间有"厚度"。
- **Popover** (`0 1px 2px /5%, 0 8px 20px -4px /8%, 0 24px 40px -8px /10%`): 下拉、对话框、浮层。扩散更远，因为它在内容之上。
- **Hover lift**: 卡片悬停时 `hover:-translate-y-0.5` + 描边变品牌色，不加阴影。

### Named Rules
**The Declare-Once Rule.** 一个元素要么靠描边表达边界，要么靠阴影，不要两者都上。1px 描边 + 宽柔和阴影 = 幽灵卡片。

**The No-Halo Rule.** 零偏移的彩色光晕是装饰不是深度。阴影必须带偏移和柔化。

## Shapes

圆角 `--radius: 0.625rem`（10px）为基准，其余全部**派生**：`sm = radius - 4px`、`md = radius - 2px`、`lg = radius`、`xl = radius + 4px`。

10px 而不是 6px：6px 是"企业后台"的味道，10px 才像近几年的产品。按钮与输入用 `md`，卡片与对话框用 `lg`/`xl`。胶囊形（`rounded-full`）只给小组件——搜索框、徽章、浮标。

**禁止**：卡片侧边色条（`border-l-4`）。它是 AI 生成界面最好认的标志。引用块改用整块浅底 + 圆角。

## Components

### Buttons
- **Shape:** 圆角 `md`（8px），高度 40px（`sm` 变体 36px，`lg` 48px）
- **Primary:** 靖蓝底 + 白字，`px-4`。悬停时底色略深（不靠透明度）
- **Hover / Focus:** 焦点环是 `ring-3` + `ring-ring/50`（半透明靖蓝），边框同时变 `ring` 色。按下时 `translate-y-px`——一像素的位移就够表达"按下去了"
- **Outline / Ghost:** 透明底。Outline 带描边，Ghost 无描边只靠悬停底色区分

### Cards / Containers
- **Corner Style:** `lg`（10px）
- **Background:** 纯白 `card`
- **Shadow Strategy:** 见 Elevation——三层极淡阴影，悬停时不加阴影只抬 2px
- **Border:** 1px `hairline`
- **Internal Padding:** 16px（`sm` 变体 12px）

### Inputs / Fields
- **Style:** 透明底 + `field-line` 描边，圆角 `md`
- **Focus:** 边框转 `ring` 色 + 3px 半透明焦点环
- **Error:** 边框与焦点环转 `destructive`，不靠图标提示

### Navigation
页头 56px 固定条。主导航是四个下拉触发器（学习 / 知识 / 练习 / 求职），激活态用 `aria-current="page"` 而不是只靠颜色。`<md` 收进汉堡抽屉，抽屉里的导航项命中区不小于 44px。

### 掌握度图谱（signature）
知识图谱是唯一一个"颜色即数据"的组件：四个档位色从令牌读取（画布要可解析值），节点大小与亮度同时编码掌握度。它不是装饰性插图，所以允许比别处更高的视觉密度。

## Do's and Don'ts

### Do:
- **Do** 让品牌色只出现在可点与被选中的地方（One Voice Rule）。
- **Do** 用 `--radius` 派生所有圆角，改一处全站生效。
- **Do** 给空态一个能立刻做的动作，而不只是一句"还没有"。
- **Do** 用 `aria-current` / `aria-label` 表达状态，颜色只是它的表现。
- **Do** 在彩色底上从该色相派生次要文字色。

### Don't:
- **Don't** 在标题上方加 kicker / eyebrow 小标签。
- **Don't** 用卡片侧边色条（`border-l-4`）。
- **Don't** 嵌套卡片，或把 12 张同尺寸卡片当成"入口列表"——那等于没有推荐。
- **Don't** 用 hero-metric 模板（大数字 + 小标签）铺满首屏。
- **Don't** 用零偏移彩色光晕、渐变文字、玻璃模糊当装饰。
- **Don't** 在 `prefers-reduced-motion` 下把 `transition-duration` 通配归零——那会连"悬停变底色"的反馈一起抹掉。只压位移。
- **Don't** 写死颜色值；`tokens.test.ts` 会拦。
