---
artifact: tasks
work_id: WORK-082
work: 图谱页性能与交互重构
status: done
mode: managed
workflow: compact
created: 2026-11-18
updated: 2026-11-18
---

# 图谱页性能与交互重构 — 任务

## 变更清单

| 文件 | 变更 |
| --- | --- |
| `frontend/apps/app/src/components/graph/GraphCanvas2D.tsx` | 重写：去掉 sigma，改为零依赖 SVG 渲染器（缩放/平移/选中/键盘可达） |
| `frontend/apps/app/src/components/graph/ConceptDetailPanel.tsx` | 右栏四档图例排版修复（「了解/熟悉」不再拆字） |
| `frontend/apps/app/e2e/graph.spec.ts` | 「无 WebGL」用例改为「纯 SVG 仍渲染」；新增「点节点出详情」用例 |
| `frontend/apps/app/package.json`、`frontend/pnpm-lock.yaml` | 移除 `sigma` 依赖 |

## 任务

### TASK-001 | done | SVG 渲染器重写（替代 sigma）
- 对应：REQ-001 / REQ-002 / REQ-003 / REQ-004 / REQ-006 / REQ-008
- 依赖：无
- 新建修改：`frontend/apps/app/src/components/graph/GraphCanvas2D.tsx`
- 做法：坐标沿用 `concept-graph-layout` 的存档/`spreadApart`（仅无存档时动态 import `graphology` + `graphology-layout-forceatlas2` 算一次）；视口用 `viewBox` 做缩放/平移；边分实线+箭头（prerequisite）与虚线（related）；标签用真实文本节点、`paint-order: stroke` 描边光晕防压字；颜色全部走 `var(--tier-*)` 令牌随换肤更新。
- 验证：生产构建 CDP 实测 longtaskMax 5974ms→127ms，openLatency 7164ms→1607ms；`canvasCount=0`、`svgCount=16`；14 节点 / 12 边。

### TASK-002 | done | 右栏图例与详情排版修复
- 对应：REQ-005 / AC-005
- 新建修改：`frontend/apps/app/src/components/graph/ConceptDetailPanel.tsx`
- 做法：图例行改 `flex items-start gap-2`，标签 `w-12 shrink-0 whitespace-nowrap`，提示 `leading-5`。
- 验证：截图确认四档图例单行显示。

### TASK-003 | done | e2e 调整
- 对应：REQ-007 / AC-006
- 新建修改：`frontend/apps/app/e2e/graph.spec.ts`
- 做法：无 WebGL 用例由「断言降级提示」改为「断言 `graph-canvas-2d` + `graph-node` 可见、`graph-fallback` 数量为 0」；新增点节点（键盘 Enter）后 `concept-detail` 可见的用例。
- 验证：生产构建上用等价行为脚本逐项核对通过（见 testing/report.md）。

### TASK-004 | done | 移除 sigma 依赖并核对产物
- 对应：REQ-008 / AC-007
- 新建修改：`frontend/apps/app/package.json`、`frontend/pnpm-lock.yaml`
- 验证：`pnpm --filter @paideia/app build` 通过，`dist/assets` 内无 `*sigma*` chunk；graphology / forceatlas2 降为小 chunk（66.9KB / 10.2KB，懒加载）。
