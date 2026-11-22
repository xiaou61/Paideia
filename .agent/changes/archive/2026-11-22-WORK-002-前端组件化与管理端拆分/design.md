---
artifact: design
work_id: WORK-002
work: 前端组件化与管理端拆分
status: approved
created: 2026-10-09
updated: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 14:45:35 +0800
approver_role: CTO
---

# 前端组件化与管理端拆分设计

本设计只覆盖前端结构、设计系统与角色接缝。后端契约、业务页面与部署不在范围内。带「计划」标记的名称是本项要新建的文件或符号。

## 系统上下文

- 现有前端：pnpm workspace，`packages/{ui,core,platform-web,platform-desktop}` + `apps/{app,public,desktop}`；共享包直出 TypeScript 源码，只有 app 走 Vite 构建。
- 本项新增两个应用面：`apps/admin`（管理端，仅浏览器）与 `apps/ui-kit`（组件展览，开发与内审用），并把 `packages/ui` 从"一个占位组件"升级为设计系统包。四个应用（`app`、`admin`、`public`、`ui-kit`）共用 `packages/ui` 的组件与样式入口。
- `apps/ui-kit` 与 `apps/admin` 都**不是** `apps/app` 的依赖，因此都不进入 Web 产物与桌面安装包——这是构造上的事实，不依赖构建工具消除死代码。
- 桌面壳继续只消费 `apps/app` 的构建产物（`apps/desktop/scripts/prepare-renderer.mjs` 从 `../../app/dist` 拷贝），管理端因此不进入安装包。
- 后端不变：`/actuator/health`（公开）与 `/api/v1/me`（受保护，当前无登录流程故返回 401）。

## 组件与职责

### 新增与修改的路径

```
frontend/
  packages/ui/                       设计系统包（升级，删除占位 Panel.tsx）
    package.json                     新增导出 "./styles.css" 与 "./vite"；新增运行时依赖
    components.json                  shadcn CLI 配置：组件落在本包
    src/index.ts                     组件与主题 API 的唯一出口
    src/lib/cn.ts                    类名合并（clsx + tailwind-merge）
    src/styles/globals.css           Tailwind 入口 + 令牌 + .glass + dark 变体
    src/theme/theme-context.tsx       ThemeProvider / useTheme（存储由外部注入）
    src/theme/theme-toggle.tsx        ThemeToggle
    src/theme/vite-plugin.ts          themeInitPlugin：向三个应用的 HTML 注入首屏脚本
    src/layout/app-shell.tsx          AppShell：页头 / 侧栏 / 内容区插槽
    src/components/**                 基元组件（见下表）
    src/tokens.test.ts                令牌单一来源检查
  packages/core/
    src/session.ts                    Role / Session / createSessionReader / canAccessAdmin
    src/index.ts                      导出上述符号
    src/workspace-boundaries.test.ts   扩展：ui 包的新边界规则
  packages/platform-desktop/
    src/index.ts                      拆出可测的 createBridgeCache
    src/index.test.ts                 新增（vitest）
  apps/admin/                         新增应用（结构对照 apps/app）
    index.html  vite.config.ts  tsconfig.json  package.json  components.json
    src/{main.tsx,env.ts,api.ts,platform.tsx,theme.ts,session.ts,router.tsx,guard.tsx}
    src/pages/{AdminConsolePage.tsx,AdminDenied.tsx}
    e2e/admin.spec.ts
  apps/ui-kit/                        新增应用：组件展览（不进任何产品产物）
    package.json  index.html  vite.config.ts  tsconfig.json
    src/main.tsx                      组合根：Web 平台 + ThemeProvider
    src/pages/UiKitPage.tsx           分节渲染全部组件与其变体、状态
  apps/app/
    index.html  vite.config.ts        样式插件与首屏脚本
    src/main.tsx                      引入 @paideia/ui/styles.css
    src/theme.ts                      把 platform.cache 接到 ThemeProvider
    src/pages/HomePage.tsx            重写为 AppShell + 组件组合
  apps/public/
    vite.config.ts  src/PublicPage.tsx 样式管线；Panel → Card
  apps/desktop/electron/{main,preload}.cjs   注入并暴露缓存快照
```

### 基元组件与消费者

