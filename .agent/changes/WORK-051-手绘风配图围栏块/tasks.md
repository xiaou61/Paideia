---
artifact: tasks
work_id: WORK-051
work: 手绘风配图（sketch 围栏块）
status: approved
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 16:21:16 +0800
approver_role: CTO
---

# 手绘风配图（sketch 围栏块）实施任务

## 依据

- 已批准需求：`requirements.md`（REQ-001 … REQ-010、AC-001 … AC-010）
- 已批准提案：`proposal.md`（方案 B）
- 已批准设计：`design.md`（契约、布局接口、绘制接口、失败处理）
- 仓库现状：围栏块机制见 `frontend/packages/ui/src/markdown/renderers/index.ts`，
  校验范式见同目录 `schema.ts`，降级外壳见 `blocks.tsx` 的 `guard()` / `BlockFallback`。

## 全局约束

- 只改前端共享包与脚本，**不碰后端、数据库、迁移**。
- 禁止 `dangerouslySetInnerHTML`；文本一律 `textContent`。
- 任何随机数都不得进入绘制：seed 由节点 id 派生。
- 新增依赖只允许 `roughjs`（运行时）与 `subset-font`（仅脚本用，devDependency）。
- 任务完成时同步把本文件的状态从 `pending` 改为 `done`，并写一句实际差异（若有）。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 字体还没加载完就量宽，导致标签被裁掉 | TASK-004 | `sketch.test.tsx::等待字体加载后才量宽` |
| React 双渲染 / 重进页面导致抖动图案变化 | TASK-003 | `sketch-paint.test.ts::同一几何两次绘制 path 相同` |
| 箭头引用了不存在的节点 id | TASK-001 | `sketch-schema.test.ts::未知箭头端点报错` |
| `rows` 为空 / 某一行没有节点 | TASK-001 | `sketch-schema.test.ts::空行与空图被拒绝` |
| 一行节点太多、总宽超出画布 | TASK-002 | `sketch-layout.test.ts::超宽行被夹取且不溢出画布` |
| 深色主题下浅底浅字 | TASK-004 | 人工截图比对（退出码写 `人工`） |
| 流式回答里的 `sketch` 围栏被执行 | TASK-005 | `markdown.test.tsx::流式模式不渲染 sketch` |
| 节点标题特别长（16 字上限处）撑破框 | TASK-002 | `sketch-layout.test.ts::标题到上限时宽度停在 MAX_W` |

## 任务

### TASK-001 | done | sketch 契约与解析

- 对应：`REQ-001`、`REQ-005`、`REQ-009`、`AC-009`
- 依赖：无
- 新建/修改：`frontend/packages/ui/src/markdown/renderers/schema.ts`（追加类型、`parseSketch`、`LIMITS` 条目）
- 接口：
  - 产出 `parseSketch(code: string): ParseResult<Sketch>`
  - 产出 `type Sketch`、`type SketchNode`、`type SketchArrow`、`type SketchTone`
- 测试：`frontend/packages/ui/src/markdown/renderers/sketch-schema.test.ts`
- 步骤：
  1. 在 `LIMITS` 加 sketch 的九个上限，写读它们的解析分支。
  2. 写 `parseSketch`：JSON 解析失败、字段类型、未知 `tone`、重复 `id`、未知箭头端点、超上限各返回一条中文错误。
  3. 写失败测试六条（合法最小图、语法错、未知 tone、未知箭头 id、空 rows、超上限）。
  4. 运行测试，确认六条全绿。
- 验证：`pnpm --filter @paideia/ui test sketch-schema` → 期望 6 passed

### TASK-002 | done | 纯布局函数

- 对应：`REQ-002`、`REQ-004`、`AC-003`
- 依赖：TASK-001
- 新建/修改：`frontend/packages/ui/src/markdown/renderers/sketch-layout.ts`
- 接口：
  - 产出 `layoutSketch(sketch: Sketch, measure: SketchMeasure): SketchGeometry`
  - 产出 `type SketchMeasure = (text: string, fontSize: number) => number`
  - 产出 `makeMeasurer(): SketchMeasure`（canvas 量宽；无 canvas 时退化为 `text.length * fontSize * 0.9`）
- 测试：`frontend/packages/ui/src/markdown/renderers/sketch-layout.test.ts`
- 步骤：
  1. 用量宽桩写一个三节点单行的用例，断言三框中心等距（≤1px）且整行居中。
  2. 实现宽度夹取与行居中。
  3. 加跨行箭头用例，断言 `points` 从底中出、顶中进。
  4. 加 `style: "back"` 用例，断言折线经过行底下方。
  5. 加 `MAX_W` 与超宽行用例。
