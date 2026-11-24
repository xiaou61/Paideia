---
artifact: requirements
work_id: WORK-088
work: 个人知识库写作体验增强
status: approved
mode: managed
mode_reason: 纯前端增强（笔记工作台与共享 MarkdownField），复用 WORK-087 已有的知识库树、move、updateDoc 端点；不新增 schema、迁移、鉴权或公共接口，故管理型而非严格型。写入仍是 Markdown，不引入块级数据模型。
workflow: compact
depends_on: [WORK-087]
related_to: [WORK-072]
created: 2026-11-24
updated: 2026-11-24
approved_by: xiaou61
approved_at: 2026-11-24 13:37:37 +0800
approver_role: CTO
---

# 个人知识库写作体验增强 需求

## 背景与动机

用户 2026-11-24 在确认 WORK-087「个人知识库」已上线后问：「我写入的这个也可以是我自己可以写入…类似语雀那一套写入 我都想要 这个也有是吗？」，随后明确「差的全部补充一下 开始吧。」（m01252）。

WORK-087 已交付：多知识库、目录树、Markdown 编辑（工具栏+编辑/预览）、显式保存、大纲、双链反链、库内检索、单篇/整库导入导出与分享。缺的是「语雀那种随手写」的手感：不点保存也不丢、目录能拖、能改名、能贴图、能 `/` 唤起。

## 目标

让写作过程接近语雀：

1. **自动保存**：正文/标题改动后停顿约 0.7 秒自动保存，状态在「未保存 / 保存中 / 已保存」之间可见；保留手动保存按钮。
2. **目录拖拽排序与移动**：把文档/目录拖到目录上=移入该目录；拖到节点之间=同级排序；拖到空白=移到库根。
3. **重命名**：目录树节点可原地重命名（文档也可在顶栏改名，二选一均生效）。
4. **图片粘贴/拖入上传**：在正文里粘贴或拖入图片，压缩后以 Markdown 图片语法插入，预览与整库分享都能显示。
5. **斜杠命令**：在正文行首输入 `/` 弹出命令菜单（标题/列表/引用/代码/表格/分割线/待办），选择即插入对应 Markdown。

## 非目标

- 不做块级（block）数据模型、不做所见即所得富文本、不做协同编辑——正文仍是一份 Markdown。
- 不新增后端表/迁移/端点（图片以 data URL 内嵌正文；重命名复用现有 updateDoc）。
- 不改分享、检索、导入导出语义。

## 用户决定

1. 用户要「差的全部补充一下」（m01252），即上表五项全做：自动保存、目录拖拽、重命名、图片上传、斜杠菜单。

## 实现选择

1. 自动保存用前端防抖，不引入后端草稿表；手动保存按钮保留。
2. 拖拽用原生 HTML5 DnD，排序落库靠已有 `moveNode`，同级重排前端重编号。
3. 重命名复用 `PUT /api/v1/notes/{id}`（updateDoc 对目录/文档均可改标题），不新增 rename 端点。
4. 图片以 canvas 压缩后的 data URL 内嵌正文：无附件后端、无鉴权问题，预览/导出/整库分享都自洽。
5. 斜杠菜单放在共享 `MarkdownField` 的可选开关后，默认关闭，避免影响知识库条目编辑等既有消费者；不做块级编辑器。

## 待确认问题

- 无阻断项。块级/所见即所得编辑器不在本次范围，若后续需要再单独立项。

## 已确认事实

- 笔记工作台：`frontend/apps/app/src/pages/NoteWorkspacePage.tsx`（显式 save、createNode/removeNode mutation）。
- 编辑器：`components/notes/DocEditor.tsx` 包装 `MarkdownField`（未传 onUpload）。
- 目录树：`components/notes/DocTree.tsx`（buildForest+递归 TreeRow，无拖拽/改名）。
- 共享编辑器：`frontend/packages/knowledge/src/markdown-field.tsx`（toolbar 7 项，onUpload 可选）。
- 后端已有：`PUT /api/v1/notes/{id}`（updateDoc，可改 title/body）、`PUT /api/v1/notes/{id}/move`（防环/跨库/超深）。

## 需求

- REQ-001 正文或标题变更后 700ms 内自动保存；保存失败显示错误且不清除 dirty。
- REQ-002 保存中不重复触发；切换文档前若有未保存改动仍提示。
- REQ-003 目录树支持 HTML5 拖拽：拖入目录作子节点、拖到同级间隙改顺序、拖到根区移到库根；拒绝拖到自身/子孙（后端已拦，前端给出提示）。
- REQ-004 排序变更后同级 sortOrder 重新编号落库，刷新后顺序稳定。
- REQ-005 目录/文档可重命名，标题去空白、空标题拒绝、超 200 字拒绝。
- REQ-006 粘贴/拖入图片：仅图片类型；超过约 2MB 或超尺寸时先等比压缩到最长边 1600px、JPEG 质量衰减；最终内嵌 data URL；非图片给出提示不插入。
- REQ-007 斜杠菜单在 `MarkdownField` 上以可选开关启用，默认关闭以免影响知识库条目编辑等既有消费者。
- REQ-008 斜杠菜单支持键盘上下选择与回车插入、Esc 关闭。
- REQ-009 不改既有 e2e 语义：`/notes/all` 旧扁平页与 `/notes/:bookId` 工作台均照常。

## 验收标准

- AC-001 输入正文停顿后无需点保存，重新进入文档内容仍在。
- AC-002 状态提示随自动保存从「未保存」变「保存中」再变「已保存」。
- AC-003 把文档 A 拖到目录 B 上，刷新后 A 在 B 下；把 A 拖到根区，刷新后 A 回到根。
- AC-004 同级拖动后顺序与拖放位置一致且刷新稳定。
- AC-005 重命名目录后树与大纲/分享标题同步。
- AC-006 粘贴一张 PNG，正文出现 `![...](data:image/...)`，预览显示图片。
- AC-007 输入 `/` 出现菜单，选「一级标题」插入 `# `，选「表格」插入表格骨架。
- AC-008 `pnpm --filter @paideia/app typecheck` 与 `build` 通过；`e2e/notes-kb.spec.ts` 与 `e2e/notes.spec.ts` 仍绿。

## 质量要求

- 组件保持可访问：菜单与拖拽均有 aria 标签/键盘可达。
- 不引入新的第三方依赖（拖拽用原生 HTML5 DnD，压缩用 canvas）。
- 不改共享 `MarkdownField` 默认行为。
