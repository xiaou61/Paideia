---
artifact: test-report
work_id: WORK-045
work: 数据大屏：全链路数据流转与运营视图
status: passed
evidence: required
verified_commit: dfb2ea1
created: 2026-10-27
updated: 2026-10-27
accepted_by: xiaou61
accepted_at: 2026-10-29 13:21:34 +0800
---

# WORK-045 验证报告

日期：2026-10-27。后端测试经 `scripts/test-db.sh` 分配独占库；前端 e2e 用独立端口
（`PAIDEIA_BACKEND_PORT=18085/18086`、`PAIDEIA_ADMIN_PORT=15179`、`PAIDEIA_APP_PORT=15180`）
避免与其它会话抢占。工作树 `.worktrees/work-045`，分支 `work-045-analytics`。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 权限 | `-Dtest=AnalyticsIntegrationTest` | 0 | 通过 | `onlyAdminCanRead`：管理员 200 且五区块都在、学习者 403、匿名 401；6 执行 / 0 失败 |
| AC-002 六链结构 | 同上 | 0 | 通过 | `sixFlowsWithFourNodes`：key 依次为 knowledge/digest/ai/learning/resume/sql，每条 **4 节点 3 边** |
| AC-003 断点识别 | `-Dtest=AnalyticsBrokenRuleTest` | 0 | 通过 | 5 执行 / 0 失败：`[55,20,1,0]` 的 index 3 判为断点；`[0,0,0,0]` 与 `[10,0,0,0]` 的 index 2 **不判**断点；首节点永不判 |
| AC-004 三态区分 | `-Dtest=AnalyticsIntegrationTest` | 0 | 通过 | `emptyFeaturesAreZeroNotMissing`：训练营 `state=zero`（已采集但 0）；`notCollectedIsDistinct`：SQL 链末节点 `state=not-collected` **且 broken=false** |
| AC-004b 埋点可用 | `-Dtest=AnalyticsInstrumentationTest` | 0 | 通过 | 3 执行 / 0 失败：点击 POST 一次表多一行、**不存在的条目也 200 且落行**（无外键是刻意的）；空 `q` 不记、带 `q` 记一条且 `result_count` 等于接口返回的 `total`；150 字关键词截断到 100 |
| AC-005 时序与切桶 | `-Dtest=AnalyticsIntegrationTest` | 0 | 通过 | `dailyUsesBeijingDays`：14 天窗口、每天 5 条 series 全部有点（含 0）、日期可解析为 ISO LocalDate；切桶用 `CONVERT_TZ(...,'+00:00','+08:00')`，与 `edition_date` 同源 |
| AC-006 监控降级 | 同上（`prometheus-url` 指向 127.0.0.1:1） | 0 | 通过 | `monitoringDownDoesNotBreakBusiness`：`reachable=false` 且 requestRate 为 null，同时 flows/volume/usage/ai **全部非空** |
| AC-007 性能 | `-Dtest=AnalyticsPerformanceTest` | 0 | 通过 | 按生产量级灌数（速递 600 / AI 200 / 浏览 200 / 点击 300 / 搜索 150）后 20 次采样：**P50 50ms、P95 59ms、max 63ms**，预算 800ms |
| AC-008 响应式 | `pnpm exec playwright test e2e/analytics.spec.ts` | 0 | 通过 | 「三档视口都不出现横向滚动」在 1920/1280/390 三档断言 `scrollWidth - clientWidth ≤ 1` |
| AC-009 导航入口 | 同上 | 0 | 通过 | `getByRole('link', { name: '数据大屏' })` 可点并进入页面；导航项在 `AdminShell` 的 MENU、面包屑在 CRUMBS、路由在 `router.tsx` |
| AC-010 零回归（后端无需登录的切片） | `mvn -B -T 1C -pl '!paideia-app' test` | 0 | 通过 | **352 执行 / 0 失败 / 14 跳过**（含 `paideia-ai`、`paideia-knowledge` 全量） |
| AC-010 零回归（app 侧 ops/分析/守卫） | `-Dtest='ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,Ops*Test,Analytics*Test'` | 0 | 通过 | **91 执行 / 0 失败**；含 `ModularityTest`（跨模块边界）与三条迁移守卫 |
| 管理端 e2e（本项 5 条） | `pnpm exec playwright test e2e/analytics.spec.ts` | 0 | 通过 | **5 passed / 58.1s**：五区块、六链、SQL 未采集、监控降级、三档视口 |
| 管理端类型检查 | `pnpm --filter @paideia/admin typecheck` | 0 | 通过 | tsc 无输出（`noUncheckedIndexedAccess` 下的一处索引访问已修） |
| 学习者端类型检查 | `pnpm --filter @paideia/app typecheck` | 0 | 通过 | tsc 无输出 |
| 反向验证：断点规则 | 把 `isBroken` 的 `index > 0` 守卫去掉跑 `-Dtest=AnalyticsBrokenRuleTest` | 1 | 通过 | `firstNodeIsNeverBroken` **ArrayIndexOutOfBounds: Index -1 out of bounds**；恢复后 5/0 |
| 反向验证：点击埋点吞异常 | 临时移除 `DigestPage` 的 `onClick` 后 `pnpm --filter @paideia/app typecheck` | 0 | 通过 | 证明类型层面两版都成立（埋点是运行时行为，类型检查证明不了它）；恢复后与提交一致 |

