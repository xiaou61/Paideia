---
artifact: design
work_id: WORK-037
work: 第三方登录与账号绑定
status: approved
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 14:45:29 +0800
approver_role: CTO
---

# 设计 · 第三方登录与账号绑定

依据：[requirements.md](requirements.md)（已批准）、[proposal.md](proposal.md)。

## 系统上下文

三个新的对外动作，全部走既有前缀，**不动 `SecurityConfiguration`**：

| 动作 | 路径 | 访问级别 | 走哪条既有规则 |
| --- | --- | --- | --- |
| 发起授权 | `POST /api/v1/auth/oauth/{provider}/start` | `PUBLIC_WRITE` | `/api/v1/auth/**` permitAll |
| 平台回调 | `GET /api/v1/auth/oauth/{provider}/callback` | `PUBLIC_WRITE` | 同上 |
| 换取令牌 | `POST /api/v1/auth/oauth/exchange` | `PUBLIC_WRITE` | 同上 |
| 列出绑定 | `GET /api/v1/account/oauth/identities` | `AUTHENTICATED` | `anyRequest().authenticated()` |
| 发起绑定 | `POST /api/v1/account/oauth/{provider}/start` | `AUTHENTICATED` | 同上 |
| 解绑 | `DELETE /api/v1/account/oauth/{provider}` | `AUTHENTICATED` | 同上 |

`GET` 回调是匿名可达的，这是必然的：平台跳转不带我们的 Authorization 头。

## 组件与职责

`paideia-account` 内新增 `internal/oauth` 包：

| 组件 | 职责 |
| --- | --- |
| `OAuthProvider`（枚举） | **平台差异的唯一落点**：authorize/token/userinfo 三个地址、clientId/secret 配置键、scope、用户信息字段名、回调节点路径 |
| `OAuthProperties` | `@ConfigurationProperties("paideia.oauth")`，每个平台一段；`enabled()` 由 clientId/secret 是否齐全决定 |
| `OAuthStateStore` | 包住既有 `ExpiringStore<OAuthState>`；`issue(provider, purpose, userId)` → `state`；`take(state)` → `OAuthState` |
| `HandoffStore` | 包住另一个 `ExpiringStore<IssuedHandoff>`；60 秒、消费即删 |
| `OAuthHttp` | JDK `HttpClient` 薄封装：POST 表单换令牌、GET 带 Bearer 取用户信息；禁重定向 |
| `OAuthClient` | 拼授权地址；`exchange(code)` → `OAuthProfile`；**不含任何业务判断** |
| `OAuthLoginService` | 登录/自动建号/绑定/解绑的全部业务规则 |
| `OAuthController` | `/api/v1/auth/oauth/**` |
| `OAuthIdentityController` | `/api/v1/account/oauth/**` |

## 请求或事件流程

**登录（Web）**

1. 前端点 GitHub → `POST /auth/oauth/github/start` → `{authorizeUrl}`。
2. 浏览器跳到 GitHub → 授权 → 平台 `302` 到 `GET /auth/oauth/github/callback?code=&state=`。
3. 后端 `take(state)`；缺失或平台不匹配 → 302 到 `#/login?oauthError=...`，**不签发任何令牌**。
4. `exchange(code)` 换令牌、取用户信息 → `OAuthProfile(providerUserId, nickname, email?)`。
5. `OAuthLoginService.loginOrRegister`：
   - 该 `(provider, providerUserId)` 已有绑定 → 取那个 `user_id`；
   - 没有绑定 → 建号（生成唯一用户名）+ 建绑定；
   - 建号与建绑定在同一事务里。
6. 签令牌 → 存 `HandoffStore`（60 秒）→ `302` 到 `#/oauth/callback?handoff=...`。
7. 前端拿 `handoff` 调 `POST /auth/oauth/exchange` → 得到 `AuthTokens` → 与密码登录同一条路径落库并进入已登录状态。

**登录（桌面）**：第 6 步改成 `302` 到后端的一个静态中转页，该页把浏览器重定向到 `paideia://oauth?handoff=...`；主进程收到后把 handoff 转给渲染进程，渲染进程走第 7 步。

**绑定**：`start` 带令牌调用，`state` 里记 `userId`。回调识别到"绑定"用途时要求已登录且令牌 `sub` 等于 `state.userId`；不等则拒绝。

**解绑**：`DELETE`，先数剩余可用登录方式（有密码算一种，其余绑定每种算一种），`<= 1` 拒绝。

## 接口与数据

### 迁移 `V20261023150000__oauth_identities.sql`

```sql
ALTER TABLE users
  MODIFY COLUMN username      VARCHAR(64)  NOT NULL,          -- 保持 NOT NULL
  MODIFY COLUMN email         VARCHAR(254) NULL,              -- 第三方可能没有邮箱
  MODIFY COLUMN password_hash VARCHAR(100) NULL;              -- 第三方注册没有密码

CREATE TABLE user_identities (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id           BIGINT UNSIGNED NOT NULL,
  provider          VARCHAR(32)  NOT NULL,   -- github / gitee
  provider_user_id  VARCHAR(128) NOT NULL,   -- 平台内稳定 id，不是昵称/邮箱
  display_name      VARCHAR(128) NULL,       -- 首次授权时的昵称，只作展示
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_identity_provider_subject (provider, provider_user_id),
  KEY idx_identity_user (user_id),
  CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;
```

**`username` 保持 NOT NULL**：它仍是站内唯一标识与界面显示名，第三方建号时生成。这一列不改可空性，避免"没有名字的用户"这种状态出现在别处。

