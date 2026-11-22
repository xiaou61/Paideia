---
artifact: tasks
work_id: WORK-003
work: 账号与认证
status: approved
created: 2026-10-09
updated: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 17:27:30 +0800
approver_role: CTO
---

# 账号与认证实施任务

## 依据

- `.agent/changes/WORK-003-账号与认证/requirements.md`（`draft`，待批准）
- `.agent/changes/WORK-003-账号与认证/proposal.md`、`design.md`（本次一并提交审阅）
- `.agent/rules/always.md`：D-10 自签 JWT 与端口纪律、会话 Cookie 被排除、编码约定、公开仓库保密规则、传输层不要求 HTTPS

## 全局约束

- **令牌机制收在 `paideia-security` 的端口之后**：业务模块不得直接依赖 Nimbus / Spring Security 的 JWT 类型。
- **账号表相关 SQL 只写在 mapper XML**，Java 里不拼 SQL；时间存 UTC；标识符小写 snake_case；主键自增。
- **令牌一律走 `Authorization` 头**，不得引入 Cookie 或会话。
- **密码、令牌、验证码明文永不进日志、异常消息与响应体**；refresh 与两类验证码只存哈希。
- 新增凭据只进环境变量或被忽略的本地配置文件；仓库内只提交占位示例。
- `packages/ui` 仍不得引用 `core` / 平台包（WORK-002 的边界检查继续生效）；`packages/auth` 可以依赖 `core`。
- 每个任务完成后更新本文件的任务状态，并在 `.agent/history/updates.md` 追加记录。

## 任务

### TASK-001 | done | `paideia-account` 模块骨架与账号表迁移

- 依赖：无
- 修改：`backend/pom.xml`（声明模块）、`backend/paideia-account/pom.xml`（新建）、`backend/paideia-app/pom.xml`（依赖）、`backend/paideia-account/src/main/java/io/github/xiaou61/account/package-info.java`（新建，声明模块与对外契约）、`backend/paideia-account/src/main/java/io/github/xiaou61/account/AccountApi.java`（新建，先只声明 `findRoleById` 之类的对外读取）、`backend/paideia-app/src/main/resources/db/migration/V1__create_account_tables.sql`（新建）
- 步骤：
  1. 建模块：`groupId io.github.xiaou61`、artifact `paideia-account`，依赖 `paideia-platform`、`paideia-persistence`、`paideia-security`、`spring-boot-starter-webmvc`、`spring-boot-starter-validation`。
  2. 写 `V1__create_account_tables.sql`：`users` 与 `refresh_tokens` 两表，字段与索引按设计；字符集 `utf8mb4` / `utf8mb4_0900_ai_ci`。
  3. 在迁移文件顶部注释写明三条约定：唯一索引大小写不敏感是库行为、审计时间走列默认值并存 UTC、SQL 只在 mapper。
  4. 确认 `ModularityTest` 仍通过（新模块符合 Modulith 的包结构约定）。
- 验证：
  - `cd backend && mvn -B -q -DskipTests package` 通过。
  - `mvn -B verify` 中 `ModularityTest` 通过（需要 3307 隧道；未就绪时记录为未运行）。
  - 用 `mysql` 客户端或集成测试确认两表存在且两个唯一索引生效：`SHOW INDEX FROM users`。

### TASK-002 | done | `AuthPort` 带角色、角色映射、删除裸签发端点

- 依赖：无
- 修改：`backend/paideia-security/src/main/java/io/github/xiaou61/security/AuthPort.java`、`JwtAuthService.java`、`SecurityConfiguration.java`、`MeController.java`、`AuthController.java`（**删除**）、对应的测试
- 步骤：
  1. `AuthPort.Subject` 改成 `(String id, String role)`；`issue(Subject)` 写 `role` claim；`authenticate` 读 `role`，**缺失或不可识别即抛 `UNAUTHENTICATED`**。
  2. 加 `JwtAuthenticationConverter`，把 `role` claim 映射为 `ROLE_ADMIN` / `ROLE_LEARNER`（默认只读 `scope`/`scp`，不映射就永远没有权限）。
  3. `MeController` 返回 `{subject, role}`。
  4. **删除** `AuthController`（无凭据签发端点的隐患直接消灭，而不是只在生产禁用）与它的用例；同步删 `SecurityConfiguration` 里对 `/api/v1/auth/token` 的放行。
