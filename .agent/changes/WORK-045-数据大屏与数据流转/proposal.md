---
artifact: proposal
work_id: WORK-045
work: 数据大屏：全链路数据流转与运营视图
status: approved
mode: strict
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 17:05:10 +0800
approver_role: CTO
---

# 数据大屏方案

## 摘要

在管理端新增一页 `/analytics`，用**一张六链泳道图 + 四个配套区块**把「数据从哪来、经过谁、落在哪、被谁用」画出来：节点上标真实行数，断点用警示色标出。

后端加**一个**只读聚合接口 `GET /api/admin/analytics/overview`（ADMIN），五个区块一次返回。数据来源两层——MySQL 聚合（业务量与流转）与 Prometheus 代理（实时速率与错误率），**两层失败互不牵连**。

按用户 2026-10-27 的三项裁决：**只给管理员**、**允许新增埋点**（但只补两项）、**60 秒轮询**。

## 推荐方案

### 为什么是"泳道图"而不是"一堆图表"

用户要的是**数据流转**，而流转的语义是"从哪到哪"。一堆独立的折线图恰恰丢掉这个语义：它能告诉你"AI 调用 55 次"，但说不出"535 条速递只触发了 1 次 AI 压缩"。

所以主视图必须是**有向的**：源 → 处理 → 落库 → 消费。六条链各一行横向铺开，节点标真实计数，边上标通过率。

### 六条链的具体形状（数字为 2026-10-27 生产实测）

| 链 | 源 | 处理 | 落库 | 消费 |
| --- | --- | --- | --- | --- |
| ① 知识内容 | 管理端录入 | 分类/概念挂载 | `knowledge_entries` **187** | `knowledge_entry_views` **55** + 搜索 `knowledge_search_log`（新） |
| ② 每日速递 | RSS/GitHub/HN | AI 压缩 | `digest_items` **535** | `/digest` → `digest_item_clicks`（新） |
| ③ AI 对话 | 用户提问 | 工具循环 | `ai_runs` **55** + `ai_run_spans` **5** | `ai_credit_ledger` **50** |
| ④ 学习闭环 | 条目浏览 55 | `learning_progress` **20** | `question_attempts` **1** | `concept_mastery_state` **1** → `review_sessions` 3 |
| ⑤ 简历 | 上传 | AI 解析/优化 | `resume_documents` **37** | `resume_edit_history` **38** |
| ⑥ SQL 练习 | 数据集 | 沙箱执行 | `code_runs` **50** | 结果查看（不采集，见下） |

链 ④ 的 `question_attempts = 1` 是**真实功能空转**，不是图表 bug——它恰恰是这页最有价值的一格。

### 为什么手写 CSS + SVG 而不是图表库

需要画的只有两种：

1. **有向泳道图**（6 行 × 4 节点 + 边）——这是布局问题，不是图表问题。节点位置固定、箭头静态，用 `flex` + 伪元素画箭头即可。
2. **按天柱状**（4 条线 × 14 天）——一个 `div` 高度数组，或 20 行 SVG。

为这两种图引入 ECharts（约 1MB）或 Recharts（约 400KB），是拿首屏体积换一点开发便利。**更关键的是**：`@paideia/ui` 的令牌（`--radius`、语义色、`tile-surface`）是设计一致性的来源，图表库自带配色与字体栈，接进来必然要写一堆覆盖。

仓库里的 `mermaid` 是 devDependency（文档用），运行时不可用。

### 后端：一个接口，五个区块

```json
{
  "collectedAt": "2026-10-27T09:00:00Z",
  "flows":      [{ "key": "knowledge", "title": "...", "nodes": [{"label","count","state"}], "edges": [{"ratio"}] }],
  "volume":     { "tables": [{"name","rows","state"}], "daily": [{"date","series","count"}] },
  "usage":      { "features": [{"name","state":"active|zero|not-collected","detail"}] },
  "ai":         { "byKind": [...], "successRate": 0.98, "credits": {"granted","spent","balance"} },
  "monitoring": { "reachable": true, "requestRate": 0.4, "errorRate": 0.0, "p95": 0.03, "aiSuccess": null }
}
```

**五区块一次返回**。分五个接口会让各区块数字来自不同时刻，用户看到"AI 运行 55 次"与"积分消耗 50 笔"对不上会以为是 bug。

### 两项新埋点（用户已授权，但只补两项）

调研后确认：其它"未采集"项**要么已有数据只是没聚合**，要么成本远超价值。

