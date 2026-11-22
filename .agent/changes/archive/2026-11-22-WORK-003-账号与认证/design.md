---
artifact: design
work_id: WORK-003
work: 账号与认证
status: approved
created: 2026-10-09
updated: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 17:27:29 +0800
approver_role: CTO
---

# 账号与认证设计

本设计覆盖账号实体、凭据校验、令牌轮换与前端认证接缝。部署与 HTTPS 不在范围内（用户已明确豁免）。带「计划」标记的名称是本项要新建的。

## 系统上下文

- 后端是模块化单体，一个模块一个 Maven 模块，边界由 Maven 依赖 + Spring Modulith 校验拦住。
- **新增 `paideia-account`**：拥有账号实体与注册/登录用例。依赖方向 `account → security`（用 `AuthPort` 签发令牌）+ `account → persistence`（MyBatis）+ `account → platform`（契约）+ `account → web`（响应包装与异常处理已在 app 层装配）。
- `paideia-security` **不认识账号表**，只认 `sub` 字符串与角色。
- 前端四个面不变；新增共享包 `packages/auth`。
- 既有端点：`GET /api/v1/me`（受保护）；`POST /api/v1/auth/token`（**本项删除**）。

## 组件与职责

```
backend/
  paideia-account/                            新增模块
    pom.xml
    src/main/java/io/github/xiaou61/account/
      AccountApi.java                         模块对外契约（供将来其它模块按 userId 关联数据）
      internal/
        user/                                 User 实体、UserMapper、UserService
        registration/                         RegistrationService（含占用判定与顺序）
        login/                                LoginService（双标识、统一错误、时序对齐）
        credential/                           图形验证码与邮箱验证码：生成、存储、校验、限流
        mail/                                 MailPort 与三个实现
        web/                                  AuthController（六个端点）、dto
    src/main/resources/mapper/                UserMapper.xml（SQL 只写在 mapper）
  paideia-app/src/main/resources/db/migration/
    V1__create_account_tables.sql             两张表
  paideia-app/src/main/resources/db/devdata/
    V900__seed_accounts.sql                   种子账号（仅 dev/测试，默认不加载）
  paideia-app/src/test/resources/             测试用 profile 与假信箱装配

frontend/
  packages/auth/                              新增共享包
    src/token-store.ts                        access 在内存 + refresh 走注入存储
    src/auth-api.ts                           六个端点的调用
    src/authorized-fetch.ts                   带令牌 + 401 刷新重放（包装 fetchImpl）
    src/auth-context.tsx                      AuthProvider / useAuth
    src/require-auth.tsx                      登录门（记住目标地址）
    src/login-form.tsx / register-form.tsx    表单（两步式注册 + 倒计时 + 错误态）
    src/captcha-image.tsx                     图形验证码（点击刷新）
    src/index.ts
  apps/app/   src/pages/{LoginPage,RegisterPage}.tsx、router.tsx、api.ts
  apps/admin/ src/pages/LoginPage.tsx、AdminHomePage.tsx、api.ts
  apps/ui-kit/src/pages/UiKitPage.tsx         加一节展示登录与注册表单
```

## 请求或事件流程

### 注册

1. `POST /api/v1/auth/captcha` → 得到 `captchaId` 与图片。
2. 用户填用户名、邮箱、密码、图形验证码 → `POST /api/v1/auth/email-code`。
3. 服务端：校验 `captchaId` 与答案（一次性）→ 限流（同邮箱间隔与小时上限、同来源 IP 上限）→ 生成 6 位数字码 → 只存 SHA-256 哈希 + 10 分钟有效期 + 尝试计数 → 经 `MailPort` 发出 → 204。
4. 用户填邮箱验证码 → `POST /api/v1/auth/register`。
5. 服务端顺序（**顺序是设计的一部分**）：校验验证码（一次性）→ 查用户名/邮箱占用 → BCrypt 哈希密码 → 插入（唯一索引兜底并发）→ 签发 access + refresh → 返回。
6. 前端写入令牌并进入已登录状态。

### 登录

1. `POST /api/v1/auth/login`，`identifier` 可以是用户名或邮箱。
2. 服务端：限流检查（同 identifier 与同来源）→ 按 username 或 email 查账号 → **无论是否查到都执行一次 BCrypt 校验**（账号不存在时对一个固定 dummy 哈希校验），使两条路径耗时接近 → 失败则计数 +1 并抛统一的 `UNAUTHENTICATED` → 成功清计数 → 签发。

