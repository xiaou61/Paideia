---
artifact: design
work_id: WORK-092
work: 架构审计整改
status: approved
updated: 2026-11-25
approved_by: xiaou61
approved_at: 2026-11-25 11:59:43 +0800
approver_role: CTO
---

# 架构审计整改：首批设计

## 变更清单

| # | 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/McpViews.java:85-98` | 修改 | 工具组过滤只返回一个集合 | 区分不限制与无匹配；无匹配不进入共享目录查询 | AC-001 |
| 2 | `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/McpViewRegistry.java:79-95,109-118` | 修改 | 仅提前拒绝未知组和不可见的管理组 | 明确不允许的指定组不创建或复用视图，沿用未找到响应 | AC-001 |
| 3 | `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/McpToolInvoker.java:94-126` | 修改 | 执行前用目录查询、scope 和角色检查 | 每次根据当前凭据检查组；不允许时不进入工具执行，保留原有审计 | AC-001 |
| 4 | `frontend/packages/auth/src/token-store.ts:28-48`、`frontend/packages/auth/src/auth-context.tsx:49-101,122-149` | 修改 | 当前令牌与刷新任务未区分登录会话 | 显式登录和结束会话切换会话标记；异步认证结果只能提交给当前会话或当前登录尝试，例行续期不切换 | AC-002 |
| 5 | `frontend/packages/auth/src/authorized-fetch.ts:41-74` | 修改 | 401 重放读取当时的当前令牌 | 一次请求绑定发起时的会话；先校验再刷新或重放，不共享跨会话的刷新任务 | AC-002 |
| 6 | `frontend/apps/app/src/main.tsx:14-22,97-110`、`frontend/apps/admin/src/main.tsx:12-19,35-45` | 修改 | 每端全局查询/变更缓存 | 以会话为键重建查询/变更缓存和有账号状态的页面树，保持各端的原有查询设置 | AC-002 |
| 7 | `frontend/apps/desktop/src-tauri/src/main.rs:162-184,194-199` | 删除 | 内置监听失败可根据首页特征复用监听者 | 正式版没有回退；开发版只由显式开发模式选定 Vite，不以 HTTP 内容当所有权证明 | AC-003 |
| 8 | `frontend/apps/desktop/src-tauri/Cargo.toml`、`frontend/apps/desktop/package.json:9-11` | 新增 | 无独立的 renderer 模式选择 | `dev-renderer` Cargo feature 仅由开发命令开启，且须是 debug 构建 | AC-003 |
| 9 | `frontend/apps/desktop/src-tauri/src/main.rs:28-99,328-354` | 修改 | 正式与开发启动都载入并写入正式缓存 | 正式分支先完成监听再载入既有缓存；开发分支用进程内空缓存，不迁移、不落盘 | AC-003 |
| 10 | `frontend/apps/desktop/src-tauri/src/main.rs:240-257,356-372` | 修改 | 创建窗口时直接安装缓存桥 | 原生导航限制预定 origin，桥只在该 origin 的主框架初始化 | AC-003 |
| 11 | `frontend/apps/desktop/src-tauri/src/main.rs:310-338,353-354` | 修改 | 开发进程也注册正式 OAuth 协议，并接收本机握手 | 只有正式模式注册和转交正式协议握手；开发模式使用普通登录验证，不抢占正式安装的协议 | AC-003 |

## 系统上下文

- 工作位于 `work/WORK-092`，`F:/Paideia/.worktrees/work-092`，2026-11-25 11:49（+0800）创建。只做本地提交，不推送、不部署。
- 首批覆盖 REQ-001～003，不改变数据库结构、HTTP API、MCP 角色、OAuth 服务端配置或模块拓扑。后两批不随本设计开工。

## 组件与失败行为

**MCP。** 对指定组先做白名单判断；无匹配用 MCP 内部的明确拒绝表达，不能用传给 `ToolCatalog` 的空集合表示。默认和全组视图有白名单时继续在该白名单内过滤，无白名单时维持原语义。发现保持现有未找到响应，调用按当次凭据重新验证，不把已缓存视图当授权凭据。合法写入、角色过滤、限流、用量和拒绝日志不扩大权限。

**前端登录。** 使用内存中的会话标记，不放令牌值或用户资料，不写磁盘。组件读的标记与 fetch 判断来自同一个会话状态。主动登录、注册、OAuth 完成、退出和明确失效分别处理；迟到的旧刷新不能更新或清理由新登录建立的会话，且旧任务的清理不能清掉新任务。网络失败保留既有处理；当前会话的服务端拒绝照旧结束会话。不能复用旧会话的 401 重试或把其响应交给新会话；同一会话内仍合并并发刷新。查询与变更缓存之间不复制任何数据，卸载旧的账号页面并清理其缓存；不声称能撤回已发给服务端的请求。平台、主题与桌面 OAuth 监听留在会话页面树外，避免不必要的重复初始化。

**桌面。** 正式分支仅在自己拿到固定地址的监听后启动嵌入静态服务和 WebView；绑定失败走现有启动对话框，不读缓存、不创建窗口。开发命令继续由 Tauri CLI 启动严格端口的 Vite，选用开发分支不做生产回退。开发缓存仅活在进程里；正式缓存路径和 Electron 旧缓存迁移维持现状。窗口导航按 URL 的 origin 比较，不按字符串前缀；不放宽外链的系统浏览器处理。验证开发手动登录和正式冷/热启动的既有 OAuth 协议行为；开发模式不注册、不转交正式 OAuth 握手，避免覆盖正式应用的系统协议归属。两个分支都保持现有 renderer origin 和 CORS。

## 测试策略与需求追踪

- **REQ-001 / AC-001：** `McpViewRegistryTest` 验证未允许组不构建视图；`McpToolInvokerTest` 用合成工具验证拒绝时不调用 handler。既有默认、角色、scope 和读写用例继续通过；跑受影响 Maven 模块测试和模块边界检查，不打服务或真实凭据。
- **REQ-002 / AC-002：** `frontend/packages/auth/src/auth-context.test.tsx` 和 `frontend/packages/auth/src/auth.test.ts` 用可控 Promise 验证旧刷新完成/失败、旧 401、新会话刷新、正常续期。两端各加会话切换回归：同一窗口先以 A 使用私有资源，切换至 B 后才释放 A 的延迟响应，断言 B 不短暂显示 A 的数据，且刷新不重建同会话的缓存。验证变更缓存和页面本地状态；数据库测试只走隔离测试入口。
- **REQ-003 / AC-003：** Rust 测试用本地无凭据夹具检查绑定失败、release 不选开发模式、origin 过滤与开发缓存；保留 OAuth 校验。只构建启动验证需要的产物；有桌面依赖时验证真实窗口的错误路径与正常启动，否则明确记录未跑的项，不用单测替代桌面实测。

## 待决定事项

- 等待批准本设计；执行命令、分片提交和验收证据在任务计划中列出。
