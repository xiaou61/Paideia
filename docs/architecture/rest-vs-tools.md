# REST ↔ MCP Tool 收敛审计（WORK-074 TASK-B3，第一轮）

> 这是**人工审计文档**，不是自动生成的图。数据来源：`docs/architecture/tool-inventory.md`（运行时 67 个工具，由 `ToolGovernanceTest` 生成）与 `docs/architecture/06-api-surface.md`（190 个端点，由 `gen-arch-diagram.mjs` 生成）。本期**只出清单与处置结论，不改代码**。

## 结论先行

- **REST 与 tool 是两套消费面，不是二选一**：REST 服务第一方前端（学习者端/管理端，需要完整字段与页面交互）；MCP tool 服务外部模型客户端（窄、按 scope、按调用者归属）。**两者大量重叠是刻意的**，重叠本身不是缺陷。
- 真正要守的纪律只有两条：① **新对外能力优先做成 MCP tool**，不要再为外部消费新增 REST；② **同一能力的两面必须同源**（都调同一 Service/API），不允许"REST 一套逻辑、tool 另一套逻辑"。
- 本轮**没有发现必须立即合并的重复实现**：tool 全部经由各模块 `internal/tools` 调本模块公共 API，REST 走 Controller→Service，二者共享 Service 层。**处置：保持现状 + 立纪律**。

## 总览

| 面 | 数量 | 生成来源 |
| --- | --- | --- |
| MCP tool（注册） | 67 | `ToolGovernanceTest` → `tool-inventory.md` |
| REST 端点 | 190 / 48 控制器 | `gen-arch-diagram.mjs` → `06-api-surface.md` |

## toolset ↔ REST 前缀对照（处置：均"保持 + 同源"，除非另行标注）

| toolset | tool 数 | 对应 REST 前缀 | 关系 | 处置 |
| --- | --- | --- | --- | --- |
| `knowledge` | 5 | `/api/v1/knowledge`（读） | 只读能力重叠 | 保持；tool 走 `KnowledgeApi`，与 REST 同源 |
| `assessment` | 2 | `/api/v1/knowledge`（SelfAssessment） | 掌握度自评重叠 | 保持；同源 |
| `learning` / `planner` | 1 / 1 | `/api/v1/learning`、`/api/v1/learn` | 学习进度/路径重叠 | 保持；同源 |
| `review` | 2 | `/api/v1/learning`（复习） | 复习重叠 | 保持；同源 |
| `note` | 4 | `/api/v1/notes` | 笔记读写重叠 | 保持；写工具需 scope WRITE；同源 |
| `resume`/`interview`/`assistant`/`memory`/`sql` | 2/2/2/2/2 | `/api/v1/ai/*`、`/api/v1/ai/resumes` 等 | AI 能力重叠 | 保持；同源（AI 端口） |
| `account` | 1 | `/api/v1/account/profile`、`/api/v1/me` | 读自己身份 | 保持；同源 |
| `admin_content` | 15 | `/api/v1/knowledge/admin`（20） | 管理写重叠 | 保持；均 ADMIN scope，REST 亦仅管理员 |
| `admin_learning` | 4 | `/api/v1/learning/admin` | 管理写重叠 | 保持 |
| `admin_users` | 6 | `/api/v1/account/admin/users` | 管理写重叠 | 保持 |
| `admin_ai` | 5 | `/api/v1/ai/admin/*` | 管理重叠 | 保持 |
| `admin_review` | 5 | `/api/v1/learning`（题/概念管理） | 管理重叠 | 保持 |
| `admin_ops` | 4 | `/api/v1/ops/admin/*` | 运维管理重叠 | 保持 |
| `admin_digest` | 2 | `/api/v1/digest/admin` | 管理重叠 | 保持 |

## 纪律（写入长期约定，供后续遵守）

1. **新增外部能力**：先做 MCP tool（`PaideiaTool` + 描述符），不要再加"专为外部"的 REST 路由。
2. **同一能力两面同源**：Controller 与 tool 都必须调同一个模块公共 API / Service；禁止在 tool 里重写业务逻辑。
3. **管理类**：REST 走 `hasRole("ADMIN")`，tool 走 `ToolScope.ADMIN` + ADMIN 角色双判（已由 `ToolCatalog.view`/`McpToolInvoker` 保证）。
4. **审计节奏**：每新增一批 tool 后重跑 `ToolGovernanceTest` 刷新 `tool-inventory.md`，与本表对齐；发现"tool 有、REST 无"或反向时，回到本表登记处置。

## 已知局限

- 本表为 **toolset 级** 第一轮审计；逐 tool × 逐端点的字段级对照留待需要时细化。
- `admin_review`、`sql` 两组的 REST 对应按控制器推断，未逐端点核对。