### 刷新与轮换

1. `POST /api/v1/auth/refresh`，请求带 refresh 明文。
2. 服务端按 SHA-256 查记录：
   - 无记录 → `UNAUTHENTICATED`
   - `revoked_at` 非空 → **判定重用**：吊销该 `family_id` 下全部记录 → `UNAUTHENTICATED`
   - `expires_at` 已过 → `UNAUTHENTICATED`
   - 否则：把旧记录 `revoked_at = now`，签发新 access，生成新 refresh（同 family），返回。
3. 并发 401 由前端合并为一次刷新（见下）。

### 登出

`POST /api/v1/auth/logout` 带 refresh → 吊销该 family 全部记录 → 204。前端清空内存中的 access 与存储中的 refresh。**access 在其剩余寿命内仍可用**。

### 前端 401 自动刷新（`authorized-fetch.ts`）

```
请求前：从 token-store 取 access → 放入 Authorization 头
响应 401 且不是刷新/登录/注册请求：
  若已有 in-flight 刷新 → 等它，然后重放
  否则发起刷新（共享同一个 Promise）：
    成功 → 更新令牌 → 重放原请求（最多一次）
    失败 → 清令牌 → 通知 AuthProvider 回登录页并说明原因
除 401 外的响应原样返回
```

**重放只做一次**，避免与后端形成循环；刷新请求自身不得触发刷新。

## 接口与数据

所有响应走既有的 `ApiResponse<T>`（`code` / `message` / `data` / `traceId`），失败经 `paideia-web` 的全局异常处理映射。

| 端点 | 请求 | 响应 | 放行 |
| --- | --- | --- | --- |
| `POST /api/v1/auth/captcha` | — | `{captchaId, imageBase64, expiresAt}` | permitAll |
| `POST /api/v1/auth/email-code` | `{email, captchaId, captchaAnswer}` | 204 | permitAll |
| `POST /api/v1/auth/register` | `{username, email, password, code}` | 令牌组 | permitAll |
| `POST /api/v1/auth/login` | `{identifier, password}` | 令牌组 | permitAll |
| `POST /api/v1/auth/refresh` | `{refreshToken}` | 令牌组 | permitAll（凭据是 refresh 本身） |
| `POST /api/v1/auth/logout` | `{refreshToken}` | 204 | permitAll（同上） |
| `GET /api/v1/me` | — | `{subject, role}`（由 `{subject}` 扩展） | 需认证 |
| ~~`POST /api/v1/auth/token`~~ | — | — | **删除** |

令牌组 = `{accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, user:{id, username, email, role}}`。

### `AuthPort` 变更（`paideia-security`）

```java
Token issue(Subject subject);            // 原来是 issue(String subject)
Subject authenticate(String token);      // 返回体带上角色

record Subject(String id, String role) { }
```

- **服务端对角色更严格**：令牌中缺少角色或角色不可识别时抛 `UNAUTHENTICATED`（这是我们自己签的令牌，合法令牌必有角色；缺失只可能来自旧版本或伪造）。这与前端 `session.ts` 的"认不出就当学习者"不同，是有意的：前端失败即降权，服务端失败即拒绝。
- 角色 → Spring Security 权限的映射需要自定义 `JwtAuthenticationConverter`（默认只读 `scope`/`scp`）。映射为 `ROLE_ADMIN` / `ROLE_LEARNER`。
- 本期**不加** `@EnableMethodSecurity` 与按角色拒绝的注解：没有任何管理员接口要保护（管理端只有一个健康徽标）。**这是一处明确的缺口**，写进报告。

### `MailPort`（`paideia-account` 内部）

```java
public interface MailPort {
    void sendVerificationCode(String to, String code, Duration ttl);
}
```

按配置择一装配：未配 `paideia.mail.host` → 日志实现（验证码写日志、不出网）；配了 → SMTP 实现。测试上下文注入内存信箱实现，集成测试直接读明文验证码。**发送能力收在端口后**，将来换供应商不动业务代码。

## 持久化与迁移

`V1__create_account_tables.sql`（字符集与库一致：`utf8mb4` / `utf8mb4_0900_ai_ci`）：