| 组件 | 消费者 |
| --- | --- |
| Button / Input / Label / Checkbox / Switch / Select | `apps/ui-kit` 展览页 |
| Card / Badge / Separator / Table / Spinner / Skeleton | 展览页；Card、Badge、Spinner、ErrorState 亦用于 `HomePage` 与管理端 |
| EmptyState / ErrorState | 展览页；`HomePage` 的失败态与空态 |
| Dialog / DropdownMenu / Tabs / Toast（sonner） | 展览页 |
| AppShell / ThemeToggle | 三个应用的真实布局 |

**每个组件都有真实消费者**，不存在只写不用的组件：展览页是设计系统的验收载体，也是两种主题下做对比度核验的场所。更重的组件（表单校验编排、分页、日期选择、命令面板）不在本期，等第一个真实业务页面驱动。

### 组件展览应用（`apps/ui-kit`）

一个页面，按组件分节，每节渲染它的**全部变体与状态**：Button 的 variant × size × disabled × loading；Input/Label/Checkbox/Switch/Select 的正常、聚焦、禁用、校验错误；Card 的普通态与玻璃层上叠正文、叠次要文本（对比度核验用的就是这几块）；Table 的有数据与空表体；Dialog/DropdownMenu/Tabs/Toast 由页面上的触发按钮唤起；Spinner/Skeleton/EmptyState/ErrorState；以及 `AppShell` 的布局骨架。顶部放 `ThemeToggle`，旁边显示当前主题，切一下即可看全组件在浅深两色下的表现。

三条约束：

1. **不依赖后端**。展览页只从 `packages/ui` 取组件，不调接口——否则"想看组件"会变成"先起数据库"。
2. **不做自动发现组件的注册表**。各组件需要的变体布局不同，自动列表只能列出名字，价值有限；新增组件时手工加一节，多写十行但一眼可读。
3. **不需要路由**。单页渲染即可；`main.tsx` 只做组合根（Web 平台 → `ThemeProvider` → `UiKitPage`），不引 `react-router`。

## 请求或事件流程

### 首屏主题（防闪烁）

1. 浏览器解析 HTML 时执行注入的脚本（`themeInitPlugin` 产出）：先看桌面快照，再看 `localStorage`，都没有则看系统偏好，然后把 `dark` 类切到 `<html>`。
2. 样式表里 `:root` 与 `.dark` 两套变量已经就位，所以首次绘制就是正确主题。
3. React 挂载后 `ThemeProvider` 用 `document.documentElement.classList` 的当前值作为初始状态——**不重复实现一遍解析逻辑**，因此脚本与组件不可能不一致。

### 主题切换

1. `ThemeToggle` 调 `useTheme().setTheme`。
2. Provider 切 `<html>` 的类，并通过注入的存储写 `theme` 键。
3. 存储的具体位置由应用决定：Web 写入 `localStorage`（键名由 `platform-web` 加 `paideia:` 前缀），桌面写入主进程的 `cache.json`。

### 管理端访问

1. `apps/admin` 的组合根构造 `createSessionReader({ getToken })`，令牌来源与 `createApiClient` 用的是同一处接缝。
2. `RequireAdmin` 调 `canAccessAdmin(session)` 判定；拒绝时渲染 `AdminDenied`，展示当前身份与原因。
3. 后端角色契约落地后，只需让 `getToken` 返回真实令牌，守卫与页面都不用改。

### 桌面端缓存读取（修正后的路径）

1. 主进程启动时读 `cache.json`，把内容 base64 后作为 `--paideia-cache-snapshot=` 启动参数传给渲染进程（`sandbox: true` 下 preload 只能 `require('electron')`，不能读文件，所以走启动参数）。
2. preload 解析该参数，作为 `paideiaDesktop.cacheSnapshot` 暴露。
3. `createDesktopPlatform()` 用快照初始化同步镜像，因此"上次存的值"在本次启动就能被同步读到。

## 接口与数据

### 样式入口与令牌

`packages/ui/src/styles/globals.css`（摘录，括号内是必须成立的理由）：

