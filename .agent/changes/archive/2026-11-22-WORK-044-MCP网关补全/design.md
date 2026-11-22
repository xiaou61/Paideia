---
artifact: design
work_id: WORK-044
work: MCP 网关补全：跨域能力、凭据治理与可观测
status: approved
mode: strict
workflow: full
related_to: [WORK-019, WORK-039]
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 14:50:46 +0800
approver_role: CTO
---

# 设计：MCP 网关补全

## 一、数据库

### 迁移 1：凭据每日上限

文件名：`V20261027160000__add_mcp_token_daily_limit.sql`

```sql
-- 凭据的每日调用上限（WORK-044）。
-- NULL = 不限。按"调用次数"而不是积分：assistant_ask 已经走积分预留-结算，
-- 再叠一层积分配额会让两套账互相解释不清（用户 2026-10-27 裁决）。
ALTER TABLE mcp_access_tokens
  ADD COLUMN daily_call_limit INT NULL COMMENT '每日调用上限；NULL = 不限' AFTER toolsets;
```

**不需要回填**：NULL 就是"不限"，与改动前的语义完全一致。

### 迁移 2：按天用量

文件名：`V20261027160100__create_mcp_token_usage.sql`

```sql
-- 凭据的按天用量（WORK-044）。
--
-- 为什么按 (token_id, day) 一行而不是在 mcp_access_tokens 上加两个计数列：
--   1. 每日配额要问"今天的计数"，单行累计值必须靠 updated_at 判断跨天，跨天那一刻有竞态；
--      按天分行后跨天天然是新行。
--   2. mcp_access_tokens 每次认证都要读，加高频写的计数列会让它成为热点
--      （PatService 已经在为 last_used_at 做 60 秒节流，就是这个原因）。
--
-- 历史累计 = SUM(calls) GROUP BY token_id，不需要额外列。
-- day 是 UTC 日期，与应用内时间一律 UTC 的约定一致。
CREATE TABLE mcp_token_usage (
  token_id   BIGINT UNSIGNED NOT NULL,
  day        DATE            NOT NULL,
  calls      INT UNSIGNED    NOT NULL DEFAULT 0,
  errors     INT UNSIGNED    NOT NULL DEFAULT 0,
  updated_at DATETIME(3)     NOT NULL,
  PRIMARY KEY (token_id, day)
) ENGINE = InnoDB;

-- 注销级联按 token_id 删；主键已覆盖该查询，无需额外索引。
```

**无外键**：与项目其它表一致（`mcp_access_tokens` 也没有指向 `users` 的外键），
级联由 `McpUserDeletionListener` 在应用层做。

## 二、`paideia-mcp` 的改动

### 2.1 计数与配额

新类 `McpUsageRecorder`（`internal` 包，包可见）：

```java
@Component
class McpUsageRecorder {

    /**
     * 记一次调用。
     *
     * <p><b>不抛异常</b>：计数失败不该让一次成功的工具调用变成失败。吞掉并记 warn。
     *
     * @param day 调用发生的 UTC 日期；由调用方传，便于测试固定时间
     */
    void record(long tokenId, LocalDate day, boolean error);

    /** 今天的调用次数。没有行就是 0。 */
    int callsToday(long tokenId, LocalDate day);

    /** 累计调用与错误（凭据页展示用）。 */
    Totals totals(long tokenId);

    /** 注销级联。 */
    int deleteAllFor(long userId);
}
```

`Totals` 是 `record Totals(long calls, long errors)`。

Mapper `McpUsageMapper`（包可见接口）：

```java
@Mapper
interface McpUsageMapper {

    /** upsert 累加。一条语句既建行又累加，避免"先查再改"的并发窗口。 */
    @Insert("""
            INSERT INTO mcp_token_usage (token_id, day, calls, errors, updated_at)
            VALUES (#{tokenId}, #{day}, 1, #{errorDelta}, #{now})
            ON DUPLICATE KEY UPDATE
                calls = calls + 1,
                errors = errors + #{errorDelta},
                updated_at = VALUES(updated_at)
            """)
    int increment(@Param("tokenId") long tokenId, @Param("day") LocalDate day,
                  @Param("errorDelta") int errorDelta, @Param("now") Instant now);

    @Select("SELECT calls FROM mcp_token_usage WHERE token_id = #{tokenId} AND day = #{day}")
    Integer callsOn(@Param("tokenId") long tokenId, @Param("day") LocalDate day);

    @Select("""
            SELECT COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(errors), 0) AS errors
            FROM mcp_token_usage WHERE token_id = #{tokenId}
            """)
    Totals totals(@Param("tokenId") long tokenId);

    @Delete("DELETE u FROM mcp_token_usage u JOIN mcp_access_tokens t ON t.id = u.token_id "
            + "WHERE t.user_id = #{userId}")
    int deleteByUser(@Param("userId") long userId);

    record Totals(long calls, long errors) {
    }
}
```

