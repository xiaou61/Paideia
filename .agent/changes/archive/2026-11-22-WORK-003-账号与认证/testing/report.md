---
artifact: test-report
work_id: WORK-003
work: 账号与认证
status: passed
created: 2026-10-09
evidence: required
accepted_by: xiaou61
accepted_at: 2026-10-29 13:16:41 +0800
---

# 账号与认证验证报告

TASK-001..TASK-012 已实施，十条验收标准各有可复查证据。真实 SMTP 发信已用 QQ 邮箱实测通过
（2026-10-10 补），**且收件人当天确认已在收件箱看到该邮件**，因此报告状态为 `passed`。

分工记录清楚：本机证明的是"SMTP 认证成功、邮件被服务器接受"，投递环节由收件人确认——
这两部分是不同性质的证据，都保留在证据表里。

## 验证环境

- 后端：JDK 25（Temurin 25.0.4）、Spring Boot 4.1.1、MySQL 8.0.46，经本地 SSH 隧道 `127.0.0.1:3307` 连到服务器。
- 前端：Node + pnpm 12.10.1、Vite 8.3.4、React 19.3.0、Tailwind 4.3.3。
- 集成测试库为 `paideia_test`；端到端测试另加 `classpath:db/devdata`（种子账号），未写入 `paideia` 库。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 迁移执行、唯一索引生效、启动后健康检查 UP | `mvn -B verify`（含集成测试）；local profile 启动 | 0 | passed | `testing/logs/backend-verify.txt`、`testing/logs/local-profile-migration.txt` |
| AC-002 注册全链路（图形验证码前置、验证码一次性、占用提示时机） | `mvn -B verify` | 0 | passed | `testing/logs/backend-verify.txt`（`AccountAuthIntegrationTest` 四条注册用例） |
| AC-003 双标识登录、失败信息不区分账号存在性 | `mvn -B verify` | 0 | passed | 同上（两条登录用例断言错误码与文案完全相同） |
| AC-004 轮换后旧 refresh 失效；重用使整条链失效 | `mvn -B verify` | 0 | passed | 同上 |
| AC-005 登出后 refresh 不可用 | `mvn -B verify` | 0 | passed | 同上 |
| AC-006 未登录被导向登录页、登录后可达；管理端放行管理员、拒绝学习者 | `pnpm --filter @paideia/app test:e2e`、`--filter @paideia/admin test:e2e` | 0 | passed | `testing/logs/app-e2e.txt`（5 条）、`testing/logs/admin-e2e.txt`（5 条） |
| AC-007 并发 401 只刷新一次；会话失效回登录页；access 不落盘 | `pnpm --filter @paideia/auth test` | 0 | passed | `testing/logs/frontend-checks.txt`（`packages/auth` 7 条） |
| AC-008 发信间隔、验证码尝试次数、登录失败三条限流 | `mvn -B verify` | 0 | passed | `testing/logs/backend-verify.txt`（三条限流用例） |
| AC-009 裸签发端点不存在 | `mvn -B verify` | 0 | passed | 同上（`bareTokenEndpointIsGone`：返回 404 且响应里无任何令牌） |
| AC-010 四个应用构建、类型检查、既有检查继续通过 | `pnpm -r typecheck && pnpm -r test` + 四个 build | 0 | passed | `testing/logs/frontend-checks.txt` |
| 组件展览回归（设计系统未被破坏） | `pnpm --filter @paideia/ui-kit test:e2e` | 0 | passed | `testing/logs/ui-kit-e2e.txt`（5 条） |
| 真实 SMTP 发信（QQ 邮箱，465 隐式 TLS） | 配好后重启后端，取图形验证码并提交发信接口 | 0 | passed | `testing/logs/smtp-real-send.txt`（`code:0` 且无发信异常；同一 captchaId 再提交返回 40000，顺带证明图形验证码一次性） |

## 验收结果

| 验收标准 | 结论 | 说明 |
| --- | --- | --- |
| AC-001 | **达成** | `paideia` 与 `paideia_test` 两个库都应用了迁移；唯一索引由"重复用户名被拒为 409"这条用例证明真的生效（不只是建了表）。 |
| AC-002 | **达成** | 未过图形验证码不发信（断言内存信箱为空）、验证码错误与重用被拒、**占用提示只在验证码通过后出现**（用不存在的验证码访问已注册邮箱，得到的是验证码错误而不是"已被注册"）。 |
| AC-003 | **达成** | 用户名与邮箱都能登录；错误密码与不存在账号返回**完全相同的错误码与文案**。耗时对齐（对不存在的账号也跑一次 BCrypt）由实现保证，未做计时断言——计时断言在 CI 上不稳定，故记为未直接测量。 |
| AC-004 | **达成** | 轮换后旧 refresh 返回 401；重用旧 refresh 后**连最新那个 refresh 也失效**（整条 family 被吊销）。 |
| AC-005 | **达成** | 登出返回成功，随后该 refresh 换令牌返回 401。 |
| AC-006 | **达成** | 学习者端 5 条 e2e（含"未登录看到登录表单、登录后显示当前用户、登出回登录页"）；管理端 5 条（含学习者被拒且显示身份、管理员进入管理区）。 |
| AC-007 | **达成** | 认证包 7 条单测：并发两个 401 **只触发一次刷新**、刷新失败清令牌且只回调一次不重放、认证端点自身的 401 不触发刷新、access 只存内存、refresh 才落盘。 |
| AC-008 | **达成** | 同邮箱 60 秒内第二次发信被拒（42900 且带剩余秒数）；验证码第 6 次尝试即使给对码也被拒；登录连续失败 5 次后连正确密码也被拒。 |
| AC-009 | **达成** | 该路径返回 404 且响应里不含任何令牌字段。 |
| AC-010 | **达成** | 9 个工程 typecheck 通过；前端 41 条单测通过；四个应用构建通过；后端 `mvn -B verify` 63 条测试通过。 |
| 真实 SMTP | **达成** | 用 QQ 邮箱 465 隐式 TLS 实测：`code:0`、日志无发信异常；**收件人 2026-10-10 确认在收件箱收到**该邮件。 |