```css
@import "tailwindcss" source(none);

/* 关闭自动探测并显式登记源码物理路径。
   不能写包名：pnpm 把 workspace 包软链在 node_modules 下，v4 的自动探测会跳过 node_modules，
   跨包类名会静默丢失（表现是组件在 ui 包里正常、在 app 里没样式）。路径相对本文件。 */
@source "../**/*.{ts,tsx}";
@source "../../../../apps/app/src";
@source "../../../../apps/admin/src";
@source "../../../../apps/public/src";

@custom-variant dark (&:where(.dark, .dark *));

:root {
  --background: oklch(1 0 0);
  --foreground: oklch(0.145 0 0);
  --card: oklch(1 0 0 / 72%);        /* 玻璃层：半透明 */
  --card-solid: oklch(1 0 0);        /* 不透明底：不支持模糊时的降级形态 */
  --muted: oklch(0.97 0 0);
  --muted-foreground: oklch(0.45 0 0);
  --border: oklch(0.145 0 0 / 12%);
  --primary: oklch(0.205 0 0);
  --primary-foreground: oklch(0.985 0 0);
  --destructive: oklch(0.577 0.245 27.3);
  --ring: oklch(0.145 0 0 / 40%);
  --glass-blur: 12px;
  --radius: 0.75rem;
}

.dark { /* 同一组变量，黑白互换并提高玻璃层透明度 */ }

@theme inline { /* 把上面的变量映射成 bg-background / text-foreground 等工具类 */ }

@layer components {
  /* 默认给不透明底，只有确认支持模糊时才增强——降级不是"看起来差一点"，而是必须先可用。 */
  .glass { background-color: var(--card-solid); border: 1px solid var(--border); border-radius: var(--radius); }
  @supports (backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)) {
    .glass { background-color: var(--card); backdrop-filter: blur(var(--glass-blur)) saturate(140%); }
  }
  @media (prefers-reduced-transparency: reduce) {
    .glass { background-color: var(--card-solid); backdrop-filter: none; }
  }
}
```

两个必须写进代码注释的约束：**玻璃层不得嵌在带 `opacity < 1` 或 `filter` 的容器内**（那会新建 backdrop root，模糊静默失效）；**模糊不用于长列表的逐项渲染**（代价随模糊区域面积增长）。

### 主题 API（`@paideia/ui`）

```ts
export type Theme = 'light' | 'dark'
export const THEME_KEY = 'theme'

/** 只要求 get/set 两个方法：core 的 KeyValueCache 结构化满足它，因此 ui 不依赖 core。 */
export interface ThemeStorage {
  get(key: string): string | null
  set(key: string, value: string): void
}
```

`ThemeProvider` 的初始值取自 `<html>` 的类；`setTheme` 同时改类与写存储。没有已保存偏好时订阅 `matchMedia('(prefers-color-scheme: dark)')`，系统换色跟随生效。

### 角色与守卫（`@paideia/core`）

```ts
export type Role = 'admin' | 'learner'
export interface Session { readonly role: Role; readonly signedIn: true }
export interface SessionReader { current(): Session | null }

/** 失败即拒绝：无令牌、载荷不可解析、角色不可识别，一律返回 null。 */
export function createSessionReader(options: { getToken?: () => string | null }): SessionReader
export function canAccessAdmin(session: Session | null): boolean
```

未识别角色一律按 `learner` 处理（最小权限）。客户端解析 JWT 载荷**不校验签名**（没有密钥也校验不了），所以它只用于界面与路由分流，**不是授权边界**——真正的授权必须在后端。这条要写进 `session.ts` 的注释，避免后来者把它当成安全机制。

### 桌面桥的增量（`DesktopBridge`）

新增只读字段 `cacheSnapshot: Record<string, string>`。`packages/platform-desktop` 拆出可测的纯函数：

```ts
export function createBridgeCache(
  bridge: DesktopCacheBridge,
  snapshot: Record<string, string> = {},
): KeyValueCache
```

`get` 读同步镜像（由快照初始化），`set`/`remove` 同步改镜像并异步落盘——端口的同步语义与既有注释都保持不变。

## 持久化与迁移

- 不涉及数据库，无 schema 变更，无数据迁移。
- 新增两个本地键：`theme`（端口键名）。Web 实际落在 `localStorage['paideia:theme']`（前缀由 `platform-web` 内部加），桌面落在主进程 `cache.json` 的 `theme`。两个位置都不含敏感信息，也不进仓库。
- 桌面 `cache.json` 是既有文件，本项只增加读取时机（启动快照），不改变其格式，旧文件可直接被读。

## 失败处理与恢复

