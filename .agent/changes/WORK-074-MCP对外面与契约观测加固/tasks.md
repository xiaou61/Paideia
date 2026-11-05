---
artifact: tasks
work_id: WORK-074
work: MCP 对外面与契约观测加固
status: approved
created: 2026-11-05
updated: 2026-11-05
---

# 实施任务

## 依据

已批准 `requirements.md`（WORK-074，strict/full）、`proposal.md`、`design.md`。

## 全局约束

- 不引入新框架依赖（守卫用自写测试，不用 ArchUnit）。
- 测试无副作用、离线可跑；Java 测试用「本地 Maven + 服务器临时独占库」跑。
- 每切片独立提交、可单独回退。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 只读工具被写进只读视图 | TASK-001 | `ToolGovernanceTest.readOnlyViewExcludesWrites` / `everyToolHasCorrectReadVisibility` |
| 被拒的调用仍执行了工具（副作用） | TASK-002 | `McpToolInvokerTest` 既有用例（lastContext 为空） |
| ADMIN 工具被非管理员调用 | TASK-002 | `McpToolInvokerTest.adminToolRequiresAdminRoleEvenWithAdminScope` |
| 生产代码越界 import ai.internal | TASK-005 | `CrossModuleContractTest.noAiInternalImportsFromOtherModules` |
| 事件被非拥有模块发布 | TASK-004 | `CrossModuleContractTest.eventsPublishedOnlyByOwner` |
| 漂移告警规则永不触发 | TASK-007 | 人工：构造漂移 → Grafana firing |

## 任务

### TASK-001 | done | 工具治理快照测试 + 生成对外能力清单
- 对应：REQ-001、AC-001
- 依赖：无
- 新建：`backend/paideia-app/src/test/java/io/github/xiaou61/ToolGovernanceTest.java`；生成 `docs/architecture/tool-inventory.md`
- 验证：**已实跑**——本地 Maven 连服务器临时独占库执行 `ToolGovernanceTest`：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`；生成清单，**运行时注册 67 个工具**（22 个源文件）。临时库/账号已清理。

### TASK-002 | done | tool 契约测试（无副作用拒绝矩阵）
- 对应：REQ-002、AC-002
- 依赖：TASK-001
- 复用：`backend/paideia-mcp/src/test/java/io/github/xiaou61/mcp/internal/McpToolInvokerTest.java` **已完整覆盖**拒绝矩阵（未知工具/视图不可见/scope/ADMIN/限流/配额/被拒不执行/被拒不烧配额/指标标签无用户输入，共 15 用例，纯单测无副作用）。
- 修改：`ToolGovernanceTest` 增加 `everyToolHasCorrectReadVisibility`——对真实 67 个工具逐个断言"只读可见、写/管理/非只读不可见"。
- 验证：`ToolGovernanceTest` 5/5 全绿（含新用例）。

### TASK-003 | done | REST↔tool 收敛审计
- 对应：REQ-003、AC-003
- 依赖：TASK-001
- 新建：`docs/architecture/rest-vs-tools.md`（toolset 级审计 + 处置纪律）
- 验证：覆盖 19 个 toolset / 67 tool 与 190 端点前缀，每项给出"保持+同源"处置。文档无 mermaid 块（`check-diagrams.mjs` 因工作树缺前端依赖未能运行，属环境限制）。

### TASK-004 | done | 跨模块事件/端口守卫
- 对应：REQ-004、AC-004
- 依赖：TASK-003
- 新建：`backend/paideia-app/src/test/java/io/github/xiaou61/CrossModuleContractTest.java`（事件部分）
- 验证：正向 `CrossModuleContractTest` 全绿；反向——往 note 主源码注入 `// new UserDeletedEvent(0L)` 后 `eventsPublishedOnlyByOwner` 红（`Tests run:3, Failures:2`），`git checkout` 还原。

### TASK-005 | done | paideia-ai 包根 API 守卫
- 对应：REQ-005、AC-005
- 依赖：TASK-004（同一测试类）
- 修改：`CrossModuleContractTest` 增加 ai.internal 扫描例
- 验证：反向——注入 `// import io.github.xiaou61.ai.internal...` 后 `noAiInternalImportsFromOtherModules` 红；`git checkout` 还原后绿。含 `ScheduledTaskRecorder` 仅 ops 实现的断言。

### TASK-006 | pending | 指标（MCP 扩展 + 内容导入 Analytics）
- 对应：REQ-006、AC-006
- 依赖：TASK-002
- 修改：`McpMetrics`/`McpToolInvoker`；`import-course.mjs`；`paideia-ops` Analytics
- 验证：指标可在 Prometheus 查询；导入统计在 Analytics 可见

### TASK-007 | pending | 导入漂移 Grafana 告警
- 对应：REQ-007、AC-007
- 依赖：TASK-006
- 新建/修改：后端口径 + Grafana 规则
- 验证：构造漂移 → 规则 firing/邮件

## 完成条件

- B 期（TASK-001–003）完成并可演示；C 期（TASK-004–007）逐项完成。
- 每个 AC 有对应证据；Java 测试经「本地 Maven + 服务器临时库」跑绿。
