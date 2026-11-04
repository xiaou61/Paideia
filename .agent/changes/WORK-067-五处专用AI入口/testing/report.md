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
| 四页 e2e（打桩 AI） | `playwright test review/progress/notes/knowledge` | passed | 25 通过 / 1 失败（我的笔记用例没先导航到列表页）；修复后 notes 7/7；review 5/5、progress 5/5、knowledge 全绿 |
| 线上端到端（ego，真实调用） | 学习记录「AI 下一步」、条目「AI 出一道题」 | passed | 下一步返回三条且带数字（"垃圾回收 0.28"等）；出题返回一条本条目相关的 Spring 题与解析；截图 `F:/caches/tmp/ego-ai5-1.png`、`-2.png` |

## 未覆盖与已知限制

- 知识图谱的「AI 用大白话讲讲」没有 e2e（节点在 canvas/WebGL 里，点击脆弱）；只有类型检查与人工可点的自检。
- 五处结果都**不落库**：刷新即失，没有"再看一次"；这是本项的范围决定，不是缺陷。
- 真实模型输出的质量只能线上看；e2e 全部打桩。
