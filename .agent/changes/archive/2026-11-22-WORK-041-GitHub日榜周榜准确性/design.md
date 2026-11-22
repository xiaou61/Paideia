---
artifact: design
work_id: WORK-041
work: GitHub 日榜/周榜准确性
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 15:54:47 +0800
approver_role: CTO
---

# GitHub 日榜/周榜准确性设计

## 系统上下文

```text
GitHub 官方页面 ──(本机反代)──> GithubTrendingSource ──候选──┐
GitHub Search API ──(仅页面失败)──> GithubTrendingSource ─────┤
                                                              ├─> GithubStarHistory ─> d1/d7
                                                              │
                                            DigestCollectionService ─> 一次 AI 调用 ─> digest_items ─> DigestController ─> 学习者端
```

- 外部依赖：`github.com/trending`（页面）、`api.github.com`（Search + star history）。
- 生产机不能直连 `github.com`，通过 127.0.0.1 反向代理；`api.github.com` 直连可达。
- 其他三个分组（news/community/hackernews）的路径不变。

## 组件与职责

| 组件 | 职责 | 变化 |
| --- | --- | --- |
| `GithubTrendingSource` | 抓页面/兜底，组装候选 | 重写：日/周页面、总 star、周期 star、兜底标签与门禁 |
| `GithubStarHistory`（新增） | 调 history 端点，日期映射，返回 `Stats(dayStars, weekStars)` | 新增 |
| `DigestHttp` | HTTP 发送 | 新增「同 host 跟随重定向」方法；保留原 NEVER 行为 |
| `DigestCollectionService` | 去重、截断、AI、入库 | 按组预检；`stars_day/week`；GitHub 标题固定 |
| `DigestMapper` | SQL | insert/findByDate/Row 加两列 |
| `DigestProperties` | 配置 | 页面基地址；cron 默认 08:00 |
| `DigestPrompt` | 提示词 | `digest-v3`；repo 名不改写；可带本周热度 |
| `DigestController` | 查询接口 | `ItemView` 加两字段 |
| `DigestPage` | 展示 | 组内切换、卡片数字、档位重算 |

## 请求或事件流程

**一次采集（08:00）**

1. `GithubTrendingSource.fetch(properties, http, nowMillis)`：
   - `referenceDay = Instant.ofEpochMilli(nowMillis).atZone(UTC).toLocalDate().minusDays(1)`。
   - 抓 `{githubTrendingUrl}?since=daily` 与 `?since=weekly`；解析候选。
   - 日/周按 `owner/repo` 合并；保存 `dailyPeriodStars`、`weeklyPeriodStars`、`language`、`totalStars`。
   - 6 路并发调用 `GithubStarHistory.fetch(repo, token, http, referenceDay)`。
   - `dayStars = stats != null ? stats.dayStars() : dailyPeriodStars`；
     `weekStars = stats != null ? stats.weekStars() : weeklyPeriodStars`。
   - 选择集合 = 日榜前 10 ∪ 周榜前 10（按 repo 去重），按 `dayStars` 降序返回。
   - 页面两条都空：走 Search，`sourceName="GitHub 新项目"`，按门禁过滤，按 `dayStars` 降序返回。
2. `DigestCollectionService`：批内去重 → 按组预检 → `takeByGroup` 截断 → 一次 AI → 入库。
3. `digest_items.heat = d1`；`stars_day = d1`；`stars_week = d7`。

**一次查询**

1. `GET /api/v1/digest?date=...` 返回所有分组，按 `heat DESC`。
2. 前端 GitHub 组默认「今日」：按 `starsDay` 降序（服务端顺序已是）；切「本周」：按 `starsWeek` 降序重排。
3. 其他分组不受影响。

## 接口与数据

### 外部端点

```http
GET /repos/{owner}/{repo}/stargazers/history?per_page=2
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2026-03-10
Authorization: Bearer <token>   # 配置为空时省略
```

返回（最新一周在前）：

```json
[{"week":1792886400,"total":107,"days":[107,0,0,0,0,0,0]}]
```

- `week` 是「该周周一之前那个周日」的 epoch 秒；`days[0]` 是该周周一，`days[6]` 是周日。
- 日期映射：`dayDate = Instant.ofEpochSecond(week).atZone(UTC).toLocalDate().plusDays(i + 1)`。
- `d1 = map.getOrDefault(referenceDay, 0)`；`d7 = sum(map.getOrDefault(referenceDay - i, 0))`，`i=0..6`。
- 301/302/307/308：只跟随 `Location` 的 host 等于 `api.github.com` 的重定向，最多 3 次；否则失败。

### 页面

```http
GET {githubTrendingUrl}?since=daily
GET {githubTrendingUrl}?since=weekly
User-Agent: PaideiaDigest/1.0
```

- 每个 `<article>` 块内取 `<h2>` 里的 `owner/repo`；描述取 `p.col-9`；语言取 `itemprop="programmingLanguage"`；总 star 取 `a[href$="/stargazers"]`；周期 star 取 `N stars today` / `N stars this week`。
- 赞助商/话题链接不得被当成仓库。

### 内部接口

```java
// GithubStarHistory
record Stats(int dayStars, int weekStars) {}
Stats fetch(String repo, String token, DigestHttp http, LocalDate referenceDay);

// GithubTrendingSource
List<FetchedItem> fetch(DigestProperties properties, DigestHttp http, long nowMillis);

// FetchedItem（字段顺序固定）
record FetchedItem(String title, String url, String summaryHint,
                   Integer heat, Integer starsWeek, String meta,
                   long publishedAt, String sourceKey, String sourceName, String groupKey) {}

// DigestMapper
int insert(..., Integer heat, Integer starsDay, Integer starsWeek,
           String meta, ...);
```

### API 返回

