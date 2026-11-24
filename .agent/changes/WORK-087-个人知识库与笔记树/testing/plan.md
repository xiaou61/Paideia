---
artifact: test-plan
work_id: WORK-087
work: 个人知识库与笔记树
status: ready
mode: strict
workflow: full
created: 2026-11-24
updated: 2026-11-24
---

# 个人知识库与笔记树测试计划

## 测试范围

本项把扁平的「我的笔记」升级成**多知识库 + 目录/文档树**，并在其上长出全文检索、大纲、双链反链、`.md` 导入导出、MCP 工具面和整库只读分享。验证按链路分四层，每层只证自己那一段：

- **数据与迁移层**：`notes` 加 `book_id/kind/parent_id/sort_order/deleted_at`、新建 `note_books`/`note_links`、`note_shares` 加 `target_type/target_id`；存量笔记回填默认库；`note_id` 保留（举报列表依赖）。迁移校验和与种子重叠守卫。
- **业务层**（`paideia-note`）：树 CRUD、移动/环检测、深度与节点数上限、软删连带子树、全文检索（ngram + LIKE 回退）、双链解析（`[[标题]]`/`[[id:N]]`）、导入导出（zip/md）、分享单篇+整库、注销级联。
- **接口与归属层**（`paideia-app`）：用户隔离、他人 404、匿名 401、API 契约（畸形体 400、访问级别声明）、MCP 工具快照与只读/写边界。
- **用户可见层**（`apps/app`）：知识库列表、工作台（树/编辑/大纲/反链/搜索/导入导出/整库分享）、公开分享页整库树浏览。

不在本计划内：跨用户协作编辑、版本历史、拖拽排序的鼠标命中细节、向量检索（需求非目标）。

## 环境与前置条件

- 后端测试一律经 `scripts/test-db.sh`（唯一入口），连远程 `paideia_e2e`，同一时刻只跑一轮。
- 命令在 `backend/` 下执行，脚本写 `../scripts/test-db.sh`。
- e2e 在仓库根执行：`scripts/test-db.sh pnpm --filter @paideia/app test:e2e <spec>`，自带后端与前端，端口由 `PAIDEIA_*_PORT` 控制；本机需 `E2E_CHANNEL=chrome`。

## 验收矩阵

| AC | 验证方式 | 具体用例/命令 |
| --- | --- | --- |
| AC-001 多知识库 | 集成 | `NoteTreeIntegrationTest#bookAndTreeRoundTrip`、`bookIsolationAndCascade` |
| AC-002 树增删改移 | 集成 | 同上 + `moveRejectsCycle`（自身/子孙 400、跨库 404、移根 200） |
| AC-003 软删连带子树 | 集成 | `bookAndTreeRoundTrip`（删目录后直读 404、树空） |
| AC-004 全文检索（中文） | 集成 | `NoteSearchIntegrationTest`（多字命中、单字 LIKE 回退、跨用户不命中、匿名 401） |
| AC-005 大纲 TOC | e2e | `notes-kb.spec.ts`（写入 `# 一级标题` 后大纲按钮可见） |
| AC-006 双链/反链 | 集成 | `NoteLinkIntegrationTest`（出链去重+悬空、入链、自引用清空、他人 404） |
| AC-007 导入导出 `.md` | 集成 | `NoteImportExportIntegrationTest`（单篇导出、整库 zip 往返、zip slip 400） |
| AC-008 MCP 读写工具 | 单测/集成 | `McpToolSnapshotTest`、`McpStartupSmokeTest`、`NoteToolsTest`、`AgentToolBridgeIntegrationTest` |
| AC-009 MCP 只读/写边界 | 集成 | `AgentToolBridgeIntegrationTest#toolsCannotWrite` |
| AC-010 分享单篇+整库 | 集成/e2e | `NoteBookShareIntegrationTest`、`notes.spec.ts` 分享用例、`notes-kb.spec.ts` 整库分享 |
| AC-011 举报下架回归 | 集成 | 既有举报用例（`NoteReportMapper` 仍按 `note_id` join）+ `NoteBookShareIntegrationTest` 撤回 |
| AC-012 注销级联 | 集成 | `AccountDeletionCascadeIntegrationTest`（含 note_books/note_links） |
| AC-013 归属与隔离 | 集成 | 各集成用例自带他人 404/匿名 401 断言 + `AuthorizationIsolationTest` |
| AC-014 前端完整链路 | e2e | `notes-kb.spec.ts`、`notes.spec.ts`（6 条扁平用例迁 `/notes/all`） |

## 退出条件

- 迁移守卫三件、API 契约/表面、归属隔离、笔记各集成类全绿。
- 两条 e2e 规格全绿；`typecheck` 与 `build` 绿。
- 架构图 `gen-arch-diagram --check` 与 `check-diagrams` 一致。
