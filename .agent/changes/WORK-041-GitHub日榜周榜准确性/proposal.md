---
artifact: proposal
work_id: WORK-041
work: GitHub 日榜/周榜准确性
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 15:54:47 +0800
approver_role: CTO
---

# GitHub 日榜/周榜准确性提案

## 摘要

把 GitHub 组从「页面解析 + 总 star 兜底」改成「官方页面提供候选、star 历史端点提供真实日增量、按 `d1/d7` 排序」，并把指纹唯一键改成按日唯一，使同一仓库可以连续在榜；前端在 GitHub 组内增加「今日 / 本周」切换。页面通路通过本机反向代理恢复，cron 改到 08:00。

## 推荐方案

**核心机制**

1. **候选**：抓 `trending?since=daily` 与 `?since=weekly`；页面不可达时才走 Search，且标「GitHub 新项目」。
2. **信号**：每个候选调一次 `/stargazers/history?per_page=2`，按 `week + (index+1)` 建日期映射，得到 `d1`（最近完整 UTC 日）与 `d7`（最近 7 个完整日）。
3. **排序**：日榜 `log10(1+max(d1,0))`，周榜 `log10(1+max(d7,0))`；`heat=d1`。**不用 z 分、不用总 star 归一化**。
4. **数据**：新增 `stars_day/stars_week`；唯一键改 `(edition_date, fingerprint)`；GitHub 只做同日预检。
5. **展示**：GitHub 组「今日/本周」切换；卡片显示 `+N★`；`owner/repo` 服务端固定，不被 AI 改写。
6. **网络**：生产机装本机反向代理，`proxy_ssl_name github.com`，配置多个 GitHub 边缘 IP；应用只访问本地代理地址。

**为什么最满足需求**

- 调研回测显示：同一批 120 个样本里，按 `d1` 排序前 10 有 9 个官方热榜仓库、0 个零增量；当前兜底是 0/11 重叠。提升来自「真实增量 + 官方候选」，不是公式复杂度。
- 不新增依赖：history 端点走现有 `java.net.http` + Jackson。
- 成本不变：一天一次 AI 调用；history 约 25 次/天，token 5000/h 足够。
- 保留降级：页面、history、Search 三层失败都有明确出口，不会让整次采集崩。

**放弃了什么**

- 放弃「全站发现」：候选仍受官方页面限制，页面没列出的爆款（除非 Search 兜底）看不到。这是 watchlist 二期要补的。
- 放弃「绝对准确」：GitHub 不公开排序算法，我们只能做到「与官方榜高度重合 + 每张卡可解释」。
- 放弃「零运维」：反向代理是新增服务。

## 范围

**包含**

- `paideia-ai` 的 GitHub 采集、页面解析、history 客户端、评分、`FetchedItem`、采集编排、提示词标题规则。
- `paideia-app` 的一次 Flyway 迁移、`DigestMapper`、`DigestController`。
- 学习者端速递页的类型、切换与卡片。
- 生产配置：cron 08:00、页面基地址、本机反向代理。

**不包含**

- watchlist、GH Archive、自建快照、点击率埋点、个性化推荐。
- 其他三个分组的行为。
- 管理端的速递编辑/删除功能（既有功能不动）。

## 备选方案

| 方案 | 最有力的理由 | 为什么没选 | 放弃了什么 |
| --- | --- | --- | --- |
| 维持现状（页面 + 总 star 兜底） | 零改动、零新服务 | 与官方榜 0/11 重叠，三分之一条目昨日零增量 | 准确性 |
| 只用 Search API 重排 | 不依赖页面，网络简单 | 对官方 90 个仓库召回仅 0–12/90，按总 star 排的是「大」不是「热」 | 召回 |
| 用 OSS Insight / HelloGitHub | 现成数据/人工精选 | OSS Insight trending 自 2026-03 起 unavailable；HelloGitHub 无 API 且按总 star | 可控性 |
| 加基线 z 分/相对增速 | 听起来更「算法」 | 回测只提升约 +0.01，语言榜上更差 | 复杂度 |
| 自建每日快照 / GH Archive | 不依赖新端点，能看全站 | 新端点已给 30 周；GH Archive 一天 1–2GB，为 12 张卡片不值 | 运维成本 |
| `/etc/hosts` 固定 IP | 最小改动 | 用户选反代：可多 upstream 重试、作用域清楚、不污染全机 DNS | 简单性 |
| watchlist 每天查 300 个仓库 | 能发现掉出页面又回暖的项目 | 用户决定一期不做 | 召回上限 |