```sql
CREATE TABLE users (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username           VARCHAR(64)     NOT NULL,
  email              VARCHAR(254)    NOT NULL,
  password_hash      VARCHAR(100)    NOT NULL,   -- 容纳后续算法升级（BCrypt 为 60）
  role               VARCHAR(16)     NOT NULL,
  email_verified_at  DATETIME(3)     NOT NULL,
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username),
  UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB;

CREATE TABLE refresh_tokens (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  token_hash  CHAR(64)        NOT NULL,          -- 只存 SHA-256，不存明文
  family_id   CHAR(36)        NOT NULL,
  expires_at  DATETIME(3)     NOT NULL,
  revoked_at  DATETIME(3)     NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_token_hash (token_hash),
  KEY idx_refresh_family (family_id),
  KEY idx_refresh_expires (expires_at),
  CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB;
```

三条要写进注释的约定：

- **唯一索引是大小写不敏感的**（`utf8mb4_0900_ai_ci`），因此 `Alice` 与 `alice` 视为同一用户名、同一邮箱。这是刻意的（防混淆），但它是**库的行为而不是我们写死的规则**，所以要显式记录。
- 审计时间走 MySQL 列默认值、存 UTC（不调用 `NOW()` 取业务时间）。
- SQL 只写在 `UserMapper.xml` 与 `RefreshTokenMapper.xml`，Java 里不拼 SQL。

### 进程内短期状态（不落库）

图形验证码答案、邮箱验证码哈希与尝试计数、三条限流计数，统一放一个带 TTL 的进程内存储：惰性清理 + 容量上限 + 一个 `@Scheduled` 每 5 分钟清过期项。**多实例部署时这三处都必须换成共享存储**——在代码里标注，不靠文档记住。`@Scheduled` 需要 `@EnableScheduling`，这是任务基础设施的雏形，将来有正式的任务模块时迁过去。

### 种子账号

`db/devdata/V900__seed_accounts.sql` 插一个 learner 与一个 admin（密码哈希为固定测试值）。**默认不加载**：e2e 通过 `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/devdata` 显式开启，生产迁移目录里永远没有它。e2e 同时把数据源指向 `paideia_test` 库，避免往 `paideia` 库写测试账号。

## 失败处理与恢复

| 情形 | 行为 |
| --- | --- |
| 图形验证码错误或已过期 | `INVALID_ARGUMENT`，不发信；前端提示并刷新图形验证码 |
| 发信超限 | `CONFLICT` → 由 web 层映射为 429（或 409，见下）；前端按剩余时间倒计时 |
| 邮箱验证码错误 | `INVALID_ARGUMENT`；尝试计数 +1，达到上限即作废该码 |
| 邮箱验证码过期 | `INVALID_ARGUMENT`，要求重新获取 |
| 用户名/邮箱已被占用 | `CONFLICT` 并指明是哪一个（**只在验证码校验通过之后才告知**） |
| 账号不存在或密码错误 | 统一 `UNAUTHENTICATED`，文案一致，耗时接近 |
| refresh 已吊销 | 判定重用 → 吊销整条 family → `UNAUTHENTICATED` |
| refresh 过期 | `UNAUTHENTICATED` |
| 邮件发送失败 | 记录日志并让注册流程失败（不能假装发出去了）；验证码记录保留，用户可重新请求 |
| Java2D 出图失败（无头环境缺字体） | 启动即自检一次出图，失败立刻报错而不是等第一次请求 500 |

HTTP 状态码映射沿用既有的 `GlobalExceptionHandler` 约定；若它当前只按错误码映射到固定状态，则 429 通过 `CONFLICT` 表达（**实现时按既有处理器能力决定，以既有约定为准，不新造一套**）。

## 安全与权限

- 密码用 **BCrypt**（`BCryptPasswordEncoder`，强度默认 10）。密码永不出现在日志、异常消息或响应里；注册与登录的请求体不得被打进访问日志。
- **令牌与验证码只存哈希**：refresh 存 SHA-256，邮箱验证码存 SHA-256，图形验证码答案存 SHA-256。
- **枚举防护**：注册的占用提示只在验证码校验通过后给出；登录失败不区分原因且耗时对齐。
- **前端守卫不是授权边界**（WORK-002 已确立的表述继续有效）：管理端前端守卫只做界面分流；**后端本期没有管理员接口，因此没有可测的后端越权拦截——这是缺口，不是已完成项**。
- **登出不是即时的**：access 在 ≤15 分钟内仍有效。要彻底即时失效需要服务端黑名单，本期不做，写进报告。
- 无 HTTPS：密码与令牌明文过网（用户 2026-10-09 明确豁免，记入 `always.md`）。
- 凭据（SMTP 授权码、`paideia.auth.secret`）只来自环境变量或被忽略的本地配置；仓库内只提交占位示例。

