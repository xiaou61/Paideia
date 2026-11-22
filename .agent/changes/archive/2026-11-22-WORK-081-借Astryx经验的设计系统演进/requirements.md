---
artifact: requirements
work_id: WORK-081
work: 借 Astryx 经验的设计系统演进
status: approved
mode: strict
mode_reason: 用户决定三条经验全部纳入并新建 @paideia/patterns 包、评估 @paideia/ui 公共出口，属跨包与公共接口/架构变化，按 strict 执行（需求→方案→设计→任务）。
workflow: full
created: 2026-11-17
updated: 2026-11-17
related_to: [WORK-002, WORK-079, WORK-080]
approved_by: xiaou61
approved_at: 2026-11-17 10:34:47 +0800
approver_role: CTO
---

# 借 Astryx 经验的设计系统演进 — 需求

## 背景

已调研 Meta 开源设计系统 Astryx（https://github.com/meta/astryx），结论是**不把它当依赖引入，只借它的经验**。本工作项把「值得借的三条经验」落成对 `@paideia/ui` 的具体演进方向，供决策，并为后续实施切片留出依据。调研全文见 `.agent/references/astryx-2026-11.md`。

## 已确认事实

- Paideia 前端是 pnpm workspace：四个应用面（app / admin / public / ui-kit）共享 `packages/ui`。设计系统**单一出口** `frontend/packages/ui/src/index.ts`；颜色唯一来源 `frontend/packages/ui/src/styles/globals.css`；Tailwind v4（`@source` 显式登记）；已有 tokens / contrast / source-coverage / skin-contract 等守卫测试；皮肤体系由 WORK-079（17 套皮肤）与 WORK-080（结构层/排版层）建立，「皮肤只覆盖设计令牌」是不变的契约。
- Astryx 是 Meta 内部用了 8 年、撑过 13000+ 应用的设计系统，MIT，React 19 + StyleX，150+ 组件、7 套主题、CLI + Storybook + Sandbox，官方标注 Beta；文档与 CLI 面向 agent 设计；theme = CSS 自定义属性覆盖；swizzle 可把组件源码 eject。
- 不引入的硬理由：会与现有皮肤体系形成两套主题、StyleX 与 Tailwind 两套样式范式冲突、Beta 稳定性、且会把桌面产物（当前 5.31MB 量级）重新拉大。

## 用户决定

- 2026-11-17 用户确认：三条经验**全部纳入**，按 REQ-001 → REQ-002 → REQ-003 顺序推进（先易后难）。
- 2026-11-17 用户确认：patterns 层**新开 `@paideia/patterns` 包**，不塞进 `packages/ui`。
- 2026-11-17 用户确认：UI 清单**由脚本生成 + CI 校验**（`--check` 防漂移），不手写。
- 方向已定：只借思路，不引入 Astryx 运行时 / StyleX。

## 实现选择

- 清单生成用 `frontend/scripts/gen-ui-manifest.mjs` 解析 `index.ts` 的导出（不引入 TS API / ts-morph）；用途描述由人工 `component-meta.ts` 提供，生成 `frontend/docs/ui-manifest.md`，`--check` 防漂移。
- `@paideia/patterns` 与 `@paideia/ui` 一样直接消费源码、无构建步骤；三个模板只吃 props、不持状态、不引 core/router。
- 新包 `.tsx` 的类名需在 `globals.css` 登记 `@source`，并纳入 `source-coverage.test.ts` 断言。
- admin 试点候选 `AiRunsListPage.tsx`（结构简单、无编辑弹窗）。

## 目标

产出可执行的「融合/借鉴」方案：明确借哪三条、每条怎么低成本落地、哪些坚决不做。

## 非目标

- 不引入 Astryx 运行时或 StyleX。
- 不新建第二套主题系统，不改现有皮肤契约。
- 本工作项**不写产品代码**；实施另开切片。

## 借鉴候选

- **REQ-001 Agent-ready 单一规范源**：从 `index.ts` 与 ui-kit 元数据生成一份 UI manifest（组件清单 / Props / 示例），人与 AI 读同一份，替代"散落在各组件文件里的隐知识"。
- **REQ-002 Patterns 页面级模板层**：新开 `@paideia/patterns` 包，提供列表页 / 详情页 / 表单向导等**组合型**页面模板，服务 admin 等高频重复页面；模板只吃 props，不引 core/router，保持纯展示。
- **REQ-003 API 原则**：参照 Astryx 的 open internals / customize without wrapping / guidance over enforcement，评估对 `index.ts` 单一出口与扩展方式的调整。

## 约束

- 必须遵守 `.agent/rules/always.md` 的前端约束：颜色只有一个来源、`@source` 显式登记、对比度守卫、`packages/ui` 保持纯展示层（不引 core / router / 平台 API）。
- 不增加运行时依赖，不增大桌面产物。
- 不自建第二套主题。

## 验收标准

- AC-001 产出「Astryx 三条借鉴点 → Paideia 具体映射」表，含落地成本与收益。
- AC-002 每条借鉴点给出「采纳 / 暂缓 / 不做」的建议与理由。
- AC-003 给出若采纳时的最小实施切片清单（不含代码实现）。

## 关联工作项

- WORK-002：确立前端四应用面与设计系统骨架，本工作项在其上演进。
- WORK-079 / WORK-080：现有皮肤体系（令牌覆盖契约、结构层/排版层）；本工作项**不改**该契约，清单与 patterns 必须与它协同工作。

## 待确认问题

- 无（Q1/Q2/Q3 已于 2026-11-17 由用户裁决，见「用户决定」）。下一步：需求批准后进入 `proposal.md`。
