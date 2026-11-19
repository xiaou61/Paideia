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