| 情形 | 行为 |
| --- | --- |
| `localStorage` 不可用（隐私模式 / 配额） | `platform-web` 已有的内存降级生效：主题仍可切换，只是不持久；不阻断启动 |
| 桌面快照缺失或不是合法 JSON | 按空快照处理，主题退回系统偏好；启动不受影响 |
| 令牌缺失 / 载荷畸形 / 角色不在已知集合 | `Session` 为 `null` 或 `learner`，管理端路由被拒（失败即拒绝） |
| Tailwind 漏扫 `packages/ui` 的类名 | 由 `source(none)` + 显式 `@source` 消除；验证步骤要求确认 ui 包内独有类名出现在产物 CSS 中 |
| `backdrop-filter` 不支持或用户要求减少透明 | `.glass` 落到不透明底（默认值），视觉完整、可读性不受损 |

## 安全与权限

- 管理端路由守卫是**界面分流，不是授权边界**。真正的越权拦截在后端（WORK-001 已确立的授权隔离检查仍是唯一可信证据）。
- 角色解析失败即拒绝，不提供任何开发者提权开关或"假装管理员"的调试后门——那会把授权缺失伪装成"能用"。
- 管理端展示当前身份与拒绝原因，使授权缺失可见而不是静默。
- 令牌读取仍只在 `createApiClient` 的 `getToken` 与 `createSessionReader` 两处，不散落到组件。
- 公开仓库保密规则不变：主题与角色都不产生任何需要入库的凭据。

## 可观测性

- 两个应用都在界面上显示当前主题与当前身份（`data-testid` 便于自动化断言），使"主题是否生效""身份是什么"无需读代码即可确认（对应可诊断性要求）。
- 管理端页头显示后端健康状态徽标（真实接口数据），在有守卫的情况下也能证明接口链路连通。

## 实施顺序

1. 样式管线与令牌层（Tailwind 接入三个应用、`@source` 登记、双主题变量、`.glass`、首屏脚本）。
2. 主题状态与切换（Provider、Toggle、存储注入与接线）。
3. 组件基元、`AppShell` 与独立的组件展览应用 `apps/ui-kit`。
4. `apps/app` 与 `apps/public` 迁移，删除 `Panel`。
5. 会话与角色端口 + `apps/admin` + 守卫。
6. 桌面端缓存缺陷修正。
7. 两条自动化检查、长期约定与索引文档、验证报告。

## 测试策略

原则：逻辑只出现在三处（角色判定、主题状态、令牌解析），这三处各有单测；其余组件靠展览页与三个应用的端到端冒烟覆盖，不写逐组件用例套件。

| 检查 | 位置 | 覆盖 |
| --- | --- | --- |
| `createSessionReader` / `canAccessAdmin` 四条分支（无令牌、畸形容器、角色缺失、admin） | `packages/core/src/session.test.ts` | AC-006 |
| 共享包边界（扩展后：ui 不得引用 core / platform-* / react-router / 桌面壳 API） | `packages/core/src/workspace-boundaries.test.ts` | AC-007 |
| 令牌单一来源：扫 `apps/**/src` 无硬编码颜色字面量 | `packages/ui/src/tokens.test.ts` | AC-002 |
| 主题：初始值取自 DOM、切换写入存储、无存储时跟随系统 | `packages/ui/src/theme/theme-context.test.tsx` | AC-003 |
| `createBridgeCache`：快照即读、写入同步可见并异步落盘 | `packages/platform-desktop/src/index.test.ts` | AC-003 在桌面端的成立条件 |
| 学习者端首页展示真实数据、主题刷新后保持 | `apps/app/e2e/home.spec.ts`（扩展既有用例） | AC-004、AC-003 |
| 展览页能构建、能渲染全部组件、对话框键盘可进入且 `Esc` 可关闭 | `pnpm --filter @paideia/ui-kit build` + 人工核验并记入测试报告 | AC-001 |
| 管理端展示真实健康数据且管理区被拒 | `apps/admin/e2e/admin.spec.ts` | AC-004、AC-006 |
| Web 产物与桌面产物中都不存在管理端与展览页入口 | 构建后检索 `apps/app/dist` 与 `apps/desktop/renderer` | AC-005 |
| 两种主题下的对比度核验（正文、次要文本、玻璃层上的文本） | 展览页人工核验并记入测试报告 | AC-003 |
| 玻璃层降级形态 | 展览页在 `@supports` 不成立时的人工复核 | AC-008 |
| 公开页构建通过且产物含预渲染内容与样式 | `pnpm --filter @paideia/public build` | 回归保护 |

