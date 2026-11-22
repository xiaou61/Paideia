---
artifact: design
work_id: WORK-031
work: Agent运行时迁移到ReActAgent
status: approved
depends_on: []
related_to: [WORK-009, WORK-022, WORK-030]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 14:59:57 +0800
approver_role: CTO
---

# 设计 · Agent 运行时迁移（第一步：升版本 + 缓存 token）

本文件只设计**第一步**。第二、三步在第一步验证通过后另行设计，避免在未验证的版本上做架构设计。

## 改动清单

| 文件 | 改什么 | 为什么 |
| --- | --- | --- |
| `backend/pom.xml` | `mcp-sdk.version`：`0.17.0` → `0.17.2` | 与 2.0.4 传递版本一致，否则运行期 `NoSuchMethodError` |
| `backend/paideia-ai/pom.xml` | `agentscope-core`、`agentscope-extensions-model-openai`：`2.0.0` → `2.0.4` | 拿到 `FinalAnswerFilterMiddleware` 等 |
| `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/TokenUsage.java` | 加 `cacheReadTokens` 分量（4 参主构造 + 3 参便捷构造） | 缓存用量现在是**结构性丢失**，没有任何地方能带出来 |
| `AgentScopeConversation.toUsage()` | 传 `usage.getCachedTokens()` | 上游报了缓存命中，我们丢掉 |
| `AgentScopeConversation` 的 `step.tokens(...)` | 第三个参数从 `0` 改为 `usage.cacheReadTokens()` | `ai_run_spans.cache_read_tokens` 一直恒为 0 |
| 各 `run.tokens(...)` 调用点 | 第三个参数从 `0` 改为 `usage.cacheReadTokens()` | `ai_runs.cache_read_tokens` 同样恒为 0 |

## TokenUsage 的形状

```java
public record TokenUsage(int inputTokens, int outputTokens, int totalTokens, int cacheReadTokens) {
    /** 三参构造保留：调用点几十处，改它们没有收益。缓存未知时按 0 记。 */
    public TokenUsage(int inputTokens, int outputTokens, int totalTokens) {
        this(inputTokens, outputTokens, totalTokens, 0);
    }
}
```

**为什么不是改所有调用点**：三参构造仍在，几十处现有调用一行不改；只有真正拿得到 `ChatUsage` 的地方才用四参构造。

**为什么不把缓存算进 `totalTokens`**：`totalTokens` 是上游给的原始值，动它会让"账实相符"这条断言失去意义。缓存单独一列。

## 计费口径不变

价目表（`pricing`）只有 `input` / `output` 两项，缓存折扣没有建模型。**本步只记录不调价**——把缓存 token 记下来是为了"看得见"，不是"少收钱"。要按缓存折扣计费是另一件事，需要价目表加字段并重定口径。

## 风险与对策

| 风险 | 检测方式 |
| --- | --- |
| 2.0.4 改了我们在用的 API（accumulator、builder、`ToolResultMessage` 构造） | **编译**。这是最直接的一道闸 |
| MCP 客户端运行期 `NoSuchMethodError` | `McpStartupSmokeTest` + 全量集成测试 |
| 升版本后行为变化（流式、思考分离、工具循环） | 现有对话/工具集成测试全跑；`AiTelemetryIntegrationTest` |
| 缓存 token 映射错（把别的字段当缓存） | 用真实调用核对：DeepSeek 同问题连问两次，第二次的 `cached` 应大于 0 |

## 验证矩阵

| 项 | 命令 / 手段 | 判据 |
| --- | --- | --- |
| 编译 | `mvn -B -pl paideia-ai -am compile` | 通过 |
| 后端正向 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 全绿，且**核对 Skipped 列不是"跳过"** |
| 后端全量 | `scripts/test-db.sh mvn -B -T 1C test` | 全绿，Skipped 与基线一致 |
| MCP 冒烟 | 全量里的 `McpStartupSmokeTest` | 通过 |
| 缓存列有值 | 真实调用 + 查 `ai_runs.cache_read_tokens` | 第二次 > 0 |
