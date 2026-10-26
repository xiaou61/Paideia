# GitHub 日榜/周榜准确性调研（2026-10-26）

状态：调研完成，**未实施**。落地需开 WORK（当前下一个空号是 WORK-041）。
范围：每日速递里的 `github` 分组（`backend/paideia-ai/.../internal/digest/`）与它的前端展示。

---

## 1. 结论（先看这段）

1. **现在的「GitHub 热榜」不是热榜。** 生产日志确认 2026-10-26 07:00 页面通路超时，整组走 Search 兜底；
   Search 兜底 = 「最近 3 天新建、总 star > 50、按总 star 排序」。我把它原样复现后与 GitHub 官方日榜/周榜对账：
   **与官方日榜 0/11 重叠，与官方周榜 0/14 重叠**；30 条里 10 条昨天几乎没人 star，还有 `.github` 仓库和明显的垃圾项目。
2. **不准的根因是信号，不是算法。** 用真实「昨日新增 star」排序，在同一批 120 个样本里
   **前 10 有 9 个是官方热榜仓库、0 个零增量**；而当前排序是 0/11 重叠。
3. **「复杂算法」被回测否掉了。** 我加了基线 z 分（相对自身 28 天均值/标准差）的几种变体，
   与官方榜单的相关性只提升约 **+0.01**，在语言榜上反而变差。日榜按 `d1`（昨日新增）、周榜按 `d7`（近 7 日新增）
   就够了；z 分可以算出来当标签，但**不进排序**。
4. **真正的瓶颈是候选源。** 任何 Search 组合对官方 90 个热榜仓库的召回只有 **0–12/90**。
   只有 trending 页面（或一个很大的自建 watchlist）能发现它们。而生产机当前 DNS 解析到的 GitHub 边缘 IP 被墙，
   换一个 GitHub 边缘 IP 就 200。**页面通路不是可选项，是唯一可行的候选源。**
5. **还有一个比算法更根本的缺陷：`digest_items` 的指纹全局唯一，没有日期。**
   同一个仓库在整个平台历史上只能出现一次；热榜常见的「连续在榜 3 天」根本存不下来。
   这也是兜底窗口从 7 天缩到 3 天的原因——它必须不断找「没见过」的仓库。
   **要做出真正的日榜/周榜，必须先把这个唯一键改成 `(edition_date, fingerprint)`。**
6. **第三方全都不行。** OSS Insight 的 trending 端点已明确返回 `data_quality.status=unavailable`
   （2026-03-01 起因事件采集量跌到基线 0.3%）；HelloGitHub 没有 API、是人工月刊，它的机器人也是按总 star 排序；
   huchenme 的托管 API 已死。只有 GitHub 自己的一手数据可用。

---

## 2. 现状取证：生产到底在跑什么

### 2.1 日志（生产机 `journalctl -u paideia`）

```
Oct 25 07:00:45  速递完成：候选 110 条，已存在 1 条，入库 57 条（降级 0，失败源 0）
Oct 26 07:00:21  WARN  GithubTrendingSource: GitHub trending 页面不可达：抓取失败：
                 https://github.com/trending?since=daily（HttpTimeoutException）
Oct 26 07:00:22  INFO  GithubTrendingSource: GitHub 页面解析无结果，Search API 拿到 30 条
Oct 26 07:01:00  速递完成：候选 123 条，已存在 0 条，入库 60 条（降级 0，失败源 0）
```

- 10-26 是 **HttpTimeoutException**（不是页面改版、不是解析空），整组来自 Search。
- 10-25 没有这条 WARN，说明页面当天是通的。**页面是间歇可达，不是永久坏。**
- 页面失败时代码先等满 20 秒超时，再走 Search；一次采集因此多花约 20 秒。

### 2.2 复现 Search 兜底并和官方榜对账

复现查询（与 `GithubTrendingSource.searchApi` 一致）：`created:>=2026-10-23 stars:>50&sort=stars&per_page=30`，返回 30 条。

同时从生产机抓官方页面（`since=daily` 11 条、`since=weekly` 14 条、5 个语言日榜共 74 条）作为弱标签。

