---
artifact: testing-plan
work_id: WORK-088
work: 个人知识库写作体验增强
status: approved
mode: managed
created: 2026-11-24
updated: 2026-11-24
---

# 测试计划

## 范围

纯前端：自动保存、目录树拖拽排序/移动、节点重命名、图片粘贴/拖入（data URL）、斜杠命令菜单。复用后端已有 `updateDoc` / `moveNode`，不新增接口。

## 验证层级

1. 类型检查：`cd frontend && pnpm --filter @paideia/app typecheck`。
2. 生产构建：`cd frontend && pnpm --filter @paideia/app build`（含懒加载守卫与体积门槛）。
3. 端到端回归：`E2E_CHANNEL=chrome PAIDEIA_BACKEND_PORT=8082 PAIDEIA_APP_PORT=5184 scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/notes-kb.spec.ts e2e/notes.spec.ts`。
4. 交互人工核对（浏览器）：拖拽移入/排序/回根、重命名、粘贴 PNG、`/` 菜单四项。

## 覆盖映射

| 验收 | 层级 |
| --- | --- |
| AC-001/002 自动保存 | 人工 + e2e 保存态 |
| AC-003/004 拖拽 | 人工 |
| AC-005 重命名 | 人工 |
| AC-006 图片 data URL | 人工 |
| AC-007 斜杠命令 | 人工 |
| AC-008 不回归 | typecheck/build/e2e |
