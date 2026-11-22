---
artifact: design
work_id: WORK-030
work: 管理端优化
status: approved
depends_on: []
related_to: [WORK-002, WORK-003, WORK-009, WORK-022, WORK-031]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 16:48:24 +0800
approver_role: CTO
---

# 设计 · 管理端优化（阶段 A）

本文件只设计**阶段 A**（用户管理 + 各页筛选 + 删积分调整页 + 总览）。阶段 B 的遥测与 payload 表另行设计。

## 一、模块落点

`paideia-ai` **不依赖** `paideia-account`，而 `paideia-app` 依赖两者。所以"一个人 + 他的积分 + 他的调用"这种跨模块聚合只能放在 `paideia-app`——与既有的 `ops` 包同一先例（运维面板也是聚合多个模块）。

| 层 | 落点 | 职责 |
| --- | --- | --- |
| `paideia-account` | `AccountAdminApi` 端口 + 实现 | 账号的读写：分页列表、按 id 查、改角色、管理员删号 |
| `paideia-ai` | 复用既有 `CreditService` / `AiRunAdminService` | 余额、流水、调用记录——**不新增查询**，能复用就复用 |
| `paideia-app` | 新包 `console` | 聚合视图与写操作入口，路径 `/api/v1/account/admin/**` |

**为什么聚合端点不拆成两个接口**：前端一次开页面就要"人 + 余额 + 用量"，拆成两个接口意味着两轮往返、两种错误状态、两处 loading。聚合在服务端只多一次批量查询。

## 二、端点

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/v1/account/admin/users?query=&role=&sort=&page=&size=` | 分页列表，每行含用户名/邮箱/角色/注册时间/余额/已用/调用次数/最近活跃 |
| `GET` | `/api/v1/account/admin/users/{id}` | 单人详情：汇总 + 最近积分流水 + 最近调用 |
| `POST` | `/api/v1/account/admin/users/{id}/role` | 改角色（body: `{role}`），成功即吊销该用户全部 refresh token |
| `POST` | `/api/v1/account/admin/users/{id}/credits` | 调积分（body: `{delta, note}`），复用 `CreditService.adjust` |
| `DELETE` | `/api/v1/account/admin/users/{id}` | 管理员删号（不走密码校验，走共用的级联） |

**权限**：全部挂 `hasRole("ADMIN")`，规则写在 `SecurityConfiguration` 的 `/api/**` 匹配表里，且必须排在更宽的 `/api/v1/account/**` 之前（排反了不报错，只静默失效——这是既有纪律）。

**不可自伤**：五个写端点里除 `GET` 外，都要在服务端比对"目标 id == 令牌里的 id"，相等直接拒绝。不能只靠前端藏按钮。

## 三、分页与聚合的算法

```
1. account 侧：按 query/role 分页取一页用户（含 created_at）
2. 拿到这一页的 userId 列表（通常 20 个）
3. ai 侧：按 userId 批量查
   - 余额：ai_credit_accounts 的 balance
   - 已用：ai_credit_ledger 里 delta < 0 的 SUM（取绝对值）
   - 调用次数：ai_runs 的 COUNT
   - 最近活跃：ai_runs 的 MAX(started_at)
4. 在内存里按 userId 对齐后返回
```

**为什么批量而不是每行查一次**：20 行 × 4 个指标 = 80 次查询。批量是 4 次（各一条 `IN (...)`）。

**排序**：`sort` 支持 `name` / `balance` / `calls` / `recent`。前两者能在 account 侧排（余额需要先 join，实现上按"取一页后内存排"会破坏分页语义，所以**按余额/调用次数排序时改为先查 ai 侧的 id 排行**）。这是本项唯一的复杂度，实现时用一个明确的二分：

- `sort=name`（默认）：account 侧分页，ai 侧只补当前页的指标
- `sort=balance|calls|recent`：ai 侧先出排好序的 userId 序列与总数，再按页取 account 信息

## 四、删号的复用边界

`AccountDeletionService.delete(userId, rawPassword)` 校验的是**用户自己的密码**，管理员拿不到。

改法：把方法体拆成两段，**级联那一段抽成包内方法**：

```java
@Transactional
public void delete(long userId, String rawPassword) {   // 自助注销
    ...校验密码...
    deleteCascade(userId);
}

@Transactional
public void deleteByAdmin(long userId) {                // 管理员删号
    requireExists(userId);
    deleteCascade(userId);
}

private void deleteCascade(long userId) {               // 唯一的级联实现
    refreshTokens.deleteByUser(userId);
    profiles.deleteByUser(userId);
    users.deleteById(userId);
    events.publishEvent(new UserDeletedEvent(userId));
}
```

**不许复制一份新的删除逻辑**：以后加表只改一处。

## 五、改角色的令牌处理

```
changeRole(userId, newRole):
  1. 校验 role 只能是 admin / learner
  2. 不可自伤：userId == 当前操作者 → 拒绝
  3. UPDATE users SET role = ?
  4. refreshTokens.deleteByUser(userId)   // U-18：让他下次刷新就重新登录
```

访问令牌不主动吊销（无状态，做不到），但它分钟级就过期；refresh 一删，最长一轮刷新内必定生效。

## 六、前端

| 页面 | 改动 |
| --- | --- |
| 用户管理（新） | 搜索框（防抖 300ms）+ 角色筛选 + 表格 + 行内展开详情 + 调积分/改角色/删除的确认弹窗 |
| 积分调整 | **删除**：`AiCreditsGrantPage.tsx` + 路由 + 菜单项 + 面包屑 + 窄屏 e2e 用例 |
| 用量审计 | 加用户/种类/时间筛选，用户列显示用户名 |
| 调用明细 | 加时间区间、模型筛选、分页；把两个原生 `<select>` 换成 `@paideia/ui` 的 `Select` |
| 调用概览 | 时间区间可选 |
| 举报处理 | 状态筛选（后端已支持，前端接上即可），默认待处理 |
| 概念 / 分类 | 按名称搜索 |
| 条目 | 补分类筛选 |
| 总览 | 加账号数、近 7 天调用次数、近 7 天扣分、待处理举报数 |

**下拉一律用家里的 `Select`**（WORK-002 已定）；管理端现在还有两处原生 `<select>` 会被这次改掉。

## 七、风险

| 风险 | 对策 |
| --- | --- |
| 新端点权限规则写错位置 → 静默放开 | 规则必须排在 `/api/v1/account/**` 之前；用集成测试以 learner 身份调一遍，断言 403 |
| 删号把管理员自己删掉 → 死锁 | 服务端比对目标 id 与令牌 id |
| 聚合查询慢 | 批量 `IN`，单页 20 条；先测实耗时 |
| 用户列表泄露 `password_hash` | 新增的查询**只选需要的列**，不复用会带出 hash 的既有 SELECT |
| 删除积分调整页漏改某处 | 三处一起改：路由、菜单/面包屑、窄屏 e2e；改完全局搜 `ai-credits/grant` 应为零 |