- 验证：
  - `mvn -B -pl paideia-security -am test` 通过。
  - 一条 profile 测试证明该路由在 `test` profile 下不存在（404），即 AC-009 的证据。

### TASK-003 | done | 进程内 TTL 存储与限流器

- 依赖：TASK-001
- 修改：`backend/paideia-account/src/main/java/io/github/xiaou61/account/internal/support/ExpiringStore.java`（新建）、`RateLimiter.java`（新建）、`PaideiaAccountConfiguration.java`（新建，`@EnableScheduling` 与清理任务）
- 步骤：
  1. `ExpiringStore<V>`：`put(key, value, ttl)` / `get(key)`（过期即视为不存在并惰性删除）/ `remove(key)` / `increment(key, ttl)`；**容量上限**（超过则拒绝新键而不是无界增长）。
  2. `RateLimiter`：固定窗口计数，接口形如 `check(key, limit, window)` → 超限抛 `CONFLICT` 并带回剩余秒数。
  3. `@Scheduled` 每 5 分钟清理过期项。
  4. 在类注释里写明天花板：**进程内、单实例有效；多实例部署必须换成共享存储（DB 或 Redis）**，并说明 `@Scheduled` 将来迁到正式的任务模块。
- 验证：
  - 单测：过期后读不到、容量上限生效、计数在窗口后归零（用可控时钟或短 TTL）。

### TASK-004 | done | 图形验证码

- 依赖：TASK-003
- 修改：`internal/credential/CaptchaService.java`（新建）、`CaptchaImageRenderer.java`（新建）、`web/AuthController.java`（新建，先放 captcha 端点）、`web/dto/CaptchaResponse.java`
- 步骤：
  1. 生成 4 位字符（去掉 `0O1Il` 等易混字符），Java2D 出 PNG（干扰线 + 噪点），输出 base64。
  2. 答案只存 SHA-256 到 `ExpiringStore`，有效期 5 分钟，`captchaId` 为不透明随机串（**不依赖 Cookie 与会话**）。
  3. 校验接口给内部用：校验通过即删除（一次性），失败即拒。
  4. **启动自检**：应用启动时渲染一次图片，失败立刻抛错并给出明确信息（无头服务器缺字体配置是已知坑，不能等到第一次请求才 500）。
- 验证：
  - 单测：一次性、过期、错误答案被拒。
  - 启动时自检通过（`local` profile 起服务即验证）。
  - `POST /api/v1/auth/captcha` 返回可解码的 PNG（集成测试断言 base64 前缀与长度下限）。

### TASK-005 | done | 邮件端口与三个实现、邮箱验证码与发信限流

- 依赖：TASK-003、TASK-004
- 修改：`internal/mail/{MailPort,LoggingMailPort,SmtpMailPort,InMemoryMailPort}.java`（新建）、`internal/credential/EmailCodeService.java`（新建）、`pom.xml`（加 `spring-boot-starter-mail`）、`paideia-app/src/main/resources/application.yml`（`paideia.mail.*` 占位说明）、`config/application-local.yml` 的说明（**不写凭据**）
- 步骤：
  1. `MailPort.sendVerificationCode(to, code, ttl)`；按 `paideia.mail.host` 是否存在装配 `SmtpMailPort` 或 `LoggingMailPort`；测试上下文注入 `InMemoryMailPort`。
  2. `EmailCodeService`：生成 6 位数字码 → 只存 SHA-256 + 10 分钟 + 尝试计数 → 发信 → 支持"校验即作废"与"尝试超限作废"。
  3. 发信限流三条：同邮箱 60 秒间隔、同邮箱每小时 5 次、同来源 IP 每小时 20 次。
  4. 配置示例只写键名与占位（如 `PAIDEIA_MAIL_PASSWORD` 环境变量名），**不写任何真实值**。
- 验证：
  - 集成测试：无有效图形验证码不发信（且假信箱里没有邮件）；有则假信箱能读到明文码；三条限流超限被拒。
  - `SmtpMailPort` 的真实发信**记为未验证**，待用户提供凭据后单独执行一次并记录。

### TASK-006 | done | 注册用例

