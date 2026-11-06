---
artifact: testing-plan
work_id: WORK-078
work: 面试自适应出题（按历史弱项与能力定难度）
status: done
created: 2026-11-06
updated: 2026-11-06
---

# 验证计划

| 目标 | 命令 / 手段 | 判据 |
| --- | --- | --- |
| 后端提示词编译 | `mvn -o -B -q -pl paideia-ai -am compile -DskipTests` | 退出 0 |
| 能力算法单测 | `pnpm --filter @paideia/assistant test` | 全绿（含 adaptive 7 条） |
| 类型/令牌/对比度 | app typecheck + ui tokens/contrast | 通过 |
| 生产构建 | `pnpm --filter @paideia/app build` | 成功 |
| 自适应注入 | Playwright mock，抓到 messages 请求体 | pageContext 含"本场自适应设置"、重点维度、简历、改进建议 |
| 起始页可见 | 截图 | "本场重点：… 难度… 依据过往 N 场" |
