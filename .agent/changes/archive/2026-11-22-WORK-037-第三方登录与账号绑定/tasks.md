---
artifact: tasks
work_id: WORK-037
work: 第三方登录与账号绑定
status: approved
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 14:45:29 +0800
approver_role: CTO
---

# 实施任务 · 第三方登录与账号绑定

依据：[requirements.md](requirements.md)、[proposal.md](proposal.md)、[design.md](design.md)。

## 依据

工作树：`.worktrees/work-037`，分支 `work-037-oauth`。全部命令在该目录下执行。

## 全局约束

- 迁移文件名必须是 14 位时间戳，且只在 `backend/paideia-app/src/main/resources/db/migration/`。
- 生产迁移只建结构，不插业务数据。
- 凭据只进 `backend/config/application-local.yml`（被忽略）与服务器上的 `application.yml`；仓库里只留 `.example` 占位。
- 后端内环测试一律走 `scripts/test-db.sh`，不得直连 `paideia_test`。
- 新增端点必须带 `@ApiAccess`，否则 `ApiSurfaceTest` 会红。
- 前端改共享包后必须跑对应 e2e 文件（`oauth`、`identity`）。

## 评审焦点

规格暗示但主路径测试不一定覆盖的失败模式，逐条指定归属：

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| `state` 重放（同一 state 用两次） | TASK-003 | `OAuthStateStoreTest.invalidatesStateAfterFirstUse` |
| `state` 过期（>5 分钟） | TASK-003 | `OAuthStateStoreTest.expiresStateAfterTtl` |
| `state` 用途不符（登录 state 用于绑定） | TASK-003 | `OAuthStateStoreTest.rejectsMismatchedPurpose` |
| `handoff` 重放 / 超时 | TASK-003 | `HandoffStoreTest.consumedOnce` / `expiresAfterTtl` |
| 无密码账号走密码登录 | TASK-001 | `AccountAuthIntegrationTest.loginRejectsPasswordlessAccount` |
| 平台返回缺稳定 id | TASK-004 | `OAuthLoginIntegrationTest.rejectsProfileWithoutSubject` |
| 用户名冲突（两次授权产生同名） | TASK-004 | `UsernameAllocatorTest.appendsSuffixOnCollision` |
| 绑定已被他人占用的第三方身份 | TASK-004 | `OAuthLoginIntegrationTest.bindingTakenIdentityFails` |
| 解绑最后一个登录方式 | TASK-005 | `OAuthIdentityIntegrationTest.cannotUnbindLastCredential` |
| 无密码用户注销 | TASK-006 | `AccountDeletionCascadeIntegrationTest.deletesPasswordlessUser` |
| 平台未配置时按钮不显示 | TASK-007 | `oauth.spec.ts` |
| 回调失败落到登录页且无令牌 | TASK-007 | `oauth.spec.ts` |
| 桌面协议伪造唤起 | TASK-009 | 手验 + `main.cjs` 参数校验单测 |

## 任务

### TASK-001 | done | 迁移、可空性与无密码账号拒绝

