---
artifact: tasks
work_id: WORK-062
work: 重复代码收敛与共享工具提取
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 20:09:56 +0800
approver_role: CTO
---

# 重复代码收敛与共享工具提取实施任务

## 依据

- `requirements.md`（REQ-001…005 / AC-001…006）
- `proposal.md`（15 片表与"不做"清单）
- `design.md`（签名、承重差异、每片"消失判据"）

## 全局约束

- 只收敛**逐字重复**；承重差异（超时/顺序/前缀/变体）一律参数化留在调用点，并在代码注释写明。
- 每片一个提交；后端片跑受影响模块 `test` + 守卫切片；前端片跑该包 typecheck+单测；改 `@paideia/core`/`@paideia/ui` 后跑两端 e2e。
- 叠放于 `feat/work-061-arch-guards`；不部署、不推送。
- 新建共享件必须**同片内被真实调用**，不留"为将来"的空件。

## 评审焦点

| 失败模式 | 归属 | 验证 |
| --- | --- | --- |
| 把承重差异一起"统一"掉（超时、清理顺序、UTC 变体、窄 longOf、multipart） | B1/B3/T2/T3/T5/F6 | 各片白名单 grep + 受影响测试 |
| 新共享件引依赖/上下文（ui 引 react-query、测试基类） | F2/T* | 设计明文禁止；typecheck 与依赖树检查 |
| 替换漏点（改了新件没换完调用） | 全部 | design「消失判据」grep |
| e2e testid/文案被改（共享件吃掉各页差异） | F2/F3 | 两端 e2e 实跑 |
| 前端行为被"顺手优化"改变（时间三档兜底等） | F5 | 保留白名单；e2e + 单测 |

## 任务

### TASK-001 | pending | B1 令牌 subject→long 收敛
- 落点 `security.CurrentUser.userId()`；替换 11 处逐字 try/catch（account×2、ai.AiRequester、app.console.AdminUserController、knowledge.CurrentUserId、mcp.McpTokenController、note×3、sandbox×2）与 6 处裸解析（ai.LearningAdviceController、ai.digest.DigestController、ops.OpsDiagnosisController×2、ops.OpsIncidentController×2）。
- 验证：受影响模块 test；守卫切片；消失判据 B1。

### TASK-002 | pending | B2 SHA-256 收敛
- 落点 `platform.Hashes.sha256Hex`；替换 9 处（account.Digest 改委托；ai×5；ops.OpsIncidentService；knowledge.QuestionCatalog；sandbox.SandboxQuotaService）；EssayCollectionService 的 catch-返回原值保留。
- 验证：受影响模块 test；消失判据 B2。

### TASK-003 | pending | B3 工具入参小工具收敛
- 落点 `platform.ToolArgs`；替换 stringOf 12、longOf 7、messageOf 12、instantOf 2、分页 3-4；窄变体与 fallback 族保留。
- 验证：受影响模块 test（ai/note/knowledge/sandbox/app）；消失判据 B3。

### TASK-004 | pending | B4 模型输出 JSON 收敛
- 落点 `ai.internal.support.ModelJson`；替换 6 处逐字 + textOrNull/textOrEmpty 5 处；两个变体保留。
- 验证：`mvn -pl paideia-ai -am test`；消失判据 B4。

### TASK-005 | pending | B5 Ops HTTP 样板收敛
- 落点 `ops.OpsHttp`（包内）；改 Prometheus/Loki/Tempo 三家 + Grafana 反代尾斜杠 1 处；`"success"` 判定与失败类型保留。
- 验证：`-Dtest='Ops*,Tempo*,Loki*'`；消失判据 B5。

### TASK-006 | pending | T1 + T4 测试样板（body / SSE）
- 新增 `MockMvcBodies`、`MockMvcSse`；替换 14 + 4 类。
- 验证：受影响类实跑（含 Memory 与 AiCredit 家族）；消失判据 T1/T4。

### TASK-007 | pending | T2 Bearer token 收敛
- 新增 `TestTokens`（subject/role 显式传参）；替换 28 类约 36 方法（含 ops 包 tokenFor 变体）。
- 验证：app 模块受影响类实跑；消失判据 T2。

### TASK-008 | pending | T3 Await 轮询收敛
- 新增 `Await.until/value`；替换 OpsDiagnosis/OpsHook 对、MemoryAuto 六循环、MemoryExtractionJobs 二循环、AssistantConversation 一循环、AiTelemetry 二循环、AiObservability 一循环；ApiWalkthrough 与 DefaultRunRecorderTest 的变体保留。
- 验证：上述类实跑；消失判据 T3。

### TASK-009 | pending | T5 清理/播种/id 收敛
- 新增 `SqlCleanup`、`UserFixture`、`TestIds`；替换 5 类表循环、4-5 处 CURRENT_TIMESTAMP 播种、顺手 id 后缀；UTC 与 passwordless 变体、各表顺序保留。
- 验证：上述类实跑。

### TASK-010 | pending | F1 errorMessage（core）
- `core` 新增 `error-message.ts`；替换 39 处；`auth.messageOf` 委托；admin 两处私有函数删除；顺手替换 24 处 `as Error` 断言。
- 验证：core 单测（新增 2-3 条）+ 相关包 typecheck；消失判据 F1。

### TASK-011 | pending | F2+F5+F6 core/ui 纯件（三件套 / 时间 / JSON 请求）
- `ui` 新增 `query-state.tsx`；`core` 新增 `format.ts` 与 `api.ts` 增强；替换 ≈65 + 12 + 32 处；multipart 3 处与时间白名单保留。
- 验证：ui/core 单测 + 两端 typecheck；消失判据 F2/F5/F6。

### TASK-012 | pending | F3 ListPagination
- `ui` 新增 `pagination.tsx`；替换 admin 5 处分页页脚（pageCount 上移）；sql-workbench 变体保留。
- 验证：admin typecheck + e2e。

### TASK-013 | pending | F4 + F7 admin 辅助（e2e session / 窗口查询串）
- 新增 `apps/admin/e2e/session.ts`（signIn/overflow/envelope）与 `apps/admin/src/ai-window.ts`；替换 10 spec + 9 处；顺手统一 `E2E_ADMIN_PATH` 并修缺 encode 漂移。
- 验证：admin e2e 实跑；消失判据 F7。

### TASK-014 | pending | 联动验证与回归
- 全量前端单测 + 学习者端与管理端 e2e（改共享包规则）；后端守卫切片 + 受影响模块 test 复跑。
- 验证：见报告。

### TASK-015 | pending | 线上只读接口计时冒烟
- 执行临时目录脚本（31 端点 ×3 轮 + 2 详情）；输出中位/最大、非 2xx；≥1s 与 ≥3s 单列；结论写进 `testing/report.md`。
- 验证：脚本输出与报告一致；凭据不回显。

### TASK-016 | pending | 验证报告与收尾
- 写 `testing/report.md`（五列证据矩阵覆盖 AC-001…006）；更新 `.agent/history/updates.md`；确认主树干净、未推送。
