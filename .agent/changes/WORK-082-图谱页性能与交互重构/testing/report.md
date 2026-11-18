---
artifact: testing-report
work_id: WORK-082
work: 图谱页性能与交互重构
status: done
created: 2026-11-18
updated: 2026-11-18
---

# 图谱页性能与交互重构 — 验证报告

## 环境与方法

- 采集工具：Playwright（系统 Chrome）+ CDP，真登录 `e2e-learner`，真实线上数据（14 概念 / 12 边）。
- 指标：`openLatency`（导航到图谱可用）、`longtask`（>50ms 主线程长任务，PerformanceObserver）、帧间隔。
- 改造前：线上 `线上 `/#/graph``。
- 改造后：`pnpm --filter @paideia/app build` 产物经 `vite preview` 起在 `localhost:5173`（同源代理到线上后端，数据完全一致）。

## 性能：改造前 vs 改造后

| 指标 | 改造前（线上，sigma） | 改造后（生产构建，SVG） | 变化 |
| --- | --- | --- | --- |
| openLatency | ≈7164 ms | ≈1607 ms | ↓ 约 4.5× |
| 最大单次长任务 longtaskMax | 5974 ms | 127 ms | ↓ 约 47× |
| 长任务总计 longtaskTotal | 6142 ms | 240 ms | ↓ 约 26× |
| 长任务个数 | 3 | 3（均 ≤127ms） | 无 >250ms 冻结 |
| `<canvas>` 数 | ≥1（WebGL） | 0 | 纯 SVG |

结论：进入图谱页不再出现秒级冻结；AC-001 达成。

## 行为与可读性核对（生产构建）

脚本逐项断言，全部通过：

- `nodeCount=14`、`edgeCount=12`、`graph-fallback` 不存在（AC-002 前半、AC-003）。
- 点击节点 → `concept-detail` 可见、节点 `aria-pressed=true`（AC-004）。
- 点空白 → 详情消失（取消选中）。
- 缩放按钮改变 `viewBox`；复位可还原。
- 「重新布局」后节点数不变（重算并持久化）。
- 切「看 3D」出现 3D 画布，再切回 2D 正常。
- **无 WebGL（覆盖 `getContext('webgl')` 返回 null）**：2D 图谱仍渲染出 14 节点，`graph-fallback` 数量 0（AC-002 达成）。

截图证据（临时目录，不提交）：`.tmp-diag/baseline-2d.png`（改造前）、`.tmp-diag/after-prod-2d.png`、`.tmp-diag/after-prod-2d-selected.png`（选中态 + 右栏详情）。四档图例单行显示（AC-005）。

## 静态检查与产物

- `pnpm --filter @paideia/app typecheck` / `tsc -p tsconfig.json`：通过。
- `pnpm --filter @paideia/app build`：成功（9.21s）。
- 产物 `dist/assets`：无 `*sigma*` chunk（AC-007 达成）；`3d-force-graph` 仍 1.37MB 但保持懒加载，仅切 3D 时下载。

## 未覆盖 / 后续

- `graph.spec.ts` 未在本机跑完整 e2e 套件（需专用 `paideia_e2e` 库 + seed 账号与后端 jar；本机有并发会话共用库风险，按团队约定不并发跑）。已用与用例等价的行为脚本在生产构建上逐项验证；用例断言已按新 DOM 更新，待 CI/独立环境跑绿。
