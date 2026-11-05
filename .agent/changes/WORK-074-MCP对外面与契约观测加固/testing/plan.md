---
artifact: test-plan
work_id: WORK-074
work: MCP 对外面与契约观测加固
status: ready
created: 2026-11-05
updated: 2026-11-05
---

# 测试计划

## 测试范围

- B：工具治理快照（67 tool 清单）、tool 无副作用拒绝矩阵、REST↔tool 审计。
- C：跨模块事件/端口守卫、ai 包边界守卫、指标（MCP by_tool + 内容导入运行记录）、导入漂移（时效 Gauge + 仓库侧哈希 + Grafana 规则）。

## 环境与前置条件

- 本地 Maven（JDK25，离线 `~/.m2` 缓存）+ **服务器 `codenest-online` 临时独占库**（每次 `CREATE DATABASE`，跑完 `DROP`）。
- 纯单测（`McpToolInvokerTest`、`ContentImportRunRegistryTest`、`ContentImportMetricsTest`、`CrossModuleContractTest`）无需 DB。
- 需 Spring 上下文的（`ToolGovernanceTest`、`ApiSurfaceTest`、`ApiContractTest`）连临时库跑。

## 验收矩阵

| AC | 检查 | 命令 | 预期 |
| --- | --- | --- | --- |
| AC-001 | 67 tool 治理清单 + 不变量 | `-Dtest=ToolGovernanceTest` | 5/5 通过 |
| AC-002 | tool 拒绝矩阵（无副作用） | `-Dtest=McpToolInvokerTest` + ToolGovernanceTest 可见性 | 20/20 + 5/5 |
| AC-003 | REST↔tool 审计 | 人工 `docs/architecture/rest-vs-tools.md` | 19 toolset 全覆盖 |
| AC-004 | 事件发布方守卫 | `-Dtest=CrossModuleContractTest` | 3/3，反向注入红 |
| AC-005 | ai 包边界守卫 | 同上 | 0 越界，反向注入红 |
| AC-006 | MCP by_tool 指标 + 导入运行记录 | `-Dtest=McpToolInvokerTest,ContentImportRunRegistryTest` | 20/20 + 2/2 |
| AC-007 | 导入时效 Gauge + 漂移哈希 | `-Dtest=ContentImportMetricsTest,ContentImportRunRegistryTest`；`check-content-drift.mjs --hash-only` | 通过 + 哈希确定 |

## 自动化检查

`scripts/test-db.sh` 的等价做法：本机无 mysql 客户端，改为「服务器临时库 + 本地 mvn」，见验证报告环境。

## 人工检查

- 阅读 `docs/architecture/tool-inventory.md`（67 tool）与 `rest-vs-tools.md`。
- 阅读 `deploy/ops/content-import-drift-alert.yaml` 规则。

## 回归范围

- `McpToolInvokerTest`（原有 19 → 20）、`ApiContractTest`、`ApiSurfaceTest` 必须保持全绿。
- 未改动任何既有工具/REST 行为；新增仅指标、端点、测试与文档。

## 已知缺口

- 部署机看不到仓库 `content/`，端到端漂移脚本 `check-content-drift.mjs` 与 **live Grafana 告警**需把新后端部署到服务器后才可验证（本轮未部署、未 push）。
- 契约测试的 tool 层"参数 schema 校验"由 MCP SDK 协议层承担，invoker 层只测 scope/role/限额/拒绝执行。
