---
artifact: proposal
work_id: WORK-044
work: MCP 网关补全：跨域能力、凭据治理与可观测
status: approved
mode: strict
workflow: full
related_to: [WORK-019, WORK-039]
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 14:50:38 +0800
approver_role: CTO
---

# 方案：MCP 网关补全

## 要解决的三件事

按用户裁决，本期做 Track A（四域全做）、Track B（凭据治理）、Track D（可观测），不做 Track C。

1. **外部助手看不到平台的主要能力**：57 个工具里学习者侧只有 12 个，简历/面试/复习/记忆四个域各 0 个。
2. **凭据失控时发现不了也限制不住**：无数量上限、无配额、无用量统计、无返回体上限。
3. **MCP 在监控里是黑的**：零 Micrometer 指标，被拒调用只存在于日志里。

## 方案选择

### 一、四个域的工具怎么落（Track A）

**方案 A1：每个域在拥有数据的模块里加一个工具类**（采用）

- 简历、面试、记忆 → `paideia-ai/internal/tools/`
- 复习 → `paideia-knowledge/internal/tools/`

理由：这是仓库既有模式。`KnowledgeTools`、`NoteTools`、`AssessmentTools` 都是这个形状——
工具与它调用的服务在同一个模块的 `internal` 包里，能直接注入服务而不需要跨模块开口子。

**方案 A2：新建一个 `paideia-mcp-tools` 模块集中放**（不采用）

会立刻撞上 `ModularityTest`：Spring Modulith 禁止跨模块访问 `internal` 包。
要让新模块访问 `ResumeDocumentService`（在 `ai.internal.resume`），必须把它提到模块根、
或加一层公开端口。**为了"集中"付出一圈契约改造成本，而这个集中本身没有价值**——
工具已经按 `toolset` 分组了，物理位置不重要。

**方案 A3：只暴露已有 REST 端点、不写工具**（不采用）

REST 与工具是两种形状：REST 的 `GET /api/v1/ai/resumes` 返回 `ApiResponse` 信封，
而工具要的是给模型读的短文本 + 结构化结果。而且 REST 端点是按 UI 需要切的，
按 UI 切出来的粒度对模型往往不合适（例如简历编辑器需要 revision 做并发控制，模型不需要）。

### 二、新增 8 个工具的具体清单

按 REQ-007 的"上限 12 个"，实际只做 8 个（每域 2 个）：

| 工具名 | 组 | scope | 只读 | 干什么 |
| --- | --- | --- | --- | --- |
| `resume_list` | `resume` | READ | 是 | 列出本人简历（id、标题、是否默认、更新时间） |
| `resume_get` | `resume` | READ | 是 | 读一份简历的结构化内容 |
| `interview_list` | `interview` | READ | 是 | 列出本人历次面试评估（分数 + 时间） |
| `interview_get` | `interview` | READ | 是 | 读单次面试的完整评分与改进建议 |
| `review_due` | `review` | READ | 是 | 待复习条目（到期时间、阶段） |
| `review_plan` | `review` | READ | 是 | 复习计划概览（总条目、各阶段分布、最近复习） |
| `memory_list` | `memory` | READ | 是 | 列出本人记忆 |
| `memory_remember` | `memory` | WRITE | 否 | 写一条记忆 |

**为什么每域只 2 个**：REQ-007 明确要求"不为了凑覆盖度加工具"。四个域真正高频的操作是
"看一眼我有什么"和"读其中一条"，写操作只有记忆域天然需要（"帮我记住这个"）。

**不做的工具**（写在这里是为了将来有人问"为什么没有"时有答案）：

- 简历的 `analyze` / `propose`：**计费**。一次上游调用，且需要 JD 文本与版本上下文。
  外部助手做这件事的正确方式是先用 `resume_get` 拿到内容，再自己在自己的模型里分析。
- 面试的 `review`（触发点评）：同样计费，且需要整场对话作为输入。
- 复习的 `start_session` / `submit`：写操作 + 与训练营状态机耦合，外部调用会把状态搞乱。
- 记忆的 `delete` / `clear`：破坏性，且外部助手误删记忆的代价远大于收益。

### 三、凭据治理怎么落（Track B）

**计数器放哪：新建 `mcp_token_usage` 表，按 (token_id, day) 一行**（采用）

```
mcp_token_usage(token_id, day, calls, errors, updated_at)
PRIMARY KEY (token_id, day)
```

理由：

