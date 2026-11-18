---
artifact: requirements
work_id: WORK-082
work: 图谱页性能与交互重构
status: done
mode: managed
mode_reason: 前端图谱页渲染与交互改造，跨多个前端文件、直接影响用户可见的性能与体验，需要持久追踪与前后对比证据；不改后端接口、数据模型、安全、迁移或公共契约，故按 managed / compact 执行。
workflow: compact
created: 2026-11-18
updated: 2026-11-18
related_to: [WORK-079, WORK-080]
---

# 图谱页性能与交互重构 — 需求

## 背景

用户反馈打开 线上 `/#/graph` 页面「巨卡无比」，并且此前已提过优化但未解决，要求这次「仔细优化，包括性能和 UI」。

## 已确认事实

- 线上生产数据规模很小：`knowledge_concepts=14`、`knowledge_concept_edges=12`、`knowledge_entry_concepts=21`、`knowledge_entries=847`。图谱只有 14 个节点，卡顿与节点数量无关。
- 用 Playwright（系统 Chrome + CDP，真登录）实测线上 `/#/graph`：首次进入出现**单个约 5.97 秒的主线程长任务**（longtask 合计 6142ms / 最大 5974ms，start≈1160ms），openLatency≈7164ms；headed 与 headless 均如此，不是「无 GPU」专属问题。
- CDP Profiler 归因：约 5.7s self-time 落在 `sigma` 4.0.3 的初始化气泡里。
- 读 sigma 4.0.3 源码：`SDFAtlasManager` 为每个唯一字符逐字生成 SDF（`generateSDFGlyph` → `getImageData` + 两次 EDT 逐像素距离场），并伴随超大的标签 GLSL 着色器；中文标签逐字生成成本高。这就是首个进入冻结的来源。
- 现有 2D 渲染链路：`GraphCanvas2D.tsx` 动态 import `graphology` + `sigma` + `graphology-layout-forceatlas2`；坐标先 `loadPositions()` 命中存档则复用，否则 `forceAtlas2.assign({iterations:120})`，再 `spreadApart(0.18)`（O(80×n²)）与 `normalizeScale(1000)`。
- 现有 UI 观感问题（截图实测）：节点是小圆点、标签横排在点右侧；布局挤在左上 2/3，右下角两个孤立节点、左下大片空白；连线细浅灰、**无箭头、方向不可见**，prerequisite 与 related 几乎无法区分；四档 tier 颜色很淡难辨；右栏图例因 `flex` 行内混排 + 窄列把「了解」「熟悉」拆成两行（「了\n解」「熟\n悉」）。

## 用户决定

- 2026-11-18 用户要求：**性能与 UI 都要优化**，「仔细优化」，并「新开一个 work」。

## 实现选择

- 2D 渲染从 sigma 改为**自研零依赖 SVG 渲染器**（`<svg>` + DOM，无 WebGL）。理由：彻底消除 5.7s 初始化与 WebGL 依赖；中文标签用真实文本节点渲染，清晰不糊、不重；交互、样式、命中检测完全可控；并从产物中移除 sigma 包。
- 保留 `graphology` 与 `graphology-layout-forceatlas2` 仅用于**坐标计算**（不改布局算法语义），坐标仍经 `concept-graph-layout.ts` 的存档/`spreadApart` 处理；进一步的可视区归一与避让在 SVG 层做。
- 3D 视图保持原样（`GraphCanvas3D` 仍是 lazy，仅切换「看 3D」时加载），不纳入本次重写，避免影响首屏与扩大改动面。
- e2e `graph.spec.ts` 中「无 WebGL 应给提示」用例改写为「无 WebGL 仍正常渲染」，其余按新 DOM 调整断言。

## 目标

- 首屏不再卡：进入图谱页无长任务级冻结；纯 DOM/SVG 渲染，弱环境（无 WebGL）也能用。
- 可读性与交互明显提升：标签可读、边的方向与类型可辨、tier 颜色可分辨、节点大小反映掌握度、选中高亮与详情、缩放/平移/复位。
- 右栏图例与详情排版修复（不再拆字）。

## 非目标

- 不改后端 `ConceptGraphService` 接口、数据模型或截断上限。
- 不重写 3D 视图（保持现状）。
- 不改路由、导航与页面信息架构（仍为 AppShell wide + 左画布 + 右详情栏）。

## 功能需求

- REQ-001 首屏性能：线上同等数据下进入 `/#/graph`，不再出现 >250ms 的主线程长任务；首次可交互明显提前（记录改造前后数字）。
- REQ-002 零 WebGL：2D 渲染不依赖 sigma / WebGL；`HTMLCanvasElement.getContext('webgl')` 返回 null 时仍渲染出全部节点与边。
- REQ-003 可读性：每个节点有清晰中文标签且不互相压成不可读；prerequisite 边带箭头、related 边用虚线区分；四档 tier 颜色一眼可辨；节点大小随掌握度分级。
- REQ-004 交互：点击节点→选中高亮（其余弱化）+ 右栏显示详情；点空白→取消选中；支持滚轮缩放、拖动平移、缩放/复位按钮。
- REQ-005 图例与详情排版：右栏四档图例「了解」「熟悉」等标签单行不拆；详情面板在 20rem 窄列内排版正常。
- REQ-006 布局质量：节点在画布可视区内分布均衡（不挤在角落、不重叠），重新布局按钮仍会重算并持久化。
- REQ-007 回归：`graph.spec.ts` 的语义（渲染、点击出详情、切 3D、重排、空态、truncated 提示）保持通过（按新实现调整断言）；`pnpm --filter @paideia/app typecheck` 通过。
- REQ-008 体积：改造后图谱页首屏不再加载 sigma chunk，2D 路径不含 sigma 引用。

## 约束与依赖

- 遵守 `.agent/rules/always.md` 前端约束：颜色只从设计令牌（CSS 变量 `--tier-*`、`--border`、`--card` 等）取，不写死色值；类名登记 `@source`；`packages/ui` 保持纯展示。
- 不新增运行时第三方依赖；`graphology` / `graphology-layout-forceatlas2` 继续使用。
- 提交信息关联 `WORK-082`；push 用户已授权直推，部署此前已授权。

## 验收标准

- AC-001 线上同数据 CDP 复测：无 >250ms 长任务，openLatency 明显下降，改造前后数字写入验证报告。
- AC-002 覆盖 `getContext('webgl')` 返回 null 后，2D 图谱仍渲染出 14 个节点与 12 条边。
- AC-003 截图证明：标签可读、prerequisite 有箭头、related 虚线、四档颜色可分辨、布局均衡。
- AC-004 点击节点出现高亮并右栏显示详情；点空白取消选中。
- AC-005 右栏图例「了解/熟悉」单行不拆。
- AC-006 `graph.spec.ts` 通过（或记录调整原因）+ typecheck 通过。
- AC-007 构建产物 grep 证明 2D 路径不再引用 sigma chunk。

## 关联工作项

- WORK-079 / WORK-080：皮肤与设计令牌体系；本项只消费令牌（`--tier-*` 等），不改令牌契约。

## 待确认问题

- 无（用户已明确「性能与 UI 都要优化，开始吧」）。