- 依赖：TASK-001、TASK-005
- 修改：`internal/user/{User,UserMapper,UserMapper.xml}`（新建）、`internal/registration/RegistrationService.java`（新建）、`web/AuthController.java`（加 register 端点）、`web/dto/RegisterRequest.java`
- 步骤：
  1. `UserMapper.xml`：`insert`、`findByUsername`、`findByEmail`、按 username 或 email 查（SQL 只在 XML）。
  2. 注册顺序：**校验邮箱验证码（一次性）→ 查占用 → BCrypt 哈希 → 插入 → 签发令牌**。
  3. 占用提示在验证码校验通过之后才给出，且指明是用户名还是邮箱；并发冲突由唯一索引兜底，捕获后转成同样的 `CONFLICT`。
  4. 角色固定为 `learner`；`email_verified_at` 取当前 UTC。
- 验证：
  - 集成测试：正常注册成功并能用刚注册的凭据登录；验证码错误/过期/超次被拒；重复用户名与重复邮箱分别被拒并指明；**验证码未通过时不泄露占用信息**。

### TASK-007 | done | 登录用例

- 依赖：TASK-006
- 修改：`internal/login/LoginService.java`（新建）、`web/AuthController.java`（加 login 端点）、`web/dto/LoginRequest.java`
- 步骤：
  1. 按 username 或 email 查账号（两者都唯一）。
  2. 账号不存在时对固定 dummy 哈希执行一次 BCrypt 校验，使两条路径耗时接近。
  3. 失败：计数 +1，抛统一的 `UNAUTHENTICATED`（账号不存在与密码错误同一文案）；成功：清计数并签发。
  4. 登录失败限流：同 identifier 15 分钟 5 次、同来源 IP 15 分钟 20 次。
- 验证：
  - 集成测试：两种标识都能登录；错误密码与不存在账号返回同一错误码与文案；超限被拒并对成功登录不计入失败计数。

### TASK-008 | done | refresh 轮换、重用检测与登出

- 依赖：TASK-007
- 修改：`internal/token/{RefreshToken,RefreshTokenMapper,RefreshTokenMapper.xml,RefreshTokenService}.java`（新建）、`web/AuthController.java`（加 refresh / logout 端点）
- 步骤：
  1. refresh 为 256 位随机串（base64url），库中只存 SHA-256；`family_id` 为 UUID。
  2. 刷新：按哈希查 → 已吊销则**吊销整条 family** 并抛 `UNAUTHENTICATED` → 过期则抛 → 否则旧记录置 `revoked_at`、签发新 access 与新 refresh（同 family）。
  3. 登出：吊销该 family 全部记录。
  4. 类注释写明：access 在剩余寿命内仍有效（JWT 固有），彻底即时失效需要服务端黑名单、本期不做。
- 验证：
  - 集成测试：轮换后旧 refresh 不可用；**重用旧 refresh 使整条 family 失效**（用新 refresh 也失败）；登出后 refresh 不可用；过期 refresh 被拒。

### TASK-009 | done | 前端共享认证包

- 依赖：TASK-008
- 修改：`frontend/packages/auth/**`（新建，见设计的文件清单）、`frontend/packages/auth/{package.json,tsconfig.json,vitest.config.ts}`
- 步骤：
  1. `token-store`：access 只在内存；refresh 走注入的存储（形状只有 `get`/`set`，结构化匹配 `KeyValueCache`，与 `packages/ui` 的 `ThemeStorage` 同一手法）。
  2. `authorized-fetch`：加 `Authorization` 头；401 且非认证请求本身 → **共享同一个 in-flight 刷新 Promise** → 成功后重放一次，失败则清令牌并回调。
  3. `auth-context`：`AuthProvider` / `useAuth`，启动时尝试用 refresh 恢复会话；暴露 `session`（复用 `core` 的 `Session` 类型）。
  4. 表单：登录（identifier + password）、注册（两步式：资料 + 图形验证码 → 邮箱验证码 → 提交）、`captcha-image` 点击刷新、60 秒重发倒计时、错误态。
  5. 表单只用 `@paideia/ui` 的既有组件与令牌，不引入新的视觉语言。
- 验证：
  - 单测：并发两个 401 只触发一次刷新；刷新失败会清令牌并回调；access 不落盘（断言存储里只有 refresh）；401 重放最多一次。

### TASK-010 | done | 两个应用的登录/注册页与登录门

