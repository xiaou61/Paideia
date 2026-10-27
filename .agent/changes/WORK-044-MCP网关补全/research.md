---
artifact: research
work_id: WORK-044
work: MCP 网关补全：跨域能力、凭据治理与可观测
status: draft
created: 2026-10-27
updated: 2026-10-27
related_to: [WORK-019, WORK-039]
---

# 调研：我们与 Uber MCP Gateway 的真实差距

## 起因

2026-10-27 用户读 Uber Engineering《设计 Uber 的 MCP 管理平台：MCP Gateway》
（公众号转载，原文为 Uber 官方博客）后问：

1. 「他们设计的这个 mcp gateway 我们可以用到吗」
2. 「你仔细想想我们的差什么东西呢？给我仔细调研一下，然后是不是可以新开一个 work 来优化呢？」

本文件回答第二个问题。第一个问题的结论是「骨架已全有」，见下面的对照表。

## 一、逐项对照：Uber 的设计 vs 我们的实现

| Uber 的组件 / 原则 | 我们的实现 | 差距 |
| --- | --- | --- |
| **MCP Registry**：几百个 server、几千个工具的唯一真源 | `ToolCatalog`，启动期校验重名 / 非法名 / schema 非 object / 只读与破坏性矛盾 | **无** |
| **`/<service-name>/mcp`** 每服务一个端点 | `/mcp`、`/mcp/x/{toolset}`、`/mcp/x/all`，各带 `/write` 变体 | **无** |
| **工具级授权 + PII 脱敏** | `McpToolInvoker`：视图校验 → scope 判定 → 角色判定 → 限流 → 异常脱敏 | **无** |
| **凭据归属与治理** | `mcp_access_tokens`：sha256 存储、明文只出现一次、可吊销、过期、注销级联硬删 | **无** |
| **Omni MCP 渐进式发现** | 内部 `find_tools` + `eager-limit` 12 + `ToolCatalog.search` 词面打分 | **外部没有**（见缺口 5） |
| **发现 ≠ 对外暴露**（默认 disabled） | 无。注册即在权限允许时可用 | **有差距**（见缺口 4） |
| **AutoCrawler**（扫 IDL 自动生成） | 不适用 | 见「为什么不搬」 |
| **响应投影**（GraphQL 式裁剪） | 前提不成立 | 见「为什么不搬」 |
| **Code Mode**（CLI + 写文件） | 不适用 | 见「为什么不搬」 |
| **第三方 server 代理**（Jira/Google） | 无 | 见「为什么不搬」 |

**结论：他们文章的骨架我们全有。** 剩下的是量级差异带来的运营能力，不是架构缺失。

## 二、为什么不搬那四项

### AutoCrawler（IDL 自动发现 + LLM 生成工具描述）

Uber 的前提是 800 个 server / 5000 个工具、几百个团队各自写 MCP server、有 Thrift/Protobuf IDL 注册表。
我们：**57 个工具、12 个组、单体应用、无 IDL**。

更关键的是**搬了会变差**：自动生成的描述会丢掉我们刻意写进描述里的归属约束。举两个真实例子：

- `note_create` 的描述写着「为**当前调用者**新建一条笔记」——模型据此知道不该替别人写；
- `KnowledgeAdminTools` 的类注释说明「更新是先读后写，因为底层服务把 null 当作清空，
  模型少写一个字段就静默抹掉正文」——这类精确性只有写工具的人知道。

Uber 也承认这一点：他们让 LLM 生成描述后，**仍然要求服务负责人在启用前审阅并完善**，
每次描述变更都要走 diff 审批。也就是说 AutoCrawler 只省了"发现"，没省"审核"。
我们连"发现"这一步都不存在。

### 响应投影（在 schema 里注入字段数组，运行时裁剪响应）

前提是工具有结构化输出、且输出大。实测：

- 57 个工具里只有 **2 个**有 `outputSchema`（`note_create`、`assistant_draft_note`）；
- `ToolResult.structured` 的生产调用点只有 **2 处**。

**没有结构化输出就没有可投影的字段树。** 这不是"用不上"，是前置条件都不具备。
真要走到那一步，先补的是 outputSchema 覆盖率，而不是裁剪机制。

### Code Mode（aifx CLI：list / search / call，输出写文件后 grep）

