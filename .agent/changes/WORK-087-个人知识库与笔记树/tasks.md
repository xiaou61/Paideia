---
artifact: tasks
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

# 个人知识库与笔记树 任务拆解

## 依据

`requirements.md`（已批准）+ `proposal.md` + `design.md`。按设计「实施顺序」纵向切片，每个任务可独立验证。

## 全局约束

- Spring Boot 4 + JDK 25；手写 MyBatis（禁 MyBatis-Plus）；仅 MySQL；Flyway 14 位时间戳迁移；模块化单体 + Spring Modulith；跨模块只经 `NoteApi`。
- 构建/测试走 `scripts/test-db.sh`；e2e 选文件（`playwright test e2e/notes.spec.ts`）；不 `pnpm -r build`。
- 公开仓库：凭据/服务器信息不入库。用户数据每条 SQL 带 `user_id`。
- 动 schema 前先 `git log --all --oneline -- backend/paideia-app/src/main/resources/db/migration`，确认无人改同一张表。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 移动成环 / 深树 | TASK-003 | `NoteServiceTest` 成环拒绝、深度上限 |
| 检索不按人过滤 | TASK-004 | `NoteSearchTest` 越权不命中 |
| 导入 zip slip / bomb | TASK-006 | `NoteImportExportServiceTest` |
| 整库分享泄漏跨库 | TASK-007 | `ShareServiceTest` 只遍历被分享库 |
| 注销删不干净 | TASK-008 | `NoteUserDeletionTest` 五表行数为 0 |
| MCP 越权 id / 只读调写 | TASK-009 | `McpToolSnapshotTest` + 越权用例 |
| 存量迁移丢数据/分享失效 | TASK-001 | 迁移用例 |

## 任务

### TASK-001 | done | 数据层迁移与实体 Mapper

- 对应：REQ-001/002/006/010/013，AC-001/006/013
- 依赖：无
- 新建：`db/migration/V20261124120000__note_books_tree_links.sql`；`internal/book/NoteBook.java`、`NoteBookMapper.java`；`internal/link/NoteLink.java`、`NoteLinkMapper.java`
- 修改：`internal/note/Note.java`（加 `bookId/parentId/kind/sortOrder/deletedAt`）、`NoteMapper.java`（映射新列 + 基础 SQL）；`NoteShareMapper` 本任务不动
- 测试：迁移用例（存量进「我的笔记」、`note_shares.target_id` 回填、原分享可读）；`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 步骤：写迁移（加法+回填+FULLTEXT ngram+note_shares 加 `target_type/target_id` 并**保留 `note_id`**，因 `NoteReportMapper.list` JOIN 依赖它）→ 实体/Mapper → 跑迁移与守卫测试
- 验证：新库 `test-db.sh` 起飞成功，守卫三件绿
- 完成记录：2026-11-24 提交 `d831d2d9`；`MigrationVersionFormatTest` / `MigrationChecksumTest` / `MigrationSeedOverlapTest` 绿，`NoteIntegrationTest`+`NoteShareIntegrationTest`+`NoteReportIntegrationTest` 共 14 条绿（Flyway 实跑新迁移，含 FULLTEXT ngram）；`note_books` 不在 devdata 种子表集合。

### TASK-002 | done | 知识库管理 API

- 对应：REQ-001，AC-001
- 依赖：TASK-001
- 新建：`internal/book/NoteBookService.java`、`internal/web/NoteBookController.java`（列表/建/改/删/排序）
- 修改：`NoteApi.java` + `NoteApiImplementation.java`（`listBooks/createBook`）
- 测试：`NoteBookServiceTest`（上限/软删连带/三态）；`AuthorizationIsolationTest` 覆盖新端点
- 步骤：Service（上限 `MAX_BOOKS_PER_USER=100`）→ 控制器 → 隔离用例
- 验证：本人可见、他人 404、匿名 401
- 完成记录：`internal/book/NoteBookService.java`（list/create/update/softDelete/requireOwned/ensureDefaultBook）+ `internal/web/NoteBookController.java`（`/api/v1/note-books` CRUD + `/{id}/tree`）+ `NoteApi.listBooks/createBook`；`NoteTreeIntegrationTest#bookIsolationAndCascade` 覆盖他人 404/匿名 401。

### TASK-003 | done | 文档树 CRUD 与防成环

