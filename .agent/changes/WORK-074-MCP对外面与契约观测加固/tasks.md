---
artifact: tasks
work_id: WORK-074
work: MCP 对外面与契约观测加固
status: approved
created: 2026-11-05
updated: 2026-11-05
approved_by: xiaou61
approved_at: 2026-11-05 16:29:54 +0800
approver_role: owner
---

# 实施任务

## 依据

已批准 `requirements.md`（WORK-074，strict/full）、`proposal.md`、`design.md`。

## 全局约束

- 不引入新框架依赖（守卫用自写测试，不用 ArchUnit）。
- 测试无副作用、离线可跑；Java 测试走 `scripts/test-db.sh` 独占库。
- 每切片独立提交、可单独回退。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 只读工具被写进只读视图 | B1 | `ToolGovernanceTest.readOnlyViewExcludesWrites` |
| 被拒的调用仍执行了工具（副作用） | B2 | `McpToolContractTest.deniedDoesNotExecute` |
| ADMIN 工具被非管理员调用 | B2 | `McpToolContractTest.adminToolRequiresAdmin` |
| 生产代码越界 import ai.internal | C2 | `CrossModuleContractTest.noAiInternalImportsFromOtherModules` |
| 事件被非拥有模块发布 | C1 | `CrossModuleContractTest.eventsPublishedOnlyByOwner` |
| 漂移告警规则永不触发 | C4 | 人工：构造漂移 → Grafana firing |

## 任务

### TASK-B1 | done | 工具治理快照测试 + 生成对外能力清单
- 对应：REQ-001、AC-001
- 依赖：无
- 新建：`backend/paideia-app/src/test/java/io/github/xiaou61/ToolGovernanceTest.java`；生成 `docs/architecture/tool-inventory.md`
- 验证：**已实跑**——本地 Maven 连服务器临时独占库执行 `ToolGovernanceTest`：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS；生成 `docs/architecture/tool-inventory.md`，**运行时注册 67 个工具**（22 个源文件）。临时库/账号已清理。

### TASK-B2 | pending | tool 契约测试（无副作用拒绝矩阵）
- 对应：REQ-002、AC-002
- 依赖：B1
- 新建：`backend/paideia-mcp/src/test/java/io/github/xiaou61/mcp/internal/McpToolContractTest.java`
- 验证：`cd backend && ../scripts/test-db.sh mvn -B -pl paideia-mcp -am -Dtest=McpToolContractTest ... test` → 通过

### TASK-B3 | pending | REST↔tool 收敛审计
- 对应：REQ-003、AC-003
- 依赖：B1
- 新建：`docs/architecture/rest-vs-tools.md`
- 验证：人工核对 22 tool 与端点，处置结论齐全

### TASK-C1 | pending | 跨模块事件/端口守卫
- 对应：REQ-004、AC-004
- 依赖：B 期完成
- 新建：`backend/paideia-app/src/test/java/io/github/xiaou61/CrossModuleContractTest.java`（事件部分）
- 验证：构造违规样例 → 测试红；正常 → 绿

### TASK-C2 | pending | paideia-ai 包根 API 守卫
- 对应：REQ-005、AC-005
- 依赖：C1（同一测试类）
- 修改：`CrossModuleContractTest` 增加 ai.internal 扫描例
- 验证：临时加一行越界 import → 测试红；移除 → 绿

### TASK-C3 | pending | 指标（MCP 扩展 + 内容导入 Analytics）
- 对应：REQ-006、AC-006
- 依赖：B2
- 修改：`McpMetrics`/`McpToolInvoker`；`import-course.mjs`；`paideia-ops` Analytics
- 验证：指标可在 Prometheus 查询；导入统计在 Analytics 可见

### TASK-C4 | pending | 导入漂移 Grafana 告警
- 对应：REQ-007、AC-007
- 依赖：C3
- 新建/修改：后端口径 + Grafana 规则
- 验证：构造漂移 → 规则 firing/邮件

## 完成条件

- B 期（B1–B3）完成并可演示 → 再开 C 期。
- 每个 AC 有对应证据；Java 测试经 `test-db.sh` 独占库跑绿。
