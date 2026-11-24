---
artifact: design
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

# 个人知识库与笔记树 设计

## 系统上下文

```
学习者浏览器 ──HTTP──> @paideia/app (React)
      │  /api/v1/note-books, /api/v1/notes..., /api/v1/public/shares/{id}
      ▼
paideia-app (Spring Boot, 模块化单体)
      ├─ paideia-note  ← 本项主体（book / note / link / importexport / share / tools / web）
      ├─ paideia-platform (ToolCatalog / SearchQuery / CurrentUser / ApiAccess)
      └─ paideia-mcp     ← 经 ToolCatalog 暴露 /mcp/x/note[/write]
外部 AI 客户端 ──PAT──> paideia-mcp ──> ToolCatalog ──> NoteTools ──> NoteApi
MySQL(paideia) ← Flyway 迁移：notes/note_books/note_links/note_shares
```

知识库/文档/链接/分享全部私有，按 `user_id` 隔离；唯一免鉴权面是 `GET /api/v1/public/shares/{shareId}`（单篇 + 整库）。

## 组件与职责

| 组件 | 职责 |
| --- | --- |
| `book/NoteBook` / `NoteBookMapper` / `NoteBookService` | 知识库实体、手写 SQL、CRUD/排序/软删（连带文档） |
| `note/Note` / `NoteMapper` / `NoteService` | 目录/文档实体（扩列）、CRUD、树查询、移动防成环、软删子树、全文检索 |
| `link/NoteLink` / `NoteLinkMapper` / `NoteLinkService` | `[[]]` 解析、保存时重建链接、出链/入链查询、悬空识别 |
| `importexport/NoteImportExportService` | 整库 zip 导出、单文档 .md 导出、.md/zip 导入（防穿越/炸弹、事务） |
| `share/NoteShareMapper` / `ShareService` | 分享对象（note/book）、有效期、撤回、下架、公开读取 |
| `NoteApi` / `NoteApiImplementation` | 模块对外契约（mcp 与前端控制器都用它），不暴露内部实现 |
| `internal/web/*Controller` | REST 端点（鉴权 + 参数校验 + 调服务） |
| `internal/tools/NoteTools` | `note` 工具集 MCP 工具（只读 + 写），`ToolDescriptor` 注册 |
| `user/NoteUserDeletionListener` | 注销事件级联删除（扩到 `note_books`/`note_links`） |
| 前端 `NoteBooksPage` / `NoteWorkspacePage` / `components/notes/*` | 知识库列表、工作台（树/编辑器/大纲/反链/搜索/导入导出/分享）、公开分享页 |

## 变更清单

