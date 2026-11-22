---
artifact: design
work_id: WORK-016
work: 移动端最佳适配
status: approved
mode: strict
workflow: full
depends_on: [proposal]
related_to: [WORK-002, WORK-011, WORK-012, WORK-014, WORK-015]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 09:02:22 +0800
approver_role: CTO
---

# 设计 · 移动端最佳适配

## 断点契约

| 档位 | 宽度 | 含义 |
| --- | --- | --- |
| `base` | < 640 (`<sm`) | 手机竖屏：单列、抽屉、全屏弹窗、底部抽屉助手 |
| `sm` | ≥ 640 | 大屏手机横屏/小平板：部分双列恢复 |
| `md` | ≥ 768 | 平板/窄窗口：管理端侧栏恢复常驻，搜索框回到页头 |
| `lg` | ≥ 1024 | 桌面：现有全部布局 |

**规则**：形态切换只用 `sm` 与 `md` 两档，`lg` 只用于"多一列/少一列"。不在 JS 里判断宽度。

## 全局基础设施

### 1. 视口与安全区

四个 `index.html`：

```html
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
```

`globals.css` 增加：

```css
@theme {
  --touch-min: 2.75rem; /* 44px */
}
@layer utilities {
  .safe-x { padding-left: max(1rem, env(safe-area-inset-left)); padding-right: max(1rem, env(safe-area-inset-right)); }
  .safe-b { padding-bottom: max(1rem, env(safe-area-inset-bottom)); }
  .safe-t { padding-top: max(0px, env(safe-area-inset-top)); }
  .touch-target { min-height: var(--touch-min); min-width: var(--touch-min); }
}
```

**不新建断点**；`@theme` 里不覆盖 Tailwind 的 `--breakpoint-*`。

### 2. 动态视口

| 位置 | 现在 | 改为 |
| --- | --- | --- |
| `globals.css` `body` | `min-height: 100vh` | `min-height: 100dvh` |
| `AdminShell` 根 | `min-h-screen` | `min-h-dvh` |
| `AdminShell` 侧栏 | `h-screen` | `h-dvh` |
| 助手面板 | `height: panel.h`（px，来自 `innerHeight`） | 保持；但 `clampToViewport` 用 `visualViewport?.height ?? innerHeight` |
| 移动抽屉 | 新增 | `max-h-[85dvh]` |

### 3. 触控目标

`button.tsx` 增加一档：`<md` 下 `size="icon-sm"` 与 `size="xs"` 通过外层 `touch-target` 撑开命中区（视觉尺寸不变）。实现方式是在 `Button` 的 `icon-sm`/`xs` 变体上加 `before:absolute before:-inset-2 before:content-['']`，仅在 `max-md` 生效。

> 注：Tailwind v4 的 `max-md:` 变体已可用；不用 `pointer: coarse` 媒体查询，因为桌面窄窗口也需要同样处理。

## `Dialog` 的窄屏形态

`dialog.tsx` 的 `DialogContent` 基础类改为：

```text
fixed z-50 bg-popover p-4 ring-1 ring-foreground/10 outline-none
/* 手机：贴底到顶 */
inset-x-0 bottom-0 top-auto max-h-[92dvh] w-full rounded-t-2xl rounded-b-none
overflow-y-auto
/* ≥sm：恢复居中浮层 */
sm:inset-auto sm:top-1/2 sm:left-1/2 sm:max-h-none sm:w-full sm:max-w-sm sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-xl
```

配套：
- `DialogFooter` 在 `<sm` 改为 `sticky bottom-0` + `safe-b`，保证保存/取消不被滚动带走。
- `DialogContent` 在 `<sm` 增加顶部一条 `h-1 w-10 rounded-full bg-border` 的抓手，纯装饰，`aria-hidden`。
- **动画**：`<sm` 用 `slide-in-from-bottom`，`≥sm` 保持现有 `zoom-in-95`。

调用点不变：`className="sm:max-w-3xl"` 仍然只表达桌面宽度。

## 学习者端导航抽屉

`LearnerHeader` 拆成三段；`<md` 时：

```
[P] Paideia                    [☰]  [avatar]
```