结果：

| 指标 | 数值 |
| --- | --- |
| 当前兜底池 ∩ 官方日榜 | **0 / 11** |
| 当前兜底池 ∩ 官方周榜 | **0 / 14** |
| 当前兜底池中昨日新增 `d1 < 20` | **10 / 30** |
| 当前兜底池中 `d1 = 0` | **8 / 30** |
| 当前兜底池的仓库年龄 | 全部 1–3 天 |
| 官方池 90 个仓库的年龄 | **75–6367 天，0 个 < 7 天** |

当前池里的典型条目（`KillaBoi/StealMyBitcoin-PoC`、`Release-DLSS-5-Swapper/.github`、
`routerhubSI/glm-5.3-abliterated`、`x86rc/unthemidator`、`abckkf321/-mcd-fair-split`）
说明「新建 + 总 star」这个组合天然会捞到刷星/垃圾仓库。

### 2.3 网络取证

| 目标 | 本机 | 生产机 |
| --- | --- | --- |
| `api.github.com` | 通（`gh` 可用） | 通（200，0.23s） |
| `github.com/trending` | 间歇（curl 常 000） | DNS 解析到 `20.205.243.166`，20s 超时 |
| `raw.githubusercontent.com` | 通 | 不通 |
| `codeload.github.com` | — | 通（200，1.5MB） |
| `api.ossinsight.io` | 不通（000） | 通（但见第 6 节，数据不可用） |

三个 DNS（系统、8.8.8.8、223.5.5.5）都返回同一个 `20.205.243.166`。用 `curl --resolve` 换 GitHub 其他边缘 IP
（例如 `20.27.177.113`、`20.200.245.247`、`140.82.113.3`）连续 3 次都是 **200，0.34–3.13 秒**，页面可正常解析。
生产机没有 nginx/caddy，systemd 单元里没有代理环境变量。

> GitHub 公网边缘 IP 会变，具体清单不写死在这里；部署时用 `curl --resolve github.com:443:<ip>` 逐个验证后取可用的。

---

## 3. 信号：GitHub star 历史端点

### 3.1 端点与返回

`GET /repos/{owner}/{repo}/stargazers/history?per_page=N`

- `per_page` 的单位是**周**，最大 30；`page` 最大 100。
- 返回数组，最新一周在前：

```json
[{"week":1792886400,"total":107,"days":[107,0,0,0,0,0,0]}]
```

- `days` 是**每日新增**（不是累计），一周 7 格。
- **坑：`week` 标签是周日，但 `days` 从周一开始。**
  我用三个「创建日期已知」的仓库做了算术核对：
  `rddmwmddr/android-ad-cleaner` 创建于 2026-10-24 05:35Z，总 star 1142，
  返回 `[..., 15, 267]`（周六、周日）加本周 `[860]`（周一），**15+267+860 = 1142 精确相等**。
  按文档/直觉当「周日开头」读，整条日增量会错一天，而且错得很像真的。
- 映射公式：`day_date = week + (index + 1) 天`，`index 0 = 周一`。
- 当天（进行中）的桶是有数据的：今天新建的仓库，star 全部落在 `days[0]`。
- 新建仓库历史不足时补 0，不返回 null。
- 端点是「按创建日统计当前仍存在的 star」，不是事件流水：取消 star 会让历史某天的计数变小，
  所以同一段历史隔几天重算可能略有差异；单日值不会为负，代码按 0 处理缺失即可。

### 3.2 鉴权、重定向、限额、时延

- 带 token：`gh api` 200。裸 `curl` 对 `facebook/react` 返回 **301**（重定向到
  `https://api.github.com/repositories/10270250/stargazers/history`），对 `vercel/next.js`、
  `torvalds/linux` 返回 200。**301 是仓库个例，不是通例，但必须能跟。**
  现有 `DigestHttp` 是 `followRedirects(NEVER)`，直接复用会把这类仓库当失败。
- 限额：计入 `core`，token **5000/小时**，匿名 60/小时。每次 1 个配额。
  生产配置里已有 `github-token`（只核对了键存在，未读取值），因此 25–60 次/天完全够用。
