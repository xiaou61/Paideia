---
artifact: test-report
work_id: WORK-085
work: 运维日志智能分析（AIOps 补齐）
status: passed
evidence: required
---

# 运维日志智能分析（AIOps 补齐）验证报告

## 验证环境

- 工作树：`F:\Paideia\.agents\worktrees\work-085-aiops-patterns`（分支 `work-085-aiops-patterns`，base `3b30c6fb`）。
- 后端：JDK 25 / Spring Boot 4.1.1 / Maven；守卫与集成用 `scripts/test-db.sh`（Git Bash `C:\Program Files\Git\bin\bash.exe`，连远程独占测试库，同一时刻只跑一轮）。
- 前端：Node + pnpm 12.10.1（`tsc`）。
- 时间：2026-11-19。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 聚类有效 | `mvn -B -pl paideia-ops -am -Dtest=PatternSyncServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ops-unit.txt`（真实压缩率为上线前人工项，见「失败与未验证项」） |
| AC-002 三类异常可检出 | `mvn -B -pl paideia-ops -am -Dtest=PatternAnomalyDetectorTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ops-unit.txt`（频次突增/新模板/模板消失/低 z 不误报/冷启动不误报） |
| AC-003 证据不直灌 | `mvn -B -pl paideia-ai -am -Dtest=OpsDiagnosisPromptTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ai-unit.txt`（v6 含 `<patterns>` 小节） |
| AC-004 闭环触发 | `mvn -B -pl paideia-ops -am -Dtest=PatternTriggerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ops-unit.txt`（冷却抑制 / 忙时跳过） |
| AC-005 降级不崩 | `mvn -B -pl paideia-ops -am -Dtest=PatternSyncServiceTest,PatternQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ops-unit.txt`（接口 404 降级 / 开关关闭输出空） |
| AC-006 脱敏与安全 | `bash ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ApiContractTest,ApiSurfaceTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/guard.txt`（新端点 ADMIN / 401-403 矩阵） |
| AC-007 成本可控 | `mvn -B -pl paideia-ai -am -Dtest=DefaultOpsDiagnoserTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ai-unit.txt`（复用 settleOpsDiagnosis 与 token 上限，无新计费分支） |
| 全量单测（ops 5 类） | `mvn -B -pl paideia-ops -am -Dtest=PatternSyncServiceTest,PatternAnomalyDetectorTest,PatternQueryServiceTest,PatternTriggerServiceTest,PatternControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ops-unit.txt`（17 条，0 failed） |
| 全量单测（ai 3 类） | `mvn -B -pl paideia-ai -am -Dtest=OpsDiagnosisPromptTest,DefaultOpsDiagnoserTest,OpsDiagnosisGoldenSetTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/ai-unit.txt`（34 条，0 failed，1 skipped 为 GoldenSet 既有条件跳过） |
| 守卫/集成 | `bash ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest,OpsDiagnosisServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/guard.txt`（46 条，0 failed） |
| 前端类型检查 | `pnpm --filter @paideia/admin typecheck` | 0 | passed | `testing/logs/admin-typecheck.txt` |
| 架构图可解析 | `node scripts/check-diagrams.mjs` | 0 | passed | 14 个 mermaid 图块全部可解析（596 文件） |

守卫首轮失败过两条（都在修好前）：
1. `patternSyncService` 两个构造器无 `@Autowired` → `No default constructor found`（已加 `@Autowired` + import）。
2. `MigrationChecksumTest.newMigrationsAreRecorded` 未登记新迁移（已用 `-Dmigration.checksums.update=true` 登记两文件）。
修复后重跑全绿。

## 验收结果

| AC | 结果 | 说明 |
| --- | --- | --- |
| AC-001 聚类有效 | passed（单元） | `PatternSyncServiceTest` 6 条通过；真实压缩率列上线前人工项 |
| AC-002 三类异常可检出 | passed | `PatternAnomalyDetectorTest` 5 条 |
| AC-003 证据不直灌 | passed | prompt v6 + 截断 + `maxEvidenceChars` 预算 |
| AC-004 闭环触发 | passed | 冷却/单飞复用 `OpsDiagnosisService.start` |
| AC-005 降级不崩 | passed | 接口不可达降级 / 开关关闭输出空 |
| AC-006 脱敏与安全 | passed | 守卫三件全绿 |
| AC-007 成本可控 | passed | 复用既有计费与上限 |

## 失败与未验证项

- **未验证（not run，需服务器）**：Loki 3.7.9 `pattern_ingester` 实测（`/loki/api/v1/patterns` 真实结构、内存水位）、真实日志压缩率、Grafana `paideia-pattern-anomaly` 真实告警投递。检查项见 `.agent/notes/ops-pattern-ingester.md`。
- GoldenSet 1 条 skipped 为该测试类的既有条件跳过（需活模型/外部环境），与本改动无关。
- 技术债：新类放在 `io.github.xiaou61.ops` 扁平包而非设计稿的 `ops/pattern/` 子包（因 `LokiClient`/`OpsLogRedactor`/`OpsEvidenceCollector` 为 package-private，子包不可见）；功能等价，已在此注明。

