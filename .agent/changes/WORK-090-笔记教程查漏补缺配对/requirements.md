---
artifact: requirements
work_id: WORK-090
work: 笔记教程查漏补缺配对
status: approved
mode: managed
mode_reason: 涉及 notes 表新增两列与索引、服务校验、MCP 工具参数、前端目录树与新建流程，跨后端迁移/服务/工具/前端，属管理型工作。
workflow: compact
depends_on: [WORK-087]
created: 2026-11-24
updated: 2026-11-24
approved_by: xiaou61
approved_at: 2026-11-24 15:33:24 +0800
approver_role: CTO
---

# 笔记教程/查漏补缺配对 需求

## 背景与动机

每个学习单元由两篇相辅相成的文档组成：教程（tutorial，完整知识正文）与查漏补缺（gap_fill，针对测验/交流暴露的错误逐条讲「错在哪→为什么→正确理解→再练一题」，可反复追加）。需要对文档增加两个可选属性并投入使用，查漏补缺由外部 AI 通过 MCP 写入。

## 目标

1. notes 表新增 `doc_type VARCHAR(16) NULL`（tutorial/gap_fill/普通）与 `pair_key VARCHAR(64) NULL`（同一知识库内唯一标识学习单元），并加 `(user_id, pair_key)` 索引。
2. 后端 Note/NoteMapper/NoteService/NoteController 支持并校验两字段；`NoteView`/`NoteNode`/`tree` 返回；folder 不允许带这两个值。
3. 包根契约 `NoteApi.createDoc` 增加带 `docType,pairKey` 的重载（旧 5 参保留）；`NoteApiImplementation` 同步。
4. MCP `note_create_doc` 增可选参数 `doc_type`/`pair_key`；`note_tree` 输出带两者。
5. 前端目录树同 pairKey 两篇成组（徽章），教程缺查漏补缺时一键补建；点任一文档可跳配对；notes-api 增字段与参数。

## 非目标

- 不做测验系统，不做 AI 自动生成查漏补缺。
- 不重构现有树/编辑器；`docType`/`pairKey` 为空的旧文档行为完全不变。
- 不做独立可折叠分组块（同 pairKey 相邻显示 + 徽章 + 一键补建即可）。

## 用户决定

1. 更新语义：`docType`/`pairKey` 传 null 时保留原值（兼容只传标题正文的重命名/保存），不支持通过接口清空。
2. 成组呈现：同 pairKey 两篇相邻显示、各带徽章，教程缺配对时紧跟一行「＋新建查漏补缺」。

## 已确认事实

- 现行 notes 表无 doc_type/pair_key；`NoteService.createNode/updateDoc` 为文档建改入口；`NoteApi.createDoc` 与 MCP `note_create_doc` 是外部写入通道。
- 目录（folder）不应携带文档类型或配对标识。

## 实现选择

- 两列均可空，旧文档保持 null，行为不变；索引 `(user_id, pair_key)`。
- `updateDoc` 传 null 视为「不修改」，避免只改标题时把配对清掉。
- 前端不做可折叠分组，仅相邻显示 + 徽章 + 一键补建，降低复杂度。

## 待确认问题

- 无阻断项。

## 关联

- WORK-087 建立知识库/目录/文档树、双链、MCP 工具面。
- 相关代码：`backend/paideia-note`、`frontend/apps/app/src/components/notes/DocTree.tsx`、`NoteWorkspacePage.tsx`、`notes-api.ts`。

## 验收标准

- AC-001 POST `/api/v1/notes`（bookId+parentId+kind=doc+docType=tutorial+pairKey=A1）建教程，同 pairKey 建 gap_fill；`GET /api/v1/note-books/{id}/tree` 返回两篇 docType/pairKey。
- AC-002 docType 为空的旧文档，树与页面表现完全一致。
- AC-003 前端树里同 pairKey 两篇成组带徽章，能一键新建缺失的查漏补缺。
- AC-004 MCP `note_create_doc` 传 doc_type/pair_key 能落库，`note_tree` 能读回。
- AC-005 相关测试全绿（后端集成与 NoteToolsTest、迁移守卫、前端 e2e）。