- 时延（20 次顺序调用）：min 1.21s，median 1.61s，max 4.09s。
  90 个仓库顺序约 3 分钟；8 路并发约 20–30 秒。
- 端点 2026-09 上线（工程博客 2026-10-13），只有约一个月；旧的 `/stargazers` 列表已被限制为仓库管理员/协作者可见。
- 不支持「负 star 历史」的完整审计（取消 star 不保留事件），但每日净增量可用。

### 3.3 页面数字 vs API 数字（对账）

| 对比 | 相对误差中位数 | 最大 | 样本 |
| --- | --- | --- | --- |
| 官方日榜 `stars today` vs API 昨日 `d1` | **8.9%** | 36.2% | 11 |
| 官方周榜 `stars this week` vs API 近 7 日 `d7` | **1.2%** | 7.7% | 14 |

结论：周榜几乎完全一致；日榜的页面数字是**滚动 24 小时**，与 UTC 自然日有约 9% 的差。
排序用 API 的 `d1` 足够，且与页面/兜底两条通路保持同一口径。

### 3.4 与采集时刻的时区冲突

- `d1` 的桶是 UTC 自然日；速递在北京时间 07:00 跑（= 23:00 UTC）。
- 此时「上一个完整 UTC 日」是**前天（周六）**；周日桶已有 23/24 小时数据。
- 若严格只用完整日，周一早上会看到周六的数据（滞后一天）；若用当前 UTC 日桶，只少约 4%，且对所有仓库一致。
- **建议把 cron 挪到 08:00 北京（00:00 UTC）**，直接用昨天完整桶，语义最干净。
  保持 07:00 也可以，用当前 UTC 日桶并在卡片标「截至 07:00」。

---

## 4. 回测：公式与候选源

### 4.1 样本

- 官方池：90 个仓库（全局日榜 11 + 周榜 14 + Python/TypeScript/Rust/Go/Java 日榜 74，去重）。
- 当前兜底池：30 个仓库（复现生产查询）。
- 共 120 个，全部拉到 5 周 history。

### 4.2 公式对比

指标说明：`off@10` = 按该公式排序后前 10 里官方仓库数；`rho_global` = 与官方全局日榜顺序的 Spearman；
`rho_lang_avg` = 与 5 个语言日榜顺序的 Spearman 平均；`rho_weekly` = 与官方周榜顺序的 Spearman。

| 公式 | off@10 | rho_global | rho_lang_avg | rho_weekly |
| --- | --- | --- | --- | --- |
| `d1` | 9 | 0.473 | 0.161 | 0.521 |
| `log10(1+d1)` | 9 | 0.473 | 0.161 | 0.521 |
| `log10(1+d1) + 0.6·log1p(z)` | 9 | 0.482 | 0.144 | 0.525 |
| `log10(1+d1) + 0.25·min(z,10)` | 9 | 0.482 | 0.135 | 0.525 |
| `log10(1+d1) + 0.1·min(z,10)` | 9 | 0.473 | 0.162 | 0.525 |
| `log10(1+d7)` | **10** | 0.282 | 0.191 | **0.538** |
| `log10(1+d1) + 0.5·log10(1+d7)` | 9 | 0.418 | 0.172 | 0.512 |
| `log10(1+d1) − 0.3·log10(total+1000)` | 8 | 0.491 | 0.198 | 0.516 |

解读：

- **z 分基本没用**：最好的情况 +0.009，语言榜上还下降。原因很直接——官方热榜里既有
  「8.8 万 star、昨天 +993、基线 396」的老项目，也有「3400 star、昨天 +1292、基线 0」的新项目；
  单靠「相对自身基线」会把老项目的爆发全压掉。GitHub 自己也不是纯相对增速。
- **按总 star 做归一化会赶走真热点**：`rho_global` 略升，但 `off@20` 从 19/20 掉到 12/20。
- **`log(d7)` 最适合周榜**（10/10 官方、rho_weekly 0.538），不适合日榜（rho_global 0.282）。
- 结论：**日榜 `d1`、周榜 `d7`，都取对数只为压缩量纲；不要 z 分、不要总 star 归一化。**

