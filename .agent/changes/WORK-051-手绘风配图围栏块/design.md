---
artifact: design
work_id: WORK-051
work: 手绘风配图（sketch 围栏块）
status: approved
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 15:18:39 +0800
approver_role: CTO
---

# 手绘风配图（sketch 围栏块）设计

## 系统上下文

```text
markdown 正文（DB 里的字符串）
  └─ Markdown（frontend/packages/ui/src/markdown/markdown.tsx）
       └─ streamdown + documentRenderers（仅非流式）
            └─ guard('手绘图', Sketch)   ← blocks.tsx，已存在的错误边界
                 ├─ parseSketch(code)    ← renderers/schema.ts，纯函数
                 ├─ layoutSketch(...)    ← 新增，纯函数，量宽可注入
                 └─ rough.svg(<svg>)     ← 懒加载的 roughjs，向真实 DOM 追加图元
```

无后端、无数据库、无网络请求。

## 组件与职责

| 位置 | 职责 | 新建/改动 |
| --- | --- | --- |
| `renderers/schema.ts` | 新增 `Sketch` 类型、`parseSketch`、`LIMITS` 里的 sketch 上限 | 改 |
| `renderers/sketch-layout.ts` | 纯布局：行宽、居中、箭头折线点、画布尺寸 | 新建 |
| `renderers/sketch.tsx` | React 外壳：解析 + 降级 + 尺寸占位 + 触发绘制 | 新建 |
| `renderers/sketch-paint.ts` | 把几何画成 SVG 元素（rough.js + `<text>`），无 React | 新建 |
| `renderers/sketch/Xiaolai.woff2` + `LICENSE.txt` | 字体子集与 OFL 许可证 | 新建 |
| `renderers/index.ts` | 注册 `{ language: ['sketch'], component }` | 改 |
| `src/styles/globals.css` | `@font-face` 与 `.sketch-block` 的配色变量（浅/深） | 改 |
| `scripts/build-sketch-font.mjs` | 扫描 content/ 生成字体子集 | 新建 |

拆成 `sketch-paint.ts` 而不全塞进组件，是为了让"画"这一步不需要 React 就能跑，
测试里可以直接对一个 `<svg>` 断言（比较 `path` 的 `d`）。

## 请求或事件流程

1. 组件拿到 `code`，`parseSketch` 同步解析；失败立刻渲染 `BlockFallback`。
2. 渲染 `<svg role="img" aria-label={title}>`，初始 `height` 给 160px 占位避免布局跳动。
3. `useEffect` 里依次：`await document.fonts.load('20px "Paideia Sketch"')`（失败就继续）
   → `makeMeasurer()` → `layoutSketch` → 动态 `import('roughjs')` → 清空 SVG → 追加图元
   → 设置真实 `width/height/viewBox`。
4. 组件卸载或 `code` 变化时取消（用 effect 的 `cancelled` 标志），避免旧图回写。

## 接口与数据

### JSON 契约

````text
{
  "title": string?      // 整图标题，≤ 40 字
  "rows": [             // 1–4 行，每行 1–5 个节点，合计 ≤ 12
    [ { "id": string,   // ≤ 24 字，块内唯一
        "title": string,// ≤ 16 字
        "sub": string?, // ≤ 28 字
        "tone": "gray" | "blue" | "amber" | "green" | "violet" }   // 缺省 gray
    ]
  ],
  "arrows": [           // ≤ 16 条
    { "from": id, "to": id,
      "label": string?,                 // ≤ 12 字
      "style": "straight" | "back" }     // back = 从下方绕回，用于循环
  ]
}
````

`LIMITS` 追加（与既有常量同一份，报错文案共用）：

```ts
sketchTitle: 40, sketchNodeId: 24, sketchNodeTitle: 16, sketchNodeSub: 28,
sketchRows: 4, sketchRowNodes: 5, sketchNodes: 12, sketchArrows: 16, sketchLabel: 12
```

### 布局接口

```ts
export interface SketchMeasure {
  (text: string, fontSize: number): number
}

export interface PlacedNode {
  node: SketchNode
  x: number; y: number; w: number; h: number
}

export interface PlacedArrow {
  from: PlacedNode; to: PlacedNode; style: 'straight' | 'back'
  points: Array<[number, number]>   // 折线点，已含起止边
  label?: string
}

export interface SketchGeometry {
  width: number; height: number
  titleY: number | null
  nodes: PlacedNode[]
  arrows: PlacedArrow[]
}

export function layoutSketch(sketch: Sketch, measure: SketchMeasure): SketchGeometry
```

常量：`PAD_X=22`、`PAD_Y=14`、`GAP_X=44`、`GAP_Y=56`、`MIN_W=110`、`MAX_W=260`、
`TITLE_SIZE=20`、`SUB_SIZE=15`、`MARGIN_X=8`、`MARGIN_Y=8`。

宽度：`w = clamp(max(measure(title,TITLE_SIZE), measure(sub,SUB_SIZE)) + PAD_X*2, MIN_W, MAX_W)`；
高度：有 `sub` 时 `h = PAD_Y*2 + 26 + 22`，否则 `PAD_Y*2 + 26`。
行宽 = 节点宽之和 + `GAP_X*(n-1)`；画布宽 = 最宽行 + `MARGIN_X*2`；每行起点 = `(画布宽 - 行宽)/2`。