## 一、实测发现并修掉的两处

### 1. `mcpGroup` 在 Prometheus 不可达时报 OK（不是本项引入，但被本项回归抓到）

`9313a8d`（WORK-044）给运维摘要加了第 8 个分组 `mcp`，状态硬编码 `OpsStatus.OK`，而文案已经
如实写"累计调用 **未知**"。结果是**绿色的卡片配"未知"**——正是这个项目最忌讳的那种误导
（`OpsSummaryIntegrationTest` 的注释里写着"不能画成 0 或正常"）。

修法：三个指标全查不到时 `status=UNKNOWN`。同时把测试里写死的 `hasSize(7)` 改成 8
（WORK-044 加分组时漏了这条断言，那之后这个测试在 master 上一直是红的）。

### 2. 时序补齐空白天

`volume.daily` 最初只输出"有数据的那几天 × 有值的 series"，导致空库里直接返回空数组，
前端画不出任何柱子。改成**14 天 × 5 series 全部铺满**（缺的补 0）：只画有数据的点会让折线
在空白处"跳跃"，看起来像数据断了而不是那天真的没有活动。

## 二、口径与设计取舍（实测确认）

- **`knowledge_search_log.result_count` 不断言"随机词必为 0"**。知识库全文索引用 `ngram` parser
  （默认 2 字切词），长 ASCII 随机串会被切成二元组，**随机词也可能命中**——实测两次：中文
  「必然搜不到」返回 1 条，`zz+hex+qq` 也返回 1 条。所以测试改成断言"记录的值 == 接口返回的
  total"，那才是 AC 真正要求的（"记的数与用户看到的数一致"）。
- **速递点击表没有外键**，与 `knowledge_entry_views` 相反（后者有 `fk_kev_entry`，性能测试
  第一次跑就被它挡住了）。点击记录要独立存活：`digest_items` 会被保留期清理，连带删点击会
  让趋势凭空掉一截。
- **`AnalyticsService.isBroken` / `stateOf` 抽成静态纯函数**，就是为了能单测——"上游也是 0"
  这条最容易在改动中被漏掉，而它决定一条从没跑过的链会不会被涂成一片红。

## 三、生产部署与实测

**部署**：`release.ps1 -WithWeb`（全量，含前端）**141.6 秒**；随后修 `campTasks` 硬编码那一次用
`-SkipWeb` **59.7 秒**。生产当前 `paideia_build_info = dfb2ea1`，六个单元
（paideia/prometheus/grafana/loki/alloy/tempo）全部 active，`/actuator/health` 200。

**迁移**：`V20261027170000__create_analytics_events.sql` 在生产库执行成功
（`flyway_schema_history.success=1`），`digest_item_clicks` 与 `knowledge_search_log` 两张表已建。

**端点鉴权**：`GET /api/v1/ops/admin/analytics/overview` 无令牌 **401**。

**数字核对**（直接对生产库跑同一批聚合 SQL，2026-10-27 17:52）：

| 指标 | 生产值 | 说明 |
| --- | --- | --- |
| 已发布条目 | 240 | 需求文档写 187 是当天早些时候的值，之后有新增 |
| 分类 | 24 | — |
| 浏览总次数（SUM view_count） | 515 | 行数是 55，页面上分开算 |
| 速递条目 | 535 | — |
| 速递点击 | **0** | 埋点刚上线，符合预期 |
| AI 运行 | 57 | — |
| AI 会话 / 消息 | 30 / 51 | — |
| 积分流水 | 50 | — |
| 学习进度 | 20 | — |
| **答题** | **1** | 断点的真实来源：浏览 515 次、答题 1 次 |
| 掌握度 / 复习会话 | 1 / 3 | — |
| 简历 / 编辑 | 37 / 38 | — |
| SQL 运行 | 50 | — |
| 搜索记录 | **0** | 埋点刚上线 |
| 笔记 | 7 | — |
| 账号 | **2** | 所有「每用户」指标的分母 |
| 训练营试卷 / 作答 | 0 / 0 | 真查库后确实是 0 |
| **面试评估** | **1** | **之前界面硬编码"0 条（表为空）"，是错的** |

