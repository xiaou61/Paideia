---
artifact: tasks
work_id: WORK-090
work: 笔记教程查漏补缺配对
status: approved
mode: managed
workflow: compact
created: 2026-11-24
updated: 2026-11-24
approved_by: xiaou61
approved_at: 2026-11-24 15:33:24 +0800
approver_role: CTO
---

# 笔记教程/查漏补缺配对 任务

## 依据

requirements.md（WORK-090）。最小改动、旧文档行为不变。

## 任务

### TASK-001 | done | 数据库迁移

- 对应：AC-001/AC-002
- 新建 `backend/paideia-app/src/main/resources/db/migration/V20261124153000__note_doc_type_pair.sql`（加 doc_type/pair_key 与 idx_note_pair）；登记 checksum。
- 验证：MigrationVersionFormatTest / MigrationChecksumTest / MigrationSeedOverlapTest 绿。

### TASK-002 | done | 后端实体/服务/接口

- 对应：AC-001/AC-002/AC-004
- 改 `Note.java`、`NoteMapper.java`、`NoteService.java`、`NoteController.java`、`NoteApi.java`、`NoteApiImplementation.java`、`internal/tools/NoteTools.java`。
- 验证：`NoteTreeIntegrationTest`（配对往返、目录/非法值 400）、`NoteIntegrationTest`（旧文档 null）、`NoteToolsTest`（透传与 tree 输出）、`ApiContractTest`。

### TASK-003 | done | 前端树/新建/跳转

- 对应：AC-003
- 改 `frontend/apps/app/src/notes-api.ts`、`components/notes/DocTree.tsx`、`components/notes/DocEditor.tsx`、`pages/NoteWorkspacePage.tsx`。
- 验证：`pnpm --filter @paideia/app typecheck`、`build`；`e2e/notes-kb.spec.ts` 与 `e2e/notes.spec.ts`。

### TASK-004 | done | 收口

- 对应：AC-005
- 跑后端守卫与集成子集、前端 e2e；写 testing/report.md；提交并部署。