## 本轮修掉的缺陷（都是真缺陷，不是测试噪音）

**一、Flyway 从来没有执行过——而且不报错、不打日志。** Boot 4 把各自动装配拆成了独立模块
（`spring-boot-jackson`、`spring-boot-webmvc` 同理），只引 `flyway-core` 时
`FlywayAutoConfiguration` 根本不在类路径上。表现是"迁移从不执行"：表与约束静默不存在。
此前没被发现，是因为 `paideia_test` 里的夹具表恰好由另一条测试路径
（`TestDatabase.migrate` 程序化调用 Flyway）建好了，把问题盖住了。
修法：加 `org.springframework.boot:spring-boot-flyway` 依赖，并在 pom 里写明原因。

**二、未知路由被兜成 500。** `@ExceptionHandler(Exception.class)` 会接住
`NoResourceFoundException`，于是"路径写错了"看起来像"服务端崩了"。加了一条专门的处理器返回 404。
这条也是 AC-009 能被干净断言的前提。

**三、夹具与生产迁移的编号冲突（两个方向）。** 夹具用 V900+（刻意排在所有生产迁移之后），
生产迁移从 V1 开始。由此产生两种失败：①夹具已应用、之后新增更低的生产版本 → Flyway 判为乱序；
②某一方应用过、另一方的 locations 解析不到 → 判为"已应用但本地找不到"。
修法：测试 profile 与端到端环境各自放开 `out-of-order` 与
`ignore-migration-patterns: '*:missing'`，**生产配置不动**。这是"一个测试库被三种迁移配置共用"的
必然结果，已在两处注释里写明。

**四、CORS 白名单与"走代理也不需要配置"的误解。** 管理端（5174）登录拿到 403，
不是权限问题而是 CORS：**浏览器对同源的非简单请求也会发 `Origin` 头**，
后端按来源判定，未列入直接 403——即使请求经由 Vite 代理。
修法：端到端环境显式给出该来源（`PAIDEIA_WEB_CORS_ALLOWED_ORIGINS` 与
`MANAGEMENT_ENDPOINTS_WEB_CORS_ALLOWED_ORIGINS` 两处都要给）。这条对新端口是通用陷阱，已记入长期约定。

## 失败与未验证项

- **无未验证项**：最后一条（真实发信）已由"服务器接受 + 收件人确认收到"两部分证据闭合。
- **未做登录耗时的计时断言**：账号不存在时对齐耗时这一点由代码保证（对固定假摘要跑一次 BCrypt），
  但计时断言在共享 CI 上不稳定，故未写。
- **桌面端认证未做端到端验证**：本机跑不了桌面 e2e（WORK-001 记录）。令牌存储走 `Platform` 端口，
  桌面端与 Web 共用同一套认证代码，但没有在真实 Electron 里验证过。
- **后端没有按角色拒绝的接口**：本期没有管理员接口，所以"后端越权拦截"这一条无从验证，
  也没有可测对象。管理端的角色判定只在界面层，**不是授权边界**。
- **access 在登出后仍有剩余寿命**（≤15 分钟）：JWT 无状态，彻底即时失效需要服务端黑名单，本期不做。
- **无 HTTPS**（用户 2026-10-09 豁免）：密码与令牌明文过网，浏览器会对登录页显示"不安全"。

## 剩余风险

| 风险 | 影响 | 说明 |
| --- | --- | --- |
| 限流与验证码状态是进程内的 | 中 | 单实例有效、重启即清零（影响只是需重新获取验证码）；**多实例部署时必须换共享存储**，代码与注释均已标注 |
| 来源 IP 取自直连地址 | 中 | 部署在反向代理后所有请求会被算成同一来源；届时需改读 `X-Forwarded-For` |
| 密码与令牌明文过网（无 HTTPS） | 高 | 用户已豁免；一旦对外网开放或出现他人真实凭据应立即重评 |
| 登出不是即时的（access 剩余寿命） | 中 | 已写进需求、设计与本报告；access 寿命压到 15 分钟 |
| 种子账号口令是固定值且写在仓库里 | 中 | 只存在于 `db/devdata`，任何 profile 默认都不加载；只在显式开启的端到端环境里生效 |
| 图形验证码可被 OCR 破解 | 低 | 它的定位是提高自动化成本并保护发信接口，真正的防线是限流 |
| 后端测试与端到端测试共用 `paideia_test` | 低 | 已用容忍配置处理；若将来并行跑多套测试，应改为各自独立的库 |