| 埋点 | 表 | 回答什么问题 | 为什么现有表答不了 |
| --- | --- | --- | --- |
| 速递点击 | `digest_item_clicks(user_id, item_id, clicked_at)` | 535 条速递有没有人看 | `digest_items` 只有内容，没有任何浏览痕迹；点开是外链，连 `knowledge_entry_views` 都不会经过 |
| 知识库搜索词 | `knowledge_search_log(user_id, keyword, result_count, searched_at)` | 用户在搜什么、**搜不到什么** | `q` 参数只进 SQL，从不落库；零结果搜索是最有价值的内容缺口信号 |

**不做的**：通用事件框架、前端 SDK、点击热图、停留时长、滚动深度。这些要一套采集协议与大量前端埋点，与"一屏看清数据流"无关。

写入纪律沿用 `EntryViewController` 的成熟模式：POST + 归属取自令牌 + **失败不阻主流程**（速递点击是外链跳转，埋点挂了也得让人跳走）。

### 时间桶按北京自然日

`digest_items.edition_date` 按北京时间切（速递 08:00 采集）。按 UTC 切会把"10-27 的速递"算到 10-26 那格。复用 `DigestProperties.zoneId()`——它已经处理了配置笔误退回系统默认。

### 聚合走直接 SQL，不走业务模块

`ModularityTest` 禁止 `paideia-app` 引用其它模块的 `internal` 包。三个选项：

1. 给每个模块加公开统计接口 → 新增 6 个公开方法只为大屏。
2. `paideia-app` 直接写只读 SQL → 一行注解，无新依赖。
3. 建独立 `analytics` 模块依赖所有业务模块 → 违反依赖方向。

选 2。只读聚合不修改任何模块数据，口径定义在大屏侧（与 `AiRunsOverviewPage` 共享 SQL **语义**而非 Java 代码）。

## 参考项目与架构

调研于 2026-10-27。这些不是要装的东西，是**设计参照**：

- **Grafana Node Graph 面板**：有现成的"有向图 + 节点指标"，但配置来自 trace 数据，不适合 MySQL 聚合。可取之处是**节点颜色编码状态 + 边宽表示流量**这两条视觉规则，照做。
- **OpenTelemetry Service Graph（spanmetrics connector）**：把 trace 聚合成服务依赖图。"边表示流向、粗细表示量"完全一致。
- **dbt lineage graph**：数据工程的"数据从哪来"标准可视化（节点=表、边=依赖）。六条链是它的业务化版本。
- **Metabase/Redash 的 Data Model**：给非工程人员看"有哪些表、多少行"。压缩进"业务量"区块。
- **Grafana Kiosk 模式**：`OpsPage` 已在用。**本项不用 iframe**——iframe 需要 Grafana 口令，大屏要一进来就能看。

## 范围

**包含**：管理端 `/analytics`（5 区块 + 导航入口 + e2e）；后端 `AnalyticsController/Service/Queries`；两张埋点表迁移 + 两个上报端点 + 学习者端两处调用；`AnalyticsIntegrationTest`；文档。

**不包含**：新增端口；学习者端可见变化；Grafana 面板改动；历史数据回填；通用埋点框架。

## 备选方案

| 方案 | 优点 | 为什么不选 |
| --- | --- | --- |
| **A. Grafana 再加 dashboard，管理端 iframe** | 不写前端、图表能力最强 | 需要 Grafana 口令；MySQL 数据要先变成 Prometheus 指标（等于给每张表写 exporter）；有向"流转"Grafana 画不好 |
| **B. 独立 `apps/analytics` Vite 应用** | 与 admin 解耦、可全屏无壳 | 多一套构建 + 部署 + 鉴权 + 导航；admin 已有壳与 `@paideia/ui`，收益为零 |
| **C. 现成 BI（Metabase/Superset）接只读账号** | 拖拽出图、零前端 | 多一个常驻服务与一套账号体系；**只读账号直连生产库**是新安全面；BI 画不出业务语义泳道 |
| **D. 用 mermaid 画泳道** | 仓库已有依赖 | devDependency 不可运行时用；SVG 无法交互（点击跳转）、无法嵌数字、主题不同源 |
| **E. 本方案** | 无新依赖、无新端口、令牌同源、可交互可下钻 | 手写布局有一点工作量；图表能力限于柱/线/泳道（够用） |

## 仓库影响

