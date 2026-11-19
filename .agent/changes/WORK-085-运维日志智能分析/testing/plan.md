---
artifact: test-plan
work_id: WORK-085
work: 运维日志智能分析（AIOps 补齐）
status: ready
---

# 运维日志智能分析（AIOps 补齐）测试计划

## 测试范围

覆盖 WORK-085 新增的「日志模板聚类 → 三类异常检测 → 结构化证据入诊断 → 告警闭环 → 知识检索」链路，以及
它与既有 `OpsDiagnosisService` / `OpsEvidenceCollector` / `OpsDiagnoser` 的接缝。不重复验证既有监控面板、
告警规则、诊断的既有行为（见 WORK-028/042/043/049 的报告）。

## 环境与前置条件

- 后端单元测试：Maven（`backend/`，`paideia-ops` / `paideia-ai`）。纯 Mockito，不依赖数据库与 Loki。
- 守卫与集成测试：`scripts/test-db.sh`（Git Bash 执行），连远程测试库；**独占**该库，同一时刻只跑一轮。
- 前端：`pnpm --filter @paideia/admin typecheck`。
- 服务器项（Loki pattern_ingester 实测、真实日志压缩率、真实告警投递）不在本轮自动化范围，列为人工/上线前检查。

## 验收矩阵

| AC | 判定 | 可执行证据 |
| --- | --- | --- |
| AC-001 聚类有效 | 单元 + 人工 | `PatternSyncServiceTest`（模板/桶写入、hash 稳定）；压缩率需真实日志，列为上线前人工 |
| AC-002 三类异常可检出 | 自动化 | `PatternAnomalyDetectorTest`：detectsFrequencySpike / detectsNewTemplate / detectsDisappeared / lowZNotAnomaly / coldStartDoesNotFlagNewTemplates |
| AC-003 证据不直灌 | 自动化 | `OpsDiagnosisPromptTest`（v6 含 patterns 小节）；`PatternQueryServiceTest.summaryTruncatedToMaxChars`；`OpsEvidenceCollector` 的 `maxEvidenceChars` 预算 |
| AC-004 闭环触发 | 自动化 | `PatternTriggerServiceTest`：cooldownSuppressesSecondTrigger / busySkipsWithoutException；复用 `OpsDiagnosisService.start` 冷却与单飞 |
| AC-005 降级不崩 | 自动化 | `PatternSyncServiceTest.patternsEndpoint404Degrades`；`PatternQueryServiceTest.summaryEmptyWhenDisabled` |
| AC-006 脱敏与安全 | 自动化（守卫） | `ApiContractTest` / `ApiSurfaceTest` / `AuthorizationIsolationTest`（新端点 ADMIN、401/403）；模板文本经 `OpsLogRedactor`/截断 |
| AC-007 成本可控 | 人工/设计 | 未新增 LLM 路径，复用 `CreditService.settleOpsDiagnosis` 与既有 token 上限；无新增计费分支 |

## 自动化检查

- `mvn -B -pl paideia-ops -am -Dtest=PatternSyncServiceTest,PatternAnomalyDetectorTest,PatternQueryServiceTest,PatternTriggerServiceTest,PatternControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=OpsDiagnosisPromptTest,DefaultOpsDiagnoserTest,OpsDiagnosisGoldenSetTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `bash ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest,OpsDiagnosisServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `pnpm --filter @paideia/admin typecheck`

## 人工检查

- 服务器开启 `pattern_ingester` 后 `curl /loki/api/v1/patterns` 返回结构正确（见 `.agent/notes/ops-pattern-ingester.md`）。
- 用一段真实日志核对压缩率与模板语义（AC-001）。
- Grafana 告警 `paideia-pattern-anomaly` 能 firing 并投递到 `paideia-ops` 收件人。

## 回归范围

- 既有诊断链路（`OpsDiagnosisServiceTest` 11 条）与端点契约（ApiContract/ApiSurface/AuthorizationIsolation）。
- 迁移三件（版本格式/校验和/种子隔离），确认新增两迁移可解析且已登记。

## 已知缺口

- Loki 实验性 `/loki/api/v1/patterns` 在 3.7.9 的结构未在服务器实测（本轮只做代码降级路径）。
- 真实压缩率、真实告警投递未验证。