### 4.3 候选源召回（对官方 90 个仓库）

| 查询 | 返回 | 命中官方 |
| --- | --- | --- |
| `created:>=3d stars:>50`（现行兜底） | 100 | **0 / 90** |
| `created:>=7d stars:>10` | 100 | **0 / 90** |
| `created:>=30d stars:>100` | 100 | **0 / 90** |
| `created:>=180d stars:>1000` | 100 | 5 / 90 |
| `pushed:>=1d stars:>3000` | 100 | 12 / 90 |
| `pushed:>=1d stars:>500` | 100 | 12 / 90 |
| trending 页面 | 11–20 | 90 / 90（定义如此） |

**Search API 不可能拼出一个热榜。** 它按总 star 排，命中的是「大」而不是「热」；
按创建时间过滤又只能命中新仓库。这是候选源的结构性限制，不是查询词没调好。

### 4.4 门禁

| 门禁 | 官方 90 个保留 | 兜底 30 个保留 |
| --- | --- | --- |
| `age >= 7 天` | 90 / 90 | **0 / 30** |
| `age >= 14 天` | 90 / 90 | 0 / 30 |
| `forks >= 5` | 90 / 90 | 28 / 30 |
| `forks/star >= 0.5%` | 90 / 90 | 29 / 30 |
| `age >= 7 且 forks >= 5` | 90 / 90 | 0 / 30 |

- 年龄门禁在今天的样本上「零误杀」，但会把 `JustAResearcher/gpu-unlock-ada` 这类
  「3 天 3200 star、424 fork、昨天 +1292」的真爆款也挡掉。
- 建议兜底通路的门禁写成 `age >= 7 OR (d1 >= 1000 AND forks >= 100)`：
  今天能保住 gpu-unlock-ada，挡掉其余垃圾。**页面通路不需要门禁**（GitHub 已经筛过一遍）。
- forks 单独不是好垃圾过滤器（垃圾项目也有 fork）。

---

## 5. 两个结构性缺陷

### 5.1 指纹全局唯一 → 同一个仓库永远只能出现一次

`V20261021161000__create_digest_tables.sql`：

```sql
UNIQUE KEY uk_digest_items_fingerprint (fingerprint)
```

`DigestCollectionService` 的预检只查 `[today-1, today+1]`；超过一天的旧指纹能通过预检，
但插入时被唯一键拒绝，异常里含 `Duplicate`，被当成「已存在」静默跳过。

后果：

- 一个仓库在平台历史上**最多出现一次**；日榜常见的连续在榜、周榜回看都不可能。
- 采集层被迫不断找「没见过」的新仓库——这正是兜底窗口从 7 天缩到 3 天的原因。
- 热榜的语义（同一个项目可以连续热）与表的语义（永不重复）冲突。

修法（建议）：

```sql
ALTER TABLE digest_items
  DROP INDEX uk_digest_items_fingerprint,
  ADD UNIQUE KEY uk_digest_items_edition_fingerprint (edition_date, fingerprint);
```

- 预检改成按组：GitHub 只查当天（允许昨天在榜的仓库今天再来）；新闻/社区/HN 保持 ±1 天。
- 现有数据没有跨天重复，迁移安全。
- 现有集成测试 `duplicateFingerprintIsRejected` 要保留（同日仍拒绝），
  `tablesAndKeysExist` 要改索引名；新增「同一指纹跨天可插入」用例。

### 5.2 页面不可达却被当成「可选通路」

- 代码写的是「页面优先、Search 兜底」，但生产 DNS 命中的边缘 IP 被墙，页面经常超时后走兜底
  （10-26 就是这样；10-25 页面是通的）。
- 兜底不是热榜，却在界面上仍叫「GitHub 热榜」——**这是最伤用户信任的一点**。
- 网络修法（按代价排序）：
  1. **`/etc/hosts` 把 `github.com` 指到一个可达的 GitHub 边缘 IP**（生产机没有 nginx，这是最小改动）。
     风险：IP 会变；失败时页面通路照旧回退，日志可查。
  2. 本机加一个 nginx/caddy 反向代理，`proxy_ssl_name github.com` + 多个 upstream，可重试。
     代价：多一个服务，生产机当前没有。
  3. 代码里做 DNS override（`InetAddressResolverProvider`）。可行但全局、隐蔽，不推荐。
  4. 第三方镜像/代理。新增依赖，不推荐。