前提是"工具输出进上下文比写文件贵"，且 agent 有文件系统。

我们：纯 DB 读写、无 shell、工具自身已经限了返回条数（`note_list` 默认 10、`admin_ops_logs` 上限 200）。

**唯一可能翻盘的场景**：某个工具真跑出巨大响应。本项 REQ-011 的"返回体上限 + 截断标记"
会先把这件事**量出来**——如果实测发现确实经常撞上限，那时再谈 Code Mode 才有依据。

### 第三方 MCP server 代理

Uber 接 Jira、Google 是因为员工要在内部 agent 里操作这些系统。我们没有这类接入需求。
将来若真接，需要的是"内部用户令牌 → 第三方 token 交换"，那是一个独立的安全设计，不应混在本项。

## 三、实测发现的真实缺口

下面每一条都是**读码确认 + 可复算的数字**，不是"Uber 有所以我们也要有"。

### 缺口 1：跨域覆盖为零（用户能直接感知）

工具按域统计（`new ToolDescriptor(...)` 第二参数 / `GROUP` 常量）：

```
[account]       1    account_me
[admin_ai]      5
[admin_content] 15
[admin_digest]  2
[admin_learning] 4
[admin_ops]     4
[admin_review]  5
[admin_users]   6
[assessment]    2
[assistant]     1
[knowledge]     5
[learning]      1
[note]          4
[sql]           1
                 --
                 56
```

**学习者侧只有 12 个工具，且集中在知识库与笔记。** 完全为 0 的域：

| 域 | 后端 API 存在 | MCP 工具 |
| --- | --- | --- |
| 简历 | `ResumeController` / `ResumeOptimizationController` | **0** |
| 面试 | `InterviewController` | **0** |
| 复习会话 | `review_sessions` 表 + `LearningController` | **0** |
| 记忆 | `AiMemoryController` | **0** |
| 训练营 | `CampController` | **0** |
| 知识图谱 | 前端 `/graph` 路由 | **0** |
| 速递（学习者侧） | `DigestController` | **0**（只有 admin 侧 2 个） |
| 题目作答 | `QuestionAttemptController` | **0** |

这就是"外部助手能用的平台"与"平台实际能力"之间的差距。
一个装了 Paideia MCP 的 Claude Desktop 用户，能查知识库、记笔记，但**看不到自己的简历、面试记录与记忆**。

### 缺口 2：凭据失控时无法发现也无法限制

| 检查项 | 结果 |
| --- | --- |
| 每用户凭据数量上限 | **无**（`PatService.create` 无计数校验） |
| 凭据级配额（日/总量） | **无**（只有 `McpRateLimiter` 120 次/分钟/token，内存单实例） |
| 凭据用量统计 | **无**（表里只有 `last_used_at`） |
| 单次返回体上限 | **无**（`McpToolInvoker` 不截断） |

后果的具体形态：

- 一个写错循环的 MCP 客户端可以在 120 次/分钟的额度内持续调用 `assistant_ask`，
  每次真实打一次上游模型。积分会被扣（有预留-结算），但**用户看不到"这个凭据花了多少"**。
- 一个返回大响应的工具（例如把 `admin_ops_logs` 的 limit 拉到 200）会把大量日志塞进
  调用方模型的上下文。我们的审计日志只记 `bytes`，不做限制。
- 凭据数量无上限意味着无限次建——虽然单机场景下危害有限，但这是零成本该有的护栏。

### 缺口 3：MCP 的运行状况不可观测

`McpToolInvoker` 只打两行日志（`mcp.audit` / `mcp.error`），**没有任何 Micrometer 指标**。

对比：定时任务有 `ScheduledTaskMetrics`，AI 有完整的 `ai_runs` 表与观测面板（WORK-036），
运维有 `admin_ops_*` 工具（WORK-042）。**只有 MCP 是黑的**。

具体后果：被限流的调用、被拒的权限、视图不存在，全都只存在于日志里；
Prometheus 抓不到"今天 MCP 被调了多少次、失败率多少、哪个工具最慢"。

### 缺口 4：没有"发现 ≠ 暴露"的开关

Uber 的核心原则之一。我们的现状：工具一注册，只要调用方的 scope/role/toolsets 允许，立刻可用。

