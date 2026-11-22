---
artifact: tasks
work_id: WORK-030
work: 管理端优化
status: approved
depends_on: []
related_to: [WORK-002, WORK-003, WORK-009, WORK-022, WORK-031]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 16:48:29 +0800
approver_role: CTO
---

# 任务 · 管理端优化（阶段 A）

阶段 B（遥测与 payload 表）在阶段 A 验收通过后追加。

## 后端

### TASK-001 | done | 账号模块的管理端能力

**做什么**

- `paideia-account`：新增 `AccountAdminApi` 端口（包根）与实现
  - `PageResult<AdminUser> list(String query, String role, int page, int size)` — **只选需要的列**，不含 `password_hash`
  - `Optional<AdminUser> findById(long id)`
  - `void changeRole(long id, String role)` — 校验枚举 + 改写
  - `void deleteByAdmin(long id)` — 与自助注销共用级联
- `AccountDeletionService`：把级联抽成私有方法，`delete` 与 `deleteByAdmin` 都调它
- `RefreshTokenMapper.deleteByUser` 复用（已存在）

**可检查结果**

- 单测覆盖：改名不存在的角色被拒；`deleteByAdmin` 与 `delete` 走到同一段级联（用 spy 断言）
- **反向验证**：把级联抽方法的改动去掉，两个入口的测试必须红
- 新增的 SELECT 结果里 `password_hash` 为 null（用一个字段级断言钉住）

### TASK-002 | done | 聚合端点

**做什么**

- `paideia-app` 新包 `console`：`AdminUserController`
  - `GET /api/v1/account/admin/users`（分页 + 四个指标）
  - `GET /api/v1/account/admin/users/{id}`（汇总 + 最近流水 + 最近调用）
  - `POST .../{id}/role`、`POST .../{id}/credits`、`DELETE .../{id}`
- 批量查询：`IN (...)` 一次取走一页的余额/已用/调用次数/最近活跃
- `SecurityConfiguration`：`/api/v1/account/admin/**` → `hasRole("ADMIN")`，**排在 `/api/v1/account/**` 之前**
- 不可自伤：写端点比对目标 id 与令牌 id

**可检查结果**

- 集成测试：管理员能列、能改角色、能调积分、能删；**学习者调任一端点都是 403**
- 集成测试：管理员传自己的 id 调写端点得到 4xx
- 集成测试：响应体不含 `password_hash`
- `sort=balance|calls` 两个分支各一条用例（证明排序不是摆设）

### TASK-003 | done | 复用既有查询补齐三个页面

**做什么**

- 用量审计：加 `userId` / `kind` / 时间区间参数（`CreditService` 加一个带过滤的查询）
- 调用明细：前端接上后端的 `model` / `page`（后端已支持，**不动后端**）
- 举报：前端接上后端的 `status`（后端已支持，**不动后端**）

**可检查结果**

- 用量审计带筛选与不带筛选的条数不同（真数据）
- 调用明细能翻到第 2 页（`size` 可调）

## 前端

### TASK-004 | done | 用户管理页

**做什么**

- 新页面 `AdminUsersPage.tsx`：搜索（防抖 300ms）、角色筛选、表格（九列）、行内展开详情、三个写操作的确认弹窗
- 路由 `/users`、侧栏菜单项「用户管理」、面包屑
- 所有下拉用 `@paideia/ui` 的 `Select`

**可检查结果**

- e2e：搜索到人、展开看到余额与流水、加 100 分后余额变化、对自己的写按钮不可用

### TASK-005 | done | 删除积分调整页

**做什么**

- 删 `AiCreditsGrantPage.tsx`
- `router.tsx`：删路由，`/ai-credits` 转向改为 `/ai-credits/audit`
- `AdminShell.tsx`：删菜单项与面包屑；「AI 积分」父项只剩「用量审计」
- `e2e/layout.mobile.spec.ts`：删那一页的用例

**可检查结果**

- 全局搜 `ai-credits/grant` 与 `AiCreditsGrantPage` 均为零命中
- 手敲该地址不渲染空白页

### TASK-006 | done | 各页筛选与总览

**做什么**

- 用量审计：用户/种类/时间筛选，用户列显示用户名
- 调用明细：时间区间（近1小时/今天/近7天/近30天/自定义）、模型筛选、分页；换掉两个原生 `<select>`
- 调用概览：时间区间可选
- 举报处理：状态筛选，默认待处理
- 概念、分类：名称搜索
- 条目：补分类筛选
- 总览：加四个运营数字（账号数、近7天调用、近7天扣分、待处理举报）

**可检查结果**

- 每个列表点一次筛选，条数跟着变
- 管理端源码里不再有 `<select`

## 验证

### TASK-007 | done | 验证与报告

**做什么**

- 后端：`scripts/test-db.sh mvn -B -pl '!paideia-app' test` → 再跑全量
- 前端：管理端 e2e 只跑改动的文件（`admin`、`knowledge`、`layout.mobile`）
- 写 `testing/plan.md` 与 `report.md`，逐条对齐 AC

**可检查结果**

- 全绿且**核对 Skipped 列**
- 管理端 e2e 打线上跑一遍（`E2E_BASE_URL`）

## 完成判据

- TASK-001..007 全部 done
- `validate` 无 WORK-030 错误
- 不 push（需单独授权）