## 仓库影响

| 模块 | 文件 | 影响 |
| --- | --- | --- |
| `paideia-ai` | `internal/digest/GithubTrendingSource.java` | 页面改为候选源（日/周），兜底改标签与门禁 |
| `paideia-ai` | `internal/digest/GithubStarHistory.java`（新增） | history 调用、301 同 host 跟随、日期映射、`d1/d7` |
| `paideia-ai` | `internal/digest/GithubScore.java`（新增） | 两个纯函数与边界处理 |
| `paideia-ai` | `internal/digest/FetchedItem.java` | 加 `starsWeek` |
| `paideia-ai` | `internal/digest/DigestCollectionService.java` | 按组预检、并发 history、`stars_day/week`、标题固定 |
| `paideia-ai` | `internal/digest/DigestMapper.java` | insert/findByDate/Row 加两列 |
| `paideia-ai` | `internal/digest/DigestProperties.java` | 页面基地址、cron 默认值 |
| `paideia-ai` | `internal/digest/DigestPrompt.java` | `digest-v3`：repo 名不改写、可带本周热度 |
| `paideia-ai` | `internal/digest/web/DigestController.java` | `ItemView` 加两字段 |
| `paideia-app` | `db/migration/V20261026160000__digest_github_signal.sql` | 两列 + 唯一键替换 |
| 前端 | `packages/knowledge/src/digest-types.ts` | 加两字段 |
| 前端 | `apps/app/src/pages/DigestPage.tsx` | 组内切换、卡片数字、档位重算 |
| 配置 | `application.yml` | cron 08:00、`github-trending-url` 占位 |
| 部署 | 生产机 nginx 配置 + systemd 环境变量 | 本地反代地址与 cron |

## 交付拆分

1. **TASK-001–003（信号与候选）**：history 客户端、页面解析、评分与 `GithubTrendingSource` 集成；只读，不改表。
2. **TASK-004–005（数据与编排）**：迁移、mapper/controller、按组预检、`stars_day/week`、标题固定。
3. **TASK-006（前端）**：类型、切换、卡片、档位。
4. **TASK-007（配置与部署准备）**：cron、页面基地址、反代配置与部署说明；实际部署单独授权。
5. **TASK-008（验证）**：按 `testing/plan.md` 跑内环与外环，写 `testing/report.md`。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| 新端点只有约一个月，可能变 | 保留页面 period stars 与 Search 兜底；日志点名通路；单测固定 fixture |
| 反向代理是新增服务，可能挂 | 代理只监听 127.0.0.1；应用超时 10s；失败即走降级；部署说明写健康检查 |
| 唯一键迁移改变去重契约 | 同日重复测试保留；跨天测试新增；新闻 ±1 天预检不动 |
| UTC 日与北京日不一致 | 08:00 调度 + 卡片写「昨日/本周（UTC 完整日）」 |
| 刷星无法根治 | 页面候选由 GitHub 先筛；兜底门禁；不做假 star 检测 |
| 没有客观真值 | 用 120 样本回测 + 影子运行与官方榜对比 + 每张卡可解释 |
| 迁移时间戳与并发会话冲突 | 14 位时间戳；合并前 `git log --all -- <迁移目录>`；部署前核 jar 内只有一个版本 |

## 验收映射

| AC | REQ | 主要证据 |
| --- | --- | --- |
| AC-001 | REQ-001/003 | `GithubTrendingSourceTest` + 影子运行重合度 |
| AC-002 | REQ-006 | 兜底门禁单测 + 日志 |
| AC-003 | REQ-002 | `GithubStarHistoryTest` 日期映射（含反向验证） |
| AC-004 | REQ-005 | `DigestIntegrationTest` 跨天/同日 |
| AC-005 | REQ-002/011/012 | `DigestIntegrationTest` + controller 测试 |
| AC-006 | REQ-004 | 前端 typecheck + 页面手工/ e2e |
| AC-007 | REQ-006 | history 失败单测 |
| AC-008 | REQ-002 | 301 单测（同 host/跨 host） |
| AC-009 | REQ-011 | 迁移集成测试 + `information_schema` |
| AC-010 | REQ-007 | 配置断言 + 文档 |
| AC-011 | 约束 | 提交前扫描 |
| AC-012 | REQ-005/非目标 | 既有 `Digest*Test` 全绿 |

## 待决定事项

无。五项裁决已由用户给出；实施中若出现范围变化，回到 `requirements.md` 修订。