- **无论选哪个，兜底通路的 `sourceName` 必须改成「GitHub 新项目」**，不能继续冒充热榜。

---

## 6. 第三方与参考实现

| 来源 | 结论 | 证据 |
| --- | --- | --- |
| **OSS Insight** `/v1/trends/repos/` | **不可用** | 返回 `data_quality.status=unavailable`，`unavailable_since=2026-03-01`，原因：事件采集量跌到基线 0.3%，「空结果 = 指标算不出来，不是没有仓库」。只有总量与 collections 仍准确 |
| **HelloGitHub** | 不是数据源 | 无公开 API；每月 28 号人工月刊；官网排行按月/年、按总 star；`github_bot.py` = 抓 `received_events` 里 1 天内的 WatchEvent → 拉总 star → 过滤 ≥100 → 按总 star 排序。正是我们要修的那种排序 |
| **huchenme/github-trending-api** | 托管服务已死，解析器可参考 | `ghapi.huchen.dev` 本机/生产机都 000；源码用 cheerio `$('.Box article.Box-row')`、`.h3`、`.float-sm-right`，支持 daily/weekly/monthly + language + spoken language；README 自己说页面空时返回 `[]`，要读缓存 |
| **trendkit** | 理念可参考 | 快照优先、`Δstars/day`、至少两次观测才有速度，否则显示 0 而不是编 |
| **fbuireu/github-star-tracker** | 存储模型可参考 | 快照存独立数据分支、带 version、坏 JSON fail-closed 不覆盖 |
| **github-trends-mcp** | **provenance 可抄** | 把「新建且星多」和「今天最热」拆成两个工具；每条结果带 `source_url / verify_url / fetched_at / count` |
| **Apify GitHub Trending Radar** | 不值得买 | 页面抓取 + 快照算 `starsGained`，$0.3/1000 条；我们自己做成本更低 |
| **GH Archive** | 不做 | 小时级事件包，一天 1–2GB，为 12 张卡片不值 |

一个共同点：这些项目在 2026-09 之前都必须自己攒每日快照，因为「GitHub 不提供 star 历史」。
**新端点把这条前提推翻了**——我们现在可以直接拿 30 周历史，不需要快照表，也不需要等两周预热。

---

## 7. 推荐方案

### P0：换信号（约 1 天）

1. **候选源**：官方 `trending?since=daily`（约 11 个；可再加 2–3 个语言日榜做多样性）。
   **周榜页在 P1 加**，P0 先不混进来。
   页面不可达时才走 Search，且 `sourceName` 改「GitHub 新项目」，并套
   `age>=7 OR (d1>=1000 AND forks>=100)`。
2. **信号**：每个候选 1 次 `/stargazers/history?per_page=5`，按 `day_date = week + (i+1)天` 映射，
   算出 `d1`（最近一天）、`d7`（最近 7 天）。
3. **排序**：日榜 `log10(1+max(d1,0))`，周榜 `log10(1+max(d7,0))`；平局用页面名次。
   **不要 z 分、不要总 star 归一化。**
4. **展示**：`heat = d1`，卡片显示 `+242★ 昨日`；`meta` = `Python · 本周 +1.2k★`；保留高热/较热档位。
5. **标题**：GitHub 条目的 `owner/repo` 必须原样保留，AI 只写中文摘要；提示词升到 `digest-v3`。
6. **诚实降级**：页面失败时 `sourceName = "GitHub 新项目"`，并在日志里点名失败通路。
7. **并发**：history 调用用 4–8 路并行；页面超时从 20s 降到 8–10s。
8. **鉴权**：带 `X-GitHub-Api-Version: 2026-03-10`；301 手动跟随并校验 `Location` 的 host 仍是 `api.github.com`。
9. **cron**：建议 08:00 北京（00:00 UTC），用昨天完整桶；否则 07:00 + 当前 UTC 日桶并标「截至 07:00」。