- 对应：`REQ-006`、`AC-006`（前置）、`AC-008`
- 依赖：无
- 新建：`backend/paideia-app/src/main/resources/db/migration/V20261023150000__oauth_identities.sql`
- 修改：`backend/paideia-account/src/main/java/io/github/xiaou61/account/internal/user/UserMapper.java`、`.../login/LoginService.java`
- 接口：`LoginService.login(String identifier, String rawPassword, String sourceAddress): AuthTokens` 签名不变
- 步骤：
  1. 写迁移：`email`、`password_hash` 改 `NULL`，建 `user_identities`
  2. 跑 `MigrationVersionFormatTest`、`MigrationSeedOverlapTest` 确认迁移合规
  3. `LoginService` 在 `passwordHash == null` 时按"凭据不匹配"处理（复用 `wasteTimeLikeAMatch` + 通用失败文案）
  4. 加 `AccountAuthIntegrationTest.loginRejectsPasswordlessAccount`
  5. 反向验证：去掉第 3 步的分支，该用例必须变红
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AccountAuthIntegrationTest,MigrationVersionFormatTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` → 全绿且 Skipped 为 0

### TASK-002 | done | provider 枚举与配置

- 对应：`REQ-009`、`AC-009`
- 依赖：无
- 新建：`.../account/internal/oauth/OAuthProvider.java`、`.../oauth/OAuthProperties.java`
- 接口：`OAuthProvider.valueOf("github"|"gitee")`；`OAuthProvider.authorizeUrl(clientId, redirectUri, state, scope): String`；`OAuthProperties.of(provider): OAuthProviderConfig`；`OAuthProviderConfig.enabled(): boolean`
- 步骤：
  1. 枚举写进两个平台的 authorize/token/userinfo 地址、scope、用户信息字段名
  2. `@ConfigurationProperties("paideia.oauth")`，每平台 `clientId/clientSecret/redirectUri`
  3. `enabled()` 由 clientId 与 secret 都非空决定
  4. `AccountSupportConfiguration` 里注册 `OAuthProperties`
  5. 在 `application-local.yml.example` 补占位
- 验证：`scripts/test-db.sh mvn -B -pl paideia-account test -Dtest=OAuthProviderTest` → 断言两个平台的授权 URL 含正确 host、scope、state

### TASK-003 | done | state 与 handoff 一次性存储

- 对应：`REQ-007`、`AC-007`
- 依赖：TASK-002
- 新建：`.../oauth/OAuthState.java`、`.../oauth/OAuthStateStore.java`、`.../oauth/HandoffStore.java`
- 接口：`OAuthStateStore.issue(OAuthProvider, Purpose, Long userId): String`；`take(String state): Optional<OAuthState>`；`HandoffStore.issue(long userId): String`；`consume(String handoff): Optional<Long>`
- 步骤：
  1. `OAuthState(provider, purpose, userId)` record，`Purpose ∈ {LOGIN, BIND}`
  2. 两个 store 都包既有 `ExpiringStore`，`issue` 生成 32 字节 URL-safe 随机串
  3. `take` 必须与"平台匹配 + 用途匹配"一起校验（不匹配也消费掉，不给穷举机会）
  4. 在 `AccountSupportConfiguration` 里注册两个 bean
  5. 三条单测：重放、过期、用途不符
- 验证：`scripts/test-db.sh mvn -B -pl paideia-account test -Dtest='OAuthStateStoreTest,HandoffStoreTest'` → 5 passed

### TASK-004 | done | 授权码客户端与自动建号

- 对应：`REQ-001`、`REQ-002`、`REQ-007`、`AC-001`、`AC-002`、`AC-003`
- 依赖：TASK-001、TASK-002、TASK-003
- 新建：`.../oauth/OAuthHttp.java`、`.../oauth/OAuthClient.java`、`.../oauth/OAuthProfile.java`、`.../oauth/OAuthLoginService.java`、`.../oauth/UsernameAllocator.java`、`.../oauth/UserIdentityMapper.java`、`.../oauth/UserIdentity.java`
- 接口：`OAuthClient.exchange(OAuthProvider, String code): OAuthProfile`；`OAuthProfile(String providerUserId, String nickname, String email)`；`OAuthLoginService.loginOrRegister(OAuthProvider, OAuthProfile): AuthTokens`；`UsernameAllocator.allocate(String preferred): String`
- 步骤：
  1. `OAuthHttp`：JDK `HttpClient`，禁重定向，超时 10 秒
  2. `OAuthClient.exchange`：POST 换 token → GET userinfo → 归一化为 `OAuthProfile`；缺稳定 id 抛 `BizException`
  3. `UserIdentityMapper`：`findByProviderAndSubject`、`insert`、`findByUserId`、`deleteByUserIdAndProvider`、`countByUserId`
  4. `UsernameAllocator`：清洗昵称 → 冲突加 `-2`、`-3`…，最多 50 次
  5. `OAuthLoginService.loginOrRegister`：事务内"查绑定 → 查不到则建号 + 建绑定"
  6. 单测 + 集成测试
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OAuthLoginIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` → 覆盖 AC-001/002/003

### TASK-005 | done | 登录/回调/交换端点

- 对应：`REQ-001`、`REQ-003`、`REQ-004`、`REQ-005`、`AC-004`、`AC-005`
- 依赖：TASK-003、TASK-004
- 新建：`.../oauth/OAuthController.java`、`.../oauth/OAuthIdentityController.java`、`.../oauth/OAuthCoordinates.java`（拼前端地址）
- 步骤：
  1. `start`（匿名）返回 authorizeUrl
  2. `callback`（匿名）：take state → exchange → 登录或绑定 → 签令牌 → 存 handoff → 302
  3. `exchange`：consume handoff → 签令牌
  4. `identities`：列出绑定 + `hasPassword`
  5. `start`（已登录）用途 BIND；`delete` 检查剩余方式
  6. 绑定冲突用与"平台出错"同一文案
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='OAuthLoginIntegrationTest,OAuthIdentityIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`

### TASK-006 | done | 无密码用户注销

