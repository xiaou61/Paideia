---
artifact: testing-plan
work_id: WORK-077
work: 模拟面试接入简历（选择 / 上传）
status: done
created: 2026-11-06
updated: 2026-11-06
---

# 验证计划

| 目标 | 命令 / 手段 | 判据 |
| --- | --- | --- |
| 类型正确 | `pnpm --filter @paideia/app typecheck` | 退出 0 |
| 颜色令牌/对比度 | `pnpm --filter @paideia/ui exec vitest run src/tokens.test.ts src/theme/contrast.test.ts` | 全绿 |
| 生产构建 | `pnpm --filter @paideia/app build` | 成功 |
| 选已有简历 → 上下文 | Playwright mock `/api/v1/**`，点开始，抓 messages 请求体 | `pageContext` 含简历项目名；候选人气泡只显示"开始面试" |
| 本页上传 → 上下文 | Playwright `setInputFiles` + mock parse | 选项显示文件名，`pageContext` 含上传原文 |
| 起始页视觉 | 截图 | 默认简历预选、可切换、有上传入口 |
