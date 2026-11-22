---
artifact: design
work_id: WORK-045
work: 数据大屏：全链路数据流转与运营视图
status: approved
mode: strict
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 17:05:27 +0800
approver_role: CTO
---

# 数据大屏设计

## 决策与约束

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D-01 | 大屏放管理端 `/analytics`，`@ApiAccess(ADMIN)` | 用户裁决：只给管理员 |
| D-02 | 单页五区块，五区块一次接口返回 | 分区请求会让数字来自不同时刻，用户会当成 bug |
| D-03 | 泳道用 flex + 内联 SVG/CSS，不引图表库 | 只需要柱/线/泳道；图表库自带主题与 `@paideia/ui` 令牌冲突 |
| D-04 | 聚合用 `paideia-app` 内直接只读 SQL | `ModularityTest` 禁止跨模块引 internal 包；只读聚合不复制业务规则 |
| D-05 | 只补两项埋点：速递点击、知识库搜索词 | 其它"未采集"项已有数据只是没聚合，或成本远超价值 |
| D-06 | 埋点写入尽力而为，失败只记 WARN | 速递点击是外链跳转，不能因埋点失败拦住用户 |
| D-07 | 搜索埋点在**服务端**记，不走前端上报 | `/entries?q=` 已经是唯一入口（页头搜索框与首页搜索都跳到这里），服务端记不需要改前端，且漏记率更低 |
| D-08 | 60 秒轮询 | 用户"怎么都可以"；10 秒级会持续压库 |
| D-09 | 时间桶按 `DigestProperties.zoneId()` | 与速递 `edition_date` 同源，否则"今天"错位一天 |

## 模块与文件

```
backend/paideia-app/src/main/java/io/github/xiaou61/analytics/
  AnalyticsController.java      GET /api/v1/admin/analytics/overview  (@ApiAccess ADMIN)
  AnalyticsService.java         五区块编排、断点判定、时区切桶
  AnalyticsQueries.java         @Mapper：只读聚合 SQL
  AnalyticsView.java            响应 record（Flows/Volume/Usage/Ai/Monitoring + 内部类型）

backend/paideia-app/src/main/resources/db/migration/
  V2026xxxxxxxxxx__create_analytics_events.sql    两张埋点表

backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/digest/
  DigestClickRecorder.java      记一次速递点击（接口 + 实现合一，单方法）
backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/digest/web/
  DigestController.java         加 POST /items/{id}/click

backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/entry/
  SearchLogRecorder.java        记一次搜索（关键词 + 命中数）
backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/web/
  KnowledgeReadController.java  /entries 带 q 时记一条

frontend/apps/admin/src/pages/AnalyticsPage.tsx
frontend/apps/admin/src/router.tsx / 导航
frontend/apps/app/src/pages/DigestPage.tsx      点外链时上报
```

## 数据模型

### 迁移：两张埋点表

```sql
CREATE TABLE digest_item_clicks (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  item_id    BIGINT      NOT NULL,
  clicked_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_dic_clicked (clicked_at),
  KEY idx_dic_item (item_id, clicked_at),
  KEY idx_dic_user (user_id, clicked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_search_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  keyword       VARCHAR(100) NOT NULL,
  result_count  INT          NOT NULL,
  searched_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ksl_searched (searched_at),
  KEY idx_ksl_keyword (keyword),
  KEY idx_ksl_user (user_id, searched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

三条设计说明：

1. **没有外键**。`digest_items` 会被采保留期清理掉，点击记录要独立存活（否则删速递时连带删历史点击，趋势会凭空掉一截）。`item_id` 保留原值，聚合时左连接、连不上就归到"已删除条目"。
2. **`keyword` 存截断后的原文，不存 hash**。大屏要显示"用户在搜什么"，hash 做不到；但只存 100 字符且只在 `q` 非空时写入，避免脏数据。
3. **索引围绕查询**：`(clicked_at)` 给按天聚合，`(item_id, clicked_at)` 给热门条目，`(user_id, clicked_at)` 给保留期清理。搜索表同理，另加 `(keyword)` 给高频词排序。

### 上报端点

| 端点 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/digest/items/{id}/click` | AUTHENTICATED | 记一次点击；条目不存在也返回 200（见下） |
| `GET /api/v1/knowledge/entries?q=...` | AUTHENTICATED | 已有端点；带 `q` 时内部记一条搜索 |

