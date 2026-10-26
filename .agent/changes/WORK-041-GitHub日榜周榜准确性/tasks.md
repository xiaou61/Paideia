---
artifact: tasks
work_id: WORK-041
work: GitHub 日榜/周榜准确性
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 15:54:47 +0800
approver_role: CTO
---

# 任务 · GitHub 日榜/周榜准确性

## 依据

- 已批准的需求：[requirements.md](requirements.md)
- 已批准的提案：[proposal.md](proposal.md)
- 已批准的设计：[design.md](design.md)
- 调研证据：[`.agent/notes/2026-10-26-github-trending-research.md`](../../notes/2026-10-26-github-trending-research.md)

## 全局约束

- 迁移文件名 14 位时间戳，只建结构、不插业务数据；现存迁移不改。
- 不新增运行时依赖；HTTP 用 `java.net.http`，JSON 用现有 Jackson。
- 凭据、代理 IP、服务器地址不入库；仓库只留环境变量占位。
- 不改变 news/community/hackernews 三组的候选、排序与去重语义。
- 每天 AI 调用次数不增加；页面/网络失败必须可降级。
- 部署（装反代、改生产配置、发版）单独授权；本计划只准备配置与说明。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| `week` 是周日标签但 `days` 从周一开始，整体错一天 | TASK-001 | `GithubStarHistoryTest.weekLabelIsSundayButDaysStartMonday` |
| 带 token 的请求被 301 引到第三方 host | TASK-001 | `GithubStarHistoryTest.crossHostRedirectIsRejected` |
| 页面改版/被拦后静默整组为空 | TASK-003 | `GithubTrendingSourceTest.emptyPageFallsBackToSearch` |
| 单条 history 失败导致整组失败 | TASK-003 | `GithubTrendingSourceTest.historyFailureFallsBackToPagePeriodStars` |
| 兜底重新引入 1–3 天垃圾仓库 | TASK-003 | `GithubTrendingSourceTest.fallbackGateDropsFreshSpam` |
| 同一仓库跨天被唯一键静默拒绝 | TASK-004 | `DigestIntegrationTest.sameFingerprintOnDifferentDaysIsAllowed` |
| 同一天重复入库 | TASK-004 | `DigestIntegrationTest.duplicateFingerprintIsRejected` |
| AI 把 `owner/repo` 翻译掉 | TASK-005 | `DigestCollectionServiceTest.githubTitleIsPinned` |
| 放宽 GitHub 预检后新闻也跨天重复 | TASK-005 | `DigestCollectionServiceTest.newsStillUsesNeighborWindow` |
| 前端切到本周但顺序仍按日榜 | TASK-006 | `digest.spec.ts` 的 GitHub 切换用例 |
| 迁移时间戳倒挂导致生产起不来 | TASK-007 | 合并前 `git log --all -- <迁移目录>` + jar 内只一个版本 |
| 反向代理挂掉后页面通路仍被当成成功 | TASK-007 | 手工：`journalctl` 出现降级日志 |

## 任务

### TASK-001 | done | history 客户端与同 host 重定向

- 对应：`REQ-002`、`AC-003`、`AC-008`
- 依赖：无
- 新建/修改：
  - 新建 `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/digest/GithubStarHistory.java`
  - 修改 `DigestHttp.java`：抽出 `send`，新增 `getTextFollowingSameHostRedirects(url, ua, headers, allowedHost, maxHops)`
  - 新建测试 `backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/digest/GithubStarHistoryTest.java`
- 接口：`GithubStarHistory.Stats(int dayStars, int weekStars)`；`Stats fetch(String repo, String token, DigestHttp http, LocalDate referenceDay)`
- 测试：`GithubStarHistoryTest`
- 步骤：
  1. 写日期映射 fixture：`week=1792281600`（2026-10-18 周日）、`days=[0,0,0,0,0,15,267]` + 下一周；断言 10-24=15、10-25=267。先跑红。
  2. 实现 `dayDate = utc(week).plusDays(i+1)` 与 `d1/d7`；跑绿。
  3. 写 301 stub：同 host 跟随后 200；跨 host 301 抛 `DigestSourceException`。先跑红。
  4. 实现同 host 重定向（最多 3 次，跨 host 拒绝）；跑绿。
  5. 写 404/空历史/缺 token 的用例；实现跳过与默认 0。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=GithubStarHistoryTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failed、0 skipped
- 反向验证：把 `plusDays(i + 1)` 改成 `plusDays(i)`，`weekLabelIsSundayButDaysStartMonday` 必须红。

### TASK-002 | done | 页面解析与日/周候选合并

