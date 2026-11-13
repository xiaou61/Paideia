---
artifact: spec
status: active
updated: 2026-10-09
---

# 平台契约

本文记录**跨工作项稳定、后续业务功能直接依赖**的接口与约定。它不替代工作项工件：这些契约由 WORK-001 建立并验证，今后实质变更时先改本文，再让受影响的工作项失效。

## HTTP 与错误契约

所有 `/api/**` 响应统一为：

```json
{ "code": 0, "message": "ok", "data": {}, "traceId": "…" }
```

- `code` 为 `0` 表示成功，非 `0` 为稳定错误码；**已发布的数值不再变更，新增只能追加**。
- `traceId` 由后端在请求入口生成（或沿用合法的上游值），每个响应都会有，包括错误响应。用户报错时用它对齐日志。
- 错误码 → HTTP 状态：`INVALID_ARGUMENT` 400、`UNAUTHENTICATED` 401、`FORBIDDEN` 403、`NOT_FOUND` 404、`CONFLICT` 409，其余 500。
- 错误响应**只**含错误码与消息，不含异常类型与堆栈；堆栈只进日志。
- 控制器可以直接返回 `ApiResponse`，也可以返回普通对象（由包装器补齐结构）；两种方式都会被补上 `traceId`。

定义位置：`backend/paideia-platform/src/main/java/io/github/xiaou61/platform/`。

## 分页约定

- 入参 `PageQuery`：`page` 从 1 起，`size` 上限 200（超限收敛到上限而不是报错），`sort` 去空白后为空白则归一为 `null`。
- 出参 `PageResult`：`total`、`page`、`size`、`items`（不可变列表）。
- mapper 侧显式写 `LIMIT #{limit} OFFSET #{offset}`，`limit`/`offset` 由调用方从 `PageQuery` 传入；总数由**独立的 count 语句**提供。
- 不使用 `RowBounds`，不引入 SQL 改写拦截器，因此分页参数不经过任何线程本地状态。

## 认证与授权

- 业务代码只依赖 `AuthPort`（签发、校验、取主体），不依赖 JWT 细节；换 OIDC 等机制时替换端口实现与解码器配置。
- 无状态：不建会话、不用 Cookie，因此关闭 CSRF，统一走 `Authorization: Bearer <token>`。桌面壳同样适用。
- **身份只从令牌解析**：任何接口都不接受客户端传入的用户标识；归属条件一律取自令牌。这是授权隔离的前提。
- 签名密钥从配置读取，不设默认值；未配置时生成一次性随机密钥并告警（适合本地起服务，部署到他人可访问的环境前必须配置固定密钥，长度不低于 32 字符）。
- `POST /api/v1/auth/token` **不校验任何凭据**，只在 `dev` / `local` profile 下存在。引入真实登录之前，绝不能让它出现在面向他人的环境里。
- 测试用密钥写在测试资源里并带明文标注，不得在任何真实环境复用。

## 模块边界规则

- 一个模块一个 Maven 模块，模块间依赖必须在 `pom.xml` 声明——未声明即编译不过。
- 模块只能访问对方**包根**下的类型；子包（如 `internal`）视为内部实现，由 Spring Modulith 在构建期拦截。
- 主类位于基础包根 `io.github.xiaou61`，其余模块是它的直接子包——这是 Modulith 识别模块的前提。
- 新增跨模块依赖前先确认方向不与现有依赖构成环（编译器会拦），并确认对方确实该被依赖。

## 前端平台能力

- 业务代码只依赖 `Platform` 端口（当前只有 `kind` 与 `cache`）；宿主差异由 `packages/platform-web` 与 `packages/platform-desktop` 各自实现。
- `packages/ui` 与 `packages/core` **不得**引用 `electron` 或 `@tauri-apps/*`，有自动化检查（`packages/core/src/workspace-boundaries.test.ts`）。
- 资源路径必须相对（Vite `base: './'`）；路由用 Hash 路由，以便同一份产物在 Web 与桌面壳下都成立。

## 设计令牌与皮肤契约

四个前端应用共用 `packages/ui/src/styles/globals.css` 这一份样式入口。颜色**只能**来自其中的令牌——应用与共享包里出现十六进制、`hsl()/rgb()` 字面量或调色板类（`bg-blue-500`）会被 `packages/ui/src/tokens.test.ts` 判红。

### 三层结构

| 层 | 位置 | 职责 |
| --- | --- | --- |
| 默认令牌 | `globals.css` 的 `:root` / `.dark` | 结构亮度与默认皮肤（黑白，"墨白"）。这一层不搬家 |
| 皮肤 | `styles/theme/skins/<族>.css` | 只覆盖需要改的令牌；选择器 `:root[data-skin='x']`（0,2,0）与 `:root[data-skin='x'].dark`（0,3,0） |
| 装饰 | `styles/theme/decor.css` | `body::before` 一层装饰（纸纹/光斑），由 `--decor-image/size/opacity/blend` 驱动；默认 `--decor-opacity: 0`，即默认主题零变化 |

皮肤目录信息在 `packages/ui/src/theme/skins.ts`（`SKINS` / `SKIN_FAMILIES`），每族一个文件 `theme/skins/<族>.ts`。外观页 `/settings/appearance` 按族分组展示，卡片自带 `data-skin` 做活预览。

### 加一个皮肤的三步

1. 在对应族的 `styles/theme/skins/<族>.css` 里写两块（亮色 + `.dark`），只写要改的令牌。
2. 在 `theme/skins/<族>.ts` 里登记 `id` / `name` / `summary` / `family` / `tags`。
3. 跑 `pnpm --filter @paideia/ui exec vitest run src/theme`——新皮肤自动进入对比度遍历与契约检查。

### 四条禁止

- **不许在 TS/TSX 里写颜色**（包括预览色板）：一律用令牌类，预览卡靠自己的 `data-skin` 取色。
- **不许改 `--paper*`**：简历纸张（WORK-018）的语义是"纸永远白、字永远黑"，由 `skin-contract.test.ts` 的保留名单拦。
- **不许覆盖未知令牌名**：拼错不会报错、也不会生效，契约测试会点名。
- **不许只写一半**：亮色块里覆盖了哪个令牌，暗色块就必须也给值——否则暗色下会出现"半新半旧"（典型症状是深底深字）。

### 换肤的联动点

- 令牌变化会派发 `paideia:themechange`（`packages/ui/src/theme/theme-events.ts`）。**读 `getComputedStyle` 取色的地方必须订阅它**，否则换肤后不会变色：知识图谱的 sigma / 3d-force-graph 画布（`apps/app/src/components/graph/*`）就是这么处理的；它们只改颜色、不重跑布局。
- CodeMirror 主题（`packages/ui/src/code-editor/theme.ts`）与 markdown 代码块都直接用 `var(--*)`，自动跟随，不需要订阅。
- 打印：`@media print` 下所有令牌强制回纸张语义，深色皮肤不会打出黑底。

## CORS 白名单

- 未列入白名单的来源会被**直接拒为 403**，不是返回 200 少一个放行头。
- 两处开关都要配：`paideia.web.cors.allowed-origins` 管 `/api/**`；Actuator 端点只认 `management.endpoints.web.cors.allowed-origins`。
- 桌面壳渲染进程的来源是 `http://127.0.0.1:5310`（Electron 主进程的本地静态服务）。

## 构建与检查命令

见 `.agent/rules/always.md`（含集成测试所需的环境变量与隧道、桌面端打包所需的双镜像）。本文不重复，避免两处不同步。
