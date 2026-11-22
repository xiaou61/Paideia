---
artifact: test-plan
work_id: WORK-031
work: Agent运行时迁移到ReActAgent
status: draft
workflow: full
created: 2026-10-21
updated: 2026-10-21
---

# 测试计划 · WORK-031 第一步（升版本 + 缓存 token）

## 范围

只覆盖**第一步**：依赖升级（2.0.0 → 2.0.4，MCP pin → 0.17.2）与 `cache_read_tokens` 修复。
第二、三步（`ReActAgent` 替换、`MiddlewareBase` 观测）不在本次范围。

## 验收矩阵

| 检查项 | 手段 | 判据 |
| --- | --- | --- |
| AC-001 升版本不炸 | `mvn -B -pl paideia-ai -am compile` | 编译通过；`dependency:tree` 里 `mcp` 是 0.17.2 |
| AC-008 缓存 token 有值 | `TokenUsageTest` + 线上真连一次 | 单测 2/2；线上 `ai_runs.cache_read_tokens > 0` |
| 反向验证 | 把三参构造改成误用 `totalTokens` 当缓存 | `TokenUsageTest` 立刻红（`expected: 0 but was: 120`） |
| 既有行为不退化 | 后端内环 `-pl '!paideia-app'` | 全绿，Skipped 与基线一致 |
| 契约未破坏 | 全量含 `ApiWalkthroughTest` | 快照 diff 只有**新增** `data.usage.cacheReadTokens` |
| MCP 客户端可加载 | 全量里的 `McpStartupSmokeTest` | 通过（pin 与传递版本不一致会在这里或运行期炸） |

## 不做的验证（并说明为什么）

- **不跑前端 e2e**：本步不改任何前端代码，唯一可见的 API 变化是新增字段，前端不读它。
- **不部署**：用户未授权；本步也不要求上线验证。
- **不测第二/三步**：未实现。

## 基线对照

| 项 | 改动前 | 改动后 |
| --- | --- | --- |
| 后端执行条数 | 543 | 545（+2 为新增 `TokenUsageTest`） |
| Skipped | 4 | 4（`LayaContentClassifierLiveTest` × 3、`FileStorageContractTest` × 1，均为既有设计） |