- 对应：`REQ-001`、`REQ-004`、`AC-001`
- 依赖：无
- 新建/修改：`GithubTrendingSource.java`（解析与候选结构）；`GithubTrendingSourceTest.java`
- 接口：内部 `record PageRepo(String repo, String description, String language, Integer totalStars, Integer periodStars)`；`List<PageRepo> parsePage(String html, boolean weekly)`
- 测试：`GithubTrendingSourceTest`
- 步骤：
  1. 用真实页面片段写测试：日榜 `3,856 stars today`、周榜 `1,024 stars this week`、赞助商链接在前、缺语言、缺总 star。先跑红。
  2. 把 `firstRepoLink` 收紧到 `<h2>` 块；新增总 star 与周榜文案解析；跑绿。
  3. 写 `mergeCandidates(daily, weekly)` 的测试：同一 repo 合并、顺序稳定、日/周各自保留 period stars。
  4. 实现合并；跑绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=GithubTrendingSourceTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failed
- 反向验证：把 `<h2>` 匹配放宽回「块内第一个 repo 链接」，赞助商用例必须红。

### TASK-003 | done | GithubTrendingSource 集成：history、并发、兜底门禁、标签

- 对应：`REQ-001`、`REQ-002`、`REQ-003`、`REQ-006`、`REQ-009`、`AC-001`、`AC-002`、`AC-007`
- 依赖：TASK-001、TASK-002
- 新建/修改：`GithubTrendingSource.java`、`GithubStarHistory.java`（注入）、`GithubTrendingSourceTest.java`
- 接口：`GithubTrendingSource(GithubStarHistory history)`；`List<FetchedItem> fetch(DigestProperties, DigestHttp, long nowMillis)`
- 测试：`GithubTrendingSourceTest`
- 步骤：
  1. 写 stub：页面两条 + history 返回固定 `d1/d7`；断言 `heat=d1`、`starsWeek=d7`、`sourceName=GitHub 热榜`。先跑红。
  2. 实现 6 路并发调用 history、失败回退页面 period stars；跑绿。
  3. 写页面全空的 stub：Search 返回 `age=2d/forks=0` 与 `age=2d/forks=424/d1=1292` 两条，断言后者保留、前者被门禁；`sourceName=GitHub 新项目`。
  4. 实现门禁 `age>=7 OR (d1>=1000 AND forks>=100)`；跑绿。
  5. 写两条页面都坏的用例：返回空列表、不抛异常、日志可查。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=GithubTrendingSourceTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failed
- 反向验证：去掉门禁，`fallbackGateDropsFreshSpam` 必须红；把 history 失败改成抛异常，`historyFailureFallsBackToPagePeriodStars` 必须红。

### TASK-004 | done | 迁移、Mapper、Controller 两字段

