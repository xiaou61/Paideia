---
artifact: test-plan
work_id: WORK-041
work: GitHub 日榜/周榜准确性
status: ready
created: 2026-10-26
updated: 2026-10-26
---

# GitHub 日榜/周榜准确性测试计划

## 测试范围

- 新增 `GithubStarHistory` 的日期映射、重定向、缺失与失败回退。
- 页面解析（日榜/周榜、赞助商、总 star）与候选合并。
- `GithubTrendingSource` 的 history 集成、并发、兜底门禁与来源标签。
- 迁移：唯一键替换、两列新增、跨天重复、同日拒绝。
- 采集编排：按组预检、`stars_day/stars_week`、GitHub 标题固定。
- 前端：类型、今日/本周切换、卡片数字与档位。
- 非目标回归：news/community/hackernews 行为不变。

## 环境与前置条件

- 后端测试必须走 `scripts/test-db.sh`（独占 `paideia_run_*` 库），不直连共用 `paideia_test`。
- 前端类型检查：`cd frontend && pnpm -r typecheck`。
- e2e 需要后端 jar 与前端 dev server；若本地速递数据为空，切换用例改为组件/手工检查并在报告注明。
- 外部端点用 stub/fixture，不在测试里依赖真实 GitHub；真实端点的探针结果见调研报告。

## 验收矩阵

| AC | 检查 | 命令/步骤 | 预期 |
| --- | --- | --- | --- |
| AC-001 | 页面候选与官方重合 | `GithubTrendingSourceTest` | 页面日/周候选合并正确；前 10 来自官方候选 |
| AC-002 | 兜底标签与门禁 | `GithubTrendingSourceTest.fallbackGateDropsFreshSpam` | 垃圾被挡；真爆款保留；`sourceName=GitHub 新项目` |
| AC-003 | 日期映射 | `GithubStarHistoryTest.weekLabelIsSundayButDaysStartMonday` | 10-24=15、10-25=267 |
| AC-004 | 跨天/同日去重 | `DigestIntegrationTest.sameFingerprintOnDifferentDaysIsAllowed`、`duplicateFingerprintIsRejected` | 跨天成功；同日 Duplicate |
| AC-005 | 两字段落库与返回 | `DigestIntegrationTest` + controller 断言 | GitHub 非空、其它 null |
| AC-006 | 前端切换 | `pnpm -r typecheck` + `digest.spec.ts`/手工 | 排序与数字一致；0 error |
| AC-007 | history 失败回退 | `GithubTrendingSourceTest.historyFailureFallsBackToPagePeriodStars` | 回退页面 period stars |
| AC-008 | 301 同 host/跨 host | `GithubStarHistoryTest` | 同 host 成功；跨 host 拒绝 |
| AC-009 | 迁移与索引 | `DigestIntegrationTest.tablesAndKeysExist` | 新索引存在、旧索引不存在 |
| AC-010 | cron 08:00 | 配置断言 + 启动日志 | 默认 `0 0 8 * * *` |
| AC-011 | 无新凭据/地址 | `git diff --cached` 扫描 + `rg` | 0 命中 |
| AC-012 | 其它分组不变 | 既有 `Digest*Test`、`DigestIntegrationTest` | 全绿 |

## 自动化检查

```bash
# 后端单元（除集成套件）
scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test

# 定向集成
scripts/test-db.sh mvn -B -pl paideia-app -am \
  -Dtest=DigestIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test

# 前端类型
cd frontend && pnpm -r typecheck

# 前端 e2e（视本地数据）
cd frontend && pnpm --filter @paideia/app test:e2e -- digest.spec.ts
```

## 人工检查

- 启动日志出现「GitHub 候选：日榜 N、周榜 M、history 成功 X、失败 Y」。
- 页面不可达时出现「改用 Search」与 `sourceName=GitHub 新项目`。
- 迁移后 `information_schema.statistics` 只有新唯一键。
- 页面切换后卡片顺序与数字一致。

## 回归范围

- `DigestCollectionServiceTest`、`DigestSummarizerTest`、`DigestWeekly*Test`、`GithubTrendingSourceTest`。
- `DigestIntegrationTest` 的既有窗口断言。
- `DigestPage` 的非 GitHub 分组展示。

## 已知缺口

- 真实 GitHub 页面与 history 端点的线上验证不在自动化内；由影子运行与部署后手工检查覆盖。
- 反向代理安装与 cron 变更属于部署，需单独授权；本计划只验证配置准备。
- 若本地速递库为空，前端切换只能做类型/单元检查，e2e 记为 not run。
