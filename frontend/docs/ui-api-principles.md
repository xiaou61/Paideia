# Paideia 设计系统 API 原则

> 出处：WORK-081「借 Astryx 经验的设计系统演进」REQ-003。Astryx（Meta 开源设计系统）把
> 「人 + AI 助手用同一套工具」写进了 API 设计；这里只取它三条可迁移的原则，落到 Paideia 的
> `@paideia/ui` 与新增的 `@paideia/patterns` 上。本文是**约定**，不是教程；每条都给出在
> 本仓库里「怎么做算对 / 怎么做算错」。

## 1. open internals：内部结构可读、可覆盖，不藏

组件不是黑盒。需要改样式时，允许通过 `className`（Tailwind 原子类）覆盖，不要为了改一个边距
就 fork 整个组件。

- **对**：`<Button className="w-full" />`，因为 `Button` 把 `className` 合并到根元素。
- **对**：需要完全接管某段渲染时用组合（children / render prop），而不是复制组件源码。
- **错**：在业务代码里复制一份 `Button` 只为改颜色；颜色应改令牌（见 `globals.css`），
  结构差异才改组件。
- **错**：把内部 DOM 结构当成公开契约去用选择器强依赖（例如 `.paideia-btn > span`）。

## 2. customize without wrapping：优先「传参/组合」，不要层层包壳

不要为每个业务场景写 `XxxCard`、`XxxButton` 这种只透传 props 的薄封装——它会制造第二套 API，
且一改底层就要同步改很多壳。需要差异时，用既有组件的 props 或 `patterns` 组合出来。

- **对**：列表页用 `@paideia/patterns` 的 `ListPage` 组合出「页头 + 筛选 + 表格 + 三态 + 分页」，
  列与行由调用方给。
- **对**：确实要新语义（例如「空状态 + 行动按钮」总是一起出现）时，做成 `Empty` 的组合用法，
  而不是给 `Empty` 加 5 个布尔开关。
- **错**：`function PrimaryButton(props) { return <Button variant="default" {...props} /> }`——
  多一层没有增加任何约束。
- **判据**：如果封装体里只有一个 `<Xxx>` 且没做数据/状态转换，就不要封装。

## 3. guidance over enforcement：用约定与示例引导，不靠运行时强拦

设计系统给的是「推荐路径 + 可见的示例」，把一致性做成**默认容易、走偏要费劲**，而不是用运行时
校验把业务卡死。

- **对**：`frontend/docs/ui-manifest.md` 与 ui-kit 的「组件索引」给出每个组件的分类与用途，
  人和 AI 都查同一份；`pnpm ui:manifest:check` 在 CI 里提示清单漂移，不阻断开发。
- **对**：能自动守的硬约束才上测试（颜色只有一个来源 `tokens.test.ts`；`@source` 覆盖
  `source-coverage.test.ts`；对比度 `contrast.test.ts`；`packages/ui` 不越界
  `workspace-boundaries.test.ts`）。
- **错**：为「禁止业务里写内联颜色」再加一层运行时拦截组件——测试已经能挡住，运行时不值得。
- **判据**：能写成静态测试的就写测试；写不成的写成文档 + 示例，接受偶发偏离。

## 暂缓：primitives 双出口

Astryx 同时提供「推荐出口」和「底层原语出口」。Paideia 目前 `@paideia/ui` 只有
`src/index.ts` 一个出口（设计系统的唯一出口）。**是否再开一个 `primitives` 子出口暂缓**：
先把上面三条原则与 `patterns` 层跑顺，等出现「业务确实需要更底层能力、而主出口又不该暴露」的
真实场景再评估，避免过早把一个出口拆成两个、把公共 API 面积翻倍。

## 与本次演进的关系

- 原则 3 的「单一规范源」= S1 的 `component-meta.json` + `gen-ui-manifest.mjs`（已落地）。
- 原则 2 的「组合而非包壳」= S2 的 `@paideia/patterns`（已落地：`ListPage` / `DetailPage` /
  `FormWizard`）。
- 原则 1 的「可覆盖」是既有事实：`@paideia/ui` 组件一直把 `className` 透传到根元素，本文把它
  写成明确约定。
