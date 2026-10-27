---
artifact: test-plan
work_id: WORK-043
work: AIOps 完整化：事件、基线、链路、SLO 与评测
status: ready
created: 2026-10-27
updated: 2026-10-27
---

# AIOps 完整化测试计划

## 测试范围

- **P0 事件地基**：迁移、指纹、upsert/resolve、时间线、诊断挂载/冷却、事件端点、管理端事件页、Grafana 分组/抑制。
- **P1 基线**：录制规则、证据基线、动态告警、日志量基线。
- **P2 链路**：OTel starter、Tempo、trace id 统一、日志关联、trace 证据/脱敏。
- **P3 SLO**：AI/登录/结算指标、SLI 录制规则、burn-rate 规则、面板。
- **P4 评测**：评分、金集、重放、helpful 率。
- 不测：自动修复（非目标）、多主机/K8s（非目标）。

## 环境与前置条件

- 后端测试一律走 `scripts/test-db.sh`（独占库 `paideia_run_*`）；不直接连共用 `paideia_test`。
- 前端 e2e 用本地后端 + 独占测试库；管理端端口用 `PAIDEIA_ADMIN_PORT` 覆写。
- Prometheus/Tempo 规则用 `promtool check rules` / `promtool test rules`。
- 生产验证通过 `ssh codenest-online`；Grafana 口令只从 `/etc/grafana/grafana.env` 读取，不写入仓库。
- live 评测需要 `PAIDEIA_OPS_EVAL=1` 与真实模型；默认 CI 只跑 prompt 模式。

## 验收矩阵

| AC | 检查 | 阶段 | 位置 / 命令 |
| --- | --- | --- | --- |
| AC-001 | 重复/恢复/重开只产生预期事件 | P0 | `OpsIncidentMapperTest`、`OpsHookIntegrationTest` |
| AC-002 | 时间线顺序与只追加 | P0 | `OpsIncidentMapperTest.detailAndNote`、事件页 e2e |
| AC-003 | 诊断挂事件、事件级冷却 | P0 | `OpsDiagnosisServiceTest`、`OpsHookIntegrationTest` |
| AC-004 | 证据含 1h/24h/7d 基线，缺失写「基线不足」 | P1 | `OpsEvidenceCollectorTest` |
| AC-005 | 动态基线规则加载；promtool 三用例 | P1 | `promtool test rules`、Grafana API |
| AC-006 | 日志量基线空闲不触发 | P1 | Grafana API + 空闲观察 |
| AC-007 | Tempo active/回环；查到真实 trace | P2 | `systemctl`、Tempo/Grafana API |
| AC-008 | X-Trace-Id 与日志 traceId 一致 | P2 | `TraceIdFilterTest`、生产 curl |
| AC-009 | 诊断证据含 trace 摘要 | P2 | `TempoClientTest`、`OpsEvidenceCollectorTest` |
| AC-010 | 评分落库、helpful 率可见 | P4 | `OpsFeedbackIntegrationTest`、e2e |
| AC-011 | 金集 prompt 模式通过；live 手动记录 | P4 | `OpsDiagnosisGoldenSetTest` |
| AC-012 | SLI 指标可查；无样本不误报 | P3 | actuator/promtool |
| AC-013 | 4–6 条 burn 规则加载；快烧可触发 | P3 | Grafana API + 演练 |
| AC-014 | SLO 告警走事件 + 诊断 | P3 | 演练 + 报告 |
| AC-015 | WORK-042 行为不变；切片 0 failed | P0–P4 | 回归切片 |
| AC-016 | 无新增公网端口；trace 无请求体/凭据 | P2 | `ss`、`TempoTraceRedactorTest` |

## 自动化检查

```bash
# P0 事件
cd backend && ../scripts/test-db.sh mvn -B -pl paideia-app -am \
  -Dtest=OpsIncidentFingerprintTest,OpsIncidentMapperTest,OpsIncidentIntegrationTest,OpsDiagnosisServiceTest,OpsDiagnosisIntegrationTest,OpsHookIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 迁移/模块边界
cd backend && ../scripts/test-db.sh mvn -B -pl paideia-app -am \
  -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 规则（P1/P3）
promtool check rules deploy/ops/prometheus-rules.yml
promtool test rules deploy/ops/prometheus-rules.test.yml

# 前端
cd frontend && pnpm --filter @paideia/admin typecheck
cd frontend && PAIDEIA_BACKEND_PORT=18081 PAIDEIA_ADMIN_PORT=15175 \
  ../scripts/test-db.sh pnpm --filter @paideia/admin exec playwright test e2e/incidents.spec.ts
```

## 人工检查

- 事件页：侧栏有「事件」入口；空状态/列表文案；时间线按时间顺序；备注追加。
- Grafana：通知策略 `group_by` 为 `['alertname','severity']`；联系人仍是 `paideia-ops`。
- P2：日志行含 trace id，点日志能跳 Tempo；trace 中无 SQL/请求体/Authorization。
- P3：SLO 面板显示预算/燃烧率；构造快烧能触发且走事件路径。
- P4：评分按钮写入后 helpful 率变化；金集 live 报告有失败清单。

## 回归范围

- WORK-042 的 6 条告警、诊断、运维页、MCP 工具行为不变。
- 学习者/管理员权限隔离不变。
- 迁移只新增表/列，不改既有列。
- `release.ps1` 仍只重启 `paideia`；监控单元独立。

## 已知缺口

- P1–P4 尚未实施；本计划先行，报告在每阶段完成后追加。
- 金集 live 模式不在 CI 自动运行（成本）；只有 prompt 模式进 CI。
- 基线/ SLO 规则的 Grafana 调度语义无法用 promtool 完全覆盖；最终以 Grafana API 的 health/state 为准。
- Tempo 磁盘/内存预算按估算，部署后记录实测。