- 对应：REQ-002/003，AC-002/003
- 依赖：TASK-001
- 新建：无（复用 note 包）
- 修改：`internal/note/NoteService.java`（`createNode/createFolder/updateDoc/move/softDeleteNode/tree`，`MAX_DOC_BODY_CHARS=200000`、`MAX_NODES_PER_BOOK=5000`、`MAX_TREE_DEPTH=32`）、`NoteMapper.java`、`internal/web/NoteController.java`（POST 扩字段、`/{id}/move`）
- 测试：`NoteServiceTest`（成环拒绝、深度上限、软删子树、标题必填正文可空）
- 步骤：树查询 → 建/改 → 移动防成环 → 软删子树 → 控制器
- 验证：`PUT /notes/{id}/move` 到自身/子孙返回业务错误；删除父节点后子节点不可见
- 完成记录：`NoteService.createNode/createFolder/updateDoc/move/softDeleteNode/tree`（`MAX_DOC_BODY_CHARS=200000`、`MAX_NODES_PER_BOOK=5000`、`MAX_TREE_DEPTH=32`）+ `NoteController`（POST 扩 `bookId/parentId/kind`、`PUT /{id}/move`）；`NoteTreeIntegrationTest` 3 条绿（防成环、跨库 404、删目录连带子树）。

### TASK-004 | done | 全文检索

- 对应：REQ-004，AC-004
- 依赖：TASK-001/003
- 修改：`NoteMapper.search`（`<choose>` 按 `fulltext` 走 `MATCH...AGAINST(... IN BOOLEAN MODE)` 或 `LIKE` 回退）、`NoteService.search`、`NoteController` `GET /search`、`NoteApi.search`
- 测试：`NoteSearchTest`（中文召回、短查询回退、跨用户不命中、结果含摘要）
- 步骤：复用 `paideia-platform/SearchQuery` 构造 BOOLEAN 串 → mapper → service（强制 `user_id` 过滤）→ 控制器
- 验证：搜「知识」命中正文含该词的本人文档，他人文档不出现
- 完成记录：复用 `paideia-platform/SearchQuery`（normalize/booleanMode/likePattern）；`NoteSearchIntegrationTest` 绿（多字「倒排索引」命中、单字「索」回退 LIKE 命中、无关词为空、空白为空、跨用户不命中、匿名 401）。

### TASK-005 | done | 双链解析与反链

- 对应：REQ-006，AC-006
- 依赖：TASK-001/003
- 新建：`internal/link/NoteLinkService.java`
- 修改：`NoteService`（create/createNode/update/updateDoc 保存后同事务 `rebuild`；软删时清理出链）、`NoteController` `GET /{id}/links`、`NoteMapper.findDocIdByTitle/findDocTitleById`
- 测试：`NoteLinkIntegrationTest`（`[[标题]]`/`[[id:123]]` 解析、悬空、自引用忽略、去重、入链、他人 404）
- 步骤：正则解析 → 解析目标 → 重建 → 出链/入链查询 → 控制器
- 验证：A 文引用 B 后，B 的反链出现 A，A 的出链出现 B；B 删除后 A 出链标悬空
- 完成记录：`NoteLinkService`（正则 `\[\[([^\]]+)\]\]`、MAX_LINKS=200、`[[id:N]]` 按 id、`[[标题]]` 同库标题、自引用/重复忽略、悬空保留标题）；`NoteLinkIntegrationTest` 1 条绿（出链去重+悬空、入链、自引用清空、他人 404）。
- 偏差：未把 `links` 加到 `NoteApi`（MCP 侧暂无消费方），仅控制器暴露；如 TASK-009 需要再补。

### TASK-006 | done | .md 导入导出

- 对应：REQ-007，AC-007
- 依赖：TASK-003
- 新建：`internal/importexport/NoteImportExportService.java`
- 修改：`NoteBookController`（`/{id}/export`、`POST /import`）、`NoteController`（`/{id}/export`）
- 测试：`NoteImportExportIntegrationTest`（单篇导出含正文、整库 zip 往返生成树、zip slip `../` 拒绝 400）
- 步骤：单文档导出 → 整库 zip 导出 → zip/md 导入（规范化路径、限条目/解压量、事务）→ 控制器
- 验证：含 `../` 的 zip 被拒并回滚；正常 `.md` 包导入成树
- 完成记录：`NoteImportExportService` + `NoteBookController`（`GET /{id}/export`、`POST /{id}/import/zip`、`POST /{id}/import/markdown`）+ `NoteController`（`GET /{id}/export`）；集成测试 1 条绿（含 zip slip 400）。

### TASK-007 | done | 分享扩展（单篇 + 整库）

- 对应：REQ-010，AC-010
- 依赖：TASK-001/003
- 修改：`share/NoteShareMapper.java`（`findValid/findValidBook/findActiveForBook`）、`share/ShareService.java`（`shareBook/activeBookShares/read` 判别）、`web/NoteShareController.java`（`/note-books/{id}/share|shares`）、`web/PublicShareController.java`（`SharedView` 联合）
- 步骤：mapper → service 分支 → 控制器 → 公开读取判别
- 验证：整库分享链接能浏览整棵树，且不含其它库/用户文档
- 完成记录：新增迁移 `V20261124130000__note_shares_target_nullable.sql`（note_id 放宽为可空，支撑整库分享）；`SharedView(type,title,body,entryId,documents)` + 嵌套 `SharedBookNode`；`NoteMapper.listShareDocs(userId,bookId)` 按分享者身份限定；`NoteReportMapper.list` 标题 `COALESCE(n.title,b.name)`。测试 `NoteBookShareIntegrationTest` 3 条绿（整库只含本人文档+撤回404、单篇回归+跨用户404、到期404）。

