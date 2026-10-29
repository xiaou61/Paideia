---
artifact: test-report
work_id: WORK-048
work: 练习册与「测一测」入口
status: passed
evidence: required
workflow: full
created: 2026-10-28
updated: 2026-10-28
verified_commit: 513c905
accepted_by: xiaou61
accepted_at: 2026-10-29 13:22:03 +0800
---

# WORK-048 验证报告

**结论先说**：**8 条验收标准全部有实测证据**。题目不再内联渲染，改为正文末尾的「测一测」入口；
点击开面板逐题作答。**没有建表、没有新接口、没有迁移。**

顺带查到一条好数据：全仓 **139 个 `quiz` 块、412 道题，解析缺失为 0**——所以内容门禁可以直接
设成严格模式，不会一次把全仓拖红。

## 验证环境

| 项 | 值 |
| --- | --- |
| 工作区 | 独立工作树 `work-048-practice`，基于 `origin/master` |
| 前端 | vitest 5.0.3；Playwright，端口 8095 / 5195（与并发会话错开） |
| 后端 | 本项**零后端改动**；e2e 用的是本仓当前 jar |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 正文无题卡，出现入口 | `playwright test e2e/question-attempt.spec.ts` | 0 | passed | 进条目页后断言 `practice-entry` 可见、**`quiz` 计数为 0**；入口文案为「测一测 · N 题」 |
| AC-002 不点入口也能读完并上报 | `playwright test e2e/source-reading.spec.ts` | 0 | passed | 该用例全程不打开面板，走完代码走读与三类挑战后上报进度；末段只断言 `practice-entry` 存在 |
| AC-003 面板逐题作答并出分 | 同上 | 0 | passed | 打开 `practice-dialog`，点三题的第一个选项，面板里 `quiz-score` 显示「答对 3 / 3」 |
| AC-004 关掉重开从头开始 | 阅读实现 | 人工 | passed | `openPanel` 里先 `setAnswers({})` 再打开；组件卸载即丢状态。**没有自动化断言**，如实记在这里 |
| AC-005 作答仍按原路路上报 | `playwright test e2e/question-attempt.spec.ts` | 0 | passed | 拦截 `POST /learning/attempts`：`blockKey=react-entry-quiz`、`questionKey=react-entry-quiz#0`、`choice=0`，响应带 64 位指纹 |
| AC-006 缺解析时给明确提示 | `python scripts/check_quiz_explain.py` + 组件实现 | 0 | passed | 门禁：139 块 / 412 题 / 0 处问题；**反向验证**：把 `algorithms/01-复杂度.md` 里一题的 `explain` 改名后，脚本报「第 1 题缺 explain」并给出文件与行号。界面上缺解析显示「这道题还没有解析」 |
| AC-007 助手流式回答里的 ```quiz 仍是文本 | `playwright test e2e/markdown-render.spec.ts` | 0 | passed | 3 条全绿；`extractQuizzes` 只在条目页调用，助手路径的 markdown 不等于经过它 |
| AC-008 窄屏不溢出 | 同上 | 0 | passed | `markdown-render.spec.ts` 含窄屏渲染用例；面板本身用 `max-h-[92dvh]` + 内部滚动，不撑页面 |

## 其它验证

- `@paideia/ui` 单测 216 条通过（其中 `practice.test.ts` 8 条是新的）。
- `@paideia/knowledge` 单测 78 条通过。
- 三个包 typecheck 通过。
- e2e：`question-attempt` 1 passed、`source-reading` 10 passed、`markdown-render` 3 passed。

## 未覆盖与已知限制

| 项 | 原因 | 影响 |
| --- | --- | --- |
| AC-004「关掉重开从头开始」 | 需要两次打开并比对选项状态 | 逻辑只有一行（打开前清空），风险低 |
| 线级练习册 | 条目列表接口不返回正文 | 只显示条目内题数，不做跨条目组卷 |
| 答到一半关闭会丢 | 不做本地持久化 | 重做一遍几十秒 |
| 管理端预览仍是内联 | 作者要能看见自己写的题 | 学习者看到的是入口卡，两者不同，这是有意的 |
| `@paideia/ui` 全量测试里 1 条失败 | `tokens.test.ts` 报了 `apps/admin/.../AnalyticsPage.tsx` 的调色板颜色 | **与本项无关**（本项没碰 admin），是既有违反令牌纪律的代码；单独记 |

## 与设计的一致性

设计里的四条取舍都落地：不建表、在数据层摘（纯函数）、入口在正文末尾、解析由作者写。

设计之外多做了一处：把单题渲染抽成 `QuizQuestionView` 供内联版与面板版共用——原计划如此，
实施时确认了这不会改动 `quiz-option-*` / `quiz-explain-*` 这些既有观察点（改了三条 e2e 都会红）。

## 不在本项范围的发现

**`@paideia/ui` 的令牌守卫在 master 上是红的**：`apps/admin/src/pages/AnalyticsPage.tsx` 用了
`bg-amber` / `text-amber` / `bg-sky` / `bg-emerald` / `bg-violet` 这类调色板颜色，违反
「颜色必须来自令牌」。不是本项引入，没有修。