- 依赖：TASK-009
- 修改：`frontend/apps/app/src/pages/{LoginPage,RegisterPage}.tsx`（新建）、`apps/app/src/{router.tsx,api.ts}`、`frontend/apps/admin/src/pages/LoginPage.tsx`（新建）、`apps/admin/src/{api.ts,pages/AdminHomePage.tsx,guard.tsx}`、`frontend/apps/ui-kit/src/pages/UiKitPage.tsx`
- 步骤：
  1. 学习者端：加 `/login` 与 `/register` 路由；页面内容整体包在 `RequireAuth` 内（**未登录导向登录页并记住目标地址**）；`api.ts` 的 `getToken` 改成从 token-store 取、`fetchImpl` 换成 `authorized-fetch`。
  2. 管理端：加登录页；`RequireAdmin` 接真实会话；未登录先去登录页，登录后是学习者则仍显示 `AdminDenied`。
  3. 加登出入口（放在 `AppShell` 的操作区，两个应用都用）。
  4. 展览页加一节展示登录与注册表单（**不接后端**，只展示外观与状态）。
- 验证：
  - `pnpm --filter @paideia/app typecheck`、`--filter @paideia/admin typecheck` 通过。
  - 手动或 e2e 确认未登录访问学习者端被导向登录页、登录后回到原地址。

### TASK-011 | done | 既有 e2e 改造、种子账号与展览页回归

- 依赖：TASK-010
- 修改：`frontend/apps/app/playwright.config.ts`、`apps/app/e2e/home.spec.ts`、`backend/paideia-app/src/main/resources/db/devdata/V900__seed_accounts.sql`（新建）、`apps/admin/e2e/admin.spec.ts`
- 步骤：
  1. 种子迁移：一个 learner 与一个 admin（固定测试密码哈希），放 `db/devdata`，默认不加载。
  2. `playwright.config.ts`：给后端的 webServer 加 `env`，设 `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/devdata` 且数据源指向 `paideia_test` 库（**不往 `paideia` 库写测试账号**）。
  3. `home.spec.ts` 改造：**保留原有三条断言**，前面加"未登录被导向登录页 → 用种子账号登录 → 回到首页"。
  4. `admin.spec.ts`：改用登录流程（登录 learner 仍被拒；登录 admin 放行管理区）。
- 验证：
  - `pnpm --filter @paideia/app test:e2e` 与 `--filter @paideia/admin test:e2e` 通过（需后端与 3307 隧道）。
  - `pnpm --filter @paideia/ui-kit test:e2e` 仍通过（展览页新增一节后节名断言同步）。

### TASK-012 | done | 长期约定、索引与验证报告

- 依赖：TASK-011
- 修改：`.agent/rules/always.md`、`.agent/INDEX.md`、`.agent/changes/WORK-003-账号与认证/testing/{plan.md,report.md}`、`.agent/history/updates.md`
- 步骤：
  1. `always.md`：新增账号与认证的长期约定——账号模块与依赖方向、令牌轮换与撤销语义（含登出后 access 的剩余寿命）、哈希存储纪律、限流与验证码的进程内天花板、种子账号只在 devdata、登录标识为用户名与邮箱双唯一。
  2. `INDEX.md`：加 `paideia-account` 与 `packages/auth` 两行，并更新受影响模块行。
  3. 写 `testing/plan.md` 与 `report.md`，逐条对应 AC-001..AC-010；**SMTP 真实发信记为未验证**（待凭据），把两处缺口（后端无按角色拒绝的接口、access 剩余寿命）写进剩余风险。
  4. 追加 `updates.md` 记录，写明本地提交边界。
- 验证：
  - `project-lifecycle.ps1 validate F:\Paideia --json` 无 error。
  - 报告里每条 AC 都指向具体命令与输出；未验证项写明原因。

## 完成条件

- TASK-001..TASK-012 全部 `done`；任何 `blocked` 必须在报告中说明阻塞点。
- `cd backend && mvn -B verify` 通过（集成测试需要 3307 隧道；未就绪的部分如实标为未运行）。
- `cd frontend && pnpm -r typecheck && pnpm -r test` 全绿；四个应用构建通过。
- 三条 e2e 套件通过（学习者端、管理端、展览页）。
- **真实 SMTP 发信**：用户提供凭据后执行一次并记录；在此之前不得写成"已验证"。
- 裸签发端点已删除且非 dev/local 下不存在（有检查）。
- `.agent/rules/always.md`、`.agent/INDEX.md`、`updates.md` 已更新。