| 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- |
| `backend/paideia-app/src/main/resources/db/migration/V20261124120000__note_books_tree_links.sql` | 新增 | 无 | 建 `note_books`、`note_links`；`notes` 加 `book_id/kind/parent_id/sort_order/deleted_at` + FULLTEXT(ngram)；`note_shares` 加 `target_type/target_id` 并回填去 `note_id`；为存量用户建「我的笔记」默认库并回填 | REQ-001/002/006/007/010, AC-001/006/013 |
| `.../note/internal/book/NoteBook.java` | 新增 | — | `record NoteBook(Long id, Long userId, String name, String description, int sortOrder, Instant deletedAt, Instant createdAt, Instant updatedAt)` | REQ-001 |
| `.../note/internal/book/NoteBookMapper.java` | 新增 | — | 手写 MyBatis：`insert / findByIdAndUser / listByUser / rename / updateSort / softDelete` | REQ-001 |
| `.../note/internal/book/NoteBookService.java` | 新增 | — | 上限校验、软删连带文档、默认库 | REQ-001/008 |
| `.../note/internal/note/Note.java` | 修改 | `id,userId,entryId,title,body,createdAt,updatedAt,excerpt` | 加 `bookId,parentId,kind,sortOrder,deletedAt` | REQ-002 |
| `.../note/internal/note/NoteMapper.java` | 修改 | `insert/findByIdAndUser/listByUser/update/delete` | 加层级列；加 `listByBook / searchByNgram / searchByLike / updateContent / move / softDeleteSubtree / countByBook / maxDepth` | REQ-002/004 |
| `.../note/internal/note/NoteService.java` | 修改 | 扁平 CRUD、`MAX_BODY_CHARS=20000` | 加 `createNode/createFolder/updateDoc/move（防成环）/softDeleteNode/tree/search`；`MAX_DOC_BODY_CHARS=200000` | REQ-002/003/004 |
| `.../note/internal/link/NoteLink.java` `NoteLinkMapper.java` `NoteLinkService.java` | 新增 | — | 解析 `\[\[标题\]\]`/`\[\[id:123\]\]`、保存重建、出链/入链、悬空识别 | REQ-006 |
| `.../note/internal/importexport/NoteImportExportService.java` | 新增 | — | 整库 zip 导出/导入、单文档 .md、防 zip slip/bomb、事务 | REQ-007 |
| `.../note/NoteApi.java` | 修改 | `list/find/create/append` + 2 record | 追加知识库/树/文档/搜索/反链方法 + `NoteBookBrief/TreeNode/DocContent/SearchHit/LinkRef/LinkPanel` | REQ-009 |
| `.../note/internal/api/NoteApiImplementation.java` | 修改 | 实现旧四方法 | 实现新增（旧四方法委托默认库保持兼容） | REQ-009 |
| `.../note/internal/web/NoteBookController.java` | 新增 | — | `/api/v1/note-books` 列表/建/改/删/排序/tree/export/import | REQ-001/007 |
| `.../note/internal/web/NoteController.java` | 修改 | 5 端点 | 加 `/search`、`/{id}/move`、`/{id}/links`、`/{id}/export`；`SaveRequest` 加 `bookId/parentId/kind` | REQ-002/003/004/006/007 |
| `.../note/internal/web/NoteShareController.java` | 修改 | `/notes/{id}/share`、`/notes/{id}/shares` | 加 `/note-books/{id}/share`、`/note-books/{id}/shares` | REQ-010 |
| `.../note/internal/web/PublicShareController.java` | 修改 | 返回单篇 `{title,body,entryId}` | 返回判别联合：`note` 单篇 / `book` 整库（tree + docs） | REQ-010 |
| `.../note/internal/share/NoteShareMapper.java` | 修改 | `note_id` | `target_type/target_id`；`findValidNote/findValidBook` | REQ-010 |
| `.../note/internal/share/ShareService.java` | 修改 | `share(noteId)` | `shareNote/shareBook`、`read` 分支、整库遍历 | REQ-010 |
| `.../note/internal/user/NoteUserDeletionListener.java` | 修改 | 删 `notes/shares/reports` | 加删 `note_books`/`note_links` | REQ-012 |
| `.../note/internal/tools/NoteTools.java` | 修改 | 4 工具 | 加 `note_list_books/note_get_tree/note_search/note_get_links`（读）+ `note_create_book/note_create_doc/note_update_doc/note_move_doc/note_delete_doc`（写） | REQ-009 |
| `backend/paideia-app/src/test/java/io/github/xiaou61/McpToolSnapshotTest.java` | 修改 | 期望 `note_list/get/create/append` | 加新工具名（含只读/写分组） | REQ-009 |
| `frontend/apps/app/src/notes-api.ts` | 修改 | 9 方法 | 加 books/tree/doc/search/links/import/export；新增 `NoteBook`/`TreeNode`/`DocContent`/`SearchHit`/`LinkRef` 类型 | REQ-013 |
| `frontend/apps/app/src/pages/NoteBooksPage.tsx` | 新增 | — | 知识库列表（建/改名/删/排序/分享整库/导入导出入口） | REQ-013 |
| `frontend/apps/app/src/pages/NoteWorkspacePage.tsx` | 新增 | — | 树 + 编辑器 + 大纲 + 反链 + 搜索 + 分享 | REQ-013 |
| `frontend/apps/app/src/components/notes/{DocTree,DocEditor,OutlinePanel,BacklinksPanel,SearchPanel,ImportExport}.tsx` | 新增 | — | 各面板 | REQ-013 |
| `frontend/apps/app/src/pages/SharedNotePage.tsx` | 修改 | 只渲染单篇 | 分支渲染整库树 + 文档 | REQ-010/013 |
| `frontend/apps/app/src/router.tsx` | 修改 | `/notes`→NotesPage、`/notes/new`→NoteNewPage | `/notes`→NoteBooksPage、`/notes/:bookId`→NoteWorkspacePage；`/share/:shareId` 保留 | REQ-013 |

## 流程

### 保存文档并维护反链

1. `PUT /api/v1/notes/{id}`（标题/正文/entryId）→ `NoteService.updateDoc`（校验归属、正文上限、标题必填）。
2. 同事务内 `NoteLinkService.rebuild(userId, docId, body)`：先 `deleteBySource(docId)`，再用正则提取目标，按 `id:` 或「同库标题匹配」解析 `target_note_id`（解析不到 → NULL + 保留 `target_title`，即悬空）。
3. 返回 `DocContent`。

### 移动节点（防成环）