- 验证：`pnpm --filter @paideia/ui test sketch-layout` → 期望 ≥ 5 passed

### TASK-003 | done | 绘制与组件注册

- 对应：`REQ-001`、`REQ-004`、`AC-001`、`AC-004`、`AC-005`
- 依赖：TASK-002
- 新建/修改：`renderers/sketch-paint.ts`、`renderers/sketch.tsx`、`renderers/index.ts`
- 接口：
  - 产出 `paintSketch(svg: SVGSVGElement, geometry: SketchGeometry, sketch: Sketch): Promise<void>`
  - 产出 `function Sketch(props: CustomRendererProps): JSX.Element`
  - 注册 `{ language: ['sketch'], component: guard('手绘图', Sketch) }`
- 测试：`renderers/sketch-paint.test.ts`、`renderers/sketch.test.tsx`
- 步骤：
  1. 写 `paintSketch`：动态 `import('roughjs')`，清空 svg，按 `geometry.nodes` 画框、按 `arrows` 画折线、追加 `<text>`。
  2. 写 seed 派生函数并加 `path` 稳定性的测试。
  3. 写 `Sketch` 组件：解析失败走 `BlockFallback`；成功后用 effect 异步绘制，卸载时 `cancelled`。
  4. 在 `index.ts` 注册，并在 `markdown.test.tsx` 补一条"流式模式不渲染 sketch"的断言（若已有同类断言则扩展现有）。
- 验证：`pnpm --filter @paideia/ui test sketch` → 期望全部通过，且 `sketch.test.tsx` 含 `block-fallback` 与 `svg[role=img]` 两条断言

### TASK-004 | done | 手写字体资产与主题配色

- 对应：`REQ-003`、`REQ-007`、`AC-002`、`AC-007`
- 依赖：TASK-003
- 新建/修改：`scripts/build-sketch-font.mjs`、`renderers/sketch/Xiaolai.woff2`、`renderers/sketch/LICENSE.txt`、`src/styles/globals.css`
- 接口：
  - `globals.css` 增 `@font-face { font-family: 'Paideia Sketch' }` 与 `.sketch-block` 的浅/深两组 CSS 变量
  - `Sketch` 的 `<svg class="sketch-block">` 使用变量作 `stroke` / `fill`
- 测试：`renderers/sketch.test.tsx::等待字体加载后才量宽`
- 步骤：
  1. 写脚本：扫 `content/**/*.md` + 固定 ASCII，用 `subset-font` 输出 woff2。
  2. 运行脚本生成资产，把 OFL 许可证与来源写进 `LICENSE.txt`。
  3. 在 `globals.css` 加 `@font-face` 与两组变量。
  4. 把绘制里的颜色换成 `var(--sketch-*)`。
  5. 加"先 await 字体、再量宽"的实现与对应测试；深浅两主题各截一张图人工比对。
- 验证：`pnpm --filter @paideia/ui test sketch` 全绿；截图见 `testing/`；对比度人工核对（退出码 `人工`）

### TASK-005 | done | 内容样例与端到端

- 对应：`REQ-006`、`REQ-010`、`AC-001`、`AC-006`、`AC-010`
- 依赖：TASK-004
- 新建/修改：`db/devdata/V95x__seed_sketch_block.sql`（编号按当时最新迁移另取，**不写死**）、`frontend/apps/app/e2e/sketch.spec.ts`
- 接口：无新增代码接口
- 测试：`frontend/apps/app/e2e/sketch.spec.ts`、`markdown.test.tsx::流式模式不渲染 sketch`
- 步骤：
  1. 在 devdata 增一条带 `sketch` 块的知识库条目。
  2. 写 e2e：登录 → 打开该条目 → 断言 `[data-testid=sketch-block] svg` 与至少一个 `<text>`。
  3. 在 e2e 里对比两次刷新的 `path` 的 `d`（AC-004）。
  4. 远程实例跳过（沿用 `REMOTE` 既有写法）。
- 验证：`pnpm --filter @paideia/app exec playwright test e2e/sketch.spec.ts` → 期望 1–2 passed

## 完成条件

- 每条 `AC-*` 在 `testing/report.md` 里有对应证据；人工项写明"人工"与结论。
- `pnpm --filter @paideia/ui test` 与受影响端 e2e 全绿。
- 构建后入口 chunk 的 gzip 体积不增大（AC-008），把两个数字写进报告。
- 新增依赖与字体许可证在 `proposal.md` 的仓库影响里已列明，`requirements.md` 的约束一致。
