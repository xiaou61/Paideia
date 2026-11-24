---
artifact: tasks
work_id: WORK-089
work: 知识库列表与新建页重做
status: approved
mode: managed
created: 2026-11-24
updated: 2026-11-24
approved_by: xiaou61
approved_at: 2026-11-24 13:53:01 +0800
approver_role: CTO
---

# 知识库列表与新建页重做 任务

## 依据

- 需求：AC-001..007；用户决定见 requirements.md。

## 任务

### TASK-001 | done | 后端 note_books 可见性/配色/标签/文档数 + 公开分享永久有效

- 新建迁移 `V20261124160000__note_books_visibility_color_tags.sql`：加 visibility/accent_color/tags 三列。
- 改 `NoteBook.java`、`NoteBookMapper.java`（insert/update/select 含新列，列表带 document_count 子查询）、`NoteBookService.java`（BookView 增字段，create/update 校验与归一，tags 解析）、`NoteBookController.java`（SaveRequest 增字段）。
- 改 `ShareService.shareBook`：公开库永久过期（9999-12-31）。
- 完成：迁移 checksum 已登记；`NoteBookShareIntegrationTest` 新增公开库永久有效用例；后端 29 条相关测试绿。

### TASK-002 | done | 前端 API 与新建知识库独立页

- 扩 `notes-api.ts`：BookView/BookInput 增 visibility/accentColor/tags/documentCount。
- 新增 `pages/NoteNewBookPage.tsx`（名称/描述/可见范围/配色/标签；提交后跳 `/notes/{id}`）。
- `router.tsx` 加 `/notes/new-book`。
- 完成：typecheck/build 绿。

### TASK-003 | done | 知识库列表页重做

- 重写 `pages/NoteBooksPage.tsx`：统计头、搜索框、排序 Select、丰富卡片（配色条/徽标/标签/文档数/更新时间）、空态与无结果态、删除确认；「新建知识库」跳 `/notes/new-book`；保留「旧版全部笔记」。
- 完成：typecheck/build 绿；列表对旧后端字段做空值兜底。

### TASK-004 | done | e2e 与验证

- 更新 `frontend/apps/app/e2e/notes-kb.spec.ts` 的 createBook helper：从列表页跳新建页填表提交。
- 完成：`e2e/notes-kb.spec.ts` + `e2e/notes.spec.ts` 共 9 条绿。

### TASK-005 | done | 工作台视觉重做：铺满、去导入导出、默认预览、纯净预览

- `AppShell` 新增 `full` 档（`max-w-none`），工作台改用铺满布局。
- 工作台移除「导入 / 导出」卡片；左右侧栏在 `lg` 以上吸顶、独立滚动。
- `DocEditor` 默认打开「预览」标签（`MarkdownField` 新增 `defaultTab`）；新增「纯净预览」按钮，一键收起目录与侧栏只看正文。
- 完成：typecheck/build 绿；`e2e/notes-kb.spec.ts` + `e2e/notes.spec.ts` 9 条绿。