1. `PUT /api/v1/notes/{id}/move {parentId, sortOrder}`。
2. `NoteService.move`：校验 `parentId != null` 时该父节点同库且归属本人；若 `parentId == id` 或 `parentId` 在 `id` 的子树内（递归查子孙）→ 抛 `BizException(BIZ, "不能移动到自身或子节点下")`。
3. 校验深度不超 `MAX_TREE_DEPTH`，更新 `parent_id/sort_order`。

### 整库分享公开读取

1. `POST /api/v1/note-books/{id}/share` → `ShareService.shareBook`（校验本人库，UUID，`target_type='book'`）。
2. `GET /api/v1/public/shares/{shareId}` → `findValidBook`（未撤回、未过期、未下架）→ 返回 `{targetType:"book", name, tree:[{id,parentId,kind,title,sortOrder}], docs:[{id,title,body}]}`；仅遍历该库、该用户、`deleted_at IS NULL` 的节点。
3. 单篇走 `findValidNote` 返回 `{targetType:"note", title, body, entryId}`。

## 接口与数据

### 数据表（迁移后）

`note_books`：
```
id BIGINT UNSIGNED AUTO_INCREMENT PK
user_id BIGINT UNSIGNED NOT NULL
name VARCHAR(200) NOT NULL
description VARCHAR(1000) NULL
sort_order INT NOT NULL DEFAULT 0
deleted_at DATETIME(3) NULL
created_at / updated_at DATETIME(3)
KEY idx_note_book_user(user_id, deleted_at, sort_order)
```
`notes`（新增列）：
```
book_id BIGINT UNSIGNED NULL, kind VARCHAR(8) NOT NULL DEFAULT 'doc',
parent_id BIGINT UNSIGNED NULL, sort_order INT NOT NULL DEFAULT 0,
deleted_at DATETIME(3) NULL,
KEY idx_note_book_tree(book_id, parent_id, sort_order), KEY idx_note_user_alive(user_id, deleted_at),
FULLTEXT KEY ft_note_title_body(title, body) WITH PARSER ngram
```
`note_links`：
```
id PK, user_id, source_note_id NOT NULL, target_note_id NULL, target_title VARCHAR(200) NOT NULL, created_at
KEY idx_note_link_source(source_note_id), KEY idx_note_link_target(target_note_id), KEY idx_note_link_user(user_id)
```
`note_shares`（改）：`share_id CHAR(36) PK, target_type VARCHAR(8) NOT NULL DEFAULT 'note', target_id BIGINT UNSIGNED NOT NULL, user_id, expires_at, revoked_at, created_at`；`KEY idx_note_share_target(target_type, target_id)`。

### REST 端点（新增/修改）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/note-books` | 本人知识库列表 |
| POST | `/api/v1/note-books` | 建库 `{name,description}` |
| PUT | `/api/v1/note-books/{id}` | 改名/描述/排序 `{name,description,sortOrder}` |
| DELETE | `/api/v1/note-books/{id}` | 软删（连带文档） |
| GET | `/api/v1/note-books/{id}/tree` | 该库节点树 |
| GET | `/api/v1/note-books/{id}/export` | 整库 zip |
| POST | `/api/v1/note-books/import` | multipart 导入为库 |
| POST | `/api/v1/notes` | 建目录/文档 `{bookId,parentId,kind,title,body}` |
| PUT | `/api/v1/notes/{id}` | 改标题/正文/entryId |
| PUT | `/api/v1/notes/{id}/move` | 移动/排序 `{parentId,sortOrder}` |
| DELETE | `/api/v1/notes/{id}` | 软删子树 |
| GET | `/api/v1/notes/search?q=&bookId=` | 全文检索（bookId 可空=全库） |
| GET | `/api/v1/notes/{id}/links` | `{outbound:[LinkRef], inbound:[LinkRef]}` |
| GET | `/api/v1/notes/{id}/export` | 单文档 `.md` |
| POST | `/api/v1/note-books/{id}/share` | 整库分享 |
| GET | `/api/v1/public/shares/{shareId}` | 公开读取（note/book 判别） |

### 上限（design 收口）

`MAX_TITLE_CHARS=200`、`MAX_DOC_BODY_CHARS=200_000`、`MAX_BOOK_NAME_CHARS=200`、`MAX_BOOK_DESC_CHARS=1000`、`MAX_BOOKS_PER_USER=100`、`MAX_NODES_PER_BOOK=5000`、`MAX_TREE_DEPTH=32`、`MAX_LINKS_PER_SAVE=200`、`SEARCH_LIMIT=50`（短查询 <2 字退 LIKE）、`MAX_IMPORT_BYTES=20MB`、`MAX_IMPORT_ENTRIES=2000`、`MAX_UNCOMPRESSED_BYTES=100MB`。

