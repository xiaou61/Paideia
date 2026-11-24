---
id: WORK-091
title: 笔记分类与难度评级
mode: managed
workflow: compact
mode_reason: 单模块内的可选字段全链路改动，边界清楚、风险低，采用 compact 流程即可。
status: approved
depends_on: [WORK-090]
created_at: 2026-11-24T17:05:00+08:00
approved_by: xiaou61
approved_at: 2026-11-24 17:04:00 +0800
approver_role: CTO
---

# 需求：笔记文档增加分类与难度

## 目标

给笔记文档增加可选的「分类」与「难度」元数据，使知识库能按主题与难度组织、筛选和展示，同时完全不破坏既有文档与调用。

## 背景

笔记文档已支持文档类型（doc_type：tutorial/gap_fill）与学习单元标识（pair_key）。为便于按主题归类与按难度筛选，再增加两个**可选**字段，保持向后兼容（均可为 null）。

## 已确认事实

- `category`：分类标签，自由文本，单值，去空白后 ≤ 64 字符。
- `level`：难度评级，枚举，只能是 `入门` / `进阶` / `高级`，或为空。
- 两个字段均可为空；老数据与旧调用（不传）行为不变。

## 实现选择

- 迁移在 `notes` 表加 `category VARCHAR(64) NULL`、`level VARCHAR(16) NULL`，只建结构，不插业务数据。
- 后端在实体、Mapper、Service、Controller、NoteApi 全链路透传并校验；`NoteService.createNode/updateDoc` 新增带 category/level 的重载，旧重载保留并委托（null 表示保留原值）。
- MCP `note_create_doc` 增加可选参数 `category`/`level`；`note_tree` 输出追加 `category=`/`level=`。
- 前端文档树显示分类/难度小标签、支持按分类筛选；新建/编辑表单可填写分类与难度。

## 用户决定

- 字段名定为 `category`（单值自由文本）与 `level`（枚举），`level` 取值固定为 `入门 / 进阶 / 高级`。
- 两字段都可为空；不引入多分类与独立分类管理页。

## 非目标

- 不做多分类（单值标签）、不做分类的独立管理页面、不做难度自动推断。

## 验收标准

- [ ] AC-001 迁移新增两列，老数据为空且现有功能不受影响。
- [ ] AC-002 MCP `note_create_doc` 传 category/level 成功，`note_tree` 能读回。
- [ ] AC-003 前端树显示分类/难度标签，并能按分类筛选。
- [ ] AC-004 后端切片测试与笔记 e2e 通过。

## 待确认问题

- 无阻断项。
