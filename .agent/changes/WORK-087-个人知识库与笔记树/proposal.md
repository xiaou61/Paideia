---
artifact: proposal
work_id: WORK-087
work: 个人知识库与笔记树
status: approved
mode: strict
created: 2026-11-24
updated: 2026-11-24
depends_on: [WORK-006]
related_to: [WORK-019, WORK-044]
approved_by: xiaou61
approved_at: 2026-11-24 10:59:12 +0800
approver_role: CTO
---

# 个人知识库与笔记树 方案

## 摘要

在 WORK-006 的扁平个人笔记之上，扩成**语雀式个人知识库**：多个知识库、库内可无限嵌套的目录/文档树、markdown 编辑与预览、全文检索（含中文）、大纲 TOC、`[[]]` 双链与反链、`.md` 导入导出、单篇与整库只读分享，并把知识库/文档能力通过既有 `note` MCP 工具集暴露给外部 AI 客户端。一次性交付完整前端。

因需求已批准（见 `requirements.md`），本方案只负责「怎么做」，不重议「做什么」。

## 推荐方案（一句话）

**在 `paideia-note` 模块内做「加法 + 回填」的数据演进**：`notes` 表扩列承载层级（`kind/parent_id/sort_order/book_id/deleted_at`），新增 `note_books` 与 `note_links` 两张表，`note_shares` 加分享对象类型；服务层新增知识库/树/检索/双链/导入导出；对外只经 `NoteApi` 与 `ToolCatalog` 加面；前端新增知识库列表页与工作台页替换现有扁平笔记页。

## 范围

- 后端：`paideia-note`（主体）+ `paideia-app`（迁移与注册）+ `paideia-mcp`（无机制改动，仅经 `ToolCatalog` 暴露新工具）。
- 数据：1 条时间戳迁移（建表 + 改列 + 回填 + 全文索引）。
- 前端：学习者端 `@paideia/app`（知识库列表、工作台、分享公开页）。
- 不在范围：协作编辑、版本历史、向量检索、真 `.md` 文件/对象存储、跨用户协作（见 requirements 非目标）。

## 备选方案与不选理由

1. **新建独立 `note_docs` 表 + 保留 `notes` 做旧扁平笔记**。不选：两套写路径、分享/举报/注销要同时维护两张表，复杂度翻倍；把层级直接加在 `notes` 上只有一套代码。仅当设计阶段证明「改 notes 会破坏分享/举报链路」才回退到此（requirements 假设 1 已留口）。
2. **前端只用树 + 不落反链表、反链每次扫描正文**。不选：反链是全库反向查询，逐篇扫正文 O(n) 且中文匹配歧义；落 `note_links` 一次解析多次查询。
3. **整库分享复用单篇接口逐个文档返回**。不选：目录结构会丢；整库分享要返回树 + 文档集合，单列接口做不到。
4. **检索请求时临时建索引/内存检索**。不选：违背「仅 MySQL FULLTEXT」的既有约束，且数据量增长后不可控。

## 仓库影响

- 迁移：`backend/paideia-app/src/main/resources/db/migration/` 新增 1 个 14 位时间戳迁移（改 `notes`、`note_shares`，建 `note_books`、`note_links`）。
- 后端代码：`paideia-note` 新增 `book/link/importexport` 子包与若干 web 控制器，扩 `NoteApi`/`NoteService`/`NoteMapper`/`NoteTools`/`ShareService`/`NoteShareMapper`/`NoteUserDeletionListener`。
- 测试：`McpToolSnapshotTest` 期望工具集变化；新增服务/控制器/迁移/越权用例。
- 前端：新增 2 个页面 + 6 个组件 + 扩 `notes-api.ts` + 改 `router.tsx` / `SharedNotePage.tsx`；`LearnerHeader` 导航项沿用 `notes`。
- 架构图：模块边界无新增模块，`docs/architecture/` 若描述笔记表结构需同步（结构图由脚本生成）。

## 交付拆分（里程碑，最终以 tasks 纵向切片落地）

- **M1 数据与骨架**：迁移 + 实体/Mapper + 知识库 CRUD + 文档树 CRUD（含防成环/软删子树）。
- **M2 检索与关系**：全文检索、大纲（前端）、双链解析与反链。
- **M3 搬运与分享**：`.md` 导入导出、单篇/整库分享扩展、注销级联扩表。
- **M4 MCP 收口**：`note` 工具集全量 + 契约/快照测试。
- **M5 前端与验收**：知识库列表 + 工作台（树/编辑器/大纲/反链/搜索/导入导出/分享）+ 分享页整库 + e2e。

## 风险与缓解

- **改 `notes`/`note_shares` 破坏既有分享/举报/注销链路** → 迁移只做「加法 + 回填」，`note_shares` 加 `target_type/target_id` 并回填后保留索引；迁移用例覆盖「存量笔记仍在默认库、原分享仍可读」。
- **移动成环 / 深树 / 大正文拖垮库** → 服务层校验「目标不是自身或子树」+ 深度/节点数/正文上限；有自动化用例。
- **导入 zip slip / zip bomb** → 解析时规范化路径并拒绝 `..`/绝对路径、设条目数与解压总量上限、只收 `.md`、全事务。
- **整库分享越权泄漏** → 公开页只按「被分享库 + 该用户」遍历，绝不跨库跨人；有隔离用例。
- **中文检索召回不稳** → 沿用 ngram 先例，短查询退 `LIKE`，结果强制按 `user_id` 过滤。
- **并发会话改同一张表** → 迁移用时间戳号，动 schema 前先 `git log --all --oneline -- <迁移目录>` 检查。

## 验收映射

| 验收 | 主要由哪些方案承担 |
| --- | --- |
| AC-001 多知识库 | `note_books` + `NoteBookService/Controller` + MCP `note_list_books/note_create_book` |
| AC-002 文档树/防成环 | `notes` 层级列 + `NoteService` 移动防成环/软删子树 + `DocTree` 拖拽 |
| AC-003 文档读写 | `NoteService.createDoc/updateDoc` + `DocEditor` |
| AC-004 全文检索 | FULLTEXT(ngram) + `search` + `SearchPanel` |
| AC-005 大纲 | 前端 `OutlinePanel` 解析标题 |
| AC-006 双链反链 | `note_links` + `NoteLinkService` + `BacklinksPanel` |
| AC-007 导入导出 | `NoteImportExportService` + `ImportExport`（防穿越/炸弹） |
| AC-008 私有隔离 | 每条 SQL 带 `user_id` + 三态用例 |
| AC-009 MCP | `NoteTools` 扩展 + `McpToolSnapshotTest` |
| AC-010 单篇+整库分享 | `note_shares.target_type` + `ShareService` + `PublicShareController` + `SharedNotePage` |
| AC-011 举报下架 | 沿用 `ReportController`/`NoteReportAdminController` |
| AC-012 注销硬删 | `NoteUserDeletionListener` 扩 `note_books/note_links` |
| AC-013 迁移无损 | 迁移回填默认库 + 迁移用例 |
| AC-014 完整前端 | `NoteBooksPage` + `NoteWorkspacePage` + 组件 + e2e |

## 待决定事项

无阻断事项。各项上限取值（正文、节点数、深度、导入包大小）在 design 中定；分享对象类型的字段与整库公开页路由在 design 中收口。
