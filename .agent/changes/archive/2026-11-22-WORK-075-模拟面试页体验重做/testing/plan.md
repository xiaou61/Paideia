---
artifact: testing-plan
work_id: WORK-075
work: 模拟面试页体验重做
status: done
created: 2026-11-06
updated: 2026-11-06
---

# 验证计划

| 目标 | 命令 / 手段 | 判据 |
| --- | --- | --- |
| 类型正确 | `pnpm --filter @paideia/app typecheck` | 退出 0 |
| 颜色只用令牌、对比度达标 | `pnpm --filter @paideia/ui test` | 21 文件 / 263 用例全绿（含 tokens、contrast） |
| 生产构建可出 | `pnpm --filter @paideia/app build` | 构建成功 |
| 起始页 / 对话页 / 评分页视觉 | Playwright 打 `vite preview`（mock `/api/v1/**`），截图三张 | 布局符合预期 |
| 不展示思考过程 | 渲染后 `getByText('思考过程').count()` | 0 |
| 既有 e2e 的失败态契约 | mock SSE 返回 error，断言 `interview-line-interviewer` 文本为空且 `没成功` 可见 | 成立 |

> 说明：真实 `identity.spec.ts` 面试用例会**真调上游并消耗积分**，本轮不跑；用同构 mock 覆盖其断言语义（成功/失败两条渲染路径）。
