---
artifact: test-report
work_id: WORK-037
work: 第三方登录与账号绑定
status: passed
evidence: required
created: 2026-10-23
updated: 2026-10-23
accepted_by: xiaou61
accepted_at: 2026-10-23 16:40:04 +0800
---

# WORK-037 验证报告

日期：2026-10-23。工作树 `.worktrees/work-037`，分支 `work-037-oauth`。

每条结论对应一次真实执行。**未执行的检查写"未验"并说明原因**，不用"代码正确"代替证据。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 GitHub 建号与绑定 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OAuthLoginIntegrationTest test` | 0 | 通过 | `createsAccountOnFirstGithubLogin`：断言生成了用户名、`password_hash` 为 NULL、留有一条 `github` 绑定 |
| AC-001 完整回调链路 | `... -Dtest=OAuthEndpointsIntegrationTest test` | 0 | 通过 | `fullCallbackFlowYieldsTokens`：回调 302 → 握手码 → 换到令牌，且 URL 中无 `accessToken`/`refreshToken` |
| AC-002 Gitee 建号 | 同上 | 0 | 通过 | `createsAccountOnFirstGiteeLogin`：同一 subject 在两个平台互不相干 |
| AC-003 重复授权不新建 | 同上 | 0 | 通过 | `repeatAuthorizationReusesAccount`：两次授权 `user.id` 相同 |
| AC-003 昵称冲突加后缀 | 同上 | 0 | 通过 | `usernameCollisionGetsSuffix`：第二个账号用户名以 `-2` 结尾 |
| AC-004 绑定后列出、解绑后消失 | 同上 | 0 | 通过 | `bindThenUnbind`（2 → 1）+ `identitiesRequireLoginAndRespectLastCredential` |
| AC-005 唯一方式不能解绑 | 同上 | 0 | 通过 | `cannotUnbindLastCredential`：409 且绑定关系原样保留；`canUnbindLastOAuthWhenPasswordExists` 证有密码时可解 |
| AC-006 无密码用户可注销 | `... -Dtest=AccountDeletionCascadeIntegrationTest test` | 0 | 通过 | `deletesPasswordlessUser`：无确认 400、`confirmed:true` 200 且 users 行归零；`passwordStillRequiredEvenWithConfirmedFlag` 证有密码时 confirm 不能绕过 |
| AC-006 级联清单 | 同上 | 0 | 通过 | `USER_DATA` 新增 `user_identities` 一行，注销后逐表为 0 |
| AC-007 伪造 state 被拒 | `... -Dtest=OAuthEndpointsIntegrationTest test` | 0 | 通过 | `forgedStateRedirectsToLogin`：302 到 `/#/login?oauthError=expired`，Location 里无 handoff |
| AC-007 state 重放 | 同上 | 0 | 通过 | `replayedStateIsRejected`：第二次回调得到 `oauthError=expired` |
| AC-007 state 跨平台挪用 | 同上 | 0 | 通过 | `stateIsBoundToProvider`：GitHub 的 state 拿去 Gitee 回调被拒 |
| AC-007 用户取消授权 | 同上 | 0 | 通过 | `userDenialIsReportedAsDenied`：`error=access_denied` → `oauthError=denied` |
| AC-008 既有认证行为不变 | `... -Dtest=AccountAuthIntegrationTest test` | 0 | 通过 | 14/0/0，**断言未放宽**；新增 `loginRejectsPasswordlessAccount` |
| 无密码账号时序对齐 | `mvn -B -pl paideia-account -Dtest=LoginServiceTest test` | 0 | 通过 | `passwordlessAccountStillPaysTheTimingCost`：验证走了 `wasteTimeLikeAMatch` 且不调 `matches` |
| AC-009 凭据不外泄 | `... -Dtest=OAuthEndpointsIntegrationTest test` | 0 | 通过 | `providersArePublicButCredentialGated` / `startIsAnonymousAndCarriesState` 断言响应不含 client secret |
| AC-010 管理端显示无邮箱 | 人工核对 + 前端类型 | 人工 | 通过 | `AdminUsersPage` 两处改为 `?? '无邮箱'`；**未跑 e2e**（需先造无邮箱账号，见"未覆盖"） |
| AC-011 桌面回调标记 | `... -Dtest=OAuthEndpointsIntegrationTest test` | 0 | 通过 | `desktopStartMarksCallback`：`desktop=true` 的回调 Location 含 `desktop=1` |
| AC-011 协议解析 | `node electron/oauth-protocol.test.cjs` | 0 | 通过 | 9 类输入（正常/错协议/错 host/空码/非法字符/超长/非字符串/带引号）全部符合预期 |
| REQ-009 平台差异集中 | `mvn -B -pl paideia-account -Dtest=OAuthProviderTest test` | 0 | 通过 | 7/0：两个平台的端点与 scope 各自正确且不共享 |
| state/handoff 一次性 | `... -Dtest='OAuthStateStoreTest,HandoffStoreTest' test` | 0 | 通过 | 10/0：重放、过期、空值、容量满时拒绝 |
| 端点形状与放行 | `... -Dtest=ApiSurfaceTest test` | 0 | 通过 | 7/0：新端点都声明了 `@ApiAccess`、都在 `/api/v1/` 下、级别只声明一处 |
| 鉴权声明与实际行为 | `... -Dtest=ApiContractTest test` | 0 | 通过 | 9/0（**此前 master 上红 1 条**，见缺陷 4） |
| 全接口自查 | `... -Dtest=ApiWalkthroughTest test` | 0 | 通过 | 1/0，12 个端点补齐覆盖后快照一致 |
| 契约快照 | 同上 | 0 | 通过 | 新增 8 个端点、改写 2 个（见第五节） |
| 模块边界 | `... -Dtest=ModularityTest test` | 0 | 通过 | 1/0 |
| 迁移闸 | `... -Dtest='MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest' test` | 0 | 通过 | 8/0；新迁移校验和已登记 |
| 后端单测全集 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 0 | 通过 | **291 执行 / 0 失败 / 13 跳过**（13 条全是 `*LiveTest` 与需对象存储的 `FileStorageContractTest`，条件跳过） |
| app 模块（第一批） | `... -Dtest='OAuth*Test,AccountAuthIntegrationTest,AccountDeletionCascadeIntegrationTest,ApiWalkthroughTest,ApiSurfaceTest,ApiContractTest,ModularityTest,Migration*Test' test` | 0 | 通过 | **68 执行 / 0 失败 / 0 跳过** |
| app 模块（第二批） | `... -Dtest='!OAuth*Test,!AccountAuthIntegrationTest,!AccountDeletionCascadeIntegrationTest,!ApiWalkthroughTest,!ApiSurfaceTest,!ApiContractTest,!ModularityTest,!Migration*Test' test` | 0 | 通过 | **253 执行 / 0 失败 / 4 跳过** |
| 前端单测 | `pnpm -r test` | 0 | 通过 | 全仓 9 个包，0 失败；auth 包 13 条（含 `completeOAuth`） |
| 类型检查 | `pnpm -r --filter @paideia/app --filter @paideia/auth --filter @paideia/platform-desktop typecheck` | 0 | 通过 | 三个包 tsc 均无输出 |
| 学习者端 e2e | `scripts/test-db.sh pnpm --filter @paideia/app exec playwright test e2e/oauth.spec.ts e2e/identity.spec.ts` | 0 | 通过 | **8 passed**（oauth 5 + identity 3） |
| 管理端 e2e | `scripts/test-db.sh pnpm --filter @paideia/admin exec playwright test` | 0 | 通过 | **27 passed** |
| 反向验证：无密码时序 | 删掉 `LoginService` 的无密码分支后跑 `LoginServiceTest` | 1 | 通过 | 1 条失败（`wanted but not invoked: wasteTimeLikeAMatch`），恢复后全绿 |
| 反向验证：最后一种方式 | 把 `remaining <= 0` 改成 `< 0` 后跑 `cannotUnbindLastCredential` | 1 | 通过 | 1 条失败，恢复后全绿 |
| 反向验证：state 重放 | 把 `OAuthStateStore.take` 的 `store.take` 改成 `store.get` | 1 | 通过 | `invalidatesStateAfterFirstUse` 失败，恢复后全绿 |
| 反向验证：握手码重放 | 把 `HandoffStore.consume` 的 `take` 改成 `get` | 1 | 通过 | `consumedOnce` 失败，恢复后全绿 |
| 反向验证：无密码注销 | 把无密码分支条件改成 `false` 后跑 `deletesPasswordlessUser` | 1 | 通过 | 1 条失败，恢复后全绿 |