在我们这个规模下**这不是风险**（一个人写工具、57 个工具、无外部贡献者）。
但有一个真实的中间形态值得注意：`ToolCatalog` 的启动校验已经能挡住"写错了"，挡不住"还没想好要不要开放"。

### 缺口 5：外部 MCP 没有按需发现

内部有 `find_tools`（`AgentToolBridge.FIND_TOOLS`），**外部 MCP 没有**。

原因在 WORK-019 已记录且仍然成立：**MCP 的元工具不能注册新工具**——`tools/list` 返回什么，
模型就只能调什么。所以外部只能靠视图端点切分，不能靠"发现后加载"。

但注意一个反直觉的事实：`/mcp/x/all` 全量 schema 只有 **13 KB / 约 7.2k tokens**。
**这个量级根本不需要按需发现。** Uber 需要 Omni MCP 是因为 5000 个工具，
我们 57 个工具全塞进去也就 7k tokens。

→ **这条不是缺口，是本项明确不做的**。写在这里是为了防止将来有人重新提出。

## 四、顺带确认的"看起来是问题但其实不是"

调研中怀疑过三处，逐一验证后**排除**，记录以免重复怀疑：

| 怀疑 | 验证结果 |
| --- | --- |
| 运维日志经 MCP 流到第三方客户端时可能泄露口令/邮箱 | **不成立**。`OpsLogRedactor` 有 5 条正则（password/token/secret 赋值、Bearer、JWT、邮箱、32+ 位十六进制），单行截断 500 字符，且有条数/总字符双重上限 |
| `/mcp/write` 上只读凭据可能看到写工具 | **不成立**。`ToolQuery.view` 在 `write=false` 时先把 `WRITE` scope 从有效集合里滤掉，清单与调用用同一条过滤链 |
| 管理工具可能被非管理员通过 `admin_*` 组路径看到 | **不成立**。`McpViewRegistry.view` 对非管理员直接把 `admin_*` 组当"未知视图"抛 404，不是返回空清单 |

## 五、一个真实存在但本项不修的隐患

**视图缓存无淘汰、server 从不 close。**

`McpViewRegistry.views` 是 `ConcurrentHashMap`，键 = `视图名｜工具组｜角色`。
每个键构建一台 `McpSyncServer` + 一个 transport。凭据的工具组白名单组合是有限的
（最多 2^12 种组合 × 2 种角色 × 十几个视图），实际取值空间被凭据数量限制。

**为什么不修**：凭据数量在单机个人项目里是个位数到几十，缓存条目同量级，每个 server 只持有
一个工具清单列表。真实内存代价以 MB 计，而"加淘汰逻辑"要处理"正在被请求使用的 server 被关闭"
这个竞态——修错的代价比不修大。

**触发修的条件**（写进 design 的已知限制）：凭据数破百，或某个凭据频繁改工具组导致缓存键快速增殖。

## 六、测量方法（供复算）

```bash
# 工具总数与分组（GROUP 常量也要解析，否则会漏 41 个）
python - <<'PY'
import re, pathlib, collections
pat = re.compile(r'new ToolDescriptor\(\s*"([a-z0-9_]+)"\s*,\s*([A-Za-z_"][A-Za-z0-9_"]*)', re.S)
rows=[]
for p in pathlib.Path('backend').rglob('*.java'):
    if '/test/' in p.as_posix(): continue
    t=p.read_text(encoding='utf-8',errors='ignore')
    m=re.search(r'String GROUP\s*=\s*"([a-z0-9_]+)"', t)
    g=m.group(1) if m else '?'
    for name,grp in pat.findall(t):
        rows.append((grp.strip('"') if grp.startswith('"') else g, name))
c=collections.Counter(g for g,_ in rows)
for g,n in sorted(c.items()): print(n,g)
print("total", len(rows))
PY
```

`outputSchema` 与 `structured` 的调用点用 `rg -U 'new ToolDescriptor\(' -A 10` 逐个看第 6 参数；
`ToolResult.ok(` 的第二参数用 `rg -n 'ToolResult\.ok\([^)]*,\s*[^)]' backend --glob '!**/test/**'`。

schema 体积是把每个 `new ToolDescriptor(...)` 块内的字符串字面量总长 × 1.4（JSON 结构开销）估算，
**不是真序列化后的 MCP 报文**。真值需要在 `/mcp/x/all` 上实际抓一次 `tools/list` 响应。