| 层 | 文件 | 变更 |
| --- | --- | --- |
| 后端 | `paideia-app/.../analytics/AnalyticsController.java` | 新建，一个 GET，`@ApiAccess(ADMIN)` |
| 后端 | `paideia-app/.../analytics/AnalyticsService.java` | 新建，编排五区块 + 时区切桶 + 断点判定 |
| 后端 | `paideia-app/.../analytics/AnalyticsQueries.java` | 新建，`@Mapper` 只读聚合 |
| 后端 | `paideia-app/.../analytics/AnalyticsView.java` | 新建，响应类型 |
| 后端 | `paideia-app/.../db/migration/V2026xxxx__create_analytics_events.sql` | 新建，两张埋点表 |
| 后端 | `paideia-ai/.../digest/web/DigestController.java` | 加 `POST /items/{id}/click` |
| 后端 | `paideia-knowledge/.../web/KnowledgeReadController.java` | `/entries` 带 `q` 时记搜索 |
| 后端 | `paideia-ai/.../digest/DigestClickMapper.java` | 新建 |
| 后端 | `paideia-knowledge/.../entry/SearchLogMapper.java` | 新建 |
| 后端 | `AnalyticsIntegrationTest.java`、`DigestClickTest.java`、`SearchLogTest.java` | 新建 |
| 前端 | `apps/admin/src/pages/AnalyticsPage.tsx` | 新建 |
| 前端 | `apps/admin/src/router.tsx`、导航配置 | 加路由与导航项 |
| 前端 | `apps/app/src/pages/DigestPage.tsx` | 点开外链时上报 |
| 前端 | `apps/app/src/pages/KnowledgeListPage.tsx`（或实际搜索页） | 提交搜索时上报 |
| 前端 | `apps/admin/e2e/analytics.spec.ts` | 新建 |
| 部署 | — | **无新单元、无新端口** |

## 交付拆分（四个阶段）

- **P1 埋点地基**：迁移两张表 + 两个上报端点 + 学习者端两处调用 + 单测。**先做这个**，因为它要尽早开始攒数据——大屏做好时若点击表还是空的，就白做了一屏。
- **P2 数据面**：`AnalyticsQueries`（六链 + 表量 + 使用度 + AI 汇总 + 新埋点派生）+ `AnalyticsService` + `AnalyticsIntegrationTest`。此阶段不写前端，用测试固化每个数字的口径。
- **P3 视图**：`AnalyticsPage` 五区块 + 断点警示 + 下钻 + 三档响应式 + 导航入口。
- **P4 收口**：e2e + 性能实测（P95 < 800ms）+ 文档 + 部署 + 生产实看。

## 风险与缓解措施

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| **口径分歧**：大屏 AI 数字与 `AiRunsOverviewPage` 对不上 | 用户不再信任任一页 | AI 区块复用同一套 SQL 语义；集成测试断言两处对同一时间窗给出相同总数 |
| **埋点表上线即空** | 大屏"速递点击"格是 0，看起来像没做 | 界面区分"表建好了但没数据"与"根本没采集"；文档写明数据要慢慢长 |
| **聚合慢**：10+ 张表，`refresh_tokens` 4609 行 | 首屏卡住 | 每条 SQL 有界（时间窗）；实测 P95 进验收；超 800ms 就分区块懒加载 |
| **埋点阻断主流程** | 速递点不开、搜索失败 | 写入 `try/catch` 只记 WARN，不抛业务异常；专门一条反向验证 |
| **生产库被大屏打慢** | 影响真实用户 | 全只读；60 秒轮询；一次查询五区块；盘点索引 |
| **`q` 为空/超长** | 脏数据或日志膨胀 | 只在非空且长度 ≤100 时记；截断而非拒绝 |
| **重复上报**（双击、返回再点） | 点击数据虚高 | 接受（这是"点击次数"不是"人数"）；界面明确写"点击次数"；按 `user_id` 去重的口径另算 |

## 验收映射

| AC | 阶段 | 检查 |
| --- | --- | --- |
| AC-001 权限 | P2 | `AnalyticsIntegrationTest` 200/403/401 |
| AC-002 六链数字准确 | P2 | 集成测试把每个节点与独立 SQL 对比；生产抽 6 个手核 |
| AC-003 断点识别 | P2/P3 | 断言链 ④ 第 3 步 `state=zero`；页面看警示色 |
| AC-004 三态区分 | P2/P3 | 断言 `camp_*` 为 `zero`；速递点击为 `active`（表存在） |
| AC-004b 埋点可用 | P1 | `DigestClickTest`、`SearchLogTest`；写库失败仍跳转 |
| AC-005 时序与切桶 | P2/P3 | 断言 `daily` 日期与 `edition_date` 对齐；< 3 点显示"数据不足" |
| AC-006 监控降级 | P2 | `prometheus-url` 指死端口，断言 `monitoring.reachable=false` 且其余四块非空 |
| AC-007 性能 | P4 | 实测 P95 写报告 |
| AC-008 响应式 | P4 | e2e 三档视口无横向滚动 |
| AC-009 导航入口 | P4 | e2e 从导航点进 |
| AC-010 零回归 | P4 | 管理端 e2e 全量、`Ops*Test`、学习者端受影响文件 |

## 待决定事项

无。三项已由用户 2026-10-27 拍板；两项细化选择（只补两项埋点、写入尽力而为）记在 `requirements.md` 的「约束与依赖」，批准本方案即视为确认。
