---
artifact: test-plan
work_id: WORK-003
work: 账号与认证
status: ready
created: 2026-10-09
---

# 账号与认证测试计划

## 测试范围

账号实体与迁移、注册全链路、登录、refresh 轮换与撤销、图形验证码、邮箱验证码与限流、
前端共享认证包、两端登录门。真实 SMTP 发信在凭据到位前不在本轮验证范围内（见「已知缺口」）。

## 环境与前置条件

- 后端：`cd backend && mvn -B verify`。集成测试需要真实 MySQL 与 `PAIDEIA_TEST_DB_PASSWORD`；
  未配置时相关类被跳过并计入 Skipped，**不会让构建悄悄变绿**。
- 数据源经本地 SSH 隧道 `127.0.0.1:3307` 连到服务器的 MySQL。本轮实测环境：MySQL 8.0.46、JDK 25、Boot 4.1.1。
- 前端：`cd frontend && pnpm -r typecheck && pnpm -r test`；端到端需要后端 jar 与隧道。
- 端到端测试后端会把数据源指向 `paideia_test` 并额外加载 `classpath:db/devdata`（种子账号），
  **不往开发用的 `paideia` 库写测试账号**。

## 验收矩阵

| 验收标准 | 检查方式 | 位置或命令 |
| --- | --- | --- |
| AC-001 迁移执行、表结构与唯一索引、启动后健康检查 | 命令 + 集成测试 | `mvn -B verify`；`AccountAuthIntegrationTest#accountTableIsMigratedWithUniqueUsername`；local profile 启动记录 |
| AC-002 注册全链路（图形验证码前置、验证码一次性、占用提示时机） | 集成测试 | `AccountAuthIntegrationTest`（四条注册用例） |
| AC-003 双标识登录、失败信息不区分账号存在性 | 集成测试 | `AccountAuthIntegrationTest`（两条登录用例） |
| AC-004 refresh 轮换与重用检测使整条链失效 | 集成测试 | `AccountAuthIntegrationTest#detectsRefreshReuseAndKillsTheWholeFamily` |
| AC-005 登出后 refresh 不可用 | 集成测试 | `AccountAuthIntegrationTest#logoutRevokesTheRefresh` |
| AC-006 未登录被导向登录页；管理端放行管理员、拒绝学习者 | e2e | `apps/app/e2e/home.spec.ts`、`apps/admin/e2e/admin.spec.ts` |
| AC-007 并发 401 只刷新一次；会话失效回登录页 | 单测 | `packages/auth/src/auth.test.ts` |
| AC-008 三条限流 | 集成测试 | `AccountAuthIntegrationTest`（三条限流用例） |
| AC-009 裸签发端点不存在 | 集成测试 | `AccountAuthIntegrationTest#bareTokenEndpointIsGone` |
| AC-010 四个应用构建、类型检查、既有检查继续通过 | 命令 | `pnpm -r typecheck && pnpm -r test`、四个 build、`workspace-boundaries.test.ts`、`tokens.test.ts` |
| 真实 SMTP 发信 | **未运行**（待用户提供凭据） | 计划：配好 `paideia.mail.host` 后发一封确认信 |

## 自动化检查

| 检查 | 命令 |
| --- | --- |
| 后端全量构建与测试 | `cd backend && mvn -B verify` |
| 前端类型检查与单测 | `cd frontend && pnpm -r typecheck && pnpm -r test` |
| 学习者端 e2e（含登录门与登出） | `pnpm --filter @paideia/app test:e2e` |
| 管理端 e2e（含角色放行与拒绝） | `pnpm --filter @paideia/admin test:e2e` |
| 组件展览 e2e（设计系统回归） | `pnpm --filter @paideia/ui-kit test:e2e` |

## 人工检查

- 用浏览器走一遍注册：图形验证码 → 收验证码（本轮未配 SMTP，验证码写后端日志）→ 完成注册。
- 确认未登录访问学习者端被导向登录页、登录后回到原目标地址。
- 确认管理端在未登录 / 学习者 / 管理员三种身份下的三种界面。

## 回归范围

- `packages/core` 的角色端口与边界检查、`packages/ui` 的令牌与对比度检查继续通过（AC-010）。
- 原学习者端三条 e2e 断言**保留**（改造为"先登录再断言"，不是删除）。
- 公开页与组件展览的构建不受影响。

## 已知缺口

- **真实 SMTP 发信未验证**：用户决定最后提供发信凭据。当前未配 `paideia.mail.host` 时使用日志实现
  （验证码写日志、不出网），因此注册链路的正确性由内存信箱的集成测试证明，而"真的能发出邮件"尚未证明。
- 桌面端 e2e 本机不可运行（WORK-001 记录的限制），认证在桌面端的表现未做端到端验证；
  令牌存放走 `Platform` 端口，桌面端与 Web 端共用同一套代码。
- **后端仍没有"按角色拒绝"的接口**：本期没有任何管理员接口要保护，因此越权拦截只在后端"没有可拦的东西"。
- 登出不是即时的：access 在剩余寿命（≤15 分钟）内仍有效，要彻底即时失效需要服务端黑名单。
- 传输层不要求 HTTPS（用户 2026-10-09 豁免）：密码与令牌明文过网。
