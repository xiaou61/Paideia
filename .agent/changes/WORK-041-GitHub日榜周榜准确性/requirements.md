---
artifact: requirements
work_id: WORK-041
work: GitHub 日榜/周榜准确性
status: approved
mode: strict
mode_reason: 跨 paideia-ai（采集与评分）、paideia-app（Flyway 迁移与唯一键）、学习者端前端（新字段与切换）、部署（本机反向代理与 cron）；同时改变公共 API 返回字段、去重契约与每日采集语义
workflow: full
depends_on: []
related_to: [WORK-027]
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 15:54:47 +0800
approver_role: CTO
---

# GitHub 日榜/周榜准确性需求

## 本项状态与本轮变化

- **2026-10-26 起草**。用户原话：「我想知道当前项目的 GitHub项目日榜 周榜 这个是怎么来的呢？设计思路是什么呢」→ 调研后：「你完整调研一下，开始吧」→ 五項裁决后：「可以的 那你开始新开work项目，然后开worktree来弄吧」。
- 本项是 **WORK-027（每日技术速递）** 的续作，只改其中的 `github` 分组与其展示，不重做速递。
- 调研证据、复现命令与全部数字在 [`.agent/notes/2026-10-26-github-trending-research.md`](../../notes/2026-10-26-github-trending-research.md)，此处只留结论。
- 本项**尚未实施**；四件工件签署后按 `tasks.md` 执行。

## 已确认事实

- **生产今天的 GitHub 组不是热榜**：2026-10-26 07:00 日志 `GitHub trending 页面不可达（HttpTimeoutException）`，整组走 Search 兜底。复现同一查询后与官方日榜 **0/11**、周榜 **0/14** 重叠；30 条里 **10 条 `d1<20`、8 条 `d1=0`**，全部是 1–3 天的新仓库，含 `.github` 仓库与明显垃圾项目。
- **官方页面可恢复**：生产机 DNS 命中的 GitHub 边缘 IP 超时；用 `curl --resolve` 换另一个 GitHub 边缘 IP 连续 3 次 200（0.34–3.13 秒），页面可解析（日榜 11 条、周榜 14 条、5 个语言日榜 74 条）。
- **star 历史端点可用**：`GET /repos/{owner}/{repo}/stargazers/history?per_page=N` 一次给 30 周；`days` 是每日增量；**`week` 标签是周日，但 `days` 从周一开始**（用 `15+267+860=1142` 精确核对）；计入 core 5000/h；20 次顺序调用中位 1.61 秒；301 是仓库个例，需跟随。
- **页面数字与 API 对账**：日榜 `stars today` vs API 昨日 `d1` 中位差 **8.9%**（滚动 24 小时）；周榜 `stars this week` vs API 近 7 日 `d7` 中位差 **1.2%**。
- **回测结论**：120 个样本中按 `d1` 排序前 10 有 **9 个**官方热榜仓库、**0 个零增量**；加基线 z 分只提升约 **+0.01**，总 star 归一化把 `off@20` 从 19/20 打到 12/20；Search 各查询对官方 90 个仓库召回仅 **0–12/90**。
- **结构性缺陷**：`digest_items` 的 `UNIQUE KEY uk_digest_items_fingerprint (fingerprint)` 不含日期；同一仓库在平台历史上只能出现一次，热榜连续在榜存不下来。
- **第三方不可用**：OSS Insight trending 自 2026-03-01 起 `data_quality.status=unavailable`；HelloGitHub 无公开 API、是人工月刊；huchenme 托管 API 已死。

## 用户决定

五项裁决（2026-10-26）：

1. **cron 改为 08:00 北京（00:00 UTC）**，使用最近一个完整 UTC 日的桶。
2. **页面网络用本机反向代理**（不是 `/etc/hosts`）：代理带 `proxy_ssl_name github.com`，可配多个 GitHub 边缘 IP 重试。
3. **周榜入口放在 GitHub 组内**，用「今日 / 本周」切换，不新增分组。
4. **页面不可达时显示「GitHub 新项目」**，不隐藏该组，也不冒充热榜。
5. **一期不做 watchlist**。

## 实现选择