### 生产实测发现的第三处缺陷

`usage()` 里训练营与面试评估原本是两个**硬编码**的 `Feature(... ZERO, "0 条（表为空）")`。
我写需求时查到生产库这两张表都是 0，就直接写死了——**上线后核对时发现面试评估已经有 1 条**。
界面会继续说"表为空"，而数据不是空的。

修法：加 `campTasks` / `campAttempts` / `interviewEvaluations` 三条 COUNT，`feature(...)` 按真实值
给 `ok` 或 `zero`。测试也改成断言"状态必须是 ok 或 zero、detail 不得含'表为空'"，而不是断言
某个固定值——那种断言会在数据变化时误报。

这条的教训是通用的：**大屏上任何一个不查库的数字，都会在数据变化后变成谎话**。

## 四、未覆盖与已知限制

1. **没有在真实生产数据上跑过页面**。性能数字来自"按生产量级灌数"的测试库（本机 MySQL），
   不是线上那个库。部署后需要实看一遍，报告里的 P95 不能当成线上值引用。
2. **两项新埋点在生产上必然为 0**（上线首日没人点、没人搜）。界面上会显示"0 次（新增采集）"
   与"还没有搜索记录"两种文案，这是设计行为，不是缺陷。
3. **`knowledge_entry_views` 的"浏览量"是每人每篇一行的累加值**，不是逐次明细。所以
   "条目浏览"节点上的数字是**总次数**（`SUM(view_count)`），而"浏览过的篇数"是行数
   （`COUNT(*)`）——两者在页面上分开算，界面上也写了说明。
4. **反向验证只覆盖了断点规则的一条分支**。已实测：去掉 `index > 0` 守卫会让
   `firstNodeIsNeverBroken` 越界报错（索引 -1），恢复后 5/0。**没有**逐条验证
   `counts[index-1] > 0` 与 `counts[index] == 0` 这两个条件各自被去掉时会不会红——
   前者会让 `zeroAfterZeroIsNotBroken` 变红、后者会让 `nonZeroIsNotBroken` 变红，属于可推断
   但未实测。
5. **学习者端 `digest.spec.ts` 有 4 条红**，全部停在 `digest-date-option` 不可见——
   测试库是空库、没有任何速递数据。这与本项无关（我改的 `onClick` 只在有条目时才挂上），
   已在项目记忆里记录过这个既有现象。速递页的类型检查通过、`tsc` 无输出。
6. **索引盘点结论：不需要新增索引**。`ai_runs(started_at)` 走 `idx_ai_runs_kind` 的前缀以外
   的路径要全扫，但表只有 200 行量级；`digest_items` 按 `created_at` 过滤**没有**对应索引
   （只有 `edition_date`），`resume_documents` 按 `created_at` 过滤只有 `(user_id, updated_at)`。
   在 P95 59ms 的实际结果下加索引是过早优化；**触发重评的条件**是任一表行数到十万级，
   或实测 P95 超过 800ms。
7. **`MODULARITY` 之外没有验证 `analytics` 与 `ops` 同包的副作用**：`AnalyticsQueries` 放在
   `io.github.xiaou61.ops` 而不是独立 `analytics` 包，是因为 `PrometheusClient` 是包级私有。
   代价是 `ops` 包变大了（多了 4 个类），收益是复用了已有的监控客户端、不新建 HTTP 通路。

## 五、提交清单

| 提交 | 内容 |
| --- | --- |
| `730e022` | 两张埋点表迁移、速递点击与搜索词上报、3 条埋点集成测试 |
| `3103bd5` | `AnalyticsQueries` / `AnalyticsService` / `AnalyticsView` / `AnalyticsController` + 断点与端点测试 |
| `d1cc48b` | 大屏页面（5 区块）、导航入口、路由、5 条 e2e |
| `11b452e` | 性能实测（P95 59ms） |
| `530dd1b` | 修 `mcpGroup` 的 OK/未知矛盾 + 更新运维摘要测试的 7→8 |
| `640b9ac` | 验证报告与任务收尾 |
| `dfb2ea1` | 训练营/面试评估改真查库（生产实测发现硬编码 0 是错的） |