## 实施中发现并修掉的缺陷

**1. `email_verified_at` 是 NOT NULL，第三方建号直接失败**（TASK-004）
`OAuthLoginIntegrationTest` 首跑 10 个错误，全部是 `Column 'email_verified_at' cannot be null`。
迁移里补上这一列可空。**没有填当前时间**：那一列的含义是"我们验证过这个邮箱"，
平台给的邮箱我们并没有验证，填时间等于记一条假账。

**2. 中文两字昵称被误退成 `user`**（TASK-004）
`UsernameAllocator` 原先套用了注册接口的 `min = 3`，于是"张伟"这种最常见的姓名长度被丢掉。
生成器直接写库、不经过注册接口，那个下限对它不是硬约束——改成"清洗后为空才退到 fallback"。

**3. `confirmed` 用原始 `boolean` 让既有注销请求全部 400**（TASK-006）
`DeletionRequest` 加了一个原始 `boolean` 字段后，不带该字段的既有请求反序列化失败，
`AccountDeletionCascadeIntegrationTest` 里 2 条既有用例立刻变红。改成 `Boolean` 包装类型。

**4. `/api/v1/ai/feedback/admin` 声明漏了 ADMIN**（TASK-010，**既有缺陷**）
`ApiContractTest` 在 **master 上就是红的**：这个端点实际要求 `hasRole("ADMIN")`，
但 `@ApiAccess` 是类级的 `AUTHENTICATED`。补声明时又撞上 `ApiSurfaceTest` 的第二条规则
（访问级别只能声明在一处），最终拆成独立的 `AiFeedbackAdminController`。

