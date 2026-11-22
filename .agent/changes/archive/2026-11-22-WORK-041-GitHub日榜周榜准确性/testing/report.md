---
artifact: test-report
work_id: WORK-041
work: GitHub 日榜/周榜准确性
status: passed
evidence: required
created: 2026-10-26
updated: 2026-10-27
verified_commit: d02dfa5 + 2026-10-27 线上复核
accepted_by: xiaou61
accepted_at: 2026-10-29 13:20:56 +0800
---

# GitHub 日榜/周榜准确性验证报告

## 验证环境

- 工作树：`F:\Paideia\.worktrees\work-041`，分支 `work/WORK-041`，基线 `ae7b96a`。
- 后端：JDK 25，独占测试库由 `scripts/test-db.sh` 分配（`paideia_run_*`，成功后删除）。
- 前端：`pnpm install --frozen-lockfile` 后 `pnpm -r typecheck`。
- 外部端点全部用 stub/fixture；真实页面与 history 端点已在调研阶段用线上请求验证，不在本自动化内。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 | `scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=GithubTrendingSourceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 通过 | `pageCandidatesUseRealDeltas`：heat=d1=250、starsWeek=d7=850；线上重合度见调研报告，未在部署后复验 |
| AC-002 | 同上 | 0 | 通过 | `emptyPageFallsBackToSearchWithGate`：age<7 的垃圾被挡、d1=1292/forks=424 保留、`sourceName=GitHub 新项目` |
| AC-003 | `... -Dtest=GithubStarHistoryTest` | 0 | 通过 | `weekLabelIsSundayButDaysStartMonday`：10-25=267、10-24=15；反向验证 2 条红 |
| AC-004 | `... -pl paideia-app -am -Dtest=DigestIntegrationTest` | 0 | 通过 | `sameFingerprintOnDifferentDaysIsAllowed` + `duplicateFingerprintIsRejected`；反向验证回退唯一键 → DuplicateKey |
| AC-005 | 集成 + `... -Dtest=DigestItemViewTest` | 0 | 通过 | `githubStarsAreStored`（250/850）+ `githubStarsAreSerialized`（JSON 字段 `starsDay`/`starsWeek`） |
| AC-006 | `cd frontend && pnpm -r typecheck` | 0 | 通过 | 所有包 Done；**e2e 未跑**，见「失败与未验证项」 |
| AC-007 | `... -Dtest=GithubTrendingSourceTest` | 0 | 通过 | `historyFailureFallsBackToPagePeriodStars`：回退页面 3856 / 8000 |
| AC-008 | `... -Dtest=GithubStarHistoryTest` | 0 | 通过 | `sameHostRedirectIsFollowed` + `crossHostRedirectIsRejected`（跨 host 拒绝） |
| AC-009 | 集成 + `... -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest ...` | 0 | 通过 | Flyway 成功应用 44 条迁移；新索引存在、旧索引 0；闸 9/0 |
| AC-010 | 配置 + 进程环境检查（人工） | 人工 | 通过 | `DigestProperties.cron`/`DigestJob`/`application.yml` 为 `0 0 8 * * *`；2026-10-26 17:18 部署后 `/proc/<pid>/environ` 已核验 `DIGEST_CRON=0 0 8 * * *`；首次触发待 2026-10-27 08:00 |
| AC-011 | `git diff` 扫描（人工） | 人工 | 通过 | 无新增凭据/服务器地址/代理 IP；配置只留 `DIGEST_*` 环境变量占位 |
| AC-012 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` + 集成 | 0 | 通过 | 306 执行 / 0 失败 / 13 跳过 + 集成 10/0；新闻/社区/HN 既有用例全绿 |
| 后端单测全集 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 0 | 通过 | 306 执行 / 0 失败 / 13 跳过（跳过的是需要环境变量的 live 用例） |
| 迁移与模块闸 | `... -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 通过 | 9 执行 / 0 失败 |
| 前端类型检查 | `cd frontend && pnpm -r typecheck` | 0 | 通过 | 15 个包全部 Done，0 error |
| 反向验证：日期映射 | 把 `plusDays(index + 1)` 改成 `plusDays(index)` 后跑 `-Dtest=GithubStarHistoryTest` | 1 | 通过 | 预期红：`weekLabelIsSundayButDaysStartMonday` 与 `saturdayIsFifthDay` 共 2 条失败；恢复后 5/0 |
| 反向验证：兜底门禁 | 把 `passesGate` 改成 `return true` 后跑 `-Dtest=GithubTrendingSourceTest` | 1 | 通过 | 预期红：`emptyPageFallsBackToSearchWithGate` 1 条失败；恢复后 5/0 |
| 反向验证：跨天唯一键 | 把迁移唯一键改成 `(fingerprint)` 后跑 `-Dtest=DigestIntegrationTest` | 1 | 通过 | 预期红：`sameFingerprintOnDifferentDaysIsAllowed` 抛 `DuplicateKey`；恢复后 10/0 |
| 部署：反代 | 生产机 `curl 'http://127.0.0.1:8082/trending?since=daily'` 与 `since=weekly` | 0 | 通过 | nginx 1.24，127.0.0.1:8082；daily 200 / 585594 字节 / 11 篇；weekly 200 / 619228 / 14 篇 |
| 部署：迁移与健康 | `systemctl is-active paideia` + `journalctl -u paideia` + `/actuator/health` | 0 | 通过 | active；`Migrating schema paideia to version 20261026160000` + `Successfully applied 1 migration`；health `UP` |
| 部署：前端产物 | `grep -rl '按昨日新增 star' /opt/paideia-app/web/assets` | 0 | 通过 | `index-BsMloTKB.js` 命中；release 118.8 秒 |

## 验证结果

- **单元**：`paideia-ai` 及除 `paideia-app` 外的全部模块 306 执行 / 0 失败 / 13 跳过；13 条跳过均为需要真实上游或环境变量的 live 用例，不是被静默漏跑的业务用例。
- **集成**：`DigestIntegrationTest` 10 执行 / 0 失败；Flyway 在独占库应用 44 条迁移，新唯一键与 `stars_day/stars_week` 生效。
- **迁移与模块闸**：9 执行 / 0 失败；新迁移文件名/校验和/种子重叠/模块边界均通过。
- **前端**：`pnpm -r typecheck` 全绿；新增的「今日/本周」切换与卡片数字通过类型检查。
- **反向验证**：日期映射、兜底门禁、跨天唯一键三条都先红后绿，红的原因与预期一致。
- **部署**：nginx 1.24 安装并 enable，`127.0.0.1:8082` 反代 daily 200/585594 字节/11 篇、weekly 200/619228/14 篇；`/actuator/health` UP；Flyway 在 `paideia` 应用 `V20261026160000`；release 118.8 秒；新前端 `index-BsMloTKB.js` 含「按昨日新增 star」。
- **未做**：新代码的首次线上采集（明天 08:00 才跑；今天的 GitHub 组仍是旧代码数据，`stars_day/week` 为 NULL）、浏览器 e2e（本会话没有线上 learner 口令）、真实端点的自动化（测试用 stub）。

## 验收结果

- **AC-003 / AC-004 / AC-005 / AC-007 / AC-008 / AC-009 / AC-011 / AC-012**：有自动化或人工证据，判定通过。
- **AC-001 / AC-002**：**2026-10-27 复核已通过**。新代码的首次线上采集在 08:00 发生，见下方「2026-10-27 复核补记」。
- **AC-006**：**2026-10-27 线上 e2e 通过**（1 passed / 2.7s）。
- **AC-010**：**2026-10-27 复核已通过**。cron 准时触发，日志与库内数据一致。

### 2026-10-27 复核补记（把三条 partial 收尾）

WORK-041 报告写下时是部署当天 17:18，三个"待观察"都因为**时间还没到**而无法判定。次日（10-27）复查：

**AC-001 / AC-002（新格式数据真的落库了）**

```
mysql> select edition_date, count(*), sum(stars_day is not null), sum(stars_week is not null)
       from digest_items where group_key='github' group by edition_date desc limit 5;
