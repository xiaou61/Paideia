# 架构审计整改：首批验证报告

记录于 2026-11-25。仅为阶段证据；等全部范围有结论后再形成整个 WORK-092 的正式验收报告。

## 验证环境

`work/WORK-092` 独立工作树。Maven 使用本地缓存；前端用本机 Chrome、合成账号和本地 HTTP 夹具；未接入共享数据库、生产、外部 MCP 或真实 OAuth 提供方。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001：发现、调用与模块边界 | `mvn -q -o -pl paideia-mcp,paideia-app -am -Dtest=McpViewsTest,McpViewRegistryTest,McpToolInvokerTest,ModularityTest -Dsurefire.failIfNoSpecifiedTests=false test`（`backend/`） | 0 | passed | `0157d669`；38 条通过，含无匹配组不返回工具、处理器不执行及原有授权用例。 |
| AC-002：会话及延迟读写 | `pnpm --filter @paideia/auth test`；`pnpm --filter @paideia/knowledge exec vitest run src/source-reading-progress.test.ts src/learning-progress.test.ts`（`frontend/`） | 0 | passed | `1e037af3`；认证 22 条、进度 14 条通过。覆盖旧请求和旧续期、账号归属与过期批次。 |
| AC-002：双端浏览器 | `$env:E2E_CHANNEL='chrome'; pnpm --filter @paideia/app exec playwright test --config e2e-session/playwright.config.ts --repeat-each=2`（`frontend/`） | 0 | passed | `1e037af3`；4 次通过。实际页面切换账号、旧响应、管理端迟到变更和例行续期。 |
| AC-002、003：编译与桥分支 | `pnpm --filter @paideia/auth test`；`pnpm --filter @paideia/auth --filter @paideia/app --filter @paideia/admin --filter @paideia/platform-desktop typecheck`；`pnpm --filter @paideia/desktop prepare-renderer`（`frontend/`） | 0 | passed | 认证 25 条通过，含 3 条 OAuth 导航夹具（Web、桌面、禁用授权）；四包类型检查通过，生成内嵌资产所需的渲染产物。 |
| AC-003：原生编译和定向测试 | `cargo fetch --locked --offline --target x86_64-pc-windows-msvc`；`cargo test --locked`；`cargo test --locked --features dev-renderer`；`cargo test --locked --features custom-protocol,dev-renderer`；`cargo check --locked --release --features custom-protocol,dev-renderer`（`frontend/apps/desktop/src-tauri`） | 0 | passed | 离线取包通过；三种特性组合各 12 条测试全通过（含只有显式无内嵌的调试构建才选开发渲染器、地址被占用时拒绝启动内嵌渲染器、导航来源限制、开发实例使用临时缓存），0 警告；release 特性组合检查通过。 |
| AC-003：隔离窗口、真实授权与回跳 | 隔离 Windows 用户会话及 OAuth 测试提供方 | manual | not run | 本轮未确认独立用户环境，避免触及现有安装的缓存、登录和协议注册。 |
| AC-004～007 | 无 | — | not run | 后续批次，尚未获实施批准。 |

## 验收结果

- AC-001、AC-002 有局部自动化证据；尚未做用户业务验收。
- AC-003 的前端构建与桥分支已通过；桌面原生在锁定依赖下完成编译与定向测试。
- AC-004～007 不在本轮。整个 WORK-092 保持打开。

## 失败与未验证项

- 双端浏览器回归不使用真实后端、数据库或生产令牌。没有确认独占数据库，因此未运行真实后端 e2e。
- 架构结构图检查中，模块依赖与前端包图一致；已有数据库/API 清单漂移仍在，留给后续校验治理批次，本批未修改 schema、外部服务依赖或公开端点。
- 用例包含应被拒绝的合成输入；没有在旧版本上另行运行红测验证，也没有尝试还原对真实凭据或运行环境的影响。

## 剩余风险

- 会话结束不能撤销服务端已经收到的操作，只能阻断迟到回调、后续请求和重新发送。
- 桌面真实窗口、协议回跳和账号绑定仍需在隔离环境复核；源码、夹具和资源构建不能代替它。
