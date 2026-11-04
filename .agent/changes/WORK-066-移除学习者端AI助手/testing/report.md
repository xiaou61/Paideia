---
artifact: test-report
work_id: WORK-066
work: 移除学习者端 AI 助手，入口改成专用流程
status: passed
evidence: required
created: 2026-11-03
updated: 2026-11-04
verified_commit: 2cecb29d
verified_at: 2026-11-04 15:26 +0800
---

# 验证报告

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 任意页面不再出现助手面板与 `ask()` 跳转 | `pnpm test` + ego：首页 → 账号菜单 → `/#/settings` | 0 | passed | 首页 `assistant-launcher`/`assistant-shell` 计数 0；设置页入口正常 |
| AC-002 条目页能生成/查看摘要，失败有可见原因 | 条目页 ego + 摘要组件测试 | manual | passed | 条目页出现「AI 摘要 / 生成摘要」；失败提示由摘要卡错误态显示 |
| AC-003 设置页能开关记忆、确认/拒绝候选、查看积分流水 | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e` + ego | 0 | passed | `/#/settings` 记忆与积分两块都渲染；余额 99803、流水在 |
| AC-004 原八处按钮不再存在，或替换流程可用 | `manual`（代码检索 + typecheck） | manual | passed | 删除 8 处 `ask()` 入口；SQL 出题改为直接用已有 API 按钮；源码块「问 AI」随 provider 断供消失 |
| AC-005 后端 memories / credits / summaries / conversations 接口与数据原样保留 | `manual`（后端未改核对） | manual | passed | 后端未改；接口与数据保留，本项只改前端入口 |
| 前端类型检查 | `pnpm typecheck` | 0 | passed | 0 错（删除包导出后的全部引用已清） |
| 前端单测 | `pnpm test` | 0 | passed | 全部包通过（assistant 包剩 3 个测试文件） |
| ego 覆盖闸门 | `node scripts/ego/check-map.mjs` | 0 | passed | 17 模块 / 56 用例；`/settings` 已登记 |

## 未覆盖与已知限制

- `client.ts` 仍保留聊天相关方法（`sendMessage` / `conversations` / `messages` / `createSubmission` 等）与其 `sse.ts` 依赖：后端接口未下线，先留着以免将来又要加回来；代价是包内有一块无 UI 调用方的死代码。
- 会话与记忆数据保留，但用户不再有「对话」入口；记忆候选改在 `/settings` 里确认。
- 桌面壳共用同一份前端产物，随之一起下线；未单独跑桌面 e2e。
