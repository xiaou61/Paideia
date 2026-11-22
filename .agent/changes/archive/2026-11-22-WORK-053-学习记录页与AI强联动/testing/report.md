---
artifact: test-report
work_id: WORK-053
work: 学习记录页与 AI 强联动（试点）
status: passed
evidence: required
workflow: full
created: 2026-11-02
updated: 2026-11-02
verified_commit: 2cecb29d
accepted_by: xiaou61
accepted_at: 2026-11-02 09:27:45 +0800
---

# WORK-053 验证报告

**结论**：学习记录页四块 + 计划落库 + 五处 AI 入口统一到对话，12/12 任务完成。
阶段一的四块与三个入口由既有 e2e 覆盖；阶段二（TASK-009）新增表与端点，e2e 验到"添加→刷新仍在→勾选→刷新仍完成→删除"；
TASK-010~012 是 UI 迁移，验到 typecheck 与包级测试，**没有逐页 e2e**。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 页面/导航/四块与空态 | e2e `progress.spec.ts`「四块都渲染」 | 0 | passed | 五个 `progress-*` 容器都可见，且没有"加载中"残留 |
| AC-001 导航入口 | e2e `progress.spec.ts`「导航里有入口」 | 0 | passed | `learner-nav-progress` → `/#/progress`，H1「学习记录」 |
| AC-002 数字与 /learn、/review 一致 | `manual`（历史代码核对） | manual | passed | 四块与两页共用同一组接口；原报告未写实时比数 e2e |
| AC-003 点入口预填不自动发送 | e2e `progress.spec.ts`「点问问打开助手并预填」 | 0 | passed | 输入框有内容、消息列表为空（没发出去） |
| AC-004 上行快照到服务端且不含敏感字段 | `manual`（历史 `page-context.test.ts` 核对） | manual | passed | 历史测试覆盖快照序列化、截断与无邮箱/令牌字段 |
| AC-007 只浏览不产生模型调用 | `manual`（历史代码核对） | manual | passed | 原报告记录的推荐理由自动调用已删除；页面浏览不发起对话请求 |
| AC-005 计划落库（采用后刷新仍在） | e2e `progress.spec.ts`「计划：添加→刷新→勾选→删除」 | 0 | passed | 刷新后仍在 = 服务端读回；勾选后刷新仍勾上；删除后消失 |
| AC-006 候选只由用户写入（解析层） | `@paideia/assistant` `plan.test.ts` | 0 | passed | 合法块取出并从正文剥离；超 3 条截断；坏 JSON/坏字段整块不采用且原文保留 |
| AC-006 候选卡片点按钮才回调 | `conversation-panel.test.tsx`「计划候选」 | 0 | passed | 卡片出现、原始 JSON 不在正文、点按钮前回调为空、点后回调一次 |
| AC-008 入口统一到对话（SQL 造数据集同源） | `@paideia/app` typecheck + `@paideia/assistant` 76 tests | 0 | passed | 精简 → 「去对话里精简」；SQL 出题 → `ask` + 保留「直接生成」降级；推荐理由 → 删自动调用、改「问助手为什么」；简历 → 「问助手差在哪」；训练营无 AI 按钮、复习早已用 `ask` |
| AC-009 连续生成不重复问已回答的问题 | `manual`（planner 提示词核对） | manual | passed | 提示词层约定上下文保留第一轮答案；原报告列为 prompt 级、无独立自动化观察点 |

## 测试与构建汇总

- 前端：`@paideia/assistant` **76 passed**（plan 4 条 + 面板接线 1 条）；`@paideia/app` typecheck 通过。
- e2e：`progress.spec.ts` 4 条通过（独占库自动删）。
- 后端：`LearningPlanService` 经 e2e 覆盖（建表迁移 + 4 个端点）；未另写切片测试。

## 未覆盖与已知限制

- **AC-002（数字与 /learn、/review 一致）**：四块与那两页取的是同一组接口，但没有写"同一时刻两边比数"的 e2e。
- **AC-004（上行快照到服务端）**：`page-context.test.ts` 覆盖序列化（含四块、按上限截断、不含敏感字段），
  没有在 e2e 里断言请求体。
- **AC-007（只浏览不产生模型调用）**：TASK-012 删掉了推荐理由的自动调用，但没写"网络里没有对话请求"的断言。
- **AC-009（连续两次生成不重复问）**：提示词层约定，没有可自动化的观察点。

- TASK-010~012 的三处按钮只验到 typecheck 与包级测试，**没有逐页 e2e**；线上未点过。
- 推荐理由不再自动润色：页面回到规则句，用户要解释得自己点（这是 REQ-009 的取舍，观感有变化）。
- 简历提案仍是结构化流程（表 + 采纳/驳回），没有改成对话——改它会把整条链路拆掉，只加了只读的「问助手」。
- 计划项的日期用浏览器本地时间；跨时区旅行时"今天"会跟着设备走。

## 反向验证

- 「点按钮前回调为空」是正向断言（不是"删掉写入后变红"）：面板测试先断言 `adopted` 为空，再点按钮断言只回调一次。
- 计划 e2e 的"刷新仍在"是新开一次请求，不是前端状态；删除后断言 `toHaveCount(0)`。
