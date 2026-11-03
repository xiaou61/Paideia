---
artifact: design
work_id: WORK-066
work: 移除学习者端 AI 助手，入口改成专用流程
status: approved
created: 2026-11-03
updated: 2026-11-03
---

# 设计（草案）

## 目标形态

- `main.tsx` 不再挂 `AssistantShell`；`@paideia/assistant` 的壳与面板从学习者端下线。
- 精简：条目页页头/正文末尾一个「生成摘要」按钮，结果用现有 `SummaryCardView` 渲染。
- 记忆：`/#/settings/memory`（开关、列表、候选确认）——复用面板里的 `MemoryPanel`。
- 积分：`/#/settings/credits`——复用 `CreditsPanel`。
- 八处 `ask()`：按裁决删除或替换；SQL 页保留已有专用接口（出题/生成数据集向导）。

## 影响面（代码核实）

| 位置 | 处理 |
| --- | --- |
| `apps/app/src/main.tsx` | 移除 `AssistantShell` / `AssistantProvider` |
| `packages/assistant/*` | 壳与对话面板下线（包内 API/纯函数保留给设置页复用） |
| `LearnPage` / `ProgressPage` / `ResumeOptimizePage` / `SqlPracticePage` | 删除 `ask()` 调用与相关按钮 |
| `packages/ui` 的 `AskButton`（code-tour / source-walk） | 删除或改为无操作 |
| e2e / 单测 | `assistant.spec.ts`、`attachments.spec.ts`、`conversation-panel.test.tsx` 等按范围删改 |

## 风险

- 记忆候选（WORK-050）目前只在面板里能确认；若不搬设置页，候选会永远积压。
- 会话与记忆数据保留，但用户失去入口；需在发布说明里写清"数据还在，入口移到哪"。
- 嵌入在桌面壳里的入口（若存在）要同步检查。
