---
artifact: tasks
work_id: WORK-048
work: 练习册与「测一测」入口
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-014, WORK-033]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 13:34:21 +0800
approver_role: CTO
---

# 任务 · 练习册与「测一测」入口

**排序原则**：先让"抽取"这个纯函数成立（S1），再让它进页面（S2），最后才是面板交互（S3）。
抽取错了，后面全错；抽取正确但面板没做好，至少阅读流是干净的。

## 任务状态

| 任务 | 名称 | 阶段 | 状态 |
| --- | --- | --- | --- |
| T-01 | `extractQuizzes` 纯函数与单测 | S1 | done |
| T-02 | 条目页入口卡 | S1 | done |
| T-03 | `QuizQuestion` 拆分与面板 | S2 | done |
| T-04 | 解析缺失的提示与内容门禁 | S3 | done |
| T-05 | e2e 与验收报告 | S4 | done |

## S1 · 抽取与入口

### T-01 | done | `extractQuizzes` 纯函数与单测

- 文件：`frontend/packages/ui/src/markdown/practice.ts`（新建）、`practice.test.ts`（新建）。
- 内容：行级状态机，三态 `text | fence | quiz`；解析成功才从正文里删，失败原样保留。
- 验收：
  - 普通正文里的 `quiz` 块被摘出，正文与题目数量对得上。
  - **代码块里的 ```quiz 不被误摘**（专门用例）。
  - 非法 JSON 的 `quiz` 块留在正文里，且不计入题目数。
  - 没有 `id` 的块得到稳定的兜底 key。
- 评审焦点：不能用正则；`fence` 态必须能正确闭合，否则一个未闭合的 ``` 会把后面整篇吞掉。

### T-02 | done | 条目页入口卡

- 文件：`frontend/packages/knowledge/src/practice-entry.tsx`（新建）、`KnowledgeEntryPage.tsx`。
- 内容：页面用 `extractQuizzes` 的 `body` 渲染正文；末尾挂入口卡（题数 + 上次成绩 + 按钮）。
- 验收：
  - 正文里看不到题卡（AC-001），入口显示 `测一测 · N 题`。
  - 不点入口也能读完并上报进度（AC-002）。
  - 题目为 0 时入口不渲染。

## S2 · 面板

### T-03 | done | `QuizQuestion` 拆分与面板

- 文件：`frontend/packages/ui/src/markdown/renderers/quiz.tsx`（抽子组件）、`practice-panel.tsx`（新建）。
- 内容：把"单题渲染 + 即时反馈"抽成 `QuizQuestion`；内联 `Quiz` 与面板共用它。
  面板是对话框：Esc 关闭、焦点陷阱、底部进度与重做。
- 验收：
  - 能逐题作答、答完显示 `x / N`（AC-003）。
  - Esc 关闭；关闭再打开从头开始（AC-004）。
  - 作答上报与本地进度写入与原来一致（AC-005）。
- 评审焦点：内联版的现有 e2e 不能因抽子组件而变红——`quiz-option-*` / `quiz-explain-*` 这些 testid 必须保持。

## S3 · 解析

### T-04 | done | 解析缺失提示与内容门禁

- 文件：`quiz.tsx`（提示文案）、`scripts/check_seed_fences.py`（新增一条检查）。
- 内容：答完但无 `explain` 时显示"这道题还没有解析"；脚本对**新增/修改**的 `quiz` 块报缺解析。
- 验收：缺解析的题不出现空白区（AC-006）；脚本对故意删掉 explain 的块返回非零。

## S4 · 验证

### T-05 | done | e2e 与验收报告

- 文件：`frontend/apps/app/e2e/markdown-render.spec.ts`（或新建 `practice.spec.ts`）、`testing/report.md`。
- 验收：AC-001..AC-008 逐条有命令与结果；助手流式回答里的 ```quiz 仍是文本（AC-007）。

## 未覆盖（明确不做）

- 线级练习册的跨条目组卷。
- AI 生成解析。
- 题目编辑界面。
- 答到一半的持久化。