2026-10-27 | 14 | 14 | 14   ← 新代码采集，全部带星标增量
2026-10-26 | 16 |  0 |  0   ← 旧代码
2026-10-25 |  8 |  0 |  0
2026-10-24 |  6 |  0 |  0
2026-10-23 | 14 |  0 |  0
```

14 条 GitHub 条目**全部**有 `stars_day` 与 `stars_week`，界面不再显示 `+0★`。

**AC-010（cron 按计划触发）**

```
Oct 27 08:00:09 GithubTrendingSource: GitHub 候选：日榜 15、周榜 16、去重 28；
                        history 成功 27、失败 1；入候选 14
Oct 27 08:00:55 DigestCollectionService: 速递完成：候选 133 条，入库 60 条，失败源 0 个
```

`DIGEST_CRON=0 0 8 * * *`，10-27 08:00 准时执行；"入候选 14" 与库里的 14 条完全对上。
日志里 `history 失败 1` 是 `trending/developers` 这类非仓库 slug 的 404，属预期降级。

**AC-006（浏览器行为）**

`E2E_BASE_URL` 指向线上、用 `e2e-learner` 账号跑：

```
ok 1 [chromium] › e2e\digest.spec.ts:118 › GitHub 组有内容时可在今日/本周之间切换 (2.7s)
1 passed
```

同时人工用 Playwright 直查组标题 `h2`，确认文案在两个方向都变：
`"GitHub 热榜 / 14 / 按昨日新增 star / 今日 / 本周"` →
点「本周」→ `"… 按本周新增 star …"` → 点「今日」→ `"… 按昨日新增 star …"`。

**复核时顺手修的一条测试缺陷**：原用例直接 `goto('/#/digest')`，依赖"页面默认落到的那一天恰好有 GitHub 组"。
线上那天没有时它会 `test.skip`——**把"没测到"伪装成"不需要测"**。改为依次点前 7 个日期选项直到找到
带 GitHub 组的一天，找不到才 skip。同一处把断言范围从整组 `innerText`（含 14 条正文）收窄到组标题
`h2`，更快也更不容易误伤。

## 失败与未验证项

> **本节三条已在 2026-10-27 复核中全部解除**，原文保留以便对照。

- ~~AC-006 的 e2e 未跑~~ → 已跑通（1 passed / 2.7s）。
- ~~新代码首次线上采集未发生~~ → 08:00 已发生，14 条全部带星标增量。
- **完整 `ApiWalkthroughTest` 未跑**：只跑了非 `paideia-app` 全集 + `DigestIntegrationTest`；`api-shape.json` 里 digest 的条目是空数组，新增 item 字段不会改变快照。**这条仍未跑**，但它与本项无关（本项不改接口形状），保留为已知缺口。
- **真实 GitHub 端点的自动化未纳入**：调研阶段已用线上请求验证（30 周、每日增量、301、5000/h、中位 1.61s），生产机也已验证反代与 history 端点 200；但测试套件里是 stub，端点变更只能靠部署后的日志暴露。

## 剩余风险

- star 历史端点只有约一个月；变更时降级到页面 period stars，再失败则整组为空，不会崩。
- 本机反向代理是新增服务；挂掉时等同页面不可达，界面会显示「GitHub 新项目」而不是热榜。
- 「昨日/本周」按 UTC 完整日；产品文案按北京时间，已在卡片写「昨日」并在配置注释说明，但用户仍可能对不上。
- 刷星无法根治：页面候选由 GitHub 先筛，兜底只靠年龄/forks 门禁。
- 「准不准」没有客观真值；本报告能证明的是「信号与降级行为正确」，不能证明「全网最热」。
