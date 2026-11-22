---
artifact: test-report
work_id: WORK-074
work: MCP 对外面与契约观测加固
status: passed
evidence: required
created: 2026-11-05
updated: 2026-11-05
---

# 验证报告

## 验证环境

- 日期：2026-11-05；工作树 `F:\Paideia\.worktrees\WORK-074`（分支 `work/WORK-074`）。
- 本地 Maven（JDK25，离线 `-o`，`~/.m2` 已缓存）；DB 用服务器 `codenest-online` 上**临时独占库**（`paideia_run_w074*`，`CREATE DATABASE`→跑→`DROP DATABASE`+`DROP USER`，无残留）。
- 纯单测无需 DB；需上下文的用临时库跑。代码**未 push、未部署**。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 工具治理快照（67 tool） | `mvn -o -pl paideia-app -am -Dtest=ToolGovernanceTest test` | 0 | passed | `surefire-reports/…ToolGovernanceTest` 5/5；`docs/architecture/tool-inventory.md` 67 行 |
| AC-002 tool 拒绝矩阵（无副作用） | `mvn -o -pl paideia-mcp -am -Dtest=McpToolInvokerTest test` | 0 | passed | 20/20（含 `callsAreCountedPerTool`、`adminToolRequiresAdminRole…`） |
| AC-002 每工具可见性 | `-Dtest=ToolGovernanceTest`（`everyToolHasCorrectReadVisibility`） | 0 | passed | 5/5 |
| AC-003 REST↔tool 审计 | 人工核对 `docs/architecture/rest-vs-tools.md` | 0 | passed | 19 toolset / 67 tool / 190 端点 |
| AC-004 事件发布方守卫 | `mvn -o -pl paideia-app -am -Dtest=CrossModuleContractTest test` | 0 | passed | 3/3；反向注入 2 处后 `Failures:2` 再 `git checkout` 还原 |
| AC-005 ai 包边界守卫 | 同上（`noAiInternalImportsFromOtherModules`） | 0 | passed | 0 越界；反向注入红 |
| AC-006 MCP by_tool 指标 | `-Dtest=McpToolInvokerTest` | 0 | passed | `paideia.mcp.tool.by_tool` 断言 20/20 |
| AC-006 导入运行记录 | `-Dtest=ContentImportRunRegistryTest` | 0 | passed | 3/3 |
| AC-006 端点契约 | `-Dtest=ApiSurfaceTest` | 0 | passed | 7/7（新端点 `@ApiAccess(ADMIN)`） |
| AC-007 导入时效 Gauge | `-Dtest=ContentImportMetricsTest` | 0 | passed | 1/1（-1→0） |
| AC-007 漂移哈希确定 | `node scripts/check-content-drift.mjs --hash-only`（两次） | 0 | passed | 64 位 sha256 一致 |
| 整体上下文 + 接口契约 | `-Dtest=ApiContractTest,ApiSurfaceTest`（临时库） | 0 | passed | 10/10 + 7/7 = 17/17 |

## 验收结果

| AC | 结论 | 证据 |
| --- | --- | --- |
| AC-001 | 满足 | 67 tool 清单 + 不变量 5/5 |
| AC-002 | 满足 | 拒绝矩阵 20/20 + 每工具可见性 5/5 |
| AC-003 | 满足 | 审计文档覆盖 19 toolset/67 tool/190 端点 |
| AC-004 | 满足 | 事件守卫 3/3（反向可证） |
| AC-005 | 满足 | ai 边界 0 违规（反向可证） |
| AC-006 | 满足 | MCP by_tool 20/20；导入运行记录 3/3；端点 7/7 |
| AC-007 | 满足（服务端+仓库侧已备） | Gauge 1/1、哈希确定；**live Grafana/端到端需部署后验证** |

## 失败与未验证项

- **未部署**：`deploy/ops/content-import-drift-alert.yaml` 的 Grafana 规则未应用到服务器；`check-content-drift.mjs` 的端到端（读后端最近哈希）需新后端上线后才能跑。这是部署/验收环节，不在本次开发环境可验证范围。
- 契约测试的"参数 schema 校验"由 MCP SDK 协议层承担，未在 invoker 层重复测。

## 剩余风险

- 内容导入运行记录是**进程内环形**（重启清空）；长期趋势依赖 Prometheus 时效 Gauge，若需要历史留存再考虑落库。
- 分支 `work/WORK-074` 尚未合并/推送。
