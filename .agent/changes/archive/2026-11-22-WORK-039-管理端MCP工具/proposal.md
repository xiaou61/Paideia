---
artifact: proposal
work_id: WORK-039
work: 管理端 MCP 工具
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 10:27:08 +0800
approver_role: CTO
---

# 方案 · 管理端 MCP 工具

需求与验收在 [requirements.md](requirements.md)。

## 一句话

在现有「工具目录 → 视图 → 调用器」三层上补一条**角色通道**，把管理端能力做成 7 个独立工具组、38 个工具；鉴权仍是两把锁（PAT 的 admin scope + 账号的 ADMIN 角色），且两把锁在清单和调用两处各判一次。

## 核心机制

### 一、角色从哪来

PAT 里只有 userId，没有角色。三种取法：

| 取法 | 问题 |
| --- | --- |
| 建 token 时把角色写进 token 行 | 降权后旧凭据仍是管理员，权限撤销最长要等 token 过期 |
| 每次请求查账号表 | 多一次查询，但角色改动立即生效 |
| 角色随请求头传 | 客户端可伪造，直接出局 |

选**每次请求查账号表**。查询封装成 `paideia-platform` 的纯接口 `RoleLookup`（平台库不引 Spring），由 `paideia-app` 用 `AccountApi.findRoleById` 实现——这样 `paideia-mcp` 仍然不 import 任何业务包，模块边界与 WORK-019 的约定一致。

### 二、两把锁各判一次

1. **凭据**：PAT 的 scopes 必须含 `admin`。建 token 时若请求 admin scope 而账号不是 ADMIN，直接拒绝（400），不静默降级——静默降级会让用户以为凭据有管理能力。
2. **角色**：请求时解析出的角色必须是 `ADMIN`。

两处都判：`ToolCatalog.view` 的角色过滤决定「清单里有没有」，`McpToolInvoker` 在调用前再判一次决定「能不能调」。只判一处的失败模式是「清单没有但猜到名字仍能调」，或者「清单可见但调用时才发现没权限」。

### 三、视图缓存键必须带角色

MCP SDK 的 server 在构建时固化工具清单，现有缓存键是 `视图名|工具组`。加管理工具后，若角色不进键：

- 管理员先访问 `/mcp/x/admin_content` → 缓存里存了含管理工具的 server；
- 非管理员随后访问同一路径 → 拿到同一台 server，**看到并可能调用管理工具**。

因此键扩为 `视图名|工具组|角色是否 ADMIN`。用布尔而不是角色字符串，避免将来多角色时缓存按角色数膨胀。

### 四、7 个工具组、38 个工具

分组名进 PAT 的工具组白名单（逗号字符串存库），**上线后改名会让已发凭据失配**，所以一次定死：

| 工具组 | 工具数 | 对应的管理端点 |
| --- | --- | --- |
| `admin_content` | 15 | 知识库 20 个端点（分类/条目/概念/关系/概念边） |
| `admin_users` | 6 | 账号 5 个 + 积分 4 个 |
| `admin_ai` | 5 | AI 调用 7 个 + 反馈 1 个 |
| `admin_review` | 5 | 笔记举报 2 个 + 数据集 4 个 |
| `admin_learning` | 4 | 题目标注 4 个 |
| `admin_ops` | 1 | 运维摘要 1 个 |
| `admin_digest` | 2 | 速递 2 个 |

合并规则：同一意图的 create/update 并成一个 upsert；同一资源的「下架/恢复」并成一个 status 参数；同一块面板的 overview/health/reconcile 并成一个快照。被合并的 11 个端点与理由写在 design 的对照表。

### 五、写工具的 scope 为什么是 ADMIN 而不是 WRITE

`ToolCatalog` 的角色过滤只对 `scope == ADMIN` 的工具生效。如果管理写工具声明为 `WRITE`：

- 非管理员可以建一个 `toolsets=["admin_content"], scopes=["read","write"]` 的 PAT；
- 视图按工具组过滤，管理写工具在清单里；
- 角色过滤不生效，因为它的 scope 不是 ADMIN；
- 结果：普通用户能删除知识条目。

所以管理写工具**必须**声明 `ADMIN`；「只读视图 / 可写视图」的区分仍由 `/write` 路径与 `readOnly` 标记承担。

## 放弃的备选

- **把管理工具混进既有工具组**：少 7 个新组，但「knowledge 组」的含义会随调用者角色变化，学习者视图与管理员视图的清单不一致，排查成本高于收益。
- **在 token 行里存角色快照**：省一次查询，但降权不立即生效，与 REQ-003 冲突。
- **一个通用 `admin_execute(action, params)` 工具**：工具数最少，但模型要在 49 个动作里选，schema 无法表达必填组合，错误率与误操作风险都更高。
