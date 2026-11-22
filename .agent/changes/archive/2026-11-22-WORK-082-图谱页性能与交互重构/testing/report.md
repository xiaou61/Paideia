---
artifact: test-report
work_id: WORK-082
work: 图谱页性能与交互重构
status: passed
evidence: required
mode: managed
workflow: compact
created: 2026-11-18
updated: 2026-11-22
accepted_by: xiaou61
accepted_at: 2026-11-22 14:47:43 +0800
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

## 证据矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 无 >250ms 长任务、openLatency 明显下降 | CDP + PerformanceObserver 复测（同数据，改造前线上 / 改造后生产构建） | 0 | passed | 上表：openLatency 7164→1607 ms、longtaskMax 5974→127 ms、longtaskTotal 6142→240 ms |
| AC-002 WebGL 被禁用后 2D 仍渲染 14 节点 / 12 边 | 覆盖 `getContext('webgl')` 返回 null 后跑行为脚本 | 0 | passed | `nodeCount=14`、`edgeCount=12`、`graph-fallback` 数量 0 |
| AC-003 标签可读、prerequisite 箭头、related 虚线、四档颜色可分辨、布局均衡 | 生产构建截图人眼核对 | 人工 | passed | `.tmp-diag/baseline-2d.png`、`after-prod-2d.png`、`after-prod-2d-selected.png`（临时目录，未提交） |
| AC-004 点节点高亮 + 右栏详情；点空白取消 | 行为脚本断言 | 0 | passed | `concept-detail` 可见、节点 `aria-pressed=true`；点空白后详情消失 |
| AC-005 图例「了解/熟悉」单行不拆 | 截图核对 | 人工 | passed | 四档图例单行显示（`after-prod-2d.png`） |
| AC-006 `graph.spec.ts` 通过 + typecheck 通过 | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/graph.spec.ts`；`pnpm --filter @paideia/app typecheck` | 0 | passed | **2026-11-22 补齐**：`e2e/graph.spec.ts` 7 条全绿（与 practice/papers 同批 `14 passed (1.3m)`，日志 `WORK-083-试卷与智能组卷/testing/logs/e2e-practice-papers-graph.log`）；typecheck 无诊断 |
| AC-007 产物不含 sigma chunk | `pnpm --filter @paideia/app build` + `grep` | 0 | passed | `dist/assets` 无 `*sigma*`；`3d-force-graph` 1.37MB 仍懒加载，仅切 3D 时下载 |

## 未覆盖 / 后续

- **已补齐**：`graph.spec.ts` 于 2026-11-22 在本机跑通（7 条全绿，见 AC-006 行）。原记录的"待 CI 跑绿"不再成立。
- 性能数字（AC-001）是**一次性实测**，环境是线上数据 + 生产构建预览；它不可重放为回归用例——后续若再改图谱渲染，
  需要重新采集，不能引用本表当证据。
- 3D 视图（`3d-force-graph`）只验了「切换后画布出现、切回 2D 正常」，**没有做 3D 下的性能与交互断言**。
