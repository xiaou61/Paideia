---
artifact: test-report
work_id: WORK-067
work: 五处专用 AI 入口
status: passed
evidence: required
created: 2026-11-04
updated: 2026-11-04
verified_commit: 2cecb29d
verified_at: 2026-11-04 15:26 +0800
---

# 验证报告

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 五个端点匿名 401 / 非法入参 400 | `-Dtest=ApiWalkthroughTest`（全接口自查） | 0 | passed | 1/1（65 秒）：五个端点各以非法入参打到 400 |
| AC-002 前端五处入口可打开并渲染结果 | `playwright test review/progress/notes/knowledge` | 0 | passed | 25 通过 / 1 失败（笔记用例未先导航）；修复后 notes 7/7；review 5/5、progress 5/5、knowledge 全绿 |
| AC-002 图谱入口（canvas/WebGL）人工核对 | `manual`（点击自检） | manual | passed | 知识图谱「AI 用大白话讲讲」只有类型检查与人工可点自检；节点在 canvas/WebGL，e2e 点击脆弱 |
| AC-003 复习交卷结果新增 `answer` | `-Dtest=LearningReviewIntegrationTest` | 0 | passed | 5/5 |
| AC-004 非 JSON / 条数越界按失败处理 | `mvn -pl paideia-ai -Dtest=QuickInsightCallerTest` | 0 | passed | 3/3：剥 ``` 围栏、条数越界拒绝、非 JSON 拒绝 |
| 前端类型检查 | `pnpm typecheck` | 0 | passed | 0 错（五处入口 + 客户端方法） |
| 线上端到端（ego，真实调用） | 学习记录「AI 下一步」、条目「AI 出一道题」 | manual | passed | 下一步返回三条且带数字；出题返回本条目相关 Spring 题与解析；截图 `F:/caches/tmp/ego-ai5-1.png`、`-2.png` |

## 未覆盖与已知限制

- 知识图谱的「AI 用大白话讲讲」没有 e2e（节点在 canvas/WebGL 里，点击脆弱）；只有类型检查与人工可点的自检。
- 五处结果都**不落库**：刷新即失，没有"再看一次"；这是本项的范围决定，不是缺陷。
- 真实模型输出的质量只能线上看；e2e 全部打桩。