- **候选源**：官方 `trending?since=daily` + `since=weekly`；页面不可达时才走 Search 兜底。
- **信号**：每个候选 1 次 `/stargazers/history?per_page=2`；按 `day_date = week + (index + 1) 天` 建日期映射，算 `d1`（最近完整 UTC 日）与 `d7`（最近 7 个完整 UTC 日）。
- **排序**：日榜 `log10(1 + max(d1,0))`，周榜 `log10(1 + max(d7,0))`；**不用 z 分、不用总 star 归一化**。`heat` 存 `d1`，用于默认排序与档位。
- **兜底门禁**：Search 候选套 `age >= 7 天 OR (d1 >= 1000 AND forks >= 100)`；页面候选不套门禁。
- **标题**：GitHub 条目的标题在服务端固定为 `owner/repo`，AI 只写摘要；其他分组保持原行为。
- **并发**：history 调用 6 路并发；页面超时 10 秒。
- **迁移**：唯一键改 `(edition_date, fingerprint)`；新增 `stars_day`、`stars_week` 两列。
- **重定向**：只跟随同 host（`api.github.com`）的 301/302/307/308，最多 3 次；跨 host 视为失败。
- **不做**：不引入新依赖；不让 AI 参与排序；不建快照表；不做 watchlist；不改新闻/社区/HN 的既有语义。

## 背景

速递的 GitHub 组当前有两条通路：页面解析与 Search 兜底。页面偶尔可达，但生产机 DNS 命中的 GitHub 边缘 IP 经常超时；一旦超时，兜底给的是「最近 3 天新建、总 star 多」的仓库，却仍以「GitHub 热榜」的名义展示。调研证明这条兜底与官方榜零重叠，且三分之一条目昨日几乎无人 star。与此同时，GitHub 在 2026-09 上线了 star 历史端点，使「相对自身基线的真实日增量」第一次可直接获取；不利用它，继续靠总 star 排序无法得到可信的热榜。

## 目标

- 让「GitHub 热榜」展示的条目与 GitHub 官方日榜/周榜有实质性重合（前 10 至少 9 条来自官方候选，且无昨日零增量条目）。
- 让热榜可以在连续多天展示同一个仓库，并支持「今日 / 本周」两种口径。
- 页面不可达时明确降级，不把「新项目」冒充成「热榜」。
- 保持每天一次 AI 调用的成本约束不变，不新增依赖。

## 非目标

- 不做跨源事件聚类、全站热度算法或机器学习排序。
- 不做 watchlist / GH Archive / 自建每日快照表。
- 不引入 OSS Insight、HelloGitHub、huchenme 等第三方作为数据源。
- 不改新闻、社区、Hacker News 三个分组的采集与排序。
- 不做点击率埋点与个性化推荐（需要另开工作项）。
- 不做 GitHub 语言筛选、开发者榜、月榜。
- 不改积分、AI 计费、提示词除 `digest-v3` 标题规则外的部分。

## 用户与工作流程

**每天 08:00 后**：学习者打开「每日速递」→ GitHub 组默认「今日」→ 卡片显示 `+242★ 昨日`、语言、`本周 +1.2k★` → 点卡片跳 GitHub → 点「存成笔记」带着标题与摘要跳到新建笔记页。

**看本周**：在 GitHub 组标题右侧切到「本周」→ 同一批条目按 `d7` 重排，卡片显示 `本周 +1.2k★`。

**页面不可达**：GitHub 组标题仍是「GitHub 热榜」，但每条卡片的来源徽标变为「GitHub 新项目」，内容只保留通过门禁的真爆款；日志点名失败通路。

**管理端/排查**：日志能看出本次走了页面还是 Search、候选数、history 调用失败数；数据库能按 `stars_day/stars_week` 复核排序。

## 功能需求

- **REQ-001**：GitHub 组的主候选源是官方 `trending?since=daily` 与 `since=weekly`；两页去重后作为候选池。
- **REQ-002**：每个候选必须用 `/stargazers/history` 计算最近完整 UTC 日的 `d1` 与最近 7 个完整日的 `d7`；单日缺失按 0。
- **REQ-003**：日榜按 `d1` 排序、周榜按 `d7` 排序；不得用仓库总 star 或 AI 重要性分替代。
- **REQ-004**：GitHub 组支持「今日 / 本周」切换；卡片分别显示昨日新增与本周新增；`owner/repo` 原样保留。
- **REQ-005**：同一仓库允许在不同 `edition_date` 重复出现；同一 `edition_date` 内同一 URL 只能一条；新闻/社区/HN 的 ±1 天预检保持不变。
- **REQ-006**：页面不可达时走 Search 兜底，`sourceName` 标为「GitHub 新项目」，并套年龄/forks 门禁；history 单条失败时回退到页面的 period stars；两条通路都失败时该组为空，不抛异常。
- **REQ-007**：调度改为 08:00 北京，`edition_date` 仍按业务时区；`d1/d7` 使用最近完整 UTC 日；UI 文案必须写清口径。
- **REQ-008**：生产机通过本机反向代理访问 GitHub 页面；代理地址走配置，不写死在代码里；代理失败等同页面不可达。
- **REQ-009**：history 调用必须有并发上限与单次超时；每天 AI 调用次数不增加；不新增运行时依赖。
- **REQ-010**：采集日志记录通路（页面/兜底）、候选数、history 成功/失败数、降级原因。
- **REQ-011**：迁移新增 `stars_day`、`stars_week` 两列，并把唯一键改为 `(edition_date, fingerprint)`；现有数据可无损升级。
- **REQ-012**：速递查询接口返回 `starsDay`、`starsWeek`；旧客户端忽略新字段仍可用；非 GitHub 分组两字段为 `null`。

