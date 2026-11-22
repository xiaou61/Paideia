---
artifact: test-report
work_id: WORK-002
work: 前端组件化与管理端拆分
status: passed
created: 2026-10-09
accepted_by: xiaou61
accepted_at: 2026-10-29 13:16:35 +0800
---

# 前端组件化与管理端拆分验证报告

全部 TASK-001..TASK-011 已实施，八条验收标准各有可复查证据。**一项检查在本机不可运行**（桌面端 e2e），其结论由单测承担并在此如实标注，见「失败与未验证项」。

## 验证环境

- 机器：本开发机（Windows），工作目录 `F:\Paideia\frontend`。
- Node + pnpm 12.10.1；Vite 8.3.4；Tailwind CSS 4.3.3；React 19.3.0；`@base-ui/react` 1.8.0。
- 后端：JDK 25（Temurin 25.0.4）与 `paideia-app-0.0.1-SNAPSHOT.jar`，以 `local` profile 启动，数据源经本地 SSH 隧道 `127.0.0.1:3307` 连到服务器的 MySQL 8.0.46。**学习者端与管理端的 e2e 都真跑通了后端**。
- 未运行：桌面端 e2e（本机限制，见下）；桌面安装包打包（需联网拉 electron-builder 二进制）。

## 验证结果

`manual` 表示人工核对产物得出。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 类型检查与单测全绿、新包在覆盖内 | `pnpm -r typecheck` && `pnpm -r test` | 0 | passed | `testing/logs/frontend-checks-task001-005.txt`（8 个工程 typecheck 通过；core 14 条 + platform-desktop 4 条 + ui 16 条 = 34 条） |
| AC-002 应用侧无硬编码颜色，且检查会拦住回退 | `pnpm -r test`（含 `tokens.test.ts`） | 0 | passed | 同上；违反复核 `testing/logs/guards-negative-proof.txt` |
| AC-003 浅深可切换、刷新保持、首屏不闪、对比度达标 | `pnpm -r test` && `pnpm --filter @paideia/ui-kit test:e2e` | 0 | passed | `testing/logs/ui-kit-e2e.txt`、`guards-negative-proof.txt`；观感证据 `testing/evidence/ui-kit-{light,dark,dark-dialog}.png` |
| AC-004 学习者端渲染后端真实数据 | `pnpm --filter @paideia/app test:e2e` | 0 | passed | `testing/logs/app-e2e.txt`（3 条：真实健康状态、追踪标识、受保护接口被拒） |
| AC-004 管理端渲染后端真实数据 | `pnpm --filter @paideia/admin test:e2e` | 0 | passed | `testing/logs/admin-e2e.txt`（被拒页面上仍显示后端真实 `UP`） |
| AC-005 桌面产物与 Web 产物都不含管理端与展览页 | 构建后检索 `apps/app/dist` 与 `apps/desktop/renderer` | 0 | passed | `testing/logs/ac005-product-grep.txt`（四个关键词命中 0 个文件；对照组 `apps/admin/dist` 命中） |
| AC-006 非管理员进不了管理端路由 | `pnpm --filter @paideia/core test` && `pnpm --filter @paideia/admin test:e2e` | 0 | passed | `session.test.ts` 六条（含"角色不可识别按学习者处理"）；`testing/logs/admin-e2e.txt` 断言被拒且身份为未登录 |
| AC-007 边界检查覆盖新增包且违规必失败 | `pnpm --filter @paideia/core test` | 0 | passed | 四种导入形式（具名/副作用/动态/跨包）逐一注入违规均被拦住、注释不误报，见下文「本轮修掉的两个真缺陷」 |
| AC-008 玻璃层无 `backdrop-filter` 时有降级 | 产物 CSS 检索 `.glass` 的三条规则 | manual | passed | `testing/logs/frontend-checks-task001-005.txt`；观感证据 `testing/evidence/` |
| 扫描路径登记正确（ui 包与各应用独有类名进入产物 CSS） | 检索产物 CSS 的 `.glass`、`.bg-popover`、`min-h-screen` | manual | passed | 同上 |
| 首屏主题脚本注入位置（app、admin、public 三处） | 产物 `index.html` 内脚本位于 `<head>` 且早于模块脚本 | manual | passed | 同上 |
| 公开页预渲染仍注入正文并输出相对样式链接 | `pnpm --filter @paideia/public build` | 0 | passed | 同上（注入正文 704 字符） |
| 桌面端缓存的同步读（本机不能跑桌面 e2e，改由单测承担） | `pnpm --filter @paideia/platform-desktop test` | 0 | passed | 4 条：快照即读、写入同步可见并异步落盘、删除、快照异常不抛错 |
| 两个 Electron 脚本语法有效 | `node --check main.cjs` && `node --check preload.cjs` | 0 | passed | 同上 |

「按预期失败」的两次复核（探索性验证，不是缺陷）见 `testing/logs/guards-negative-proof.txt`：对比度检查在令牌被改坏时失败（实测 3.35:1）、令牌单一来源检查在注入 `#ff00ff` 时失败。两次都做了复原自检。

