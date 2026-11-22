---
artifact: tasks
work_id: WORK-044
work: MCP 网关补全：跨域能力、凭据治理与可观测
status: approved
mode: strict
workflow: full
related_to: [WORK-019, WORK-039]
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 14:50:54 +0800
approver_role: CTO
---

# 任务计划：MCP 网关补全

## 交付批次

按"能独立验证"切片，每批一个提交：

- **P1 护栏**（TASK-001..005）：数据库、计数、配额、截断、指标。这是 Track B + D，
  也是 Track A 的地基——新增工具上线时就该有护栏。
- **P2 跨域工具**（TASK-006..009）：四个域各一个工具类。
- **P3 前端与验证**（TASK-010..012）：凭据页、快照测试、e2e。

顺序理由：护栏先上，新增的 8 个工具一出生就在配额与截断的保护下；
如果反过来，P2 的工具会有一段时间裸奔。

## 任务

### TASK-001 | done | 迁移：每日上限列与按天用量表

**产出**
- `backend/paideia-app/src/main/resources/db/migration/V20261027160000__add_mcp_token_daily_limit.sql`
- `backend/paideia-app/src/main/resources/db/migration/V20261027160100__create_mcp_token_usage.sql`

**内容**：见 design 第一节，逐字使用其中的 SQL。

**检查**
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` 全绿。
- `rg "V20261027160" backend/paideia-app/src/main/resources/db/migration/` 出现 2 个文件，版本号不重复。

**注意**：迁移文件名必须是 14 位时间戳。`MigrationVersionFormatTest` 会挡住纯数字号与同秒重复。

---

### TASK-002 | done | 用量记录：Mapper 与 Recorder

**产出**
- `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/usage/McpUsageMapper.java`
- `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/usage/McpUsageRecorder.java`
- `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/usage/McpUsageUserDeletionListener.java`
- `backend/paideia-mcp/src/test/java/io/github/xiaou61/mcp/internal/usage/McpUsageRecorderTest.java`

**接口**（必须与 design 第五节字面一致）

```java
@Component
class McpUsageRecorder {
    void record(long tokenId, LocalDate day, boolean error);
    int callsToday(long tokenId, LocalDate day);
    Totals totals(long tokenId);
    int deleteAllFor(long userId);
    record Totals(long calls, long errors) {}
}
```

**实现要点**
- `record` 内部 try/catch 吞掉异常并 `log.warn`：计数失败不能让一次成功的工具调用变成失败。
- `McpUsageMapper.increment` 用 `ON DUPLICATE KEY UPDATE` 累加，一条语句既建行又累加。
- `deleteByUser` 用 JOIN `mcp_access_tokens`（`mcp_token_usage` 没有 `user_id` 列）。
- `McpUsageUserDeletionListener` 仿 `McpUserDeletionListener`：`@TransactionalEventListener(BEFORE_COMMIT)`。

**检查**
- 单测覆盖四条：累加同一 token 同一天、跨天分成两行、`callsToday` 无行返回 0、
  `record` 在 mapper 抛异常时不向外抛（用 Mockito 桩）。
- `scripts/test-db.sh mvn -B -pl paideia-mcp -am test` 全绿。

---

### TASK-003 | done | 配额与拒绝原因：`Denial`、`Outcome`、`PatCaller` 扩字段

**产出**
- 新增 `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/Denial.java`
- 新增 `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/Outcome.java`
- 改 `McpToolInvoker`：`doInvoke` 返回 `Outcome`，每个失败分支带自己的 `Denial`
- 改 `McpViewRegistry.PatCaller`：加 `Integer dailyCallLimit`
- 改 `PatAuthenticationFilter`：认证后把 `daily_call_limit` 放进 request attribute
- 改 `PatService.PatPrincipal`：加 `Integer dailyCallLimit`
- 改 `McpViewRegistry.invoke`：从 context 取上限塞进 `PatCaller`

**接口**（与 design 第五节一致）

```java
enum Denial { NONE, UNKNOWN_TOOL, PERMISSION, RATE_LIMIT, QUOTA, VIEW_NOT_FOUND }
record Outcome(ToolResult result, Denial denial)
record PatCaller(long userId, long tokenId, String role, Set<ToolScope> scopes,
                 Set<String> toolsets, Integer dailyCallLimit)