**为什么 `deleteByUser` 要 JOIN**：`mcp_token_usage` 没有 `user_id` 列。加一列会让
"凭据换主人"这种（目前不存在但可能出现的）场景产生两处不一致。JOIN 删除的代价可接受——
注销是低频操作，且 `token_id` 是主键前缀。

### 2.2 `McpToolInvoker` 的改造

现有 `doInvoke` 的判定顺序保持不变，**在末尾插入配额判定**，并把收尾统一到一处：

```java
ToolResult invoke(McpViewRegistry.PatCaller caller, McpViews view, String toolName,
                  Map<String, Object> arguments) {
    long started = System.nanoTime();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Outcome outcome = null;
    ToolResult result;
    try {
        outcome = doInvoke(caller, view, toolName, arguments);
        result = outcome.result();
    } catch (RuntimeException unexpected) {
        errors.error("mcp.tool.failed userId={} tool={}", caller.userId(), toolName, unexpected);
        result = ToolResult.fail("工具执行失败，请稍后重试");
        outcome = new Outcome(result, Denial.NONE);
    }
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    audit.info("tokenId={} userId={} tool={} view={} durationMs={} isError={} bytes={} denied={}",
            caller.tokenId(), caller.userId(), toolName, view.name(), durationMs,
            result.error(), result.text() == null ? 0 : result.text().length(),
            outcome.denial().key());
    record(caller, toolName, today, result, outcome.denial());
    return result;
}
```

`record(...)` 负责三件事：写用量、打指标、无异常传播。

```java
private void record(McpViewRegistry.PatCaller caller, String toolName, LocalDate today,
                    ToolResult result, Denial denial) {
    try {
        usage.record(caller.tokenId(), today, result.error());
    } catch (RuntimeException failure) {
        // 计数失败不能把一次成功的调用变成失败
        errors.warn("mcp.usage.record.failed tokenId={} tool={}", caller.tokenId(), toolName, failure);
    }
    metrics.calls(toolsetOf(toolName), resultClass(result, denial));
    metrics.duration(toolName, durationSeconds);
    if (denial != Denial.NONE) {
        metrics.denied(denial.key());
    }
}
```

**`Denial` 枚举**（包可见）：

```java
enum Denial {
    NONE(""),
    UNKNOWN_TOOL("unknown_tool"),
    PERMISSION("permission"),
    RATE_LIMIT("rate_limit"),
    QUOTA("quota"),
    VIEW_NOT_FOUND("view_not_found");

    String key() { ... }
}
```

`doInvoke` 返回 `Outcome(ToolResult result, Denial denial)`，每个失败分支带自己的 `Denial`。
**这是本项唯一改变 `doInvoke` 签名的地方**，为的是让"为什么被拒"能同时进日志、指标与配额原因。

**配额判定位置**：在 `limiter.allow(...)` 之后、真正 `call` 之前：

```java
if (!limiter.allow(caller.tokenId())) {
    return new Outcome(ToolResult.fail("调用过于频繁，请稍后再试"), Denial.RATE_LIMIT);
}
Integer dailyLimit = caller.dailyCallLimit();
if (dailyLimit != null && usage.callsToday(caller.tokenId(), today) >= dailyLimit) {
    return new Outcome(ToolResult.fail(
            "今天已经调用 " + dailyLimit + " 次，达到这个凭据的每日上限；明天再来，或让账号主人调高上限。"),
            Denial.QUOTA);
}
```

**为什么配额在限流之后**：限流是内存判定（几乎零成本），配额要查库。先过便宜的。

**`PatCaller` 加一个字段** `Integer dailyCallLimit`。它从哪来：`PatAuthenticationFilter`
在认证时把 `daily_call_limit` 放进 request attribute，`McpViewRegistry.invoke` 再放进 `PatCaller`。

### 2.3 返回体截断

```java
/** 外部调用的返回体上限。内部 harness 不受影响——它有自己按轮计算的上下文预算。 */
static final int MAX_RESULT_CHARS = 32 * 1024;

/**
 * 截断超长文本。
 *
 * <p>**只截 text，不截 structured**：structured 是给客户端程序读的，
 * 截断 JSON 会让它变成非法结构。text 是给模型读的，截断并标记是安全降级。
 */
static ToolResult cap(ToolResult result) {
    String text = result.text();
    if (text == null || text.length() <= MAX_RESULT_CHARS) {
        return result;
    }
    String kept = text.substring(0, MAX_RESULT_CHARS);
    return new ToolResult(result.error(),
            kept + "\n\n[已截断：原文 " + text.length() + " 字符，只返回前 " + MAX_RESULT_CHARS
                    + " 字符。需要完整内容时请缩小查询范围。]",
            result.structured());
}
```

