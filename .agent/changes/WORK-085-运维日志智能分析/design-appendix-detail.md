# WORK-085 设计附录：测试策略 / 需求追踪 / 待决定事项

> `design.md` 的明细附录（为把核心设计控制在恢复软阈值内而拆出）。决策与变更以 `design.md` 为准，本文件是可执行清单与追踪表。

## 11. 测试策略

内环（按 AGENTS.md，`scripts/test-db.sh` 包住、带 `-T 1C`）：
- 单测：`PatternAnomalyDetectorTest`（三类 + 边界：ratio 达标但 z 不足不算、NEW 的 minCount、DISAPPEARED 连续窗口）、`PatternSyncServiceTest`（假 `LokiClient`/接口 404 降级）、`PatternQueryServiceTest`（`maxPatternChars` 截断）、`PatternMapper` 映射测试（`PatternsSqlTest` 或 MyBatis 集成）。
- 改动的既有测试同步修：`OpsDiagnosisPromptTest`（v6 + `<patterns>`）、`OpsDiagnosisGoldenSetTest`、`DefaultOpsDiagnoserTest`、`OpsDiagnosisServiceTest`（Evidence 12 参数）。
- `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`（约 88s）。
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test`（约 66s；新端点必须被 ApiContractTest 覆盖访问级别、迁移三件校验命名与校验和、ModularityTest 确认新包在 paideia-ops 内）。

外环（只改动那一端、只到文件级）：
- 管理端 `frontend/apps/admin`：`pnpm playwright test`（若存在 admin e2e 文件）或人工按 `OpsPage` 卡片验收；不跑全站 e2e（未改共享包）。
- Loki/Grafana 侧为部署实测：切片一在真实 Loki 3.7.9 上验证 `pattern_ingester` 启动、内存、`/loki/api/v1/patterns` 返回结构与压缩比（AC-001 抽样核对）。

可复现用例（AC-002）：用固定种子日志窗口造「频次突增 / 新模板 / 模板消失」三组 fixture，断言 `ops_pattern_anomalies` 各出一条且 kind 正确。

## 12. 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001 日志结构化字段 | 沿用 journald→Alloy→Loki 不动；仅核对 `alloy-config.alloy` unit/level 标签与日志格式（无新增采集组件），在切片一验证 |
| REQ-002 模板抽取/聚类 | 变更 #1 Loki `pattern_ingester`；#5 `PatternSyncService` 落库；AC-001 压缩比实测 |
| REQ-003 模板频次基线 | #5 + `ops_log_template_buckets`（附录 §5.2）+ `baseline-weeks` |
| REQ-004 三类异常 | #6 `PatternAnomalyDetector`（§4-B）+ `ops_pattern_anomalies` |
| REQ-005 结构化证据入诊断 | #10/#11/#12（Evidence.patterns + prompt v6）+ `PatternQueryService.summary` |
| REQ-006 接入告警闭环 | #7 `PatternTriggerService` + #17 告警规则 + 复用 `OpsDiagnosisService` |
| REQ-007 知识闭环 | #14/#15 三表+全文索引 + #13 `knowledge/search`（MySQL 全文，不向量化） |
| REQ-008 最小服务拓扑 | #8 `PatternQueryService` 输出依赖行（配置化，不引 CMDB） |
| REQ-009 可配置可降级 | `paideia.ops.patterns.*`（附录 §5.4）+ §7 降级路径 |
| REQ-010 观测与成本 | §9 Micrometer 指标 + WORK-009 记账 |

验收对应：AC-001→切片一/二；AC-002→切片二 + §11 fixture；AC-003→切片三 `maxPatternChars`/`maxEvidenceChars`；AC-004→`PatternTriggerService` 冷却+单飞；AC-005→§7；AC-006→§8 + ApiContract/AuthorizationIsolation；AC-007→§5.5 容量 + WORK-009 封顶。

## 13. 待决定事项

1. **pattern ingester 参数与内存实测**：`MemoryMax` 512M→1024M 仅为起始值，切片一在真实 Loki 3.7.9 实测后定最终值；`downsample_period`、chunk 与 tsdb v13 兼容性同批验证。
2. **`/loki/api/v1/patterns` 可用性**：3.7.9 实验接口若不可用，降级读 `loki_patterns_*` Prometheus 指标（两条路径都已实现，实测后二选一）。
3. **阈值初值**：`freq-spike-ratio=3.0`、`freq-spike-z=3.0`、`new-template-min-count=10`、`disappeared-min-baseline=20` 为建议值，先保守观察一周后按实测基线修正（proposal 已列同类风险）。
4. **日志日量（Q3）**：仍无准确数字；容量参数全部可配，上线实测再调，不作阻塞。
5. **触发是否升级为正式 event/incident**：当前 `PatternTriggerService` 不写 `ops_incidents`（仅在 `ops_pattern_anomalies.incident_id` 留空），只复用 `start` 的单飞/邮件/落库；若后续需要事件时间线合并，再评估复用 `OpsIncidentService.upsertFiring`（需为其合成 fingerprint，属增量改动，不在本期）。