> 注意：**只做 P0 不改唯一键，昨天在榜的仓库今天仍会被静默跳过**，日榜会一天比一天空。
> 要做真正的日榜，P0 与 P1 应同批上线；只想先验证信号可以用 P0 影子跑，不上线。

### P1：允许重复 + 周榜切换（约半天）

1. 迁移：唯一键改 `(edition_date, fingerprint)`；预检按组（GitHub 只查当天）。
2. 加两列 `stars_day INT NULL`、`stars_week INT NULL`（`heat` 继续存 `d1`，用于默认排序）。
3. 候选源加 `trending?since=weekly`；入选集合 = 日榜前 10 ∪ 周榜前 10
   （同一个仓库只存一条，两个数字都存）。
4. 前端 GitHub 组加「今日 / 本周」切换：按 `starsDay` / `starsWeek` 重排、重算档位；
   卡片在周榜模式显示 `本周 +N★`。
5. 「连续在榜 N 天」用 URL 查前几天的 `digest_items`，不改表。

### P2：网络与观测（约半天）

1. `/etc/hosts` 固定一个可达的 GitHub 边缘 IP；写进部署说明；页面失败时告警/日志。
2. 影子运行 7 天：每天把「我们的 top 10」与官方页面 top 10 对比，记录重合数与 Spearman。
3. 管理端/日志显示每次采集走了哪条通路、候选数、history 失败数。

### P3：可选

- watchlist：把最近 30 天出现过在页面上的仓库（约 300 个）每天各查一次 history（300 配额/天），
  用来发现「掉出页面但又开始涨」的仓库。**不做也能上线**，属于召回增强。
- 点击率埋点：目前没有任何「用户点了哪条」的数据，这是唯一能真正衡量「准不准」的标签。
  要做需另开一项。

### 明确不做

- 不做 z 分排序（回测无增益）。
- 不用 Search 当热榜（召回 0–12/90）。
- 不用 OSS Insight / HelloGitHub（不可用/不是数据源）。
- 不建 GH Archive / ClickHouse、不自己攒快照（新端点已给 30 周）。
- 不让 AI 挑项目/排序。
- 不新增依赖（`java.net.http` + Jackson 已在用）。

---

## 8. 代码落点

| 位置 | 改动 |
| --- | --- |
| `GithubTrendingSource.java` | 页面改为候选源（日/周），解析 `owner/repo`、描述、语言、总 star；`stars today`/`stars this week` 兼容；兜底改标签+门禁 |
| 新增 `GithubStarHistory.java` | 一次 history 调用、301 跟随、`week + (i+1)` 日期映射、`d1/d7` |
| 新增 `GithubScore.java` | 纯函数：`log10(1+d1)` / `log10(1+d7)`，0/缺失处理；单测重点 |
| `FetchedItem.java` | 加 `Integer starsWeek`（`heat` 继续表示日增量）；更新 3 个源的构造器 |
| `DigestCollectionService.java` | 按组预检窗口；`heat=d1`、`stars_day/stars_week`；并发拉 history |
| `DigestMapper.java` | `insert` 加两列；`findByDate`/`Row` 加两列；新增按 URL 查前几天的可选方法 |
| 迁移 `V<14位时间戳>__digest_github_signal.sql` | 两列 + 唯一键替换 |
| `DigestController.java` | `ItemView` 加 `starsDay/starsWeek` |
| `frontend/.../digest-types.ts` | 加两个字段 |
| `frontend/apps/app/src/pages/DigestPage.tsx` | GitHub 组今日/本周切换；`+N★` 格式；周榜模式重算档位 |
| `application.yml` | cron 08:00（可选）；页面超时 8–10s |

估算：后端约 250–350 行 + 测试，前端约 60 行，1 次迁移。约 1.5–2 天；无新依赖。

---

## 9. 测试要覆盖的点

1. **日期映射**：fixture `week=2026-10-18(周日), days=[0,0,0,0,0,15,267]`
   断言 `10-24(周六)=15`、`10-25(周日)=267`。这条能抓住「周日开头」的错读。
