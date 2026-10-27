---
artifact: tasks
work_id: WORK-045
work: 数据大屏：全链路数据流转与运营视图
status: approved
mode: strict
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 17:05:32 +0800
approver_role: CTO
---

# 数据大屏任务

## 前置检查

- 需求与方案已批准；设计已批准。
- 工作树：`.worktrees/work-045`（分支 `work-045-analytics`）。
- 迁移目录动前先跑 `git log --all --oneline -- backend/paideia-app/src/main/resources/db/migration/` 查同表冲突。
- 数据面数字口径以 2026-10-27 生产实测为基线（`knowledge_entries` 187、`digest_items` 535、`ai_runs` 55、`question_attempts` 1 等）；测试用独占库，**不假设这些数字**——集成测试自己造数据再断言。

## 任务

### TASK-001 | pending | 两张埋点表与校验和

- 对应：`REQ-016`、`REQ-017`
- 依赖：无
- 新建/修改：`db/migration/V2026xxxxxxxxxx__create_analytics_events.sql`、`migration-checksums.txt`
- 接口：`digest_item_clicks`、`knowledge_search_log`（结构见 design）
- 测试：`MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest`
- 步骤：
  1. 查同表冲突，取当前时间戳写迁移。
  2. `-Dmigration.checksums.update=true` 登记，复核 diff 只有新增一行。
- 验证：三条守卫测试 0 failed。

### TASK-002 | pending | 速递点击上报

- 对应：`REQ-016`、`REQ-018`、`AC-004b`
- 依赖：TASK-001
- 新建/修改：`DigestClickRecorder.java`、`DigestController.java`、`apps/app/.../DigestPage.tsx`
- 接口：`POST /api/v1/digest/items/{id}/click`（AUTHENTICATED）：恒返回 200
- 测试：`DigestClickRecorderTest`（写一次多一行、条目不存在不抛）、前端 e2e 不新增（点击是外链，用单测守）
- 步骤：
  1. 写 recorder 与端点 → 单测。
  2. 前端在 `<a>` 的 `onClick` 里 `void api.post(...).catch(() => {})` —— **必须吞掉**，且不能 `preventDefault`。
- 验证：`-Dtest=DigestClickRecorderTest`；手动点一次确认表里多一行且页面照常跳转。

### TASK-003 | pending | 搜索词上报

- 对应：`REQ-017`、`REQ-018`、`AC-004b`
- 依赖：TASK-001
- 新建/修改：`SearchLogRecorder.java`、`KnowledgeReadController.java`
- 接口：`GET /api/v1/knowledge/entries?q=` 内部记一条（不改响应）
- 测试：`SearchLogRecorderTest`（空 q 不记、超长截断 100、`result_count` 等于返回 total）
- 步骤：
  1. 写 recorder（含截断）。
  2. 在 `entries(...)` 拿到结果后记一条；**只在该请求是用户主动搜索时**（`q` 非空）。
- 验证：`-Dtest=SearchLogRecorderTest`；手动搜一次确认表里多一行且结果页正常。

### TASK-004 | pending | 聚合查询（只读 SQL）

- 对应：`REQ-001`~`REQ-015`
- 依赖：TASK-001、TASK-003（搜索数据可用）
- 新建/修改：`AnalyticsQueries.java`
- 接口：六链各节点计数、12 张表总量、按天时序、功能使用度、AI 汇总、积分三数
- 测试：`AnalyticsQueriesTest`（`@SpringBootTest` + 独占库，造数后逐条断言）
- 步骤：
  1. 先写测试插最小数据集（1 用户、1 条目、1 速递、2 次 AI 运行、1 次答题）。
  2. 逐条实现 SQL，`GROUP BY DATE(CONVERT_TZ(...))` 用**数字偏移**（`+08:00`），不用时区名——生产 `mysql.time_zone_name` 查不到，实测 `CONVERT_TZ(...,'+00:00','+08:00')` 可用。
- 验证：`-Dtest=AnalyticsQueriesTest`。

### TASK-005 | pending | 服务编排与断点判定

- 对应：`REQ-001`~`REQ-015`、`AC-002`、`AC-003`、`AC-006`
- 依赖：TASK-004
- 新建/修改：`AnalyticsService.java`、`AnalyticsView.java`
- 接口：`AnalyticsView`（五区块 record）
- 测试：`AnalyticsServiceTest`（断点规则、监控降级、三态判定）
- 步骤：
  1. 断点规则：`count==0 && 上游>0` → `zero`；上游也 0 → 不标。
  2. 监控层每个查询独立 try/catch；任一失败 `reachable=false`，MySQL 区块不受影响。
  3. 三态：有数据 `active`、采集到但为 0 `zero`、无数据源 `not-collected`。
