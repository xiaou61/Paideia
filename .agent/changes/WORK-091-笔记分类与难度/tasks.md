---
id: WORK-091
status: approved
approved_by: xiaou61
approved_at: 2026-11-24 17:04:00 +0800
approver_role: CTO
---

# 任务

## TASK-001 | done | 后端字段与迁移

- 对应：AC-001
- 新建：`backend/paideia-app/src/main/resources/db/migration/V20261124170000__add_note_category_level.sql`
- 修改：`Note.java`、`NoteMapper.java`、`NoteService.java`、`NoteController.java`、`NoteApi.java`、`NoteApiImplementation.java`
- 验证：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`

## TASK-002 | done | MCP 工具

- 对应：AC-002
- 修改：`internal/tools/NoteTools.java`、`NoteToolsTest.java`
- 验证：`NoteToolsTest` 通过（含 category/level 读回）

## TASK-003 | done | 前端展示与表单

- 对应：AC-003
- 修改：`frontend/apps/app/src/notes-api.ts`、`components/notes/DocTree.tsx`、`components/notes/DocEditor.tsx`、`pages/NoteWorkspacePage.tsx`
- 验证：`pnpm --filter @paideia/app typecheck`、`build`

## TASK-004 | done | 回归验证与交付

- 对应：AC-004
- 验证：`playwright test e2e/notes.spec.ts`（连同 notes-kb）