2. **页面解析**：日榜/周榜两种文案、赞助商链接、缺语言、缺总 star。
3. **301**：stub 返回 301 到 `api.github.com/repositories/...`，断言跟随后解析成功；跨 host 的 301 必须拒绝。
4. **打分**：`d1=0`、缺失、仓库不存在、history 不足 2 天。
5. **跨天重复**：同一指纹在 `TODAY` 与 `TODAY+1` 都插入成功；同一天仍是 Duplicate。
6. **兜底门禁**：`age<7` 但 `d1=1292/forks=424` 保留；`age<7` 且无 fork 的垃圾剔除。
7. **预检窗口**：GitHub 昨天的条目不再阻断今天；新闻仍是 ±1 天。
8. **集成**：`GithubTrendingSourceTest` 的 heat 断言改成来自 history，不再来自页面 `stars today`。

---

## 10. 成本与风险

**每天成本**：P0 约 11–25 次 history；P0+P1 约 25 次（页面失败时是 30 个 Search 候选）。
token 5000/h，约 1–2 分钟并发完成；外加 1–2 次页面 + 1 次 Search + 1 次 AI。
AI 调用次数不变（周榜用同一批条目，不额外总结）。

**风险**：

- 新端点只有约一个月，可能变；页面 `stars today` 与 Search 作为降级，日志点名通路。
- `/etc/hosts` 固定 IP 会失效（GitHub 换边缘）；失效时回到「新项目」降级，不会崩。
- 唯一键迁移改变了去重契约；必须同时改测试与新闻的预检窗口，否则新闻会跨天重复。
- 日/周的「今天」按 UTC，产品文案按北京时间；不标清楚用户会对不上。
- 刷星无法根治：页面能挡一部分，Search 兜底只能靠年龄/forks 门禁；没有 account-age 数据（stargazers 列表已限制）。
- 「准不准」没有客观真值：我们能证明的只是「与 GitHub 官方榜重合度提高 + 每张卡可解释」，不能证明「全网最热」。

---

## 11. 未决问题（需要用户裁决）

1. **cron 07:00 还是 08:00？** 08:00 语义最干净（昨天完整），但改变现有产品承诺。
2. **页面网络修法**：`/etc/hosts`（最小）还是加一个反代（更稳）？
3. **周榜入口**：GitHub 组内切换（推荐）还是单独一个分组？
4. **兜底怎么称呼**：页面不可达时，是显示「GitHub 新项目」还是干脆隐藏该组？
5. **要不要 watchlist**（P3）：每天 +300 次请求，换召回；一期可以不做。

---

## 附：复现命令（不含任何凭据）

```bash
# 1) 生产日志
ssh <生产机别名> "journalctl -u paideia --since '3 days ago' --no-pager | grep -aE 'trending|GitHub'"

# 2) 官方页面（生产机 DNS 被墙时用 --resolve 换边缘 IP）
curl -s --resolve github.com:443:<可达边缘IP> \
  -H 'User-Agent: Mozilla/5.0' 'https://github.com/trending?since=daily'

# 3) star 历史（gh 会跟随 301）
gh api -H 'X-GitHub-Api-Version: 2026-03-10' \
  'repos/facebook/react/stargazers/history?per_page=5'

# 4) 复现兜底查询
gh api 'search/repositories?q=created:%3E=2026-10-23+stars:%3E50&sort=stars&per_page=30'
```

回测脚本为一次性脚本，未入库；关键逻辑：`day_date = utcdate(week) + (index+1) days`，
`d1 = stars(today-1)`，`d7 = stars(today-1..today-7)`，`rho = spearman(score, -official_rank)`。

---

## 旁注：一个与本次无关但发现的问题

`mcp__paideia__admin_digest_list_items` 与 `admin_ops_summary` 当前调用都返回 MCP 服务端 500
（`HttpServletStreamableServerTransportProvider` 栈），无法用它查看线上速递条目。
本次改用「生产日志 + 复现查询」取证。若管理端确实依赖这两个工具，需要单独排查。
