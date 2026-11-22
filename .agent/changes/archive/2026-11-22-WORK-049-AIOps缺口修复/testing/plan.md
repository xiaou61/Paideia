---
artifact: test-plan
work_id: WORK-049
work: AIOps 缺口修复：事件闭环、历史复用、告警关联与评测可信度
status: approved
created: 2026-10-28
updated: 2026-10-28
---

# WORK-049 测试计划

## 测试范围

- 事件生命周期：resolved 投递、手动关闭、关闭后再触发、keyset 分页与时间筛选。
- 诊断证据：历史同类、同期 OPEN 事件、z-score、日志单元白名单、12k 预算。
- 诊断编排：同实例复用窗口、重启补诊断、现有冷却与单飞不退化。
- 规则与部署：z-score 录制规则与 promtool 用例、阈值占位符渲染、安装脚本语法。
- 金集：24 条 prompt 模式；live 模式手动（本次不跑）。
- 管理端：事件页 typecheck 与构建；深链只做类型/构建级验证。

## 环境与前置条件

- 后端：`scripts/test-db.sh` 分配独占 MySQL 库；本机 MySQL 3306（凭据在忽略文件里）。
- 规则：服务器 `codenest-online` 上的 `promtool`（本机未安装）。
- 前端：工作树内 `pnpm install --filter @paideia/admin...`；本机无浏览器自动化所需的完整服务，不跑 e2e。
- 生产验证（AC-001/008/012）需要：干净工作树部署 + `install-ops.sh` + 用户批准的时间窗口。

## 验收矩阵

| 验收 | 检查方式 | 环境 |
| --- | --- | --- |
| AC-001 | 本地集成等价路径 + 部署后合成 webhook | 本地 / 生产 |
| AC-002/003 | `OpsIncidentIntegrationTest`、`OpsIncidentMapperTest` | 本地测试库 |
| AC-004/005 | `OpsIncidentContextTest`、`OpsEvidenceCollectorTest` | 本地单测 |
| AC-006 | `OpsDiagnosisServiceTest`、`OpsIncidentIntegrationTest` | 本地 |
| AC-007 | `promtool check/test rules`；Grafana 加载 | 服务器 / 部署后 |
| AC-008 | `check-ops-thresholds.mjs` 正负向；部署后两侧一致性 | 本地 / 生产 |
| AC-009 | `OpsDiagnosisServiceTest`、`OpsIncidentMapperTest` | 本地 |
| AC-010/011 | `OpsDiagnosisGoldenSetTest` prompt / live | 本地 / 有 Key 的环境 |
| AC-012 | 维护窗口临时阈值演练 | 生产 |
| AC-013 | admin typecheck + build + 人工点击 | 本地 |
| AC-014 | `OpsEvidenceCollectorTest` | 本地 |
| AC-015 | `Ops*Test` + 非 app 全量 + 模块/迁移守卫 | 本地测试库 |

## 自动化检查

1. `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='Ops*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
2. `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`
3. `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test`
4. `ssh codenest-online "cd /tmp/paideia-w49 && promtool check rules prometheus-rules.yml && promtool test rules prometheus-rules.test.yml"`
5. `node scripts/check-ops-thresholds.mjs`（含负向：把比例改成 1.5 必须非零退出）
6. `bash -n deploy/ops/install-ops.sh`
7. `frontend/node_modules/.bin/tsc -p apps/admin/tsconfig.json` 与 `pnpm --filter @paideia/admin build`

## 人工检查

- 部署后：合成 webhook firing → resolved → firing，核对 2 个事件与时间线（AC-001）。
- 部署后：改一个阈值 → 重跑 install-ops.sh → 重启两侧 → 摘要与 Grafana 规则读数一致（AC-008）。
- 有 Key 的环境：跑 live 金集并把报告路径写进本报告（AC-011）。
- 维护窗口：临时阈值触发一次 SLO 告警 → 事件 → 诊断 DONE → 恢复（AC-012）。
- 事件页点两个 Grafana 链接，落到对应时间窗与日志面板（AC-013）。

## 回归范围

- 全部 `Ops*Test`（事件、诊断、证据、反馈、摘要、工具）。
- 非 `paideia-app` 模块全量（AI、知识库、账号、安全等）。
- 模块边界与迁移守卫。
- 管理端 typecheck 与构建。
- 不跑学习者端 e2e（本项未触及学习者端）；不跑三端全量（未改共享包）。

## 已知缺口

- 生产部署与 `install-ops.sh` 尚未执行：AC-001/007（Grafana 加载）/008/012 只能等部署后。
- live 金集需要真实 Key；本机未设置。
- AC-013 未做浏览器点击，只到类型与构建。
- 事件页 e2e（`incidents.spec.ts`）未跑：它需要后端 jar 与完整本地服务，本项改动已由 typecheck/build 与后端集成测试覆盖主路径。