## 验收结果

| 验收标准 | 结论 | 说明 |
| --- | --- | --- |
| AC-001 | **达成** | 8 个工程 typecheck 通过；34 条单测通过；两个新包（`@paideia/ui`、`@paideia/ui-kit`）与两个新应用（`admin`、`ui-kit`）都在 `-r` 覆盖内。 |
| AC-002 | **达成** | 检查扫描 `packages/ui/src` 与全部 `apps/*/src`，含文档文案；已复核它会失败。 |
| AC-003 | **达成（桌面端由单测承担）** | 单测覆盖切换与系统跟随；e2e 断言刷新后未经交互 `html` 即为深色（首屏脚本所落）；对比度由 10 条实测断言守住；三处入口都确认脚本注入位置正确。桌面端的持久化由 `createBridgeCache` 的 4 条单测承担，未在真实 Electron 里跑过（见未验证项）。 |
| AC-004 | **达成** | 学习者端 3 条、管理端 3 条 e2e 都在**真实后端**上通过，页面展示的是接口返回而非硬编码。 |
| AC-005 | **达成** | 学习者端产物与桌面渲染产物中，管理端与展览页的关键词命中 0 个文件；对照组证明检索方法有效。 |
| AC-006 | **达成** | 角色判定 6 条单测 + 管理端 e2e 断言被拒；「失败即拒绝」的各分支（无令牌、载荷畸形、角色缺失、角色不可识别）都有用例。 |
| AC-007 | **达成** | 边界规则扩展到 `packages/ui` 的 `components/`、`layout/`、`theme/`，并修掉了原检查的一个真缺陷（见下）。 |
| AC-008 | **达成** | `.glass` 三条规则都在产物 CSS 里：默认不透明底、`@supports` 增强、`prefers-reduced-transparency` 关闭模糊。默认值即降级形态。 |

## 本轮修掉的两个真缺陷

**一、共享包边界检查漏检纯副作用导入。** 原正则只认 `from` 与 `require(`，于是 `import 'electron'` 这种写法能**整个绕过检查**——连 WORK-001 时代就存在的 electron 规则一起漏。这是用探针复核时发现的。现已同时认 `from`、`import 'x'`、`import('x')` 三种写法，并在扫描前剥掉注释（否则注释里提到依赖名会误报）。四种写法逐一注入违规确认被拦住。

**二、桌面端缓存的同步读永远为空。** `platform-desktop` 的 `get` 只读一个进程内镜像，而镜像只由 `set` 写入、启动时不填充——因此"上次存的值"在桌面端重启后读不到，主题偏好每次启动都丢。修法：主进程读 `cache.json` 并经启动参数注入（`sandbox: true` 下 preload 读不了文件），preload 暴露为 `cacheSnapshot`，`createBridgeCache` 用快照初始化镜像。端口的同步语义与 IPC 形状未变。

## 失败与未验证项

- **桌面端 e2e 未运行**：WORK-001 已记录本机限制（Playwright 经 `cmd.exe` 启动 Electron 的 spawn 被限制）。因此桌面侧的结论来自 `createBridgeCache` 的 4 条单测 + 两个 cjs 的语法检查 + 人工核对调用链，**不等于在真实 Electron 里验证过**。这是本报告里唯一没有端到端证据的一条。
- **桌面安装包未打包**：`build:desktop` 需要联网拉 electron-builder 二进制。AC-005 的证据取自 `prepare-renderer` 之后的 `apps/desktop/renderer`（那正是安装包会打包的内容），不是安装包本身。
- **观感证据是截图级**：`testing/evidence/` 下三张（浅色整页、深色整页、深色含对话框），未在窄屏与不同显示缩放下逐项核验。

## 剩余风险

| 风险 | 影响 | 说明 |
| --- | --- | --- |
| 四个应用共用同一份编译后的 CSS（各约 58 kB） | 中 | 已知取舍：`@source` 只登记一处换来"不会漏"。若要按应用裁剪，需让各应用有独立 CSS 入口与 `@source` 列表。 |
| 静止边框对比度低于 3:1 | 低 | 刻意的：Geist 自己的默认边框就是 `hsl(0 0% 92%)`，层次靠字号与留白建立。焦点指示已由 ring 合成后 ≥3:1 覆盖。 |
| 后端尚未签发角色，管理端恒为"无权限" | 中 | 设计意图（授权缺失必须看得见），但意味着管理端在此之前无法真正使用；后端角色契约落地后前端不需改动即可放行。 |
| 字体仍是系统 CJK 栈，没有 Geist 的字形与字距质感 | 低 | 界面只做中文、Geist Sans 无中文字形；引 webfont 需要子集化流水线。 |
| `cn` 包与 shadcn 注册表较新（2026 年） | 低 | 已锁精确版本。 |
| 展览页 chunk 超过 500 kB（构建告警） | 低 | 开发/内审页，不进产品产物。 |