## 可观测性

- 登录与刷新的失败在服务端日志里**可区分**（图形验证码失败 / 验证码过期 / 验证码次数超限 / 账号不存在 / 密码错误 / refresh 已吊销 / 检测到重用），但**对外响应不区分**（除注册的占用提示外）。
- 日志一律不含密码、令牌明文、验证码明文。
- `/actuator/health` 的既有行为不变；新增的账号数据源复用同一数据源，不新增健康指示器。

## 实施顺序

1. `paideia-account` 模块骨架 + `users` 迁移 + 种子迁移 + 模块注册（Modulith 校验通过）。
2. `AuthPort` 带角色 + `JwtAuthenticationConverter` + 删除裸签发端点。
3. 进程内 TTL 存储与限流器（先有工具，后有消费者）。
4. 图形验证码（含无头出图自检）。
5. `MailPort` 与三个实现；邮箱验证码与发信限流。
6. 注册用例（顺序与占用判定）。
7. 登录用例（双标识、统一错误、时序对齐）。
8. refresh 轮换、重用检测、登出。
9. 前端 `packages/auth`。
10. 两个应用的登录/注册页与登录门；展览页加表单一节。
11. 既有 e2e 改造、测试报告、长期约定与索引更新。

## 测试策略

原则：认证是安全敏感路径，**每条验收标准都要有一条可本地执行的自动化检查**；不写逐方法用例套件。

| 检查 | 位置 | 覆盖 |
| --- | --- | --- |
| 迁移在 MySQL 上执行、表结构与唯一索引符合预期 | `paideia-account` 集成测试（连 `paideia_test`） | AC-001 |
| 注册：无有效图形验证码不发信；验证码错误/过期/超次被拒；成功后占用提示只在验证之后出现 | 同上 | AC-002 |
| 登录：用户名与邮箱两种标识都能成功；失败响应与耗时都不区分账号存在性 | 同上 | AC-003 |
| 刷新：轮换后旧 refresh 不可用；重用旧 refresh 使整条 family 失效 | 同上 | AC-004 |
| 登出后 refresh 不可用 | 同上 | AC-005 |
| 三条限流超阈值被拒 | 同上 | AC-008 |
| 非 dev/local profile 下裸签发端点不存在 | `paideia-app` 的 profile 测试 | AC-009 |
| 前端：未登录被导向登录页、登录后可达、管理端对非管理员被拒 | `apps/app` 与 `apps/admin` 的 e2e（先登录再断言） | AC-006 |
| 前端：并发两个请求同时 401 只触发一次刷新 | `packages/auth` 单测（假 fetch） | AC-007 |
| 令牌存储：access 不落盘、refresh 走注入存储 | `packages/auth` 单测 | AC-007 |
| 四个应用构建、类型检查、既有边界检查与令牌检查继续通过 | `pnpm -r typecheck && pnpm -r test` + 四个构建 | AC-010 |
| **真实 SMTP 发信** | 待用户提供凭据后单独执行一次并记录 | AC-002 的一部分，**在此之前记为未验证** |

## 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001 | 持久化与迁移（`users`） |
| REQ-002 | 请求或事件流程·注册 + 接口与数据 + 失败处理 |
| REQ-003 | 请求或事件流程·登录 |
| REQ-004 | 请求或事件流程·刷新与轮换 |
| REQ-005 | 请求或事件流程·登出 + 安全与权限（剩余寿命说明） |
| REQ-006 | `AuthPort` 变更 + 角色映射 |
| REQ-007 | 组件与职责·`packages/auth` + 前端 401 自动刷新 |
| REQ-008 | 组件与职责·两个应用的页面与 `require-auth` |
| REQ-009 | 推荐方案 4 的落地：图形验证码端点与进程内 TTL 存储 |
| REQ-010 | `MailPort` 与三个实现 |
| REQ-011 | 进程内短期状态 + 三条限流 + 测试策略 |
| REQ-012 | 接口与数据（删除裸签发端点）+ AC-009 检查 |
| REQ-013 | 安全与权限 |

## 待决定事项

无。两处已知缺口（后端没有按角色拒绝的接口、access 在登出后仍有剩余寿命）已写入「安全与权限」，将在验证报告中作为缺口与剩余风险列出，不在此处反复。
