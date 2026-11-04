---
artifact: test-report
work_id: WORK-067
work: 五处专用 AI 入口
status: passed
evidence: required
created: 2026-11-04
updated: 2026-11-04
verified_commit: 29adbeea
---

# 验证报告

| 检查项 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| 解析与形状校验 | `mvn -pl paideia-ai -Dtest=QuickInsightCallerTest` | passed | 3/3：剥 ``` 围栏、条数越界拒绝、非 JSON 拒绝 |
| 五个端点可达且校验可读 | `-Dtest=ApiWalkthroughTest`（全接口自查） | passed | 1/1（65 秒）：五个端点各以非法入参打到 400；`/sql-datasets/draft` 缺 topic 也补上了覆盖 |
| 复习交卷结果新增 answer | `-Dtest=LearningReviewIntegrationTest` | passed | 5/5 |
| 前端类型检查 | `pnpm typecheck` | passed | 0 错（五处入口 + 客户端方法） |
| 四页 e2e（打桩 AI） | `playwright test review/progress/notes/knowledge` | passed | 见下：错题讲解、下一步三条、笔记要点、条目出题 |

## 未覆盖与已知限制

- 知识图谱的「AI 用大白话讲讲」没有 e2e（节点在 canvas/WebGL 里，点击脆弱）；只有类型检查与人工可点的自检。
- 五处结果都**不落库**：刷新即失，没有"再看一次"；这是本项的范围决定，不是缺陷。
- 真实模型输出的质量只能线上看；e2e 全部打桩。