速递点击**条目不存在也返回 200**：用户点的是外链，前端拿到任何非 2xx 都不该影响跳转；而这条埋点本身对用户没有价值，报错只会制造噪音。

搜索埋点在**同一次请求内**记：`result_count` 就是本次返回的 `total`，不需要额外查询，也不会与实际结果不一致。

## 接口

### `GET /api/v1/admin/analytics/overview`

无查询参数（窗口固定：近 14 天时序、近 7 天增量、当前总量）。响应：

```json
{
  "collectedAt": "2026-10-27T09:00:00Z",
  "flows": [
    {
      "key": "knowledge",
      "title": "知识内容",
      "nodes": [
        { "label": "管理端录入", "count": 187, "state": "ok", "unit": "条" },
        { "label": "分类/概念挂载", "count": 22, "state": "ok", "unit": "条" },
        { "label": "条目浏览", "count": 55, "state": "ok", "unit": "次" },
        { "label": "搜索", "count": 0, "state": "zero", "unit": "次" }
      ],
      "edges": [ { "ratio": 0.29 }, { "ratio": 1.0 } ]
    }
  ],
  "volume": {
    "tables": [ { "name": "refresh_tokens", "rows": 4609, "delta7d": 1200, "state": "append-only" } ],
    "daily": [ { "date": "2026-10-27", "series": "ai_runs", "count": 3 } ]
  },
  "usage": {
    "accounts": 2,
    "features": [
      { "name": "AI 对话", "state": "active", "detail": "55 次运行" },
      { "name": "答题", "state": "zero", "detail": "1 条记录（近乎未使用）" },
      { "name": "速递点击", "state": "active", "detail": "0 次（新增采集，尚无数据）" }
    ]
  },
  "ai": {
    "byKind": [ { "kind": "chat", "runs": 30, "ok": 28 } ],
    "successRate": 0.98,
    "credits": { "granted": 200000, "spent": 5000, "balance": 195000 }
  },
  "monitoring": {
    "reachable": true,
    "requestRate": 0.42,
    "errorRate": 0.0,
    "p95Seconds": 0.03,
    "aiSuccessRate": null
  }
}
```

`state` 三态（节点与功能通用）：`ok` / `zero` / `not-collected`。`not-collected` 只在"这项根本没有数据源"时用，本期预计只有 SQL 练习的"结果查看"一处。

### 断点判定规则

一个节点 `state=zero` 当且仅当 `count == 0` **且**它的上游节点 `count > 0`。上游也 0 时不标警示——那说明整条链没跑，不是这一步断了。这条规则放在 `AnalyticsService`，不放前端：判据只该有一处。

## 实现要点

### 聚合 SQL（`AnalyticsQueries`）

全部只读，全部有界。锚点：

```java
// 各表总量：一张表一条 COUNT(*)；12 张关键表
@Select("SELECT COUNT(*) FROM knowledge_entries WHERE status = 'PUBLISHED'")
long publishedEntries();

// 按天时序：一条 SQL 出四类，避免四次往返
@Select("""
    SELECT DATE(started_at) d, 'ai_runs' AS series, COUNT(*) AS c FROM ai_runs
    WHERE started_at >= #{from} GROUP BY d
    UNION ALL
    SELECT DATE(created_at) d, 'digest_items', COUNT(*) FROM digest_items
    WHERE created_at >= #{from} GROUP BY d
    ...""")
```