- `☰` 打开 `Dialog`（复用现有原语，`<md` 才渲染），内容为三项主导航 + 搜索框 + 主题开关。
- 每项是整行 44px 高的 `Link`，带 `aria-current="page"`。
- 点击任一项后 `close()`；`Esc`/遮罩/关闭按钮都可关；关闭后焦点回 `☰`。
- `≥md` 完全沿用现状（横排导航 + 页头搜索），**一行样式不改**。

实现要点：抽屉内容与桌面导航共用同一份 `NAV` 常量，不复制数组。

## 各页面窄屏处理

| 页面 | 改动 |
| --- | --- |
| 首页 | hero `p-8` → `p-5 sm:p-8 lg:p-10`；`h1` `text-3xl` → `text-2xl sm:text-3xl`；搜索表单 `flex` → `flex-col sm:flex-row`，按钮 `w-full sm:w-auto` |
| 知识库分类 | 已单列，补 `gap-3`；卡片内 padding 收紧 |
| 条目列表 | 已单列；筛选栏换行；分页按钮 44px |
| 条目页 | 正文 `px` 由 shell 控制；`<lg` 目录改为折叠面板（`<details>` 语义或 `Dialog`）；上下篇单列 |
| 交互块 | 见下节 |
| 笔记页 | `<lg` 已是单列；列表 `max-h` 在窄屏去掉；编辑/预览切换保持现有分段控件，补 44px |
| 新建笔记 | 条目选择器 `max-w-md` 在窄屏 `max-w-none`；下拉结果 `max-h-[50dvh]` |
| 我的自评 | 筛选栏 `flex-wrap` 已有；行内时间戳在 `<sm` 改为块级换行 |
| 登录/注册/分享 | `AuthLayout` 的 `px-6` → `px-4 sm:px-6`；`pb-20` → `pb-10 sm:pb-20` |

## 交互块窄屏

- `code-tour`：已有 `lg:grid-cols-[1.15fr_1fr]`，窄屏自然堆叠。补：代码块 `max-h-[60dvh]`，步骤按钮 44px。
- `code-map`：已有 `overflow-x-auto`。补：容器加 `snap-x` 与右侧渐隐提示；`<sm` 节点最小宽度下调。
- `code-challenge`：排序题上下按钮 `size-7` → `touch-target`；选项按钮 `min-h-11`。
- `quiz`：选项 `min-h-11`，解析区不与选项挤行。
- `learning-path`：已经是整行按钮，补 `min-h-11`。

## 助手底部抽屉

`assistant-shell.tsx` 增加一个容器分支：

```tsx
const narrow = panel.w < 640  // ← 不对，宽度是面板自己的
```

**正确做法**：用 `matchMedia('(max-width: 639px)')` 的订阅，或直接读 `window.innerWidth` 并在 resize 时更新。因为这是**运行时容器切换**（不是纯样式），且需要影响 `style` 属性，所以必须用 JS。

```tsx
const [narrow, setNarrow] = useState(() => typeof window !== 'undefined' && window.innerWidth < 640)
useEffect(() => {
  const mq = window.matchMedia('(max-width: 639px)')
  const onChange = () => setNarrow(mq.matches)
  mq.addEventListener('change', onChange)
  return () => mq.removeEventListener('change', onChange)
}, [])
```

- `narrow === true`：渲染 `fixed inset-x-0 bottom-0 z-40 max-h-[85dvh] rounded-t-2xl`，宽度忽略 `panel.w`，隐藏拖拽把手与调宽把手。
- `narrow === false`：现有浮动窗，一行不改。
- 共用 `<AssistantBody>`（把当前 `section` 内部内容抽成一个局部组件，减少重复）。
- 状态：`open` 共用；`x/y/w/h` 只在宽屏读写。窄屏不写 `x/y`，避免把 320 宽写回本地后桌面端打开变小。

**这一点是对 REQ-023 的例外说明**：JS 断点在这里被允许，因为切换的是 DOM 结构（是否渲染拖拽把手）而不是样式。CSS 无法隐藏 `style={{height}}`。

## 管理端

