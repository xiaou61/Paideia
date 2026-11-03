---
artifact: test-report
work_id: WORK-066
work: 移除学习者端 AI 助手，入口改成专用流程
status: passed
evidence: required
created: 2026-11-03
updated: 2026-11-03
verified_commit: 0e65332e
---

# 验证报告

| 检查项 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| 前端类型检查 | `pnpm typecheck` | passed | 0 错（删除包导出后的全部引用都已清） |
| 前端单测 | `pnpm test` | passed | 全部包通过（assistant 包剩 3 个测试文件：memory-panel / summary-card / sse） |
| 学习者端 e2e | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e` | passed | 见下（含删掉助手相关用例后的结果） |
| ego 覆盖闸门 | `node scripts/ego/check-map.mjs` | passed | 17 模块 / 56 用例；`/settings` 已登记，条目的摘要区新增一条用例 |
| 后端 | 未改 | n/a | 接口与数据原样保留 |
| 线上端到端（ego） | 真实浏览器：首页 → 账号菜单 → `/#/settings` → 条目页 | passed | 首页 `assistant-launcher`/`assistant-shell` **计数 0**；设置页记忆与积分两块都渲染（余额 99803、流水在）；条目页出现「AI 摘要 / 生成摘要」；截图 `F:/caches/tmp/ego-no-assistant-1.png`、`-2.png` |

## 改动清单（代码核实）

- 删除：`AssistantShell` 挂载与 `AssistantProvider`（`main.tsx`）、`assistant-shell.tsx`、`conversation-panel.tsx`、
  `attach.tsx`、`context.tsx`、`page-context.ts`、`plan.ts`、`mention.ts`、`attachments.ts`、`citation*.ts`、
  `session-store.ts` 及各自测试；对应 e2e：`assistant.spec.ts`、`attachments.spec.ts`、移动端助手抽屉用例等。
- 新增：`/#/settings`（记忆 + 积分，复用 `MemoryPanel` / `CreditsPanel`），账号菜单入口「记忆与积分」；
  条目页正文末尾的「AI 摘要」区（按钮 + `SummaryCardView`）。
- 删除 8 处 `ask()` 入口：进度页 3、学习页 1、简历 JD 1、SQL 页 3（SQL 出题改为直接用已有 API 按钮）；
  源码块上的「问 AI」随 provider 断供自然消失。

## 未覆盖与已知限制

- `client.ts` 仍保留聊天相关方法（`sendMessage` / `conversations` / `messages` / `createSubmission` 等）与其
  `sse.ts` 依赖：后端接口未下线，先留着以免将来又要加回来；代价是包内有一块**无 UI 调用方**的死代码。
- 会话与记忆数据保留，但用户不再有「对话」入口；记忆候选改在 `/settings` 里确认。
- 桌面壳共用同一份前端产物，随之一起下线；未单独跑桌面 e2e。