调用点在 `invoke` 的 `doInvoke` 返回之后、`record` 之前。

### 2.4 指标

新类 `McpMetrics`（包可见，`@Component`）：

```java
@Component
class McpMetrics {

    private final MeterRegistry registry;

    McpMetrics(MeterRegistry registry) { this.registry = registry; }

    /** 一次工具调用。`result` ∈ ok / tool_error / denied。 */
    void calls(String toolset, String result);

    /** 单个工具的耗时。 */
    void duration(String tool, double seconds);

    /** 被拒的原因。 */
    void denied(String reason);
}
```

指标名（Prometheus 会加 `_total` 后缀）：

- `paideia.mcp.tool.calls` → `paideia_mcp_tool_calls_total{toolset,result}`
- `paideia.mcp.tool.duration` → `paideia_mcp_tool_duration_seconds{tool}`（Timer）
- `paideia.mcp.denied` → `paideia_mcp_denied_total{reason}`

**基数**：`tool` 65、`toolset` 14、`result` 3、`reason` 5。全部是有限枚举，无用户输入进标签。

**工具组怎么取**：`ToolCatalog.find(toolName)` 的 descriptor 里有 `toolset()`。
但被拒时可能拿不到 descriptor（未知工具），所以 `toolsetOf` 在找不到时返回 `"unknown"`。

### 2.5 凭据创建上限与配额字段

`PatService.create(...)` 加一个参数 `Integer dailyCallLimit`，并在开头加数量校验：

```java
/** 每用户凭据数量上限。单机个人项目里这是零成本护栏。 */
public static final int MAX_TOKENS_PER_USER = 20;

if (tokens.countByUser(userId) >= MAX_TOKENS_PER_USER) {
    throw new BizException(ErrorCode.INVALID_ARGUMENT,
            "最多只能有 " + MAX_TOKENS_PER_USER + " 个凭据；先吊销不用的再建新的。");
}
```

`McpTokenMapper` 加：

```java
@Select("SELECT COUNT(*) FROM mcp_access_tokens WHERE user_id = #{userId} AND revoked_at IS NULL")
int countActiveByUser(@Param("userId") long userId);
```

**只数未吊销的**：吊销过的凭据不该占名额，否则用户每次换设备都得先删历史。

**`dailyCallLimit` 的校验**：`null` 或 `0` 表示不限；负数拒绝；上限 100000（防手滑写错位数）。

### 2.6 凭据列表带用量

`PatService.TokenView` 加两个字段 `long totalCalls, long totalErrors` 与 `Integer dailyCallLimit`。
`list(userId)` 对每张凭据查一次 `totals`。

**N+1 查询问题**：凭据数上限 20，一次列表最多 21 条查询。这是低频页面（用户主动打开），
接受。若要优化可以改成一次 `GROUP BY token_id` 的批量查询——**现在不做，因为凭据数被 20 限住了**。

## 三、`paideia-ai` 的新工具

### 3.1 `ResumeTools`（`ai.internal.tools`）

依赖 `ResumeDocumentService`（同模块 `internal.resume`，可访问）。

```java
public final class ResumeTools {

    private static final String GROUP = "resume";

    @Component
    public static class List implements PaideiaTool {
        private final ResumeDocumentService documents;
        // descriptor: resume_list / resume / READ / readOnly=true / defaultGroup=false
        // call: documents.list(userId) → 文本行 "id=1 标题=… 默认 更新于…"
    }

    @Component
    public static class Get implements PaideiaTool {
        private final ResumeDocumentService documents;
        // descriptor: resume_get / resume / READ / readOnly=true / defaultGroup=false
        // schema: documentId (integer, 必填)
        // call: documents.get(userId, id) → 渲染成带小标题的纯文本
    }
}
```

`resume_get` 的输出格式：把 `ResumeData` 渲染成模型好读的文本（基本信息的键值、
教育/工作/项目/技能的列表、自我评价）。**不返回原始 JSON**——模型读结构化 JSON 的
token 效率低于带小标题的文本。

### 3.2 `InterviewTools`

依赖 `InterviewReviewService.listMine(userId)`（同模块 `internal.interview`）。

```java
public final class InterviewTools {
    private static final String GROUP = "interview";

    @Component
    public static class List implements PaideiaTool { /* interview_list */ }

    @Component
    public static class Get implements PaideiaTool {
        // interview_get：按 id 从 listMine 的结果里筛
        // **不新增 mapper 方法**：listMine 已经返回全部字段（含完整 evaluation）
    }
}
```

