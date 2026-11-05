---
artifact: design
work_id: WORK-074
work: MCP 对外面与契约观测加固
status: approved
created: 2026-11-05
updated: 2026-11-05
approved_by: xiaou61
approved_at: 2026-11-05 16:29:54 +0800
approver_role: owner
---

# 设计

## B-1 工具治理快照

- 新增测试 `backend/paideia-app/src/test/java/io/github/xiaou61/ToolGovernanceTest.java`：
  - `@SpringBootTest @ActiveProfiles("test") @EnabledIfEnvironmentVariable(named="PAIDEIA_TEST_DB_PASSWORD", matches=".+")`
  - `@Autowired ToolCatalog catalog`
  - 断言不变量：每个 `snapshot()` 的 `name` 以 `toolset + "_"` 开头；`scope != null`；`readOnly ⇒ scope != WRITE`；`description` 非空；名字唯一；`view(READ-only)` 全部 `readOnly`；`view(写视图)` 含全部非 ADMIN 工具。
  - 生成清单：按确定性顺序把 `name / toolset / scope / readOnly / destructive / defaultGroup` 写到 `docs/architecture/tool-inventory.md`（测试内生成，作为"对外能力清单"真源；沿用项目已有"测试写台账"做法，如 ApiWalkthroughTest）。
- 覆盖：REQ-001、AC-001、部分 REQ-002/AC-002。

## B-2 tool 契约测试

- 新增 `backend/paideia-mcp/src/test/java/io/github/xiaou61/mcp/internal/McpToolContractTest.java`：
  - 构造 `ToolCatalog`（真实或桩工具）与 `McpToolInvoker`（假 limiter/usage/metrics）。
  - 断言拒绝分支：未知工具→`UNKNOWN_TOOL`；视图不可见/scope 不足→`PERMISSION`；ADMIN tool + 非 ADMIN→`PERMISSION`；限流/配额→`RATE_LIMIT`/`QUOTA`；且**被拒时不执行**（用会抛异常的桩工具证明未被调用）。
  - 全量覆盖 `catalog.snapshot()` 的每个 tool 在"scope 不足视图"里不可见。
- 覆盖：REQ-002、AC-002。

## B-3 收敛审计

- `docs/architecture/rest-vs-tools.md`：按模块列出 tool 与其对应 REST 端点，标"重复 / 仅 tool / 仅 REST"，给处置建议。人工审计 + 脚本辅助生成候选。

## C-1 跨模块事件/端口守卫

- 新增 `backend/paideia-app/src/test/java/io/github/xiaou61/CrossModuleContractTest.java`：
  - 扫描 `backend/**/src/main` 源码：收集"谁 import 了 `UserDeletedEvent/EntryAssessmentChangedEvent/RoleLookup/ScheduledTaskRecorder`"与"谁 `publishEvent` 了哪个事件"。
  - 对照内嵌声明表断言：事件只允许由拥有模块发布、消费方在白名单内；违反则失败并点名文件。
- 覆盖：REQ-004、AC-004。

## C-2 ai 包根 API 守卫

- 同一 `CrossModuleContractTest` 中加一例：非 `paideia-ai` 的 `src/main` 若出现 `import io.github.xiaou61.ai.internal.` → 失败（`src/test` 豁免）。
- 覆盖：REQ-005、AC-005。

## C-3 指标

- `McpMetrics`：新增 `callsByTool(tool, result)` → `paideia.mcp.tool.calls` 或新 counter `paideia.mcp.tool.by_tool{tool,result}`；`McpToolInvoker.record` 传入 `toolName`。
- 内容导入：`import-course.mjs` 结束时 POST 汇总到 `POST /api/v1/ops/admin/content-import`（ADMIN），后端写 `content_import_runs`（若无表则复用 Analytics 只读聚合 + 一次运行日志表，需评估迁移）。Analytics `/overview` 增加"最近导入"区块。
- 覆盖：REQ-006、AC-006。

## C-4 导入漂移告警

- 后端定时任务计算"源内容 sha256 集合 vs 最近导入记录"，产出 gauge `paideia.content.drift`（漂移条数）；Grafana 规则 `drift > 0` 告警（复用 WORK-028 邮件通道）。
- 覆盖：REQ-007、AC-007。

## 验证策略

- Java 测试一律 `cd backend && ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=<类> -Dsurefire.failIfNoSpecifiedTests=false test`（独占库）。
- 每切片一个提交。