## 质量要求

- 日期映射必须单测覆盖「`week` 周日标签 + `days` 周一起始」这一坑；反向验证：把映射改成周日开头，用例必须红。
- 页面解析必须覆盖日榜/周榜两种文案、赞助商链接、缺语言、缺总 star。
- 301 只跟随同 host；跨 host 重定向必须拒绝（防止带 token 的请求被引到别处）。
- history 失败、页面失败、301、404、空历史都不得让整次采集失败。
- 迁移在空库与有数据库都能执行；同一仓库跨天插入成功、同日重复仍被拒。
- 前端类型检查 0 error；GitHub 组切换后排序与显示数字一致。

## 约束与依赖

- 项目规范：手写 MyBatis、仅 MySQL、Flyway 14 位时间戳、时间存 UTC、凭据/服务器地址不入库、不新增依赖、公开仓库保密规则。
- 依赖 WORK-027 现有的速递表、采集编排、AI 摘要与前端页面；不改变它们对非 GitHub 分组的行为。
- 生产机当前没有 nginx/caddy；反向代理是新增的运维服务，部署需单独授权。
- GitHub token 必须来自配置；缺失时 history 调用受 60/h 限制，候选池需相应收缩或整组降级。
- GitHub 官方页面与 star 历史端点都是外部依赖；端点可能变化，必须保留降级路径。

## 关联工作项

- **WORK-027 每日技术速递**：本项修改其 GitHub 分组、采集编排、表结构与前端展示；不重做速递。
- **WORK-036 AI 观测面板优化**：共用 `ai_runs` 的采集记录；本项不改变其接口。
- 与 WORK-035/037 等无共享数据或接口。

## 验收标准

- **AC-001**：页面可达时，GitHub 组前 10 至少 9 条来自官方日榜/周榜候选；`sourceName` 为「GitHub 热榜」。
- **AC-002**：页面不可达时，`sourceName` 为「GitHub 新项目」，且不出现 `d1=0` 或 `age<7 且 forks<100` 的条目。
- **AC-003**：日期映射用例通过：`week=2026-10-18`、`days=[0,0,0,0,0,15,267]` 时，`2026-10-24=15`、`2026-10-25=267`；改成周日开头必须红。
- **AC-004**：同一指纹在 `TODAY` 与 `TODAY+1` 都能插入；同一天第二次仍抛 Duplicate。
- **AC-005**：`stars_day/stars_week` 在 GitHub 条目上非空且等于 `d1/d7`；其它分组为 `null`。
- **AC-006**：前端「今日/本周」切换后，GitHub 组按对应字段降序；卡片数字与排序一致；类型检查 0 error。
- **AC-007**：history 单条失败时该条回退页面 period stars；两条页面通路都失败时该组为空且日志点名失败。
- **AC-008**：301 同 host 跟随成功；跨 host 301 被拒绝（单测）。
- **AC-009**：迁移在空库与含数据库都成功；索引名为 `uk_digest_items_edition_fingerprint`；旧索引不存在。
- **AC-010**：cron 默认值为 `0 0 8 * * *`（Asia/Shanghai）；UI/文档写清「昨日/本周」是 UTC 完整日。
- **AC-011**：仓库内没有新增凭据、服务器地址或代理 IP；配置项只留环境变量占位。
- **AC-012**：新闻、社区、HN 三组的候选、排序、去重行为与改动前一致（既有测试全绿）。

## 假设

- 官方 trending 页面在可访问网络下仍保持 `<article>` + `stars today`/`stars this week` 结构；改版时解析失败会走兜底。
- star 历史端点的 `days` 语义在 30 周窗口内稳定；若变化，降级到页面 period stars。
- 生产机可通过本机反向代理稳定访问 GitHub 页面；若代理失效，降级路径可用。
- 08:00 北京对应的 UTC 00:00 能让「昨日」成为一个完整桶；若部署时区变化，需要重新核对。
- 现有速递的 60 条总上限足够容纳 GitHub 组的 10–15 条。

## 待确认问题

无。五项产品/运维裁决已由用户给出；实现细节按「实现选择」执行，若实施中发现范围变化，回到本文件。
