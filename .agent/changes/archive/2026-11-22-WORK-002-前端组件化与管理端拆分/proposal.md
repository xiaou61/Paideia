---
artifact: proposal
work_id: WORK-002
work: 前端组件化与管理端拆分
status: approved
created: 2026-10-09
updated: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 14:45:34 +0800
approver_role: CTO
---

# 前端组件化与管理端拆分提案

## 摘要

在已批准的需求与已经裁决的技术方向上，落地三件事：**建立设计令牌与样式管线**（Tailwind CSS v4 + shadcn/ui 源码进仓库，浅深双主题、黑白玻璃）、**把 `packages/ui` 变成真组件库并迁移现有页面**、**按角色拆出 `apps/admin` 并加路由守卫**。附带修正 WORK-001 留下的一处端口缺陷：桌面端 `Platform.cache` 的同步读永远返回空，导致任何"记住用户选择"的功能在桌面端重启后失效——本项是它的第一个消费者（主题），必须在同一处修好。

## 推荐方案

### 1. 样式与令牌：一个 CSS 入口，显式登记扫描路径

单一入口 `packages/ui/src/styles/globals.css`，内容分四段：`@import "tailwindcss" source(none)` 关闭自动探测、`@source` 显式登记四个源码目录、`:root`/`.dark` 两套 CSS 变量、`@theme inline` 把变量映射成 Tailwind 工具类，外加 `.glass` 组件类与 `@custom-variant dark`。

关闭自动探测并显式登记是必需的，不是洁癖：Tailwind v4 扫描时排除 `node_modules`，而 pnpm 把 workspace 包软链在 `node_modules` 下，靠自动探测会**静默丢掉 `packages/ui` 里的类名**——表现为"组件在 ui 包里长这样、在 app 里渲染成没样式"。`@source` 必须写物理源码路径（`packages/...`、`apps/...`），不能写包名。

三个前端应用各加 `@tailwindcss/vite` 插件并引入这一个 CSS。`packages/ui` 增加一个 `./styles.css` 导出映射，沿用"共享包直出源码、无独立构建"的既有形态。

### 2. 首屏不闪烁：由共享 Vite 插件注入初始化脚本

主题类必须在首次绘制前落到 `<html>` 上。做法是 `packages/ui` 导出一个 Vite 插件（`themeInitPlugin`），用 `transformIndexHtml` 往三个应用的 HTML 注入约十行脚本：先读桌面快照、再读 `localStorage`、都没有则跟随系统偏好，然后切 `.dark`。

放在 ui 包里导出而不是在三个 `index.html` 里各抄一份，是因为三份拷贝必然漂移，而漂移的症状（某个端首屏闪一下）很难被发现。

### 3. 主题状态：ui 只认注入的存储，不认平台

`packages/ui` 导出 `ThemeProvider` / `useTheme` / `ThemeToggle`。`ThemeProvider` 接受一个只有 `get(key)`/`set(key, value)` 的存储参数——这个形状**结构化匹配** `packages/core` 的 `KeyValueCache`，因此 ui 不需要依赖 core（ui 仍是"不得引用平台 API"的纯展示层），应用侧把 `platform.cache` 直接传进去即可。

### 4. 组件：shadcn/ui 源码进仓库，基座用默认的 Base UI

用 `shadcn` CLI（当前 4.21.4）以 `--base base`（Base UI）把组件生成到 `packages/ui/components`，令牌适配到上面那套变量。本期交付 21 个常见基元，每个都有真实消费者：**独立应用 `apps/ui-kit` 的组件展览页** + 两个应用的真实页面。展览页同时是两种主题下对比度核验的载体，也是唯一能一眼看全设计系统的地方。

展览页独立成应用而不是塞进 `apps/app` 的某个路由，原因是它**从构造上就不进任何产品产物**：`apps/app` 的构建产物服务 Web 与桌面安装包，`apps/ui-kit` 只是第四个面，按现有的"一个面一个应用、共享 packages"模式存在。这样既不用在产品里写"开发期才注册"的门控，也不用担心演示代码被发到用户机器上。

`packages/ui` 新增依赖：`@base-ui/react`、`class-variance-authority`、`clsx`、`tailwind-merge`、`lucide-react`、`sonner`（Toast）；Tailwind 只在开发期需要。

### 5. 两端拆分与角色守卫

新增 `apps/admin`（独立 Vite 应用，`base: './'`、Hash 路由、端口 5174、同样的 `/api` 与 `/actuator` 代理），共享 `packages/ui` 与 `packages/core`。桌面壳继续只消费 `apps/app` 的产物，因此管理端**按构造**不会进安装包。