### TASK-008 | done | 注销级联扩表

- 对应：REQ-012，AC-012
- 依赖：TASK-001
- 修改：`internal/user/NoteUserDeletionListener.java`（USER_TABLES 加 `note_links`、`note_books`）
- 测试：`AccountDeletionCascadeIntegrationTest`（USER_DATA 扩两表并造种子行）
- 步骤：扩删除清单 → 用例
- 验证：注销后任何人（含本人）不可再访问
- 完成记录：USER_TABLES 现依次为 note_links/note_reports/note_shares/notes/note_books；测试 4 条绿（含"别人的数据不受影响"）。

### TASK-009 | done | MCP 工具面与快照/契约

- 对应：REQ-009，AC-009
- 依赖：TASK-002/003/004/005
- 修改：`internal/tools/NoteTools.java`（加 9 个工具）、`NoteApi.java`（补方法）、`backend/paideia-app/src/test/java/io/github/xiaou61/McpToolSnapshotTest.java`（期望集合）
- 测试：`McpToolSnapshotTest`、`McpStartupSmokeTest`、只读令牌不能调写工具用例、越权 id 用例
- 步骤：工具实现（`ToolDescriptor` 名称符合 `^[a-z][a-z0-9]*_[a-z][a-z0-9_]*$`）→ 更新快照 → 契约测试
- 验证：`/mcp/x/note` 列出全部读工具，`/mcp/x/note/write` 列写工具
- 完成记录：新增 9 个工具（读 note_list_books/note_tree/note_search/note_links，写 note_create_book/note_create_folder/note_create_doc/note_move/note_delete_node），全部 defaultGroup=false；NoteApi 补 tree/links/createFolder/createDoc/move/deleteNode 与 NoteNode/LinkRef/Links 记录；note 组工具数 4→13、全量 65→74（McpToolSnapshotTest 已改）；AgentToolBridgeIntegrationTest 的 ASSISTANT 只读清单补 4 个新读工具。测试：McpToolSnapshotTest 3、McpStartupSmokeTest 5、NoteToolsTest 6、AgentToolBridgeIntegrationTest 9 全绿。

### TASK-010 | pending | 前端知识库列表与工作台骨架

- 对应：REQ-013，AC-014
- 依赖：TASK-002/003/004
- 新建：`frontend/apps/app/src/pages/NoteBooksPage.tsx`、`pages/NoteWorkspacePage.tsx`、`components/notes/DocTree.tsx`、`components/notes/DocEditor.tsx`、`components/notes/OutlinePanel.tsx`
- 修改：`frontend/apps/app/src/notes-api.ts`（加 books/tree/doc/search 类型与方法）、`router.tsx`（`/notes`→NoteBooksPage、`/notes/:bookId`→NoteWorkspacePage）
- 测试：`playwright test e2e/notes.spec.ts`（建库→建树→编辑→大纲）
- 步骤：API 客户端 → 列表页 → 工作台（树+编辑器+大纲）→ 路由
- 验证：可建库、进入库建文档、编辑保存、大纲跳转

### TASK-011 | pending | 前端反链/搜索/导入导出/分享与公开页整库

- 对应：REQ-006/007/010/013，AC-006/007/010/014
- 依赖：TASK-005/006/007/010
- 新建：`components/notes/BacklinksPanel.tsx`、`SearchPanel.tsx`、`ImportExport.tsx`
- 修改：`notes-api.ts`（links/import/export/share）、`NoteWorkspacePage.tsx`、`SharedNotePage.tsx`（整库树浏览）
- 测试：`playwright test e2e/notes.spec.ts`（反链、搜索、导出、单篇/整库分享）
- 步骤：反链面板 → 搜索面板 → 导入导出入口 → 分享含整库 → 公开页分支
- 验证：写作双链出现反链；导出得到 `.md`；整库分享链接可浏览树

### TASK-012 | pending | e2e 收口与验收记录

- 对应：全部，AC-001..014
- 依赖：TASK-001..011
- 修改：`frontend/apps/app/e2e/notes.spec.ts`（补全链路）、必要时 `docs/architecture/` 结构图
- 测试：后端切片（`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` + 守卫三件）+ `playwright test e2e/notes.spec.ts`
- 步骤：跑全部相关用例 → 记录证据 → 更新架构图（若表结构图脚本生成则重生成）
- 验证：切片测试与 e2e 全绿，产出可核查的验收证据

## 完成条件

- 所有 AC-001..014 有可核查证据（自动化用例或 e2e）。
- 迁移无损、分享/举报/注销旧链路回归通过。
- 切片测试与 `e2e/notes.spec.ts` 绿；无新增未解决告警。
- 工作区改动按切片提交，`push` 仍需单独授权（除用户既有全局授权外）。
