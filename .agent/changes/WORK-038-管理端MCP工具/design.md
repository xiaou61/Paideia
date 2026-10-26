---
artifact: design
work_id: WORK-038
work: 管理端 MCP 工具
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 10:27:08 +0800
approver_role: CTO
---

# 设计 · 管理端 MCP 工具

依据：requirements.md、proposal.md。

## 角色通道（接口）

```java
// paideia-platform：纯接口，不引 Spring
public interface RoleLookup {
    /** 账号不存在时返回空。返回值是账号模块的角色名（admin / learner）。 */
    Optional<String> roleOf(long userId);
}
```

装配在 `paideia-app`：`new RoleLookup() { roleOf -> accountApi.findRoleById(String.valueOf(userId)) }`，作为 Bean 注入 `paideia-mcp`。

请求链：

```
PAT Bearer
  → PatAuthenticationFilter: authenticate(token) → PatPrincipal(userId, tokenId, scopes, toolsets)
                              + roleLookup.roleOf(userId) → role
                              → request attributes: userId / tokenId / scopes / toolsets / role
  → McpViewRegistry.contextExtractor: 5 个属性 → McpTransportContext
  → McpViewRegistry.view(path, allowedToolsets, role)
        cacheKey = name + "|" + sorted(toolsets) + "|" + (isAdmin ? "admin" : "user")
        query = ToolQuery.view(toolsets, write, role, scopes)
  → McpToolInvoker.invoke(PatCaller(userId, tokenId, role, scopes, toolsets), ...)
        visible = catalog.view(...).contains(toolName)      // 第二道
        scope check: caller.scopes 含 descriptor.scope
        role check: descriptor.scope == ADMIN → caller.isAdmin()
        ToolContext(userId, role, scopes, EXTERNAL)
```

## 工具清单

命名规则：`<toolset>_<verb_object>`。R = 只读；W = 写；D = 破坏性写。全部 `scope=ADMIN`、`defaultGroup=false`。

### admin_content（15）

| 名称 | 类型 | 调用 |
| --- | --- | --- |
| `admin_content_list_categories` | R | `CategoryService.fullTree()` |
| `admin_content_save_category` | W | `CategoryAdminService.create/update`（有 id 走 update） |
| `admin_content_delete_category` | D | `CategoryAdminService.delete` |
| `admin_content_list_entries` | R | `EntryAdminService.page(status, PageQuery)` |
| `admin_content_get_entry` | R | `EntryAdminService.detail` |
| `admin_content_save_entry` | W | `EntryAdminService.create/update` |
| `admin_content_delete_entry` | D | `EntryAdminService.delete` |
| `admin_content_save_concept` | W | `ConceptAdminService.create/update` |
| `admin_content_delete_concept` | D | `ConceptAdminService.delete` |
| `admin_content_link_concept_entry` | W | `ConceptAdminService.linkEntry` |
| `admin_content_unlink_concept_entry` | D | `ConceptAdminService.unlinkEntry` |
| `admin_content_link_entries` | W | `RelationAdminService.create` |
| `admin_content_unlink_entries` | D | `RelationAdminService.delete` |
| `admin_content_link_concepts` | W | `ConceptAdminService.createEdge` |
| `admin_content_unlink_concepts` | D | `ConceptAdminService.deleteEdge` |

### admin_users（6）

| 名称 | 类型 | 调用 |
| --- | --- | --- |
| `admin_users_list` | R | `AccountAdminApi.list` + `AiAdminApi.usage` |
| `admin_users_get` | R | `AccountAdminApi.findById` + `AiAdminApi.usage` |
| `admin_users_credit_ledger` | R | `CreditService.auditPage` |
| `admin_users_set_role` | W | `AccountAdminApi.changeRole` |
| `admin_users_adjust_credits` | W | `AiAdminApi.adjust`（工具层补「非零、必须写原因、不能调自己」） |
| `admin_users_delete` | D | `AccountAdminApi.deleteByAdmin`（服务层拒绝删自己） |

### admin_ai（5）

| 名称 | 类型 | 调用 |
| --- | --- | --- |
| `admin_ai_overview` | R | `AiRunAdminService.overview + health + reconcile` |
| `admin_ai_series` | R | `AiRunAdminService.series` |
| `admin_ai_list_runs` | R | `AiRunAdminService.page` |
| `admin_ai_get_run` | R | `AiRunAdminService.detail` |
| `admin_ai_list_feedback` | R | `AiFeedbackService.page` |