### 侧栏抽屉

`AdminShell.tsx`：
- `<md`：`aside` 改为 `fixed inset-y-0 left-0 z-40 w-64 -translate-x-full data-[open=true]:translate-x-0 transition-transform`，加遮罩，`h-dvh`。
- 顶栏出现 `☰`（`md:hidden`），点开抽屉；选中菜单后关闭。
- `≥md`：现有 `sticky` 侧栏与收起按钮不变。
- 注意：现有 `collapsed` 状态只在 `≥md` 生效；窄屏恒为展开抽屉。

### 表格转卡片

四个页面统一模式：

```tsx
<Table className="hidden md:table">…</Table>
<ul className="md:hidden divide-y rounded-xl border" data-testid="…-cards">
  {rows.map(row => <CardItem key={…} … />)}
</ul>
```

`CardItem` 是各页面内的局部函数（**不抽公共组件**），字段与操作从同一份 `rows` 取。

| 页面 | 卡片内容 |
| --- | --- |
| 知识条目 | 标题 / 分类 + 状态徽标 / 更新时间 / 编辑·删除 |
| 知识分类 | 名称 / 条目数 / 排序 / 编辑·删除 |
| 举报 | 笔记标题 / 理由 / 状态 / 处理 |
| 积分审计 | 用户 / 动作 / 变动 / 时间 |

现有 `data-testid` 全部保留在卡片上（同样的值 + `-card` 后缀用于区分，桌面表格的 testid 不变），使现有 9 条管理端 e2e 继续通过。

## PWA

`apps/app`：

```ts
// vite.config.ts
import { VitePWA } from 'vite-plugin-pwa'
VitePWA({
  registerType: 'autoUpdate',
  manifest: {
    name: 'Paideia · 个性化学习', short_name: 'Paideia',
    display: 'standalone', start_url: '.', scope: '.',
    theme_color: '#18181b', background_color: '#ffffff',
    icons: [192, 512, 512-maskable],
  },
  workbox: {
    globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
    navigateFallback: 'index.html',
    navigateFallbackDenylist: [/^\/api\//, /^\/actuator\//],
    cleanupOutdatedCaches: true,
  },
  devOptions: { enabled: false },
})
```

- `base: './'` 与 `start_url: '.'` 一致（桌面壳从自定义协议加载，绝对路径会 404；PWA 走 HTTP 时 `.` 解析为当前目录，两边都对）。
- **不配 `runtimeCaching`** —— 默认不缓存任何非预缓存请求，`/api` 天然直连。
- 图标用 Pillow 生成（本机已有 Pillow 11.2.1）：品牌色块 + 白色 "P"，192/512/512-maskable 三份 PNG，放 `apps/app/public/`。
- `index.html` 加 `theme-color` 与实际色值一致。

**打包依赖**：`vite-plugin-pwa` 走 devDependency。安装失败时的退路见任务 T-09 的验证步骤。

## 测试设计

| 层 | 内容 |
| --- | --- |
| 单测 | `Dialog` 窄屏类名快照、`assistant-shell` 的 `narrow` 分支渲染、安全区工具类存在性 |
| e2e 移动 | `app` 与 `admin` 各加 `mobile` project（`Pixel 7` + `iPhone 13`），只跑 `**/*.mobile.spec.ts` |
| e2e 桌面 | 现有全部用例不动，必须全绿 |
| PWA | 新增 `pwa.mobile.spec.ts`：`vite build` + `preview` 下断言 manifest 200、SW 注册、`caches.keys()` 不含 `/api` |

移动 spec 覆盖：
- 11 条 app 路由的 360px 无溢出冒烟
- 6 个 admin 页面 375px 无溢出 + 卡片可见
- 导航抽屉开合与焦点还原
- 助手底部抽屉形态与关闭
- 交互块命中区 ≥44

## 回滚

- 布局改动全在样式类上，回滚 = revert 提交。
- PWA 是单文件配置 + 三个图标，去掉 `VitePWA(...)` 即回到原产物；已安装的 SW 会因 `sw.js` 404 在下一次访问时注销。
- 无数据库、无后端、无部署形态变化。