- 对应：`REQ-006`、`AC-006`
- 依赖：TASK-001
- 修改：`.../account/internal/user/AccountDeletionService.java`、`.../account/internal/web/AccountDeletionController.java`
- 接口：`AccountDeletionService.delete(long userId, String rawPassword)` 保留原签名；新增 `deleteWithoutPassword(long userId)` 仅供"该账号确实没有密码"时使用
- 步骤：
  1. 读账号；`passwordHash == null` 时跳过密码校验，但**要求**请求体带 `confirm: true`
  2. 有密码的账号行为完全不变（反向验证）
  3. 级联里 `user_identities` 走 `ON DELETE CASCADE`，不额外写监听器
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AccountDeletionCascadeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

### TASK-007 | done | 前端登录页与回调页

- 对应：`REQ-001`、`REQ-007`、`AC-001`、`AC-002`
- 依赖：TASK-005
- 修改：`frontend/packages/auth/src/auth-api.ts`（`AuthUser.email: string | null`）、`.../login-form.tsx`、`frontend/apps/app/src/router.tsx`
- 新建：`frontend/packages/auth/src/oauth-buttons.tsx`、`frontend/apps/app/src/pages/OAuthCallbackPage.tsx`
- 步骤：
  1. 放宽 `AuthUser.email`，修掉编译器指出的使用点
  2. `oauth-buttons`：读后端下发的可用平台（`GET /auth/oauth/providers` 或内联到 captcha 响应），未启用则不渲染
  3. 登录页加按钮组；`oauthError` 查询参数映射为可读文案
  4. `/oauth/callback` 路由：取 handoff → 调 exchange → `apply()` → 跳转
  5. e2e：`oauth.spec.ts` 三条（按钮可见、回调成功、回调失败）
- 验证：`cd frontend && pnpm --filter @paideia/app exec playwright test e2e/oauth.spec.ts`

### TASK-008 | done | 账号与安全页

- 对应：`REQ-003`、`REQ-004`、`REQ-005`、`AC-004`、`AC-005`
- 依赖：TASK-005、TASK-007
- 修改：`frontend/apps/app/src/pages/ProfilePage.tsx`
- 步骤：
  1. 列出绑定（provider 图标 + 昵称 + 绑定时间）
  2. 未绑定的平台给"绑定"按钮，走 start 后 window.location
  3. 已绑定的给"解除"，最后一个可用方式时按钮禁用并说明原因
  4. e2e 断言
- 验证：同上 e2e 文件

### TASK-009 | done | 桌面协议接管

- 对应：`REQ-010`、`AC-011`
- 依赖：TASK-007
- 修改：`frontend/apps/desktop/electron/main.cjs`、`frontend/apps/desktop/electron/preload.cjs`、`frontend/apps/desktop/package.json`（`protocols` 字段）
- 步骤：
  1. `app.setAsDefaultProtocolClient('paideia')`
  2. 单实例锁 + `second-instance` 取 argv 里的 `paideia://oauth?handoff=`
  3. 校验 handoff 格式（只允许 URL-safe base64、长度上限），非法则丢弃
  4. 通过 IPC 交给渲染进程，渲染进程走 exchange
  5. 后端补一个极简静态中转页（`/oauth/desktop`），把 `handoff` 转成 `paideia://`
- 验证：手工在桌面产物里点 GitHub 登录 → 浏览器授权 → 应用被唤起并已登录

### TASK-010 | done | 管理端邮箱列与契约快照

- 对应：`AC-010`、`AC-008`
- 依赖：TASK-001、TASK-005
- 修改：`frontend/apps/admin/src/pages/AdminUsersPage.tsx`、`backend/paideia-app/src/test/resources/api-shape.json`
- 步骤：
  1. 邮箱列 `null` 显示"无邮箱"（次要文字色）
  2. 跑 `ApiWalkthroughTest` 加 `-Dapi.shape.update=true` 重录
  3. review 快照 diff，确认只有 `user.email` 可空 + 新端点
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ApiWalkthroughTest,ApiSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false test` → 绿

### TASK-011 | done | 全量验证与报告

- 对应：全部 AC
- 依赖：TASK-001..TASK-010
- 步骤：
  1. `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`
  2. `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test`
  3. `cd frontend && pnpm -r test`（单测）
  4. `playwright test e2e/oauth.spec.ts e2e/identity.spec.ts`
  5. 真连 GitHub / Gitee 各一次（若有凭据），如实记录
  6. 写 `testing/plan.md` 与 `testing/report.md`
- 验证：报告里每条 AC 有命令与结论；未验的写"未验"并说明原因

## 完成条件

- 全部 11 个任务 done，`testing/report.md` 每条 AC 有证据或如实标注未验。
- `validate --strict` 对 WORK-037 零告警。
- 既有 `AccountAuthIntegrationTest` 断言未放宽、全绿。