`email` 的唯一索引保留。MySQL 唯一索引允许多个 `NULL`，因此多个无邮箱账号可以共存——这正是需要的语义。

### 端点的请求与响应

```
POST /api/v1/auth/oauth/{provider}/start
  200 { authorizeUrl: string, state: string }   // state 仅供调试日志对齐，前端不用它

GET /api/v1/auth/oauth/{provider}/callback?code=&state=
  302 Location: <web>/#/oauth/callback?handoff=<opaque>     成功
  302 Location: <web>/#/login?oauthError=<code>             失败（code ∈ denied|expired|failed|disabled）

POST /api/v1/auth/oauth/exchange  { handoff }
  200 AuthTokens（与 /auth/login 完全相同的结构）

GET  /api/v1/account/oauth/identities
  200 { providers: [{ provider, displayName, boundAt }], hasPassword: boolean }

POST   /api/v1/account/oauth/{provider}/start      → { authorizeUrl }
DELETE /api/v1/account/oauth/{provider}            → 204 语义（ApiResponse data 为空）
```

### 前端形状变化

`AuthUser.email` 由 `string` 改为 `string | null`。这是**唯一**的破坏性前端契约变化，编译器会指到每一处使用点。

## 持久化与迁移

- 迁移只做结构（`ALTER` + `CREATE TABLE`），不插数据。
- 既有 100 条账号（线上 2 条、测试库若干）在 `ALTER` 后行为不变：它们的 `email` 与 `password_hash` 仍非空。
- 回滚不需要：`ALTER ... NULL` 是收缩性变更，旧代码读非空数据完全正常。若必须回滚 schema，先确认没有无邮箱账号才能改回 `NOT NULL`——这一点写进迁移注释。

## 失败处理与恢复

| 情形 | 行为 |
| --- | --- |
| `state` 不存在/已消费/过期 | 302 到登录页 `oauthError=expired`；不建号、不签发 |
| 用户拒绝授权（平台回 `error=access_denied`） | 302 `oauthError=denied` |
| 平台未配置（缺 clientId/secret） | `start` 返回 409 `CONFLICT`，文案"该登录方式尚未启用"；按钮在前端按配置隐藏 |
| 平台返回的用户信息缺稳定 id | 按失败处理，**不回落到昵称或邮箱**建号 |
| 建号用户名冲突 | 加数字后缀重试，最多 50 次；仍冲突则失败（不静默截断） |
| 绑定一个已被他人占用的第三方身份 | 与"平台出错"同一句文案，避免账号枚举 |
| 解绑最后一个登录方式 | 409，文案说明"至少保留一种登录方式" |
| `handoff` 不存在/已消费/超 60 秒 | 400，前端回登录页提示重新登录 |

## 安全与权限

- `state` 一次性、5 分钟、服务端记录用途与 userId；**不接受任何客户端传入的用户标识**。
- 回调端点不接受 `redirect_uri` 参数（由服务端从配置拼），避免开放重定向。
- `OAuthHttp` 只连 `OAuthProvider` 里写死的两个主机，**不接受任何用户输入作为 URL**——这不是通用代理，无需 SSRF 守卫，但注释要写明这一点，避免后人误以为可以传任意地址。
- 授权码、平台 access token **只出现在本次请求的内存里**，不写库、不写日志。日志只记 `provider` 与结果。
- Client Secret 只在 `application.yml`（服务器上，600 权限）中，`.example` 里只有占位。
- 桌面 `paideia://` 只承载 handoff（60 秒、一次性），不含长效凭据。

## 可观测性

日志三条，均不带敏感值：

- `oauth start provider=github purpose=login`（debug）
- `oauth callback provider=github outcome=ok|expired|denied|failed`（info/warn）
- `oauth bind user=… provider=… outcome=…`（info）

## 实施顺序

1. 迁移 + 可空性 + `LoginService` 挡无密码账号（先让既有测试全绿）
2. `OAuthProvider` / `OAuthProperties` / `OAuthStateStore` / `HandoffStore`（纯单测）
3. `OAuthHttp` + `OAuthClient`（桩出站，验两个平台的字段差异）
4. `OAuthLoginService`（登录/建号/绑定/解绑，集成测试）
5. `OAuthController` + `OAuthIdentityController`
6. `AccountDeletionService` 放宽
7. 前端：`AuthUser.email` 放宽 → 登录页 → 回调页 → 账号设置页
8. 管理端邮箱列
9. 桌面协议接管
10. 契约快照重录 + 全量验证 + e2e

## 测试策略

- **单测**（`paideia-account/src/test`）：`OAuthStateStore`（一次性、过期、用途不匹配）、`HandoffStore` 同上、`OAuthClient`（两个平台的授权 URL 与字段解析，桩 HTTP）、用户名冲突生成器。
- **集成**（`paideia-app`）：`OAuthLoginIntegrationTest` — 覆盖 AC-001..AC-007 与 AC-011 的服务端部分，出站 HTTP 用 `@MockitoBean` 替身；`AccountDeletionCascadeIntegrationTest` 补一条无密码用户。
- **契约**：`ApiWalkthroughTest` 的 `api-shape.json` 需重录并 review diff（`user.email` 变可空 + 新端点）。
- **e2e**：`oauth` spec 覆盖登录页两个按钮、回调页、账号设置页三条路径；平台侧用路由拦截伪造回调（不连真平台）。
- **手验**：真实 GitHub / Gitee 各跑一次（需要真实凭据，若本机没有则如实记为未验）。

## 需求追踪

见 proposal 的「验收映射」表。本条新增：REQ-010 / AC-011（桌面）由第 9 步与手验覆盖。

## 待决定事项

无。