桌面端 e2e 在本机跑不了（WORK-001 已记录：Playwright 经 `cmd.exe` 启动 Electron 的 spawn 被本机限制），因此桌面侧的结论由 `createBridgeCache` 单测 + **记录为未运行**的桌面 e2e 共同构成，不写成"已验证"。

## 需求追踪

| 需求 | 设计落点 | 主要验收 |
| --- | --- | --- |
| REQ-001 | 令牌层（`globals.css` 的 `:root`/`.dark`/`@theme inline`）、令牌单一来源检查 | AC-002、AC-003 |
| REQ-002 | 基元组件表、`AppShell`、ui 包边界规则 | AC-001、AC-007 |
| REQ-003 | `apps/admin` 结构；桌面壳只消费 `apps/app` 产物 | AC-004、AC-005 |
| REQ-004 | 首屏脚本 + `ThemeProvider` + 存储注入 | AC-003 |
| REQ-005 | `session.ts` 的端口与 `canAccessAdmin`；`RequireAdmin` | AC-006 |
| REQ-006 | `HomePage` 重写、`PublicPage` 迁移、删除 `Panel` | AC-001、AC-002 |
| REQ-007 | 边界检查扩展 + 桌面产物检索 | AC-005、AC-007 |

## 待决定事项

无新增。提案中的"组件交付面"一项仍待用户确认（21 个基元的范围是否够），它只影响组件任务的大小，不改变本设计的结构与接缝。

## 实施期修订：视觉方向（2026-10-09，用户批准）

以上「接口与数据」里的令牌取值与 `.glass` 的定位已被一次修订取代。这里保留原方案记录，并说明改了什么、为什么、以及用户如何批准的。

**改了什么**

1. **令牌取值整组替换。** 原方案是一套自定的 oklch 黑白灰阶（浅色底 `oklch(0.97)` 浅灰、卡面 72% 白半透明）。现改为 Vercel Geist 设计系统的公开实测值：浅色底纯白、正文 `hsl(0 0% 9%)`、默认边框 `hsl(0 0% 92%)`、组件底 `hsl(0 0% 95%)`；深色对应 `hsl(0 0% 4%)` / `93%` / `18%` / `10%`。
2. **圆角由 12px 收到 6px**，并给出与 Geist materials 规范对齐的四档映射。
3. **卡面由半透明玻璃改为不透明 + 1px 细线 + 多层低透明阴影。** `.glass` 保留为**可选工具类**（降级形态与 `@supports` 结构不变），但默认不再使用；页面的径向高光背景一并删除。
4. **新增标题阶梯**（`text-lg/xl/2xl/3xl`，带随字号收紧的负字距）与全局 `h1/h2/h3` 的字重与字距，并新增 `PageHeader`。

**为什么改**

用户在看过展览页后反馈"不太好看"。核查确认原因不在组件库：一是 shadcn 注册表默认密度偏紧（控件 32px、正文 14px），二是**原令牌把页面做成浅灰底 + 半透明卡面**，既非高对比黑白、也没有内容可让玻璃成立，两头不占。用户随后要求按 Vercel 观感调整，并选定"用其实测令牌替换"。

**一个关键事实**：Vercel 的对外设计规范（`vercel.com/design.md`）把 "glass effects" 列入 **Hard reject** 清单——"Vercel 观感"与"玻璃拟态"本就是相冲突的两个方向，原设计把它们混在一起是错的。另需修正一句早先说法：Vercel 并非没有设计系统，它有公开的 Geist Design System，只是组件包 `@vercel/geistcn` 未发布到公开 npm（查为 404）。

**为什么不重开批准**

REQ-001（令牌层覆盖含玻璃层级）与 AC-008（玻璃层有降级形态）**仍然成立**：玻璃仍是令牌层的一个层级、降级结构未动，只是不再是默认卡面；REQ-002..REQ-007 与全部验收标准含义未变。改动只落在样式取值与默认层次手法上，不涉及目标、范围、接口、数据、安全或架构。用户在选项中选择"换成 Vercel 实测值"即是对这次修订的明确批准；按"豁免门槛的决定必须写入受影响工件"，该批准记入本文件、验证报告与更新历史，不另开批准轮次。后续再改视觉方向同样记在这里。