- 验证：`-Dtest=AnalyticsServiceTest`。

### TASK-006 | pending | 端点与权限

- 对应：`REQ-015`、`AC-001`
- 依赖：TASK-005
- 新建/修改：`AnalyticsController.java`
- 接口：`GET /api/v1/admin/analytics/overview`
- 测试：`AnalyticsIntegrationTest`（ADMIN 200 / 学习者 403 / 匿名 401）
- 步骤：`@ApiAccess(ApiAccess.Access.ADMIN)` + 一条 GET，直接返回 service 结果。
- 验证：`-Dtest=AnalyticsIntegrationTest`。

### TASK-007 | pending | 大屏页面：泳道与四个区块

- 对应：`REQ-001`~`REQ-015`、`AC-003`、`AC-004`、`AC-005`
- 依赖：TASK-006
- 新建/修改：`apps/admin/src/pages/AnalyticsPage.tsx`、`router.tsx`、导航
- 接口：调 `GET /api/v1/admin/analytics/overview`，60 秒轮询
- 测试：`pnpm --filter @paideia/admin typecheck`；e2e 在 TASK-009
- 步骤：
  1. 五区块骨架 + 骨架屏。
  2. 泳道：flex 四节点 + 伪元素箭头；`state` 三色；`ratio=0` 虚线。
  3. 时序柱状：纯 CSS 高度条；数据点 < 3 写"数据不足（N 个时间点）"。
  4. 每区块下钻链接。
- 验证：typecheck 通过；本地起后端实看一遍。

### TASK-008 | pending | 响应式与视觉细节

- 对应：`AC-008`
- 依赖：TASK-007
- 新建/修改：`AnalyticsPage.tsx` 样式；必要时 `@paideia/ui` 的共享组件
- 测试：e2e 三档视口
- 步骤：≥1536 三列 / 1280–1535 两列 / <1024 单列；数字 `tabular-nums`。
- 验证：`AC-008` 的 e2e 断言无横向滚动。

### TASK-009 | pending | e2e：入口、五区块、断点、响应式

- 对应：`AC-003`、`AC-008`、`AC-009`
- 依赖：TASK-007、TASK-008
- 新建/修改：`apps/admin/e2e/analytics.spec.ts`
- 测试：导航点进 `/analytics`；五区块可见；断点警示存在；三档视口无横向滚动
- 步骤：按现有 `incidents.spec.ts` 的写法（signIn → 点导航 → 断言）。
- 验证：`../scripts/test-db.sh pnpm --filter @paideia/admin exec playwright test e2e/analytics.spec.ts`

### TASK-010 | pending | 性能实测与索引盘点

- 对应：`AC-007`
- 依赖：TASK-006
- 新建/修改：无代码（必要时补索引迁移）
- 测试：`GET /overview` 连跑 20 次取 P95
- 步骤：
  1. 在测试库（或导出生产数据）跑 20 次，记录 P50/P95。
  2. 若 > 800ms，先看慢查询（`EXPLAIN`），补索引；仍超则改分区块懒加载。
- 验证：P95 数字写进报告。

### TASK-011 | pending | 收口：文档、部署、生产实看

- 对应：全部 `AC-*`
- 依赖：TASK-001..TASK-010
- 新建/修改：`testing/report.md`、`.agent/history/updates.md`、`.agent/notes/dev-workflow.md`
- 测试：管理端 e2e 全量、`Ops*Test`、学习者端受影响文件
- 步骤：跑切片 → 从干净工作树发版 → 生产打开页面核对六个数字 → 写报告。
- 验证：报告每条 AC 有命令 + 退出码 + 证据。

## 风险与验证对照

| 风险 | 任务 | 哨兵 |
| --- | --- | --- |
| 迁移撞号/漏登记校验和 | TASK-001 | 三条迁移守卫测试 |
| 埋点阻断速递跳转 | TASK-002 | `DigestClickRecorderTest` + 反向验证（把 catch 去掉应变红） |
| `q` 为空写入脏数据 | TASK-003 | `SearchLogRecorderTest` |
| 时区切桶错位一天 | TASK-004 | 断言 `daily` 日期与 `edition_date` 对齐 |
| 断点规则把"整链没跑"误标成断点 | TASK-005 | `AnalyticsServiceTest` |
| Prometheus 挂了整页空白 | TASK-005 | 死端口降级测试 |
| 聚合太慢 | TASK-010 | P95 实测 |
| 新页面没有导航入口 | TASK-009 | e2e 从导航点进 |
