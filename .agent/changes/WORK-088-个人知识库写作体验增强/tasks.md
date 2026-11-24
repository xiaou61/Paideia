---
artifact: tasks
work_id: WORK-088
work: 个人知识库写作体验增强
status: approved
mode: managed
created: 2026-11-24
updated: 2026-11-24
approved_by: xiaou61
approved_at: 2026-11-24 13:37:37 +0800
approver_role: CTO
---

# 个人知识库写作体验增强 任务

## 依据

- 需求：REQ-001..009，AC-001..008。
- 复用：`notes.updateDoc`（改标题/正文）、`notes.moveNode`（移动）。

## 任务

### TASK-001 | done | 自动保存

- 改动：`NoteWorkspacePage.tsx` 增防抖 useEffect（700ms），在 dirty 且已选中文档时自动调用 save；新增保存状态 `'idle'|'saving'|'saved'`；`DocEditor.tsx` 展示状态文案与保留手动保存。
- 验证：typecheck；e2e 编辑后不点保存重进仍在。
- 完成：`NoteWorkspacePage.tsx` 增 700ms 防抖 effect + `savingRef` 防重入；`DocEditor` 保存按钮改为仅 saving 时禁用。证据：typecheck/e2e 绿。

### TASK-002 | done | 目录树拖拽排序/移动

- 改动：`DocTree.tsx` 行级 draggable + 拖放指示；新增 prop `onMove(dragId,parentId,index)`；`NoteWorkspacePage.tsx` 实现按目标父级重排并逐条 `moveNode`。
- 验证：typecheck；e2e 可后续补；手动验证拖入/排序/回根。
- 完成：`DocTree.tsx` 行级 draggable + before/after/into 放置提示；工作区 `applyMove` 计算目标父级与序号，同级重编号落库，前端拦截拖入自身子孙。

### TASK-003 | done | 节点重命名

- 改动：`DocTree.tsx` 行 hover 重命名按钮 + 对话框输入；`NoteWorkspacePage.tsx` mutation 读 doc 后 `updateDoc` 改标题。
- 验证：typecheck；重命名目录后树/大纲标题变化。
- 完成：`DocTree` hover 铅笔按钮；工作区 rename 对话框 + mutation（read 后 updateDoc）。无新增后端端点。

### TASK-004 | done | 图片粘贴/拖入上传

- 改动：新增 `components/notes/image-upload.ts`（canvas 压缩→data URL）；`DocEditor.tsx` 传 `onUpload` 给 `MarkdownField`；`NoteWorkspacePage.tsx` 接上传回调。
- 验证：typecheck；粘贴图片出现 data URL 且预览显示。
- 完成：新增 `components/notes/image-upload.ts`（canvas 压到最长边 1600、base64≤180k）；`DocEditor` 透传 onUpload 给 `MarkdownField`。

### TASK-005 | done | 斜杠命令菜单

- 改动：`frontend/packages/knowledge/src/markdown-field.tsx` 增可选 `slashCommands?: boolean`，实现 `/` 菜单（上/下/Enter/Esc）与插入模板；`DocEditor.tsx` 开启该开关。
- 验证：typecheck；`/` 后选「一级标题」「表格」插入正确 Markdown。
- 完成：`markdown-field.tsx` 增可选 `slashCommands` 与 `SLASH_COMMANDS` 九项，支持 ↑/↓/Enter/Esc；`DocEditor` 启用。默认关闭，不影响其他消费者。

## 完成条件

- AC-001..008 全部满足；typecheck/build 绿；既有笔记 e2e 不回归。
