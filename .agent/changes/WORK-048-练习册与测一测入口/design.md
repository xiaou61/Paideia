---
artifact: design
work_id: WORK-048
work: 练习册与「测一测」入口
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-014, WORK-033]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 13:34:21 +0800
approver_role: CTO
---

# 设计 · 练习册与「测一测」入口

## 系统上下文

```
条目正文（markdown，含 ```quiz 围栏）
        │
        ▼
extractQuizzes(body)          ← 纯函数，行级状态机
        │
        ├── body'（已去掉 quiz 块）──► MarkdownBody（现有渲染器，一行不改）
        │
        └── Quiz[]（已解析）────────► PracticeEntry（入口卡）
                                            │ 点击
                                            ▼
                                      PracticePanel（对话框，复用 Quiz 的交互）
                                            │
                                            ▼
                                   recordAttempt + progress.quizzes（既有）
```

没有新表、没有新接口、没有迁移。

## 抽取

`frontend/packages/ui/src/markdown/practice.ts`（新文件，与 `schema.ts` 同级）：

```ts
export interface ExtractResult {
  /** 去掉所有 quiz 围栏之后的正文。 */
  body: string
  /** 按出现顺序解析出来的题目集；解析失败的块**保留在正文里**（宁可露出原文，也不静默吞掉）。 */
  quizzes: Quiz[]
}

export function extractQuizzes(markdown: string): ExtractResult
```

**行级状态机**，不用正则：逐行扫描，维护三个状态 `text | fence | quiz`。

- 遇到 ` ```quiz ` 开围栏：进入 `quiz` 态，开始收集行。
- 遇到其它 ` ``` ` 开围栏：进入 `fence` 态——**这个状态下不识别 quiz**，所以代码示例里的
  ```quiz 不会被误摘。
- `quiz` 态里遇到收围栏：`parseQuiz` 解析。成功则从正文里删掉这一段、加入 `quizzes`；
  失败则**原样保留**（这样作者能看见自己写错了，而不是消失）。

题的 `id` 沿用块自带的 `id`（`parseQuiz` 已读取）；缺 `id` 时用 `block-<序号>` 兜底，
保证同一个条目内 key 稳定。

## 入口卡

`frontend/packages/knowledge/src/practice-entry.tsx`（新）：

```
┌─────────────────────────────────────────────┐
│ ✎ 测一测 · 6 题                              │
│   读完可以自己验一下，不测也可以往下走。       │
│   上次：4 / 6            [开始测一测]         │
└─────────────────────────────────────────────┘
```

- `data-testid="practice-entry"`。
- 题数为 0 时**整块不渲染**。
- "上次分"来自 `progress.read()?.quizzes[id]`，没有就不显示那一行。
- 按钮文案随状态变：没做过 → `开始测一测`；做过 → `再做一次`。

## 面板

`frontend/packages/ui/src/markdown/practice-panel.tsx`（新），或放在 knowledge 包。

- 对话框形态：`role="dialog"` + `aria-modal="true"` + 标题「测一测（条目名）」。
- 内容：逐题渲染**复用现有 `Quiz` 组件的内部渲染**——把那部分逻辑抽成 `QuizQuestion` 组件，
  内联版与面板版共用，避免两套 UI 逐渐分叉。
- 底部：进度 `已答 x / N`、答完显示 `x / N 答对`、`重做`、`关闭`。
- Esc 关闭；关闭即丢弃本次作答（不持久化答到一半的状态）。
- 作答时的 `recordAttempt` 与 `progress.update({quizzes})` **原样调用**，服务端判定不变。

## 条目页接线

`KnowledgeEntryPage`：

```tsx
const { body, quizzes } = useMemo(() => extractQuizzes(entry.body), [entry.body])
...
<MarkdownBody markdown={body} ... />
{quizzes.length > 0 ? <PracticeEntry quizzes={quizzes} entryTitle={entry.title} /> : null}
```

`MarkdownBody` 本身**不改**：它拿到的已经是去掉 quiz 的正文，`quiz` 渲染器自然不会被触发。
管理端预览与笔记预览仍走原路径（内联显示），因为作者要能看到自己写的题。

## 内容门禁

`scripts/check_seed_fences.py` 旁边加一条检查（或复用既有脚本）：
扫描 `content/` 下所有 `quiz` 块，**`explain` 缺失**的输出为告警，列出文件与题号。

- 一期只告警不失败：165 个块不可能一次补齐。
- 门禁在**新增/修改**的文件上才报错（按 git diff 判断），避免一次改动把全仓拖红。

## 已知限制

- **线级练习册只有数字**：条目列表接口不返回正文，跨条目抽题要服务端读正文，一期不做。
- **答到一半关闭会丢**：不做本地持久化，重开即重做。
- **解析缺失时题目仍然可答**：只是没有解释。门禁只保证新内容带上解析。
- **管理端预览仍是内联**：作者看到的是题块原样，与学习者看到的不同。这是有意的。