## 失败处理、安全与权限

- **归属校验**：每条 SQL 带 `user_id`；`requireOwner` 不命中统一 `NOT_FOUND`（不区分「不存在 / 非本人」）。
- **三态**：匿名 → 401（断言 `@ApiAccess(AUTHENTICATED)`）；他人 id → 404/空；本人 → 放行。有自动化矩阵。
- **成环/上限**：移动递归查子树；节点数/深度/正文超限抛业务错误。
- **导入安全**：`ZipInputStream` 规范化条目名，拒绝含 `..`、绝对路径、非 `.md`；累计解压字节 > `MAX_UNCOMPRESSED_BYTES` 或条目 > `MAX_IMPORT_ENTRIES` 立即中止；整个过程 `@Transactional`，失败不落半截。
- **公开分享**：仅 GET、UUID 不可枚举、`expires_at>now`、`revoked_at IS NULL`、举报下架即失效；整库只遍历被分享库该用户 `deleted_at IS NULL` 节点。
- **MCP**：工具 scope 限本人；PAT 只读令牌被 `ToolCatalog.view(readOnly=true)` 过滤，不能调写工具。

## 可观测性

- 复用模块现有日志；导入/导出/检索/移动失败返回结构化 `BizException`，不吞错、不透出上游原始报文。
- MCP 工具失败按现有 `ToolResult` 错误语义返回可见错误。

## 实施顺序

1. 迁移（建表/改列/回填/全文索引）→ 2. entities+Mapper → 3. `NoteBookService`+`NoteService` 树/检索 → 4. `NoteLinkService` → 5. `NoteImportExportService` → 6. `NoteApi`+实现 → 7. controllers → 8. 分享扩展 → 9. 注销级联 → 10. `NoteTools`+快照测试 → 11. 前端（列表→工作台→组件→分享页→路由）→ 12. e2e 与收尾。

## 测试策略

- **单元**：`NoteBookServiceTest`、`NoteServiceTest`（成环拒绝、深度/节点上限、软删子树、默认库）、`NoteLinkServiceTest`（解析/悬空/重建）、`NoteImportExportServiceTest`（zip slip、zip bomb、导入树、导出往返）、`ShareServiceTest`（整库隔离、到期、撤回）。
- **迁移**：在 `scripts/test-db.sh` 新库跑 Flyway，断言存量笔记进「我的笔记」、`note_shares.target_id` 回填、原分享可读；既有 `MigrationVersionFormatTest/MigrationChecksumTest/MigrationSeedOverlapTest` 保持绿。
- **契约/边界**：`ModularityTest`、`ApiContractTest`、`ApiSurfaceTest`、`AuthorizationIsolationTest` 覆盖新端点三态；`McpToolSnapshotTest` 更新期望；`McpStartupSmokeTest`。
- **e2e**：`playwright test e2e/notes.spec.ts`（改后跑），覆盖「建库→建树→编辑→检索→反链→导入导出→单篇/整库分享」一条链路。

## 需求追踪

| 需求 | 设计落点 | 验收 |
| --- | --- | --- |
| REQ-001 | `note_books` + `NoteBookService/Controller` | AC-001 |
| REQ-002 | `notes` 层级列 + `NoteService.move/tree` | AC-002 |
| REQ-003 | `NoteService.createNode/updateDoc` + `DocEditor` | AC-003 |
| REQ-004 | FULLTEXT(ngram) + `search` + `SearchPanel` | AC-004 |
| REQ-005 | `OutlinePanel` | AC-005 |
| REQ-006 | `note_links` + `NoteLinkService` + `BacklinksPanel` | AC-006 |
| REQ-007 | `NoteImportExportService` + `ImportExport` | AC-007 |
| REQ-008 | 全链路 `user_id` + 三态用例 | AC-008 |
| REQ-009 | `NoteTools` 扩展 + `McpToolSnapshotTest` | AC-009 |
| REQ-010 | `note_shares.target_type` + `ShareService` + `PublicShareController` + `SharedNotePage` | AC-010 |
| REQ-011 | 沿用举报/下架控制器 | AC-011 |
| REQ-012 | `NoteUserDeletionListener` 扩表 | AC-012 |
| REQ-013 | `NoteBooksPage` + `NoteWorkspacePage` + 组件 | AC-014 |

## 待决定事项

无阻断。实现中若发现「改 `notes` 影响分享/举报链路」超出可接受范围，按 requirements 假设 1 回退为独立文档表并回填 requirements/design。