## 剩余风险

- 若线上 Loki 版本的 `/loki/api/v1/patterns` 结构与预期不同，`PatternSyncService` 会降级为「本轮 0 条」，不误报但也不产出模板——按笔记的降级路径处理。
- `Gauge` 抓取时读 DB（3 次 countOpen）；DB 抖动时返回 0，不影响 `/metrics`，但会让告警短暂「无异常」。

## 上线实测修正（2026-11-19，部署后追加）

首次部署到 codenest-online 后在服务器上实测，发现并修掉 4 个真实问题。**这些修正未经设计稿签署流程变更，属实测驱动的实现纠偏**，逐条记录如下。

| # | 问题 | 证据 | 修正 |
| --- | --- | --- | --- |
| 1 | `loki-config.yaml` 的 `pattern_ingester.metric_aggregation.enabled` / `downsample_period` 是**不存在的字段**，Loki 会拒绝启动 | `loki -verify-config`：`field enabled not found in type aggregation.Config`、`field downsample_period not found` | 改为在 `limits_config` 里开 `metric_aggregation_enabled: true` / `pattern_persistence_enabled: true`；`metric_aggregation` 只留 `loki_address`。`-verify-config` exit 0、重启后 `/config` 与 journal 正常 |
| 2 | `LokiClient.patterns()` 把窗口内所有采样点**求和成一条**，长窗口数据会全塞进同一个检测桶 | 真实返回是 `data:[{pattern,level,samples:[[epoch秒,条数],...]}]`，10 秒一格 | 改为返回 `PatternSeries(pattern, List<PatternSample(at,count)>)`，保留每条采样时间；`PatternSyncService` 按采样点时间折算桶 |
| 3 | 查询窗口太窄 → 永远查不到模板 | Loki `pkg/pattern/ingester_querier.go` 硬编码 `minClusterSize = 30`，窗口内累计不足 30 条的模板被剪掉（`loki_pattern_ingester_query_pruned_total` 涨）；实测 40 行相同日志能查到，`paideia.service` 60 分钟窗口为空 | 新增 `paideia.ops.patterns.lookback-minutes`（默认 60，必须显著大于检测窗口）；上线 60 分钟窗口可覆盖 15m 桶 |
| 4 | 检测用「当前（半截）桶」与完整基线比，桶刚开始时会把正常低计数误判成 `DISAPPEARED` | 代码走查 + 桶按 15m 对齐的语义 | `PatternAnomalyDetector` 改为只看**最近一个完整窗口**：`windowEnd = bucketStart(now)`、`windowStart = windowEnd - window` |

顺带修掉一处数据放大：`ops_log_templates.total_count` 原为累加（`total_count + VALUES(...)`），每几分钟重算同一段历史会越刷越大，改为 `GREATEST(total_count, VALUES(...))`（= 最近一轮回看窗口内的条数）。

新增回归测试（补测，均通过）：

| 检查项 | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| ops 单测（含新增 `LokiClientPatternsTest` 3 条、`syncSplitsSamplesAcrossBuckets` 1 条） | `mvn -B -pl paideia-ops -am -Dtest=LokiClientPatternsTest,PatternSyncServiceTest,PatternAnomalyDetectorTest,PatternQueryServiceTest,PatternTriggerServiceTest,PatternControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 21 条全绿（ops 相关 21 = sync 7 + detector 4 + query 3 + trigger 2 + controller 2 + loki 3） |
| 配置可解析 | 服务器 `loki -config.file=/etc/loki/loki-config.yaml -verify-config` | 0 | 通过（旧配置此项直接失败） |
| Grafana 规则加载 | `grep -c "uid: paideia-" /etc/grafana/provisioning/alerting/paideia-rules.yaml` | 0 | 18 条（原 17 + `paideia-pattern-anomaly`），`grafana-server` active |
| Grafana 面板加载 | 覆盖 `provisioning/dashboards/paideia-ops.json` + restart | 0 | 最后一个 panel =「日志模板异常（未处理）」 |

### 已知限制（写进运维认知，不是缺陷）

- `minClusterSize = 30` 是 Loki 写死的：本项目约 7 行/分钟的日志量，单模板在短窗口内凑不到 30 条就查不到。
  因此模板表可能长时间为空/很少，这是**正常现象**，不代表同步故障。要更多模板只能拉长 `lookback-minutes`（上限受 Loki `retain_for` 3h 约束）。
- 检测窗口锚定「上一个完整桶」，所以异常从发生到入库最多延迟一个窗口（15m）+ 一个同步周期（5m）。
