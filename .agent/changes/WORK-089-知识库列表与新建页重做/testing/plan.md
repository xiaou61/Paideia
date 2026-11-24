---
artifact: test-plan
work_id: WORK-089
work: 知识库列表与新建页重做
status: approved
created: 2026-11-24
updated: 2026-11-24
---

# 测试计划

## 范围

- AC-001..007：列表搜索/排序/卡片、新建页表单与跳转、可见性与公开分享有效期、旧接口兼容。

## 检查项

1. 后端迁移守卫三件（命名/校验和/种子隔离）。
2. `NoteBookShareIntegrationTest`（含公开库永久分享、私有库 30 天、到期 404）。
3. `NoteTreeIntegrationTest` 与 `NoteIntegrationTest` 回归。
4. `ApiContractTest` 覆盖请求体新增字段不破坏契约。
5. 前端 `tsc` 类型检查与生产构建。
6. e2e：`notes-kb.spec.ts`（新建页建库、工作台、分享）+ `notes.spec.ts`（旧扁平页回归）。