角色逻辑收在 `packages/core` 的两个符号上：`createSessionReader({ getToken })` 与 `canAccessAdmin(session)`。前者从既有 `getToken` 这个接缝取令牌并解析载荷里的 `role`；**取不到令牌或角色不可识别时一律按学习者处理（失败即拒绝）**。当前后端不签发角色，因此实际结果恒为"非管理员"，管理端路由展示拒绝而不是假装放行。业务组件不得直接比较角色字符串。

### 6. 修正桌面端缓存缺陷

`packages/platform-desktop` 的 `get` 只读一个进程内镜像，而镜像只由 `set` 写入、启动时不填充，所以"上次存的值"在重启后读不到。修法：主进程把 `cache.json` 的内容作为启动参数传给渲染进程，preload 暴露为快照，`createDesktopPlatform()` 用快照初始化镜像。三处各改几行，端口的同步语义与 IPC 设计都不变。

### 7. 把两条纪律变成自动化

- **令牌单一来源**：扫描 `apps/**/src`，出现硬编码颜色字面量即失败。
- **共享包边界**：扩展现有 `workspace-boundaries.test.ts`，`packages/ui` 除原有的桌面壳专有 API 外，还不得引用 `@paideia/core`、`@paideia/platform-*`、`react-router`。

## 范围

包含：样式管线与令牌层；主题切换与首屏防闪烁；`packages/ui` 的组件库化与 21 个基元；独立的组件展览应用 `apps/ui-kit`；`apps/app` 与 `apps/public` 的迁移；`apps/admin` 新应用与其路由守卫；会话与角色端口；桌面端缓存缺陷修正；上述两条自动化检查；长期约定与索引的文档更新。

不包含：任何业务功能页面；后端角色模型与权限落库；公开页的 SEO 能力扩展；国际化；webfont 与字体子集化；离线同步；性能压测。

## 备选方案

**管理端用单应用路由级分面（`apps/app` 内 `/admin/*`）。** 最强的一面是零新工程、共用同一套壳与认证，改动最小。未选理由：管理端代码会进同一份 bundle，也就进了 Windows 安装包——一个学习工具装上管理台不合理，且暴露面更大。代价：本方案要多维护一个应用与一份 vite 配置。

**主题持久化直接读写 `localStorage`。** 最强的一面是两端都可用、代码最少，而且能省掉桌面端缓存修正与初始化脚本的双分支。未选理由：本项目的平台能力纪律是把宿主相关能力收在 `Platform` 端口之后，主题是它的第一个真实消费者，绕过端口等于让端口继续无人验证。代价：本方案多改了主进程与 preload 三处，初始化脚本也要同时认两种存储位置。

**不引 Tailwind，纯 headless 原语 + 手写 CSS 变量。** 最强的一面是样式与令牌 100% 自控、依赖最少。未选理由：每个组件的每种状态（hover/focus/disabled/invalid）都要自己写，是数周的量，且会重复实现 Tailwind 已经免费提供的部分。代价：接受一个构建期样式依赖，以及"扫描路径必须显式登记"这条纪律。

**用现成外观型组件库（Mantine / Ant Design）。** 最强的一面是开箱即用、管理端表格表单立刻能拼。未选理由：它的视觉语言与已定的黑白玻璃方向直接冲突，覆盖主题的成本高于自建。代价：视觉一致性要自己保证。

**组件只做被真实页面消费的那几个（约 9 个）。** 最强的一面是零未使用代码，符合本项目"不建空壳"的既有纪律。未做为主要方案的理由：用户明确要求把常见组件都组件化。缓解：给每个组件一个真实消费者——展览页——使"未使用"不成立。

**把展览页做成 `apps/app` 里的开发期路由（`import.meta.env.DEV` 守卫 + 动态 import）。** 最强的一面是改动最小：一个页面文件加两行路由，不必新开应用。未选理由（用户 2026-10-09 裁决）：它只能在 `pnpm dev` 里看，构建产物与桌面安装包都没有它；而玻璃效果在 Electron 里的真实表现正是最需要对照的场景。独立应用虽然多几个小文件，但"不进产品产物"是构造上的事实，不依赖构建工具是否真的把死代码消除掉。

## 仓库影响

- **新增**：`frontend/apps/admin/`、`frontend/apps/ui-kit/`（两个应用）、`packages/ui` 下的 `components/`、`styles/`、`theme/`、`layout/`、`lib/`。
- **修改**：`packages/ui`（package.json、导出、占位 `Panel` 删除）、`packages/core`（新增会话端口）、`packages/platform-desktop`、`apps/desktop/electron/{main,preload}.cjs`、`apps/app`（样式引入、路由、首页重写）、`apps/public`（vite 配置、页面改用组件）、`packages/core/src/workspace-boundaries.test.ts`、`.agent/INDEX.md`、`.agent/rules/always.md`（长期约定）。
- **不受影响**：`backend/`、`deploy/`（不存在）、数据库与后端契约。