`interview_get` 从 `listMine` 里筛的理由：`listMine` 的 limit 是多少需要确认（读码时看），
若它已限 50 条以内，直接筛即可；否则要在 `InterviewReviewService` 加一个按 id 查的方法。

### 3.3 `MemoryTools`

依赖 `MemoryStore`（同模块 `internal.memory`）。

```java
public final class MemoryTools {
    private static final String GROUP = "memory";

    @Component
    public static class List implements PaideiaTool {
        // memory_list：memories.list(userId, limit, 0)
        // schema: limit (integer, 默认 20, 上限 50)
    }

    @Component
    public static class Remember implements PaideiaTool {
        // memory_remember：memories.rememberText(userId, content)
        // scope = WRITE, readOnly = false
        // schema: content (string, 必填, 上限 2000)
        // 返回：ToolResult.ok("已记住。", Map.of("memoryId", id))
    }
}
```

**为什么用 `rememberText` 而不是 `rememberStructured`**：`rememberStructured` 需要 `kind`，
而 kind 是枚举（事实/偏好/目标等）。让外部模型填 kind 等于让它猜我们的内部枚举，
猜错的后果是一条归错类的记忆。`rememberText` 是"他说过的话"那一路，语义上正好对应
"帮我记住这个"。

### 3.4 `ReviewTools`（`knowledge.internal.tools`）

依赖 `LearningReviewService`（同模块 `internal.learning`）。

```java
public final class ReviewTools {
    private static final String GROUP = "review";

    @Component
    public static class Due implements PaideiaTool {
        // review_due：reviews.due(userId, Instant.now())
    }

    @Component
    public static class Plan implements PaideiaTool {
        // review_plan：从 due + progress 组装概览
        // **不新增服务方法**：用 LearningProgressService 已有的进度读取
    }
}
```

## 四、前端（`/settings/mcp`）

改动集中在 `McpTokensPage.tsx`：

1. 创建表单加一个 `Input`：「每日调用上限」，placeholder「不填 = 不限」。
2. 凭据行加一行文字：`今日 12 / 100 次 · 累计 1,234 次 · 失败 3 次`。
3. `mcp-tokens-api.ts` 的 `McpToken` 加 `dailyCallLimit: number | null`、`callsToday: number`、
   `totalCalls: number`、`totalErrors: number`；`McpTokenInput` 加 `dailyCallLimit?: number`。

**`callsToday` 由后端算**：前端不知道"今天"是哪个时区。后端按 UTC 算，与配额判定同一处。

## 五、接口契约（供任务块引用，必须字面一致）

```java
// paideia-mcp / internal
enum Denial { NONE, UNKNOWN_TOOL, PERMISSION, RATE_LIMIT, QUOTA, VIEW_NOT_FOUND }
record Outcome(ToolResult result, Denial denial)

class McpUsageRecorder {
    void record(long tokenId, LocalDate day, boolean error);
    int callsToday(long tokenId, LocalDate day);
    Totals totals(long tokenId);
    int deleteAllFor(long userId);
    record Totals(long calls, long errors) {}
}

class McpMetrics {
    void calls(String toolset, String result);
    void duration(String tool, double seconds);
    void denied(String reason);
}

class McpToolInvoker {
    static final int MAX_RESULT_CHARS = 32 * 1024;
    static ToolResult cap(ToolResult result);
}

class PatService {
    public static final int MAX_TOKENS_PER_USER = 20;
    public CreatedToken create(long userId, String name, Set<ToolScope> scopes,
                               Set<String> toolsets, Integer expiresInDays,
                               Integer dailyCallLimit, String callerRole);
}

record PatCaller(long userId, long tokenId, String role, Set<ToolScope> scopes,
                 Set<String> toolsets, Integer dailyCallLimit)
```

## 六、已知限制（不在本项修）

- **视图缓存无淘汰**：`McpViewRegistry.views` 的键是 `视图名｜工具组｜角色`，
  server 从不 close。凭据数被 20 限住后，缓存条目上界是 `20 × 视图数 × 2`，可接受。
  触发修的条件：凭据上限被调高到 100 以上。
- **`mcp_token_usage` 无保留策略**：按天一行，每凭据每天一行，20 个凭据一年约 7300 行。
  不需要清理。若将来要清理，删 `day < now - 90d` 即可，累计值会因此变小——**所以现在不清理**。
- **配额按 UTC 日切**：北京时间用户会在早上 8 点"跨天"。这与项目"时间一律存 UTC"的约定一致，
  但不等于用户直觉的"今天"。若要改成北京时间日切，改 `LocalDate.now(ZoneOffset.UTC)`
  一处即可，**本项不做**（改了两处判断必须同时改，风险大于收益）。
