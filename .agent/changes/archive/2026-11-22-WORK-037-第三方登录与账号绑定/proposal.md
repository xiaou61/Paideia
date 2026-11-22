---
artifact: proposal
work_id: WORK-037
work: 第三方登录与账号绑定
status: approved
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 14:45:29 +0800
approver_role: CTO
---

# 方案 · 第三方登录与账号绑定

需求与验收在 [requirements.md](requirements.md)。

## 一句话

在本项目既有的**无状态 Bearer 架构**上，用一份手写的通用授权码客户端接 GitHub 与 Gitee；账号侧新增一张 `user_identities` 关联表，让"一个账号多种登录方式"成为真实可读写的状态；不引入 OAuth 客户端框架、不加 Cookie、不加会话。

## 核心机制（三个取舍）

### 一、不引 `spring-boot-starter-oauth2-client`

**它最强的理由**：省掉授权码交换、令牌解析、provider 差异化的实现，Spring 维护，已知坑都有答案。

**为什么仍然不用**：它的整条设计围绕 `OAuth2AuthorizedClientRepository`，默认实现是 `HttpSessionOAuth2AuthorizedClientRepository`——授权状态存在 HTTP 会话里。本项目 `SessionCreationPolicy.STATELESS`、无 Cookie、纯 Bearer，桌面壳的页面来源又是 `http://127.0.0.1:5310`。要用它只有两条路：改回会话 cookie（桌面端 `SameSite`/`Secure` 语义失效，正是 D-10 当初排除会话的原因），或写一个自定义 `AuthorizedClientRepository` 把状态放别处——那时已经把它最核心的部分换掉了，剩下的封装反而成了要绕开的壳。

**放弃的是什么**：多平台适配要自己写（GitHub 与 Gitee 的端点、参数名、用户信息字段各不相同）。代价用"平台差异集中在一个枚举里"来控住，不让它扩散到登录主链路。

### 二、`state` 放 `ExpiringStore`，不落库、不放 Cookie

授权回调是无状态端点，必须能识别"这次回调是我发起的那一次"。既有 `ExpiringStore` 已经承担图形验证码、邮箱验证码与限流三处同类职责，天花板一致（进程内、多实例不可用）——本项**不引入新的天花板类别**，只多一个使用者。

`state` 记录的取值不是裸字符串，而是 `(provider, purpose, userId)`：这样回调能同时校验"是哪个平台"和"这次是要登录还是要绑定"，而不必再传一个可被篡改的查询参数。

### 三、令牌不经过 URL，用一次性握手码

回调落在**后端**，但令牌要交给**前端**。直接 `302` 到 `#/oauth/callback?access=…&refresh=…` 有两个问题：refresh 寿命 14 天却会留在浏览器历史里，用户截图或复制地址即泄露；桌面端则要跨进程传递。

改为：后端换完令牌，把它与一个随机 `handoff` 关联存进同一个 `ExpiringStore`（60 秒、消费即删），只把 `handoff` 放进 URL。前端（或桌面壳）拿 `handoff` 调一次 `POST /api/v1/auth/oauth/exchange` 换回令牌。**URL 里永远没有令牌**。

## 范围

**做**：GitHub 与 Gitee 授权登录；首次授权自动建号；已登录后绑定/解绑；账号设置页列出绑定；第三方用户可注销；桌面端经 `paideia://` 接管回调。

**不做**：QQ / 微博 / 微信（备案门槛）；邮箱验证码登录与找回密码（用户未纳入本期）；按邮箱自动合并账号；第三方资料持续同步。

## 备选方案

| 方案 | 最强的理由 | 为什么没选 |
| --- | --- | --- |
| `spring-boot-starter-oauth2-client` | 省实现、Spring 维护 | 绑定 HTTP 会话，与无状态 + 桌面端冲突（见上） |
| 引入聚合登录 API（免签中间服务） | 一处接入十几家，不要备案 | 凭据边界从平台转移到第三方小站，且可随时下线；需求已明确排除 |
| 先做邮箱验证码登录，第三方延后 | 覆盖率 100%、零备案、零新依赖 | 不解决"登录方式更多样"这个诉求；两条路都要走，先做哪个不改变总成本 |
| 令牌直接放 URL fragment | 少一个端点、少一次往返 | refresh 落进浏览器历史与截图，泄露面不可接受 |

## 仓库影响

| 模块 | 影响 |
| --- | --- |
| `paideia-account` | 新增 `internal/oauth`（客户端、枚举、状态）；`users` 三列可空；新增 `user_identities` 表与 mapper；`AccountDeletionService` 放宽确认方式 |
| `paideia-security` | **不改**。回调走既有 `/api/v1/auth/**` 放行；绑定走 `/api/v1/account/**` 落 `authenticated` |
| `paideia-web` | 不改 |
| `paideia-app` | 新迁移；`api-shape.json` 需重录（`user.email` 变可空 + 新增端点） |
| `frontend/packages/auth` | `AuthUser.email` 放宽；新增四个 API 方法；登录表单加两个按钮 |
| `frontend/apps/app` | 新增 `/oauth/callback` 路由；`ProfilePage` 加"账号与安全" |
| `frontend/apps/admin` | 用户列表邮箱列显示"无邮箱" |
| `frontend/apps/desktop` | `setAsDefaultProtocolClient` + 协议唤起处理 |

## 交付拆分

1. 数据与模型（迁移、mapper、可空性）——可独立验证：既有认证测试全绿。
2. 通用授权码客户端与 state（含两个 provider 的端到端单测）——可独立验证：不打网络，桩出站 HTTP。
3. 登录/绑定/解绑/交换端点——可独立验证：集成测试。
4. 前端登录页与回调页——可独立验证：e2e。
5. 账号设置页——可独立验证：e2e。
6. 桌面协议接管——可独立验证：手工 + 单元。
7. 契约快照重录与全量验证。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| GitHub 对公网 IP 回调的支持无官方明文 | 实施时用真实应用验证；若不可登记，验收报告如实写"仅本地可跑通"，不假装线上可用 |
| 改 `users` 可空性影响既有路径 | `password_hash` 可空后 `LoginService` 必须显式挡无密码账号，否则空口令会走 `matches(null,…)`；加反向验证 |
| 第三方登录用户无法注销（既有缺陷） | 见 REQ-006，本项必须修，属阻断验收项 |
| 桌面协议被恶意页面伪造唤起 | `paideia://` 只承载一次性 handoff；不携带任何长效凭据，且 handoff 消费即删 |
| `state` 进程内存储 | 单实例现状可接受；多实例前必须换共享存储，与既有三处同一条件，记入代码注释 |

## 验收映射

| REQ | AC | 落点 |
| --- | --- | --- |
| REQ-001 | AC-001, AC-002 | 登录页按钮 + start/callback 端点 |
| REQ-002 | AC-003 | 自动建号 + 用户名冲突处理 |
| REQ-003 | AC-004 | `/api/v1/account/oauth/*/start` + 绑定 |
| REQ-004 | AC-005 | 解绑前置检查 |
| REQ-005 | AC-004 | 账号设置页列表 |
| REQ-006 | AC-006 | `AccountDeletionService` |
| REQ-007 | AC-007 | 回调失败处理 |
| REQ-008 | AC-009 | 配置外置 |
| REQ-009 | — | provider 枚举集中差异 |
| REQ-010 | AC-011 | 桌面协议接管 |
| 既有行为不变 | AC-008, AC-010 | 全量回归 |

## 待决定事项

无。Q1（桌面端回调）与 Q3（用户名冲突）已在需求中定案；Q2（生产回调是否可登记）属实施期实测项，不阻断方案批准。
