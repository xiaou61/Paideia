---
artifact: proposal
work_id: WORK-016
work: 移动端最佳适配
status: approved
mode: strict
workflow: full
depends_on: [requirements]
related_to: [WORK-002, WORK-011, WORK-012, WORK-014, WORK-015]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 09:02:01 +0800
approver_role: CTO
---

# 提案 · 移动端最佳适配

## 摘要

不重构、不引运行时断点系统、不新建抽象层。只在**四个决策点**上把窄屏形态补齐，再补一套移动测试矩阵与 PWA 壳：

1. **`packages/ui` 的 `Dialog`**：`<sm` 时从居中浮层改为贴底到顶的全屏面板，页脚按钮常驻可见 —— 一处改动，三个应用所有弹窗自动受益。
2. **两个外壳**（`AppShell`+`LearnerHeader`、`AdminShell`）：窄屏把导航收进抽屉，桌面行为一行不变。
3. **`AssistantShell`**：`<640` 换底部抽屉容器，`ConversationPanel` 等子组件不动。
4. **`globals.css`**：补 `viewport-fit=cover` 所需的 `.safe-*` 工具类与 `--touch-min`，全高容器统一改 `dvh`。

管理端的四个表格页各自补一份卡片呈现，但**只提供字段与操作**，容器判断复用同一套模式。

PWA 只加在学习者端：`vite-plugin-pwa` 的 `generateSW` + `autoUpdate`，预缓存静态产物，`/api` 与 `/actuator` 走网络、永不缓存。

## 关键取舍

### 为什么不用 `useBreakpoint` / `ResponsiveProvider`

CSS 断点已经能表达全部需求，而 JS 断点带来三个新问题：首帧按服务端猜测的宽度渲染再跳变、SSR 与客户端不一致（`apps/public` 是预渲染的）、测试里要 mock 视口事件。**能用 CSS 表达的就不要引入运行时状态。**

### 为什么 `Dialog` 改在共享层而不是各页面

当前 6 处 `DialogContent` 调用点里有 4 处写了 `sm:max-w-*`，各自为移动端补样式就是 6 份重复。改成 `Dialog` 自己按 `sm` 切换形态后，调用点只表达"这个弹窗有多宽"（桌面语义），**移动端形态由组件决定**。AC-013 就是守这条。

### 为什么管理端表格做卡片而不是"横向滚动优化"

四个表格的列宽加起来在 375px 上必然要横向拖动才能看到"操作"列 —— 而操作列是后台最常用的目标。横向滚动意味着每次操作都要先拖一次。卡片把记录的字段与操作放在同一屏内，是唯一不需要横向拖动的形态。

代价是四份卡片布局要写。**这是本项最重的一块**，但它是纯展示代码，没有逻辑风险。

### 为什么 PWA 只用 `vite-plugin-pwa`

手写 service worker 要自己处理版本哈希、预缓存清单、更新握手、缓存清理，等于重写 Workbox。`vite-plugin-pwa` 在 Vite 生态里是标准解，`generateSW` 模式几乎零配置，`autoUpdate` 直接满足 REQ-026。

风险与缓解：
- **体积**：插件本身是 devDependency，产物多一个 SW 文件 + manifest + 三个图标。预缓存清单会内联进 SW，正常几 KB。**必须记录增量**（AC-014）。
- **缓存错配**：`autoUpdate` + `cleanupOutdatedCaches` 保证旧壳被清掉；`/api` 不进预缓存、也不配 runtime caching。
- **开发干扰**：`devOptions.enabled = false`，`pnpm dev` 下没有 SW。
- **iOS**：只能"添加到主屏幕"，没有安装提示条；验收只断言 manifest 可解析与 SW 注册成功，安装流程如实标注为人工验证。

### 被否掉的选项

| 选项 | 否掉的理由 |
| --- | --- |
| 底部标签栏 | 只有 3 个入口，占一整条高度不划算；用户已裁决抽屉 |
| 移动端单独一套路由/页面 | 两套页面必然漂移，等于把"某页忘了适配"变成"某页忘记同步" |
| 通用 `ResponsiveTable` 抽象 | 四个页面字段不同，抽象会变成一堆条件开关；不如各写一份卡片 |
| `react-use` / `usehooks-ts` 的 `useMediaQuery` | 同上，CSS 已够；且是新依赖 |
| CSS Container Queries | 本项所有形态切换都跟**视口**走（导航、抽屉、弹窗），不是跟容器宽度走；容器查询解决的是另一种问题 |
| 用 `100svh` 替代 `100dvh` | `svh` 在地址栏收起后留白；`dvh` 跟随实际视口，底部按钮始终可见 |

## 影响面

| 文件 | 改动性质 |
| --- | --- |
| `frontend/packages/ui/src/components/dialog.tsx` | 形态随断点切换 |
| `frontend/packages/ui/src/styles/globals.css` | 安全区/触控工具类；`100vh` → `100dvh` |
| `frontend/packages/ui/src/layout/app-shell.tsx` | 页头窄屏内边距、主内容内边距 |
| `frontend/apps/app/src/components/LearnerHeader.tsx` | 导航抽屉 + 搜索入口 |
| `frontend/apps/app/src/pages/*.tsx` | 逐页窄屏检查（多为内边距/网格） |
| `frontend/packages/assistant/src/assistant-shell.tsx` | 底部抽屉容器 |
| `frontend/apps/admin/src/components/AdminShell.tsx` | 抽屉侧栏 |
| `frontend/apps/admin/src/pages/*.tsx` | 四个表格页加卡片呈现 |
| `frontend/apps/app/vite.config.ts` + `package.json` | PWA 插件与图标 |
| `frontend/apps/app/index.html` / `admin/index.html` / `public/index.html` | `viewport-fit=cover`、`theme-color` |
| `frontend/apps/{app,admin}/playwright.config.ts` + 新增 spec | 移动 project |
| `frontend/apps/desktop/electron/main.cjs` | 最小窗口尺寸 |

## 风险

| 风险 | 缓解 |
| --- | --- |
| 管理端四个表格页改动量大、可能碰坏现有 e2e（9 条） | 卡片用 `md:hidden`，表格保留 `hidden md:block`；现有断言选择器全部保留在桌面分支下 |
| 助手面板状态（位置/尺寸）在窄屏下无意义 | 底部抽屉不读也不写 `x/y`，只沿用 `open`；回到宽屏时仍用原浮动窗位置 |
| `Dialog` 改形态可能影响已有 6 个调用点 | 只改基础样式，不改 props 契约；`sm:max-w-*` 仍然有效 |
| PWA 预览构建与 e2e 的交互 | PWA 只在 `vite build` 生效；e2e 默认走 `pnpm dev`，不受影响；另加一条针对生产构建的验证 |
| 与本机无 Docker 类似的环境限制 | 移动 project 用 Playwright 内置设备描述符，不需要模拟器 |