**时区陷阱**：`DATE(started_at)` 按会话时区切（JDBC 已强制 UTC）。若直接用，会得到 UTC 日而不是北京日。做法：把边界换算成 UTC 瞬时（`Instant`）传入，`GROUP BY` 用 `DATE(CONVERT_TZ(started_at, '+00:00', #{zoneOffset}))`。`CONVERT_TZ` 需要 MySQL 的时区表；**生产上 `mysql.time_zone_name` 可能为空**，所以传偏移量（`+08:00`）而不是时区名，避免依赖时区表。

### 与 `AiRunsOverviewPage` 的口径一致

`TelemetrySummaryMapper.countByKind` 已经是"按 kind 分组计数"。大屏的 `ai.byKind` **复用同一条 SQL**（通过 `AnalyticsQueries` 里的等价查询），并在集成测试里断言：同一时间窗、同一筛选条件下，两处返回的总数相同。

### 监控层降级

`AnalyticsService` 里把五个 Prometheus 查询各自 `try/catch`：任一失败 → `reachable=false` 且那几项为 null；MySQL 区块照常返回。**不能用一个 exception 包住整个方法**——那会让 Prometheus 挂掉时整页空白。

## 前端结构

```
AnalyticsPage
├─ 区块 1：六链泳道（FlowsSection）
│   每行：标题 → 四个节点（含 count 与 state 色）→ 三个边（含通过率）
│   节点可点 → 跳对应明细页
├─ 区块 2：业务量（VolumeSection）
│   上：近 14 天按天柱状（四条 series，legend 可点隐藏）
│   下：表行数列表（按行数排序，标"只增不减"）
├─ 区块 3：功能使用度（UsageSection）
│   三态色块 + 说明；顶部显式写"当前 N 个账号"
├─ 区块 4：AI 与成本（AiSection）
│   kind 分布条 + 成功率 + 积分三数
└─ 区块 5：监控摘要（MonitoringSection）
    四个实时值 + 不可达时的明确提示 + 下钻链接
```

视觉规则（照 Grafana Node Graph 与 dbt lineage 的做法）：

- 节点色：`ok` 用中性 `tile-surface`，`zero` 用 `warning` 语义色，`not-collected` 用虚线边框 + `muted`。
- 边：正常为细实线，`ratio=0` 为虚线红色；`ratio` 越低线越细。
- 数字用 `tabular-nums` 等宽，避免刷新时跳动。

响应式：≥1536 三列，1280–1535 两列，<1024 单列堆叠。**任何断点都不隐藏区块。**

## 失败与降级

| 情况 | 行为 |
| --- | --- |
| Prometheus 不可达 | 监控区块显示"不可达"，其余四块正常 |
| MySQL 查询超时 | 该区块显示"查询超时"，其余区块正常（每区块独立 try/catch） |
| 埋点表还没有数据 | 显示 0 并标注"新增采集，尚无数据"，不是"未采集" |
| 某链上游为 0 | 整条链淡化显示，节点不标警示（不是断点） |
| 页面首次加载 | 骨架屏，不是空白 |

## 测试

- `AnalyticsIntegrationTest`（`@SpringBootTest` + `MockMvc`）：权限三态、五区块结构、断点判定、监控降级、时序日期对齐。
- `DigestClickRecorderTest`：写一次多一行；条目不存在不抛异常。
- `SearchLogRecorderTest`：`q` 空不记；超长截断到 100；`result_count` 与返回总数一致。
- 前端 `analytics.spec.ts`：导航入口、五区块存在、断点警示可见、三档视口无横向滚动。

## 回滚

迁移只加表，不改既有列 → 回滚只需停用管理端路由与两个上报调用。表留着不碍事（无外键、无触发器）。

## 已知限制

- 速递点击只覆盖**站内点击**；用 RSS 客户端直接读的统计不到。界面上要写清。
- 点击表记录的是**点击次数**不是人数；同一人反复点会累加。
- 搜索埋点只覆盖走 `/entries?q=` 的搜索；若将来有别的搜索入口，需要同步改。
- 内部浏览量（`knowledge_entry_views`）是**每人每篇一行累加**，不是明细，所以"按天浏览量趋势"只能是"当天有浏览的篇数"，不是总次数。这一条必须在界面上说明，否则会误读。
