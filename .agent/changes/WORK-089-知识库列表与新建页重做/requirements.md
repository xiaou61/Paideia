---
artifact: requirements
work_id: WORK-089
work: 知识库列表与新建页重做
status: approved
mode: managed
mode_reason: 涉及 note_books 表新增可见性/配色/标签字段与公开分享语义，跨后端迁移、服务与前端两页，属管理型工作。
workflow: compact
depends_on: [WORK-087, WORK-088]
related_to: [WORK-076]
created: 2026-11-24
updated: 2026-11-24
approved_by: xiaou61
approved_at: 2026-11-24 13:53:01 +0800
approver_role: CTO
---

# 知识库列表与新建页重做 需求

## 背景与动机

用户 2026-11-24 看过线上「我的知识库」页后指出：设计过于简约；「新建知识库」只有一个行内输入框，应改成完整页面；列表必须有搜索框；建库表单要能设置「是否公开」等字段（m01439/m01441）。

现状：`frontend/apps/app/src/pages/NoteBooksPage.tsx` 仅标题 + 行内输入框 + 单个按钮 + 平铺卡片；`note_books` 只有 name/description/sort_order，没有可见性、配色或标签。

## 目标

1. 列表页重做：顶部标题与统计、搜索框（按名称/描述/标签过滤）、排序（最近更新/名称/创建时间）、更丰富的卡片（配色、可见性徽标、描述、标签、文档数、更新时间）、空态与无结果态。
2. 新建知识库改为独立页面 `/notes/new-book`：名称、描述、可见范围（私有/公开）、主题配色、标签；提交后进入该库工作台。
3. 后端 `note_books` 增加 `visibility`、`accent_color`、`tags` 字段，列表返回文档数。
4. 「公开」语义（用户选定）：公开=整库分享链接永久有效，仅持链接者可只读浏览，不出现在公共列表；私有库分享仍 30 天过期。

## 非目标

- 不做「公共知识库」发现页，不做跨用户浏览列表。
- 不做封面图片上传、不做块级编辑器。
- 不改分享页面渲染结构（仍复用 SharedView）。

## 用户决定

1. 「是否公开」=整库分享链接永久有效，仅持链接者可只读浏览，不出现在公共列表（推荐项，m01443）。
2. 新建表单除名称/描述/是否公开外，增加**主题色/卡片配色**与**标签**（m01443）。

## 实现选择

1. `note_books` 增列：`visibility VARCHAR(16) NOT NULL DEFAULT 'private'`、`accent_color VARCHAR(16) NULL`、`tags VARCHAR(500) NULL`（逗号分隔，服务端去重归一）；列表查询用子查询带 `document_count`。
2. 服务端校验 visibility ∈ {private, public}，accentColor 为 `#RRGGBB` 或空，标签最多 10 个、每个 ≤20 字符。
3. `ShareService.shareBook`：公开库生成 `expires_at = 9999-12-31 23:59:59`，私有库维持 30 天。
4. 前端配色使用预设色板（单选），卡片头部按 accentColor 渲染渐变；标签在表单内回车/逗号生成胶囊。
5. 新建页走 `/notes/new-book` 静态路由，置于 `/notes/:bookId` 之前。

## 待确认问题

- 无阻断项。

## 已确认事实

- `backend/paideia-note/src/main/java/io/github/xiaou61/note/internal/book/{NoteBook,NoteBookMapper,NoteBookService}.java`、`internal/web/NoteBookController.java` 为库管理与树入口。
- 迁移目录 `backend/paideia-app/src/main/resources/db/migration/`，新迁移用 14 位时间戳；checksums 登记在 `backend/paideia-app/src/test/resources/migration-checksums.txt`。
- `frontend/apps/app/src/notes-api.ts` 已有 listBooks/createBook/updateBook/removeBook；`@paideia/ui` 提供 Select/Label/Switch/Badge/CardDescription/DropdownMenu 等。

## 验收标准

- AC-001 列表页有搜索框，输入关键字可按名称/描述/标签过滤；无结果显示空态文案。
- AC-002 列表页有排序（最近更新/名称/创建时间），排序生效。
- AC-003 卡片展示配色、可见性徽标、描述、标签、文档数、更新时间；删除仍有二次确认。
- AC-004 点击「新建知识库」进入独立页面，可填名称/描述/可见范围/配色/标签；名称为空时不能提交。
- AC-005 创建后跳转到该库工作台，列表能看到新库及其标签/配色/文档数 0。
- AC-006 私有库分享链接 30 天过期；公开库分享链接永久有效（expires_at 为 9999 年）。
- AC-007 旧接口兼容：不传新字段时默认 private、无配色、无标签；现有笔记/树/分享测试不回归。