- **按天分行的理由**：每日配额需要"今天的计数"。单行累计值要靠 `updated_at` 判断是否跨天，
  跨天那一刻的并发会算错；按天分行后，跨天是天然的新行，没有竞态。
- **不放在 `mcp_access_tokens` 上**：那张表每次认证都要读，加两个高频写的计数列会让
  它成为热点（`PatService.authenticate` 已经在做 `touchLastUsed` 节流，就是因为这个）。
- **历史累计值**：`SUM(calls)` 按 token 聚合即可，不需要额外列。

**每日配额存在 `mcp_access_tokens` 上**（新列 `daily_call_limit INT NULL`）：
它是凭据的静态属性，不是计数，放这里最自然，且改动一行即可。

**配额判定在 `McpToolInvoker` 里**（采用）：那里已经有 `McpRateLimiter` 的限流判定，
两个判定语义相近（都是"别打了"），放一起读起来一致。区别是限流是内存固定窗口（防瞬时爆发），
配额是数据库每日计数（防长期失控），**两者都保留**。

**计数写在 `McpToolInvoker` 的 finally 里**：那里已经有审计日志的收尾，同一处记指标 + 计数，
不会出现"日志记了但计数没记"的分叉。

**返回体上限：32 KB，在 `McpToolInvoker` 统一截断**（采用）

放在 invoker 而不是各工具里：57 个工具各写一遍必然漏；而且这是"外部调用的护栏"，
内部 harness 不该被它影响（内部有自己的上下文预算）。

### 四、可观测怎么落（Track D）

**在 `McpToolInvoker` 里打 Micrometer 指标**（采用）：

- `paideia_mcp_tool_calls_total{toolset, result}` —— `result` ∈ `ok` / `tool_error` / `denied`
- `paideia_mcp_tool_duration_seconds{tool}` —— Timer
- `paideia_mcp_denied_total{reason}` —— `reason` ∈ `rate_limit` / `quota` / `permission` / `unknown_tool`

**为什么不复用审计日志**：日志是给人读的、无结构查询面。Prometheus 要的是稳定标签集与低基数。
两者并存：日志留细节（tokenId、userId），指标留趋势。

**基数控制**：`tool` 标签只有 65 个取值（57 + 8 新增），`toolset` 14 个，`reason` 4 个。
不会爆。

### 五、前端

`/settings/mcp` 页面加两列展示：今日调用次数 / 每日上限，以及累计调用与错误次数。
创建表单加一个"每日调用上限"输入（可空 = 不限）。

**不新增页面、不新增概念**：配额就是"每天最多调多少次"，用户已经在别处见过这个说法。

## 影响面

| 模块 | 改动 |
| --- | --- |
| `paideia-platform` | 无（`ToolDescriptor` 已有全部需要的字段） |
| `paideia-mcp` | 计数/配额/截断/指标；`mcp_access_tokens` 加一列；新表 `mcp_token_usage` |
| `paideia-ai` | 新增 `internal/tools/ResumeTools`、`InterviewTools`、`MemoryTools` |
| `paideia-knowledge` | 新增 `internal/tools/ReviewTools` |
| `paideia-app` | 两个迁移；`admin_ops_summary` 可选加一行 MCP 计数 |
| 前端 | `/settings/mcp` 加展示与一个输入框 |

## 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 新增工具改变默认视图清单（破坏 AC-005） | 全部 `defaultGroup=false`，且用快照测试钉住 `/mcp` 的数量 |
| 计数写入让 `mcp_access_tokens` 变热点 | 计数写在新表，不碰凭据表；配额列只读不写 |
| 每日计数在跨天时算错 | 按 (token_id, day) 分行，跨天天然是新行；day 用 UTC 日期 |
| 截断把结构化结果弄坏 | 只截 `text`，不截 `structured`；截断时附明确标记 |
| 指标基数爆炸 | `tool` / `toolset` / `reason` 都是有限枚举，无用户输入进标签 |
| 跨模块访问 internal 包 | 工具放在拥有数据的模块里，不新建跨模块模块 |

## 不采用的其他方案

- **在 `McpViewRegistry` 里截断**：那里只有 MCP 协议层，`ToolResult` 已经组装完；
  而内部 harness 走的是 `AgentToolBridge`，两处各写一遍截断逻辑迟早分叉。
  放 `McpToolInvoker` 是外部调用的唯一必经之路。
- **配额用 Redis 计数器**：项目仍是单机单实例，无 Redis。用数据库按天分行已经够，
  且重启不清零（REQ-013）。
- **给 `ToolDescriptor` 加 `enabled` 字段做 Track C**：用户裁决本期不做。