- 对应：`REQ-011`、`REQ-012`、`AC-004`、`AC-005`、`AC-009`
- 依赖：无
- 新建/修改：
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V20261026160000__digest_github_signal.sql`
  - 修改 `DigestMapper.java`、`web/DigestController.java`
  - 修改 `DigestIntegrationTest.java`
- 接口：`insert(..., Integer heat, Integer starsDay, Integer starsWeek, String meta, ...)`；`Row` 与 `ItemView` 加 `starsDay`、`starsWeek`
- 测试：`DigestIntegrationTest`
- 步骤：
  1. 写迁移；在独占测试库跑 Flyway，断言新索引存在、旧索引不存在。
  2. 改 mapper SQL 与 record；改 controller 的 `toView`。
  3. 更新测试的 `insert(...)` 调用；新增 `sameFingerprintOnDifferentDaysIsAllowed`；保留 `duplicateFingerprintIsRejected`。
  4. 断言 `findByDate` 返回两字段。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=DigestIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failed
- 反向验证：把唯一键改回 `(fingerprint)`，跨天用例必须红。

### TASK-005 | done | 采集编排：按组预检、stars 字段、标题固定

- 对应：`REQ-005`、`REQ-004`、`AC-004`、`AC-005`、`AC-012`
- 依赖：TASK-003、TASK-004
- 新建/修改：`DigestCollectionService.java`、`FetchedItem.java`、`DigestPrompt.java`、`DigestCollectionServiceTest.java`
- 接口：`FetchedItem(..., Integer heat, Integer starsWeek, String meta, ...)`；`insert(...)` 按 TASK-004
- 测试：`DigestCollectionServiceTest`、`DigestPromptTest`（若存在则更新版本断言）
- 步骤：
  1. 改 `FetchedItem` 与三个源的构造器；编译通过。
  2. 按组预检：GitHub `[today,today]`、其它 `[today-1,today+1]`；写 `newsStillUsesNeighborWindow`。
  3. `stars_day/stars_week` 赋值；GitHub 标题固定为 `item.title()`；写 `githubTitleIsPinned`。
  4. `DigestPrompt` 升 `digest-v3`，加 repo 名不改写规则；`user()` 带本周热度。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=DigestCollectionServiceTest,DigestSummarizerTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failed
- 反向验证：把 GitHub 预检窗口改回 ±1 天，`newsStillUsesNeighborWindow` 的对偶用例（GitHub 昨天在榜今天可入）必须红。

### TASK-006 | done | 前端：类型、今日/本周切换、卡片

- 对应：`REQ-004`、`AC-006`
- 依赖：TASK-004
- 新建/修改：`frontend/packages/knowledge/src/digest-types.ts`、`frontend/apps/app/src/pages/DigestPage.tsx`
- 接口：`DigestItem` 加 `starsDay: number | null`、`starsWeek: number | null`
- 测试：`pnpm -r typecheck`；`apps/app/e2e/digest.spec.ts` 的切换用例
- 步骤：
  1. 加类型字段；`pnpm -r typecheck` 先跑红（后端字段尚未消费时也应通过，作为基线）。
  2. GitHub 组标题加「今日 / 本周」切换；按 `starsDay`/`starsWeek` 降序，null 最后。
  3. 卡片：日榜显示 `+N★ 昨日`，周榜显示 `本周 +N★`；档位按当前排序重算。
  4. 跑 typecheck；若本地有速递数据，跑 `digest.spec.ts`。
- 验证：`cd frontend && pnpm -r typecheck` → 0 error；`pnpm --filter @paideia/app test:e2e -- digest.spec.ts`（视本地数据）
- 反向验证：把周榜排序改成仍按 `starsDay`，切换用例必须红（或手工观察顺序不变）。

### TASK-007 | done | 配置与反代部署准备

- 对应：`REQ-007`、`REQ-008`、`AC-010`、`AC-011`
- 依赖：无
- 新建/修改：`DigestProperties.java`、`application.yml`、部署说明（`.agent/notes/dev-workflow.md` 或 `deploy/`）
- 接口：`githubTrendingUrl`；cron 默认 `0 0 8 * * *`
- 测试：配置解析测试 / 手工检查
- 步骤：
  1. 加 `github-trending-url` 占位与 cron 默认值；本地启动确认读到默认。
  2. 准备反代配置（监听 127.0.0.1、`proxy_ssl_name github.com`、多 upstream）；写部署说明。
  3. 部署前检查 `git log --all -- <迁移目录>` 无更晚时间戳；确认 jar 内只有一个 `V20261026160000`。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=DigestPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`（若存在）；部署说明人工核对
- 执行记录：配置已完成（cron 08:00、`github-trending-url` 占位）；2026-10-26 17:03–17:18 部署：装 nginx 1.24、127.0.0.1:8082 反代（Host/SNI=github.com、多边缘 IP、32k 头缓冲）、systemd drop-in 注入 `DIGEST_GITHUB_TRENDING_URL`/`DIGEST_CRON`，从工作树 release 118.8 秒；反代 daily 200/11 篇、weekly 200/14 篇，health UP，Flyway 应用新迁移。
- 反向验证：无（配置与部署准备，靠启动日志与人工核对）

### TASK-008 | done | 验证与报告

- 对应：全部 `AC-*`
- 依赖：TASK-001–007
- 新建/修改：`testing/plan.md`、`testing/report.md`
- 测试：按 `testing/plan.md` 的验收矩阵
- 步骤：
  1. 写测试计划（验收矩阵、命令、环境）。
  2. 跑内环：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`；跑集成：`-Dtest=Digest*Test`。
  3. 跑前端 `pnpm -r typecheck` 与相关 e2e。
  4. 写报告：每条 AC 的命令、退出码、结果、证据；未验证项如实写。
- 验证：报告 `status: partial`（e2e 未跑；新代码首次线上采集待 2026-10-27 08:00）
- 执行记录：单测 306/0/13、集成 10/0、迁移闸 9/0、前端 typecheck 0 error；三条反向验证均先红后绿；部署后反代/迁移/健康/前端 bundle 已核验。
- 反向验证：日期映射、跨天唯一键、兜底门禁三条各做一次 red-green。

## 完成条件

- 所有 `TASK-001`–`TASK-008` 为 `done`，或 `blocked` 有明确原因。
- 每条 `AC-*` 在 `testing/report.md` 有证据行；未验证项单独列出。
- 日期映射、跨天唯一键、兜底门禁三条反向验证已记录。
- 仓库内无新增凭据/服务器地址/代理 IP。
- 部署已执行（2026-10-26 17:18）；新代码首次采集待 2026-10-27 08:00，浏览器 e2e 未跑。
