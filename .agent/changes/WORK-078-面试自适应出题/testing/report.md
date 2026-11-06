---
artifact: test-report
work_id: WORK-078
work: 面试自适应出题（按历史弱项与能力定难度）
status: passed
evidence: required
created: 2026-11-06
updated: 2026-11-06
---

# 验证报告

## 验证环境

- 日期：2026-11-06；改动：后端 `ReviewPrompt`/`InterviewPrompt`（提示词与版本号）、`InterviewReviewService.EvaluationView`（加 `conversationId`）、前端 `@paideia/assistant/adaptive.ts` + 单测 + `types.ts`、`InterviewPage.tsx`（自适应 / 历史详情 / 弱项复习）。
- Playwright 用系统 Chrome 打 `vite preview`，`/api/v1/**` 全 mock（含历史评估、简历、对话 SSE），不连后端、不花积分。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 后端提示词编译（固定四维 / review-v2 / interview-v2） | `mvn -o -B -q -pl paideia-ai -am compile -DskipTests` | 0 | passed | 编译通过 |
| AC-002 能力算法单测 | `pnpm --filter @paideia/assistant test` | 0 | passed | 3 files / 19 tests（adaptive 7 条：EWMA、弱项、难度档、旧名归一、空输入、上下文本） |
| AC-005 类型检查 | `pnpm --filter @paideia/app typecheck` | 0 | passed | tsc 无输出 |
| AC-005 令牌/对比度 | `pnpm --filter @paideia/ui exec vitest run src/tokens.test.ts src/theme/contrast.test.ts` | 0 | passed | 11 tests 全绿 |
| AC-005 生产构建 | `pnpm --filter @paideia/app build` | 0 | passed | 构建成功、懒加载守卫通过 |
| AC-003 自适应注入 pageContext | 抓 messages POST body | 0 | passed | `{"ctxHasAdaptive":true,"ctxHasFocus":true,"ctxHasResume":true,"ctxHasAdvice":true}` |
| AC-004 起始页显示重点/难度 | Playwright 截图 | 0 | passed | `focusVisible:true`；`.agent/prototypes/work078-shots/07-adaptive-start.png`（"本场重点：项目经验、技术深度 · 难度 中等 · 依据过往 2 场"） |
| AC-006 历史详情 + 对话回放 | Playwright 点历史条目 | 0 | passed | `{"detailVisible":true,"transcriptVisible":true}`；`08-history-list.png` / `09-history-detail.png` |
| AC-007 弱项 → 知识库复习 | Playwright mock 知识库条目 | 0 | passed | `{"suggestionVisible":true,"suggestionCount":2}`；截图中两行弱项 + 可点条目（Java 并发基础 / Spring Boot 项目实战） |

## 验收结果

| AC | 结论 | 证据 |
| --- | --- | --- |
| AC-001 | 满足 | `ReviewPrompt.DIMENSIONS` 固定四维、`review-v2`；`InterviewPrompt` `interview-v2` |
| AC-002 | 满足 | adaptive 7 条单测全绿（近期低分把能力从 3 拉到 2.6，验证衰减方向正确） |
| AC-003 | 满足 | 请求体 pageContext 含"本场自适应设置"、重点维度、简历、改进建议；气泡仍只"开始面试" |
| AC-004 | 满足 | 截图 07 展示重点/难度/场次 |
| AC-005 | 满足 | 编译 / 单测 / typecheck / 令牌 / 构建全绿 |
| AC-006 | 满足 | `EvaluationView` 带 `conversationId`；点历史弹报告详情 + `messages` 回放（截图 09） |
| AC-007 | 满足 | 评分卡"针对弱项去复习"列出弱项与知识库条目链接（截图 09） |

## 失败与未验证项

- **真实长跑自适应效果未验**：需多场真实面试产生历史才能观察"是否真的变难/变准"，属线上观测项，不做假数据断言。
- 旧评估（review-v1 时代）维度名不规范，靠关键词归一到四维；归一不上的旧维度不会进入"重点"判断（仍参与总分为主）。

## 部署结果（2026-11-06）

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 从提交干净构建并部署 | 独立 worktree 跑 `scripts/release.ps1` | 0 | passed | 完成：174.7 秒（health 29s） |
| 线上健康 | `curl /actuator/health` | 0 | passed | HTTP 200 |
| 线上版本 | `cat deploy.prom` | 0 | passed | `paideia_build_info{version="60da934"} 1` |
| 自适应界面已上线 | grep `/opt/paideia-app/web` | 0 | passed | `index-DUiqDYzt.js` 命中「本场重点」「本场自适应设置」 |

> 说明：从 `60da9340` 的独立 worktree 部署；主工作区当时有别的会话未提交改动，未被带上线。

## 剩余风险

- 能力模型是 EWMA 近似，不是真正 IRT；维度样本少时（1 场）权重退化合理但波动大。
- 自适应用的是"整场评分"，不含逐题响应，无法做题目级参数估计。