```

**判定顺序**（固定，写在注释里）：未知工具 → 视图不可见 → scope → 角色 → 限流 → 配额 → 执行。

**配额文案**：`"今天已经调用 N 次，达到这个凭据的每日上限；明天再来，或让账号主人调高上限。"`

**检查**
- `McpToolInvokerTest` 新增三条：配额用尽返回 `Denial.QUOTA` 且不调用工具、
  上限为 null 时不查库、限流优先于配额（两者都触发时 `Denial` 是 `RATE_LIMIT`）。
- 既有 34 条单测全绿（`PatCaller` 加字段后构造点全部要改）。

---

### TASK-004 | done | 返回体截断

**产出**
- 改 `McpToolInvoker`：加 `static final int MAX_RESULT_CHARS = 32 * 1024` 与 `static ToolResult cap(ToolResult)`
- 调用点在 `doInvoke` 返回后、`record` 之前

**实现要点**
- **只截 `text`，不截 `structured`**：截断 JSON 会让客户端拿到非法结构。
- 截断后追加明确标记：`\n\n[已截断：原文 N 字符，只返回前 32768 字符。需要完整内容时请缩小查询范围。]`
- 不截断时不改对象（返回原引用）。

**检查**
- `McpToolInvokerTest` 新增三条：刚好等于上限不截断、超一个字符被截断且带标记、
  `structured` 在截断后保持原值。
- 反向验证：把 `MAX_RESULT_CHARS` 临时改成 10 再跑，三条测试必须红；改回后绿。

---

### TASK-005 | done | 指标

**产出**
- 新增 `backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/McpMetrics.java`
- 改 `McpToolInvoker`：在收尾处打三个指标
- `backend/paideia-mcp/src/test/java/io/github/xiaou61/mcp/internal/McpMetricsTest.java`

**接口**（与 design 第五节一致）

```java
@Component
class McpMetrics {
    void calls(String toolset, String result);
    void duration(String tool, double seconds);
    void denied(String reason);
}
```

**指标名**
- `paideia.mcp.tool.calls` → `paideia_mcp_tool_calls_total{toolset,result}`
- `paideia.mcp.tool.duration` → `paideia_mcp_tool_duration_seconds{tool}`
- `paideia.mcp.denied` → `paideia_mcp_denied_total{reason}`

**`result` 的取值**：`ok`（成功）/ `tool_error`（工具自己返回失败）/ `denied`（被拒）。

**检查**
- 用 `SimpleMeterRegistry` 断言：一次成功调用后 `paideia_mcp_tool_calls_total{toolset="note",result="ok"}` 为 1；
  一次配额拒绝后 `paideia_mcp_denied_total{reason="quota"}` 为 1 且 `calls` 的 `result` 是 `denied`。
- 断言标签里不含 userId / tokenId（基数控制）。

---

### TASK-006 | done | 凭据创建上限与配额字段贯通

**产出**
- 改 `PatService`：`MAX_TOKENS_PER_USER = 20`、`create(...)` 加 `Integer dailyCallLimit` 参数
- 改 `McpTokenMapper`：`countActiveByUser`
- 改 `McpAccessToken`：`dailyCallLimit` 属性
- 改 `McpTokenMapper.insert` / 各 `SELECT`：带 `daily_call_limit`
- 改 `McpTokenController`：`CreateRequest` / `CreatedView` / `TokenView` 带该字段
- 改 `McpTokensPage` 之外的调用点（测试）

**校验规则**
- `null` 或 `0` = 不限
- 负数拒绝：`"每日调用上限不能是负数"`
- 超过 100000 拒绝：`"每日调用上限不能超过 100000"`

**检查**
- `PatServiceTest` 新增四条：第 21 个凭据被拒、吊销一个后可以再建（只数未吊销的）、
  负数被拒、`dailyCallLimit` 落库并能读回。
- 既有 `PatServiceTest` 全绿。

---

### TASK-007 | done | 简历与面试工具

**产出**
- `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/tools/ResumeTools.java`（2 个工具）
- `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/tools/InterviewTools.java`（2 个工具）
- `backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/tools/ResumeToolsTest.java`
- `backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/tools/InterviewToolsTest.java`

**工具清单**

| 名 | 组 | scope | readOnly | defaultGroup |
| --- | --- | --- | --- | --- |
| `resume_list` | `resume` | READ | true | false |
| `resume_get` | `resume` | READ | true | false |
| `interview_list` | `interview` | READ | true | false |
| `interview_get` | `interview` | READ | true | false |

**实现要点**
- `resume_get` 把 `ResumeData` 渲染成带小标题的纯文本（不返回原始 JSON——模型读 JSON 的
  token 效率低于文本）。
- `interview_get` 从 `listMine(userId)`（限 50 条）里按 id 筛，**不新增 mapper 方法**。
- 找不到时返回 `"没有找到"`，不区分"不存在"与"不是你的"（REQ-019）。

**检查**
- 每个工具一条"归属"测试：桩服务返回空 → 工具返回"没有找到"。
- `resume_get` 一条渲染测试：给一份带教育/工作/技能的 `ResumeData`，断言输出含各段小标题。
- `ToolCatalog` 启动校验通过（测试里构造目录）。

---

### TASK-008 | done | 记忆工具

**产出**
- `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/tools/MemoryTools.java`（2 个工具）
- `backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/tools/MemoryToolsTest.java`

**工具清单**

| 名 | 组 | scope | readOnly | defaultGroup |
| --- | --- | --- | --- | --- |
| `memory_list` | `memory` | READ | true | false |
| `memory_remember` | `memory` | WRITE | false | false |

**实现要点**
- `memory_remember` 用 `MemoryStore.rememberText(userId, content)`，**不用 `rememberStructured`**：
  后者要 `kind` 枚举，让外部模型猜我们的内部枚举，猜错就是一条归错类的记忆。
- `content` 上限 2000 字符（与 `AskTool` 的 `MAX_QUESTION` 同量级）。
- 成功返回 `ToolResult.ok("已记住。", Map.of("memoryId", id))` —— 带结构化结果，便于客户端确认。

**检查**
- 一条写入测试：调用后 `rememberText` 收到正确参数，返回的 structured 含 `memoryId`。
- 一条超长测试：2001 字符被拒。
- 一条 `memory_list` 的 limit 上限测试（传 999 被夹到 50）。

---

### TASK-009 | done | 复习工具

**产出**
- `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/tools/ReviewTools.java`（2 个工具）
- `backend/paideia-knowledge/src/test/java/io/github/xiaou61/knowledge/internal/tools/ReviewToolsTest.java`

**工具清单**

| 名 | 组 | scope | readOnly | defaultGroup |
| --- | --- | --- | --- | --- |
| `review_due` | `review` | READ | true | false |
| `review_plan` | `review` | READ | true | false |

**实现要点**
- `review_due` → `LearningReviewService.due(userId, Instant.now())`，渲染 `ReviewDueView` 列表。
- `review_plan` → 用 `due` 的结果 + `LearningProgressService.listMine(userId, limit)` 组装概览
  （总条目、各阶段分布、最近复习时间）。**不新增服务方法**。

**检查**
- `review_due` 一条空列表测试（返回"当前没有到期待复习的条目"）。
- `review_plan` 一条分布统计测试（给三个不同 stage 的条目，断言输出含各 stage 计数）。

---

### TASK-010 | done | 前端凭据页

**产出**
- 改 `frontend/apps/app/src/mcp-tokens-api.ts`：`McpToken` 加 `dailyCallLimit`/`callsToday`/`totalCalls`/`totalErrors`
- 改 `frontend/apps/app/src/pages/McpTokensPage.tsx`：创建表单加输入框、凭据行加用量文字
- 改 `frontend/apps/app/e2e/mcp-tokens.spec.ts`：补两条断言

**文案**
- 输入框标签「每日调用上限」，placeholder「不填 = 不限」
- 凭据行：`今日 12 / 100 次 · 累计 1,234 次 · 失败 3 次`（不限时省略 `/ 100`）

**检查**
- `pnpm --filter @paideia/app typecheck` 通过。
- `playwright test e2e/mcp-tokens.spec.ts` 通过。
- 新增两条 e2e 断言：创建带上限的凭据后列表显示 `今日 0 / 100 次`；不填上限时显示 `今日 0 次`。

---

### TASK-011 | done | 工具清单快照测试（AC-005 / AC-013）

**产出**
- `backend/paideia-app/src/test/java/io/github/xiaou61/McpToolSnapshotTest.java`

**内容**：断言两件事。

1. `/mcp` 默认视图（`ToolQuery.view(Set.of(), false, "USER", Set.of(READ))` 加上 `defaultsOnly`）
   的工具数与名字集合，与一份写死在测试里的清单一致。改动前后应当**完全相同**。
2. 全部 65 个工具的 `(name, toolset, scope, readOnly, destructive)` 五元组快照。

**为什么写死而不是生成**：生成式快照会在改动时自动更新，就失去了"钉住不变"的意义。
写死之后，任何清单变化都必须显式改测试，改的时候会想一下"这是不是我要的"。

**检查**
- 测试通过。
- 反向验证：临时给某个工具加 `defaultGroup=true`，测试必须红；改回后绿。

---

### TASK-012 | done | 全量验证与报告

**产出**
- `.agent/changes/WORK-044-MCP网关补全/testing/report.md`

**执行**
1. `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`
2. `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,McpAdminIntegrationTest,McpToolSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
3. `cd frontend && pnpm --filter @paideia/app typecheck`
4. `cd frontend/apps/app && playwright test e2e/mcp-tokens.spec.ts`
5. `python $lifecycle validate . --strict`（本项目历史项有旧警告，只看 WORK-044 相关的）

