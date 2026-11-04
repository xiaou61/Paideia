---
artifact: design
work_id: WORK-067
work: 五处专用 AI 入口
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 09:10:00 +0800
approver_role: owner
---

# 设计

## 后端

- `QuickInsightCaller extends SingleShotModelCaller`：五个方法，各自一段 system 提示 + 结果形状。
  两条纯文本（错题讲解、概念解释），三条 JSON（下一步、要点、出题），JSON 解析剥 ``` 围栏、
  形状不符抛 `INTERNAL`（控制器退款）。
- `QuickInsightController`（`/api/v1/ai`）：五个 POST；入参长度/下标校验；`billed()` 统一
  「取平台凭据 → 预留 → 调用 → 按用量结算 / 失败退款」。
- `LearningReviewService.ResultView` 增加 `answer`（正确项下标）：交卷后才下发，供讲解与回看。

## 前端

| 页面 | 入口 | 渲染 |
| --- | --- | --- |
| `ReviewPage` | 每道错题的「AI 讲讲这道错题」 | 卡片内一段文字 |
| `ProgressPage` | AI 下一步卡片里的按钮 | 有序三条 |
| `ConceptDetailPanel` | 「AI 用大白话讲讲」 | 面板内一段文字 |
| `NotesPage` | 编辑器头部「AI 提炼要点」 | 正文上方要点卡 |
| `KnowledgeEntryPage` | 摘要旁的「AI 出一道题」 | 弹窗：题干 + 四选项 + 解析 |

## 关键取舍

- **不建表存结果**：存了就要定"过期、更新、隐私"，而它们都是可再看一次的派生文本。
- **不自动调用**：五个动作都花积分，进页面就调等于替用户花钱。
- **复习的正确答案下标由服务端下发**：客户端原本拿不到答案，讲解就没有"正确项"可讲。