## 交付拆分

每一片都能独立演示或验证，不按技术分层切割。

| 片 | 内容 | 覆盖验收 |
| --- | --- | --- |
| 1 | 样式管线与令牌层（Tailwind 接入、双主题变量、玻璃层、首屏初始化脚本） | AC-003 的前置、AC-008 |
| 2 | 主题状态与切换（Provider、Toggle、存储注入） | AC-003 |
| 3 | 组件基元、布局壳与独立的组件展览应用 `apps/ui-kit` | AC-001、AC-004 的前置 |
| 4 | `apps/app` 与 `apps/public` 迁移，删除占位组件 | AC-002、AC-004 |
| 5 | 会话与角色端口 + 管理端应用与路由守卫 | AC-006、AC-005、AC-004 |
| 6 | 桌面端缓存缺陷修正 | AC-003 在桌面端的成立条件 |
| 7 | 两条自动化检查、文档与验证报告 | AC-002、AC-007、AC-005 |

## 风险与缓解措施

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Tailwind 扫描不到 `packages/ui` 的类名（pnpm 软链在 `node_modules` 下） | 高（组件静默失去样式，且只有肉眼看才看得出） | `source(none)` + 显式 `@source` 物理路径；验证步骤要求确认 ui 包里的类名出现在产物 CSS 中 |
| 玻璃层嵌在带 `opacity`/`filter` 的容器里，模糊静默失效 | 中 | `.glass` 只用 `@supports` 增强、默认给不透明底；组件层不引入 `opacity` 容器；展览页做视觉核验 |
| 21 个组件在写的过程中变成无验证的样板 | 中 | 展览页提供统一消费者；组件不带业务逻辑，逻辑只出现在守卫、主题、令牌检查三处，这三处各有一条单测 |
| 管理端角色未接通却看起来"能用"，掩盖授权缺失 | 高 | `createSessionReader` 失败即按学习者处理；管理端页显式展示当前身份与被拒结果，不提供任何后门开关；不引入 dev-only 提权 |
| 桌面端缓存修正改动 WORK-001 的既有文件 | 中 | 只做"快照初始化镜像"最小改动，不动端口的同步语义与 IPC 形状；桌面 e2e 本机跑不了，改为对快照种子逻辑的单测 + 记录未运行项 |
| 新增依赖（Base UI、Tailwind、sonner 等）扩大攻击面与安装体积 | 中 | 全部来自已裁决方向；不引 webfont；CJK 走系统字体栈 |

## 验收映射

| 验收标准 | 对应部分 |
| --- | --- |
| AC-001 | 交付拆分 3、4、5（各应用与包都在 `pnpm -r typecheck/test` 覆盖内） |
| AC-002 | 推荐方案 7（令牌单一来源检查）+ 交付拆分 4 |
| AC-003 | 推荐方案 1、2、3 + 交付拆分 1、2、6 |
| AC-004 | 交付拆分 4、5（两个应用各有 e2e 断言真实数据） |
| AC-005 | 推荐方案 5（按构造不进桌面产物）+ 交付拆分 5、7（构建后检索） |
| AC-006 | 推荐方案 5（`createSessionReader`/`canAccessAdmin` 单测两条分支）+ 交付拆分 5 |
| AC-007 | 推荐方案 7（边界检查扩展）+ 交付拆分 7（人工违规复核） |
| AC-008 | 推荐方案 1（`.glass` 的 `@supports` 结构与不透明底）+ 交付拆分 1 |

## 待决定事项

一项，且不阻断本项实现：**组件交付面**。本提案按"常见基元"给出 21 个，更重的组件（表单校验编排、分页、日期选择、命令面板、图表）留到第一个真实业务页面出现时按需引入，理由与 WORK-001 拒绝建空壳模块一致。若希望本期一次性铺到更完整的集合，需要在批准前说明，我会相应扩充组件任务。

## 门槛合并说明

用户在批准需求时（2026-10-09）明确选择**把方案、设计、任务三件合并为一次审阅**，而不是逐门槛分别确认。因此 `design.md` 与 `tasks.md` 在本提案签署之前就已起草，三件共享同一次审阅与同一批签署。

这条决定改变了门槛的**节奏**，没有改变门槛的**内容**：三件工件仍然各自需要用户确认，只是确认发生在一次审阅里。按本设计的严格顺序检查工具会把"上游未签署而下游已存在"报告为一个结构性错误——这是这个合并节奏的必然中间态，不是工件本身有问题；三件按 `proposal → design → tasks` 顺序签署后即消失。