**报告必须逐条给出 AC-001..AC-014 的证据**，包括命令、退出码、关键输出行。
**没有实测的 AC 写"未验证"并说明原因**，不许写"应该没问题"。

## 评审焦点

规格暗示但没有被任务测试覆盖的失败模式，逐条指定归属：

| 失败模式 | 归属任务 | 测试名 |
| --- | --- | --- |
| 计数写入失败把成功调用变成失败 | TASK-002 | `recordSwallowsMapperFailure` |
| 跨天时配额算错（把昨天的计数当今天） | TASK-002 | `countsSplitByDay` |
| 限流与配额同时触发时原因归类错误 | TASK-003 | `rateLimitTakesPrecedenceOverQuota` |
| 配额为 null 时仍然查库（性能） | TASK-003 | `nullLimitDoesNotQueryUsage` |
| 截断破坏 structured 导致客户端解析失败 | TASK-004 | `capKeepsStructuredIntact` |
| 指标标签含用户输入导致基数爆炸 | TASK-005 | `labelsCarryNoUserInput` |
| 吊销过的凭据占名额，用户换设备时建不了新的 | TASK-006 | `revokedTokensDoNotCountTowardLimit` |
| 新工具漏设 `defaultGroup=false`，默认视图悄悄变大 | TASK-011 | `defaultViewUnchanged` |
| 工具跨模块访问 internal 包导致 ModularityTest 红 | TASK-007/008/009 | 编译期即暴露；TASK-012 跑 ModularityTest |
| 配额按 UTC 日切，北京时间用户早上 8 点跨天 | 已知限制 | design 第六节记录，不在本项修 |
| 并发两个请求同时通过配额判定，超额一次 | 已知限制 | 计数与判定非原子；超额 1 次的代价小于加锁的复杂度，记在 report |
