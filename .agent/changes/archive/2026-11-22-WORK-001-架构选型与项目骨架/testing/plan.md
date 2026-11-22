---
artifact: test-plan
work_id: WORK-001
work: 架构选型与项目骨架
status: ready
created: 2026-10-09
---

# 架构选型与项目骨架测试计划

## 测试范围

本次交付是架构与技术底座，不是业务功能。验证目标是：模块边界真的被强制、数据访问在多库策略下真的跑在 MySQL 上、认证与授权隔离真的成立、同一份前端代码真的能构建出 Web 与桌面两种产物且互不污染。不验证任何业务行为（本期没有业务）。

## 环境与前置条件

- JDK 25、Maven 3.9、Node 24、pnpm 12（本机已具备）。
- 集成测试依赖服务器上的 MySQL：需要一个从本机 `127.0.0.1:3307` 到服务器 `127.0.0.1:3306` 的 SSH 隧道，并提供 `PAIDEIA_TEST_DB_PASSWORD`。缺失时相关用例跳过并在 stderr 明确提示，构建不会因此变红。
- 后端本地运行需要数据源，因此以 `--spring.profiles.active=local` 启动，配置读 `backend/config/application-local.yml`（被 gitignore，含口令）。
- 桌面端安装包构建需要能访问 GitHub（electron-builder 拉取 NSIS 与签名辅助二进制）。

## 验收矩阵

| 验收标准 | 检查方式 | 位置或命令 |
| --- | --- | --- |
| AC-001 | 人工检查：技术选项登记表覆盖 D-01..D-15，结论全部由用户裁决并留痕 | `.agent/references/technology-options.md`、`.agent/rules/always.md` |
| AC-002 | 构建 + 启动 + 健康检查 | `mvn -B verify`；`java -jar … --spring.profiles.active=local` + `curl /actuator/health` |
| AC-003 | 端到端：真实浏览器中页面展示后端真实数据 | `cd frontend && pnpm test:e2e` |
| AC-004 | 构建期模块边界校验 + 人为违反的实证 | `mvn -B verify` 的 `ModularityTest`；人为注入记录见 `tasks.md` TASK-001/TASK-002 |
| AC-005 | 真实 MySQL 上的分页用例 | `mvn -B verify` 的 `PaginationIntegrationTest` |
| AC-006 | 授权隔离与越权拒绝 | `mvn -B verify` 的 `AuthorizationIsolationTest` |
| AC-007 | 桌面端运行时（渲染服务、调用后端、CORS）；安装包 | `pnpm build:desktop`；桌面启动后观察后端请求计数 |
| AC-008 | 公开页预渲染 + 两个 surface 互不混入 | `pnpm --filter @paideia/public build` + 产物交叉检索 |

## 自动化检查

1. `cd backend && PAIDEIA_TEST_DB_PASSWORD=… mvn -B verify` —— 5 个模块的单元与集成测试。
2. `cd frontend && pnpm -r typecheck` —— 6 个包的类型检查。
3. `cd frontend && pnpm -r test` —— 共享包单测与共享包边界检查。
4. `cd frontend && pnpm test:e2e` —— Web 端到端（Playwright 自行拉起后端与前端）。
5. `cd frontend && pnpm --filter @paideia/public build` —— 公开页预渲染。

## 人工检查

- 技术选项登记表是否覆盖全部影响架构的决策点，且没有替用户下结论。
- 常驻规范里的已裁决清单与需求/提案/设计是否一致。
- 服务端错误响应是否只暴露错误码与追踪标识，不含堆栈。

## 回归范围

本期建立的检查即为后续的回归基线：模块边界校验、分页与归属过滤、授权隔离、CORS 白名单、双端构建与 surface 隔离。任何一项在后续工作项中被改动，都要重跑对应检查。

## 已知缺口

1. **桌面端安装包未产出**：electron-builder 需要从 GitHub 拉取 NSIS 与签名辅助二进制，本机网络对 github.com 持续超时。代码与运行时行为已验证，仅打包一步未完成。
2. **桌面端 Playwright 用例未在本机运行**：该环境不允许 node spawn `cmd.exe`（实测 `error=ENOENT`，而该文件存在），而 Playwright 在 Windows 上正是经 cmd.exe 启动 Electron。用例已保留。
3. **授权隔离的端到端验证依赖测试作用域的示例表**，不是真实业务实体；第一个带归属的业务功能落地后应迁到真实实体上。
4. 未做性能与容量验证，本期不在范围内。