`ItemView` 在 `heat` 后增加：

```json
{ "starsDay": 242, "starsWeek": 1203 }
```

非 GitHub 分组两字段为 `null`；旧客户端忽略即可。

## 持久化与迁移

迁移文件：`backend/paideia-app/src/main/resources/db/migration/V20261026160000__digest_github_signal.sql`

```sql
-- 同一仓库允许跨天重复；同一天仍只一条
ALTER TABLE digest_items
  DROP INDEX uk_digest_items_fingerprint,
  ADD UNIQUE KEY uk_digest_items_edition_fingerprint (edition_date, fingerprint);

ALTER TABLE digest_items
  ADD COLUMN stars_day  INT NULL AFTER heat,
  ADD COLUMN stars_week INT NULL AFTER stars_day;
```

- 现有数据满足新唯一键（旧键更严格），无需回填。
- `heat` 保留原语义：GitHub 为 `d1`，其它为源信号或 AI 分；`stars_day/stars_week` 仅 GitHub 非空。
- 预检按组：GitHub `[today, today]`；其它 `[today-1, today+1]`。
- 索引 `idx_digest_items_edition (edition_date, group_key, heat)` 不变。

## 失败处理与恢复

| 失败 | 行为 |
| --- | --- |
| 页面日榜/周榜任一失败 | 用另一页；两页都失败走 Search 兜底 |
| 页面两页都失败 | `sourceName="GitHub 新项目"`；Search 候选套门禁；日志点名 |
| 单条 history 失败 | 回退该条的页面周期 star；记录失败计数 |
| 全部 history 失败 | 全部回退页面周期 star；日志告警 |
| history 301 跨 host | 拒绝，视为该条失败 |
| 仓库 404/改名 | 跳过该条 |
| 反向代理挂 | 等同页面不可达；走兜底 |
| AI 失败 | 既有降级：`summary_state=degraded` |
| 并发两个实例 | 唯一键 `(edition_date, fingerprint)` 仍兜底；重复异常按已存在处理 |

## 安全与权限

- token 只从 `DigestProperties.githubToken` 读取；不写日志、不进响应、不进仓库。
- 页面基地址是配置项，不是用户输入；反向代理只监听 `127.0.0.1`。
- 重定向只允许同 host `api.github.com`，防止带 Authorization 的请求被引到第三方。
- 页面解析的输出经过长度截断（描述 200 字、标题 512、meta 255），沿用现有边界。
- 公开仓库纪律：仓库内只留环境变量占位，不写代理 IP/服务器地址。

## 可观测性

日志（`DigestCollectionService` / `GithubTrendingSource` / `GithubStarHistory`）：

- `GitHub 候选：日榜 N、周榜 M、去重 K；history 成功 X、失败 Y`
- `GitHub 页面不可达（daily/weekly），改用 Search：N 条（门禁后 M）`
- `GitHub star 历史失败（repo）：<原因>`
- 既有 `ai_runs` 记录不变（一次 AI 调用一条）。

## 实施顺序

1. `GithubStarHistory` + `DigestHttp` 重定向 + 单测（日期映射、301、缺失）。
2. 页面解析（日/周）+ 候选合并 + 单测。
3. `GithubTrendingSource` 集成（history 并发、兜底门禁、标签）+ 单测。
4. 迁移 + `DigestMapper`/`DigestController` + 集成测试（跨天/同日/索引）。
5. `DigestCollectionService` 按组预检、`stars_day/week`、标题固定 + 单测。
6. 前端类型、切换、卡片、档位 + typecheck。
7. 配置（cron/基地址）+ 反代配置/部署说明（部署单独授权）。
8. 验证与报告。

## 测试策略

| 层 | 测试 | 覆盖 |
| --- | --- | --- |
| 单元 | `GithubStarHistoryTest` | 日期映射（周一/周日坑）、301、404、空历史、d7 边界 |
| 单元 | `GithubTrendingSourceTest` | 日/周页面、赞助商、总 star、history 失败回退、兜底门禁 |
| 单元 | `DigestCollectionServiceTest` | 按组预检、标题固定、stars 字段 |
| 集成 | `DigestIntegrationTest` | 迁移/索引、跨天重复、同日拒绝、API 字段 |
| 前端 | `pnpm -r typecheck` | 类型与组件 |
| e2e | `apps/app/e2e/digest.spec.ts` | GitHub 组切换（若本地数据可用） |

反向验证：

- 日期映射：把 `plusDays(i + 1)` 改成 `plusDays(i)`，映射用例必须红。
- 跨天唯一键：把唯一键改回 `fingerprint`，跨天用例必须红。
- 兜底门禁：把门禁去掉，垃圾候选用例必须红。

## 需求追踪

| REQ | 设计元素 | 验收 |
| --- | --- | --- |
| REQ-001 | 页面日/周候选 | AC-001 |
| REQ-002 | `GithubStarHistory` + 日期映射 | AC-003/AC-007/AC-008 |
| REQ-003 | `heat=d1`、前端 `starsWeek` 排序 | AC-001/AC-006 |
| REQ-004 | 组内切换 + 卡片 | AC-006 |
| REQ-005 | 复合唯一键 + 按组预检 | AC-004/AC-012 |
| REQ-006 | 兜底标签 + 门禁 + history 回退 | AC-002/AC-007 |
| REQ-007 | cron 08:00 + UTC 完整日 | AC-010 |
| REQ-008 | 反代配置 | AC-011 |
| REQ-009 | 6 路并发 + 超时 | 性能/日志 |
| REQ-010 | 日志 | 手工检查 |
| REQ-011 | 迁移两列 + 唯一键 | AC-005/AC-009 |
| REQ-012 | `ItemView` 两字段 | AC-005 |

## 待决定事项

无。