箭头端点（按两框相对位置选边，不引入寻路）：
- 同一行且 `from.x < to.x`：出右中、进左中，直线。
- 同一行但反向，或 `style: "back"`：从 `from` 底部出、走 `行底 + 28` 的水平线、从 `to` 底部进。
- 不同行：出 `from` 底中、进 `to` 顶中；`x` 不同时插一个中点形成折线。

`points` 交给 `rough.path()` 生成 `d`，箭头头部用两段短线（`rough.line`）画。

### 绘制接口

```ts
export async function paintSketch(svg: SVGSVGElement, geometry: SketchGeometry, sketch: Sketch): Promise<void>
```

内部：`const rough = (await import('roughjs')).default; const rc = rough.svg(svg)`。
seed：`seedOf(id, index) = hash(id) % 2^31`——**不取 `Math.random()`**。

两条已实测的细节（2026-10-28，Chromium）：

- **`stroke`/`fill` 可以直接传 `var(--sk-*)`**：浏览器把呈现属性当 CSS 值解析，切换 `html.dark`
  后计算值实时跟随（fill `rgb(231,245,255)` → `rgb(59,42,19)`）。所以不需要 JS 读主题。
- **rough.js 每个框输出两条 `path`**（填充一条 `stroke="none"`、描边一条 `fill="none"`）。
  所以 AC-001 的"框数 = 节点数"要按 `[data-node]` 计数，**不能数 `path`**。

## 持久化与迁移

无。正文仍是 markdown 字符串；不新增表、不新增迁移。

## 失败处理与恢复

| 失败 | 行为 |
| --- | --- |
| JSON 语法错误 / 字段类型错 / 未知 `tone` | `parseSketch` 返回 `{ ok:false, error }` → `BlockFallback`（原因 + 原始文本） |
| 箭头引用了不存在的 `id` | 同上，错误文案指出是哪个 id |
| 超出上限 | 同上，文案给出上限数值（与 `LIMITS` 同源） |
| 字体加载失败 | **不阻断**：用系统字体继续画，图仍然出现 |
| `roughjs` chunk 加载失败 | 渲染 `BlockFallback`，`error` 写"绘制模块加载失败" |
| 组件卸载时仍在 async | `cancelled` 标志，不写 DOM |

## 安全与权限

- 只做 `JSON.parse` + 字段白名单，不执行任何代码，不解析 HTML。
- **禁止 `dangerouslySetInnerHTML`**；所有文本走 `textContent`，所有属性来自受控枚举或数字。
- 不发起任何网络请求（字体与 roughjs 都是打包进产物的本地资源）。
- 只在 `documentRenderers` 注册，流式回答里不生效——沿用 `markdown.tsx` 现有边界。

## 可观测性

不需要。静态内容渲染，失败通过 UI 降级对作者可见。

## 实施顺序

1. `schema.ts` 契约与解析（纯函数，先测）。
2. `sketch-layout.ts`（纯函数，注入量宽，先测）。
3. `sketch-paint.ts` + `sketch.tsx` + 注册。
4. 字体资产与生成脚本。
5. 内容样例与 e2e。

## 测试策略

| 层 | 文件 | 断言 |
| --- | --- | --- |
| 解析 | `renderers/sketch-schema.test.ts` | 合法/语法错/未知 tone/未知箭头 id/超上限 |
| 布局 | `renderers/sketch-layout.test.ts` | 三节点等距居中、宽度夹取到 MIN/MAX、back 箭头走下方、未知 id 抛错由解析拦下 |
| 组件 | `renderers/sketch.test.tsx` | 坏 JSON → `block-fallback` 可见；好 JSON → 出现 `svg[role=img]`，`<text>` 数 ≥ 节点数 |
| e2e | `frontend/apps/app/e2e/sketch.spec.ts` | devdata 种子条目渲染出 `[data-testid=sketch-block] svg`，`REMOTE` 时跳过 |
| 体积 | 人工 | `pnpm --filter @paideia/app build` 入口 chunk 与本项实施前对比（AC-008） |

happy-dom 没有 canvas：`makeMeasurer()` 在 `getContext('2d')` 为 null 时退化为
`text.length * fontSize * 0.9`。这是**测试与降级**用的确定性兜底，不是给生产用的估算。

## 需求追踪

| 需求 | 设计落点 | 测试 |
| --- | --- | --- |
| REQ-001 | 契约 + `sketch-paint.ts` | `sketch.test.tsx`、e2e |
| REQ-002 | `layoutSketch` 宽度与居中 | `sketch-layout.test.ts` |
| REQ-003 | `@font-face` + `font-family` 回退链 | e2e 截图、`sketch.test.tsx` |
| REQ-004 | `seedOf` 由 id 派生 | `sketch-layout.test.ts`（seed 稳定）、e2e 两次刷新对比 |
| REQ-005 | `parseSketch` + `BlockFallback` | `sketch-schema.test.ts`、`sketch.test.tsx` |
| REQ-006 | 只挂 `documentRenderers` | `markdown.test.tsx` 既有断言 + 新增一条 |
| REQ-007 | `.sketch-block` 的浅/深变量 | 人工截图 + 对比度计算 |
| REQ-008 | 动态 `import('roughjs')` | 构建体积对比 |
| REQ-009 | `LIMITS` 单一来源 | `sketch-schema.test.ts` |
| REQ-010 | devdata 种子 + e2e | `sketch.spec.ts` |

## 待决定事项

- `back` 箭头在节点超过 3 个时的绕行高度是否够用，留到样例渲染时看图再调常量（不改契约）。
