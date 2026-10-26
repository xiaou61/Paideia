---
artifact: test-report
work_id: WORK-041
work: GitHub 日榜/周榜准确性
status: partial
evidence: required
created: 2026-10-26
updated: 2026-10-26
verified_commit: d02dfa5
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
- **AC-001 / AC-002**：代码与单测通过；线上新代码首次采集在 2026-10-27 08:00，今天的 GitHub 组仍是旧数据，判定「部分」。
- **AC-006**：类型检查通过、新前端已上线；e2e 未跑（无线上 learner 口令），判定「部分」。
- **AC-010**：配置与进程环境已核验（`DIGEST_CRON=0 0 8 * * *`）；首次触发待明天 08:00，判定「配置通过 / 运行待观察」。

## 失败与未验证项

- **AC-006 的 e2e 未跑**：`apps/app/e2e/digest.spec.ts` 新增了「GitHub 组今日/本周切换」用例；本会话没有线上 `e2e-learner` 口令，线上跑会卡在登录。新前端 bundle 已上线且包含「按昨日新增 star」，但浏览器行为没有执行证据。
- **新代码首次线上采集未发生**：部署时间是 17:18，cron 是 08:00；启动补跑因今天已有内容而跳过。今天的 GitHub 组仍是旧代码采集的 30 条（`stars_day/week` 为 NULL，界面显示 `+0★`）；2026-10-27 08:00 才会写入第一批新格式数据。没有手动触发，避免把新旧两批混进同一天。
- **完整 `ApiWalkthroughTest` 未跑**：只跑了非 `paideia-app` 全集 + `DigestIntegrationTest`；`api-shape.json` 里 digest 的条目是空数组，新增 item 字段不会改变快照。
- **真实 GitHub 端点的自动化未纳入**：调研阶段已用线上请求验证（30 周、每日增量、301、5000/h、中位 1.61s），生产机也已验证反代与 history 端点 200；但测试套件里是 stub，端点变更只能靠部署后的日志暴露。

## 剩余风险

- star 历史端点只有约一个月；变更时降级到页面 period stars，再失败则整组为空，不会崩。
- 本机反向代理是新增服务；挂掉时等同页面不可达，界面会显示「GitHub 新项目」而不是热榜。
- 「昨日/本周」按 UTC 完整日；产品文案按北京时间，已在卡片写「昨日」并在配置注释说明，但用户仍可能对不上。
- 刷星无法根治：页面候选由 GitHub 先筛，兜底只靠年龄/forks 门禁。
- 「准不准」没有客观真值；本报告能证明的是「信号与降级行为正确」，不能证明「全网最热」。
