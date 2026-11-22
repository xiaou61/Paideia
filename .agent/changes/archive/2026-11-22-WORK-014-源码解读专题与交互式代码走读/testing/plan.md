---
artifact: testing-plan
work_id: WORK-014
work: 源码解读专题与交互式代码走读
status: approved
workflow: full
created: 2026-10-17
updated: 2026-10-18
---

# 测试计划 · 源码解读专题与交互式代码走读

## 分层

| 层 | 覆盖什么 | 命令 |
| --- | --- | --- |
| 纯函数 | 五类块的解析、上限、permalink、判定与计分 | `pnpm --filter @paideia/ui test` |
| 组件 | 走读切步/预测/状态、地图联动、路径状态、三种练习、小测、降级 | 同上（`renderers/blocks.test.tsx`） |
| 存储 | 进度读写、损坏容错、重置、最近学习 | `pnpm --filter @paideia/knowledge test` |
| 预填桥 | 填输入框且**不发送** | `pnpm --filter @paideia/assistant test` |
| 端到端（学习者） | 种子条目上的走读、地图联动、练习、小测、进度恢复、问 AI、无 GitHub 请求、首页入口 | `pnpm --filter @paideia/app test:e2e -- source-reading.spec.ts` |
| 端到端（管理端） | 预览里五种块可渲染、无「问 AI」 | `pnpm --filter @paideia/admin test:e2e` |
| 构建 | 主包体积不因 Shiki 增加 | `pnpm --filter @paideia/app build` |

## 反向验证（证明测试会红）

1. 把 `Markdown` 的 `plugins` 在 `streaming` 分支也带上 `documentRenderers` → 应当有测试红（流式不启用块）。
2. 把 `ConversationPanel` 消费 pending 的地方改成同时 `send()` → 预填单测应红。
3. 把 `CodeTour` 的 `savedStep` 去掉 → 刷新恢复的用例应红。
4. 把 `judgeFill` 的逐空比较改成"长度相等即通过" → schema 判定用例应红。

## 不覆盖（如实登记）

- 真实浏览器里的**语法高亮颜色**：只断言构建产物里 Shiki 在独立 chunk，不逐 token 比对颜色。
- 地图的**视觉重叠**：只断言坐标计算与联动，不截图比对。
- 窄屏 360px 的布局：本轮未加 e2e（组件层用流式布局保证不横向溢出，但没有像素级断言）。
- 语音/读屏：只保证 `aria-label` 与文字化反馈，未做屏幕阅读器实测。