**5. `useAuth().api` 调受保护端点稳定 401**（TASK-008）
账号与安全区块首版用 `useAuth().api`，它稳定返回 401，界面只显示一句"未认证"，
看起来像后端没实现。改用页面级 `../api`（与画像读同一套，挂了 401 刷新与令牌注入）后立刻正常。

**6. `browser.newContext()` 会继承 `use.storageState`**（TASK-007）
写 e2e 时以为新建 context 就没有会话，实际继承了登录态，`/#/login` 被 `RequireGuest`
弹回首页。验"未登录状态"必须显式传 `storageState: { cookies: [], origins: [] }`。

## 全量跑不稳的原因（重要，不是本项引入）

单条命令跑 app 模块全量时，`SandboxIntegrationTest` 与 `ResumeEndpointsIntegrationTest`
会报 `Too many connections`（MySQL `max_connections=151`）。实测：

- 这两个类**单独跑 13/13 全绿**；
- 全量跑时约 30 个 `@SpringBootTest` 的上下文连接累积，撞上 151 上限；
- **主树（master）全量同样不干净**（294 执行 / 5 失败），失败项包括 `ApiContractTest`、
  `ApiWalkthroughTest` 与 `SandboxIntegrationTest`。

所以本项改用**两批**证明：68 + 253 = 321 条，0 失败、0 错误、4 跳过。
`max_connections` 偏小是环境容量问题，属测试基础设施的既有债，不在本项范围内。
它应该单独开一项处理（调大上限或给测试上下文加连接池上限），本报告不假装它不存在。

## 未覆盖（如实记录）

| 项 | 为什么没做 | 影响 |
| --- | --- | --- |
| **真实 GitHub / Gitee 授权往返** | 本机没有平台凭据（clientId/clientSecret），也不该在公开仓库里放 | 这是本项**最大的一处未验**：端到端只验到"平台被桩掉时我们这一侧全对"。真实平台在字段、scope、错误码上的细微差异没被验到 |
| **生产回调地址能否登记** | 取决于真实 GitHub 应用（需求 Q2） | 线上是 `http://<IP>:8081`，无域名无 HTTPS。GitHub 文档只明文允许 loopback 用 http；公网 IP 能否登记**未实测**。若不能，第三方登录在生产不可用 |
| **桌面端完整链路手验** | 需要打桌面安装包（约 20 分钟）且需真实平台凭据 | 只验了协议解析（9 条断言）与"Web 端零影响"；`second-instance`/`open-url` 两条唤醒路径没有真机跑过 |
| **管理端无邮箱的 e2e** | 需要先造一个无邮箱账号，而管理端 e2e 打的是真实后端 | 显示逻辑只有代码保证；后端 `email: null` 的返回由集成测试覆盖 |
| **`user.email` 可空在契约快照里** | 快照按实际响应记录类型，而样本账号都有邮箱 | 快照里仍是 `string`。"可空"由集成测试 `accountWithoutEmailIsAllowed` 与前端 `string \| null` 类型守 |
| **多实例部署** | 单实例自托管；`state`/`handoff` 与既有三处共用同一进程内存储 | 多实例前必须换共享存储，与既有三处同一条件，已在代码注释写明 |
| **设置密码入口** | 不在本期范围 | 第三方用户目前无法补设密码，因此"绑定唯一方式时不可解绑"这条会一直生效 |

## 结论

需求 10 条全部有对应证据；11 条验收标准中 9 条由自动化覆盖并有反向验证，
AC-010 只有代码级保证，AC-011 的桌面端只验到协议解析一层。
**最大的缺口是真实平台往返未验**，它决定了这个功能在线上是不是真的能用。