### admin_review（5）

| 名称 | 类型 | 调用 |
| --- | --- | --- |
| `admin_review_list_note_reports` | R | `ShareService.listReports` |
| `admin_review_takedown_note` | D | `ShareService.takedown` |
| `admin_review_list_datasets` | R | `SqlDatasetMapper.listCommunity` |
| `admin_review_list_dataset_reports` | R | `SqlDatasetMapper.listReports` |
| `admin_review_set_dataset_status` | W | `SqlDatasetMapper.updateStatus`（下架时同时 `markReportsHandled`） |

### admin_learning（4）

| 名称 | 类型 | 调用 |
| --- | --- | --- |
| `admin_learning_list_questions` | R | `QuestionConceptAdminService.list` |
| `admin_learning_suggest_concepts` | R | `QuestionConceptAdminService.suggestions` |
| `admin_learning_set_question_concepts` | W | `QuestionConceptAdminService.replace` |
| `admin_learning_export_evaluation` | R | `QuestionConceptAdminService.exportAttempts` |

### admin_ops（1）

| 名称 | 类型 | 调用 |
| --- | --- | --- |
| `admin_ops_summary` | R | `OpsSummaryService.summary` |

### admin_digest（2）

| 名称 | 类型 | 调用 |
| --- | --- | --- |
| `admin_digest_list_items` | R | `DigestMapper.findByDate`（日期缺省按 `DigestProperties.today()`） |
| `admin_digest_delete_item` | D | `DigestMapper.deleteById` |

## 端点对照与合并

| 原端点 | 归入工具 |
| --- | --- |
| `GET /ai/admin/runs/overview`、`/health`、`/reconcile` | `admin_ai_overview`（一块面板的三个数字） |
| `GET /ai/admin/runs/meta` | 不单独出工具；筛选值写进 `admin_ai_list_runs` 的描述 |
| `GET /ai/admin/credits/audit`、`/ledger` | `admin_users_credit_ledger`（同一本账的两个视图） |
| `GET /account/admin/users/{id}`、`GET /ai/admin/credits/{userId}` | `admin_users_get` |
| `POST /account/admin/users/{id}/credits`、`POST /ai/admin/credits/{userId}/adjust` | `admin_users_adjust_credits`（两处重复） |
| `POST|PUT /knowledge/admin/categories` | `admin_content_save_category` |
| `POST|PUT /knowledge/admin/entries` | `admin_content_save_entry` |
| `POST|PUT /knowledge/admin/concepts` | `admin_content_save_concept` |
| `POST /sandbox/admin/sql-datasets/{key}/take-down`、`/restore` | `admin_review_set_dataset_status` |
| `POST /knowledge/admin/tracks` | 不出工具（导入脚本专用，管理端界面明确未做） |

## 返回格式

- 列表类工具返回编号文本行（`id=… 标题=…`），与既有学习者工具一致；不返回 JSON 字符串，模型读文本更稳。
- 单条详情返回多行 `字段：值`，正文原样附在最后。
- 错误统一 `ToolResult.fail("…：" + message)`，不抛异常（`McpToolInvoker` 还会兜一层）。
- 分页参数在工具层夹到 `1..100`；导出类夹到 `1..500`。

## 前端

`McpTokensPage` 的工具组清单新增 7 条（含已有的 `sql` 遗漏项补齐）：

| key | 标签 |
| --- | --- |
| `admin_content` | 内容管理 |
| `admin_users` | 用户与积分 |
| `admin_ai` | AI 观测 |
| `admin_review` | 举报与数据集 |
| `admin_learning` | 题目标注 |
| `admin_ops` | 运维 |
| `admin_digest` | 速递 |

权限范围多一个 `admin` 选项；是否展示由当前账号角色决定，但**服务端不依赖前端隐藏**。

## 已知限制

- 角色每次请求查一次账号表：多一次主键查询。用户在意的规模是「两个账号」，不做缓存；将来若成为瓶颈，按 `userId` 做短 TTL 缓存，但必须保证降权后 TTL 内生效可接受。
- 38 个工具在同一个管理视图里上下文成本约 5–6k token；按组切分视图可只加载需要的一组。
- `admin_content_unlink_entries` / `unlink_concepts` 需要关系 id，id 来自 `admin_content_get_entry` 或创建时的返回值；没有独立的「列出全部关系」工具。
