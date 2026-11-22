---
artifact: proposal
work_id: WORK-081
work: 借 Astryx 经验的设计系统演进
status: approved
created: 2026-11-17
updated: 2026-11-17
approved_by: xiaou61
approved_at: 2026-11-17 10:40:46 +0800
approver_role: CTO
---

# 借 Astryx 经验的设计系统演进 — 提案

## 摘要

把 Astryx 值得借的三条经验落成 Paideia 的三件具体产物，**全部自研、零运行时依赖、不引入 Astryx / StyleX**：

1. **REQ-001 单一规范源**：脚本从 `packages/ui/src/index.ts` 的导出生成一份 `ui-manifest.md`（组件名 / 分类 / 一句话用途 / 源文件），人机共读，`--check` 防漂移。
2. **REQ-002 Patterns 层**：新开 `@paideia/patterns` 包，提供列表页 / 详情页 / 表单向导三个纯展示页面模板。
3. **REQ-003 API 原则**：把「开放内部 / 定制而不包裹 / 引导而非强禁」写进文档，评估但不实现 `@paideia/ui/primitives` 双出口。

## 推荐方案

### 核心机制

- **REQ-001**：`index.ts` 已是"唯一出口"的好底子，缺的是"可被程序读的清单"。用一个 Node 脚本解析 `index.ts` 的 `export {...} from './x'`，与一份手写的 `component-meta.ts`（每组件一句话用途与分类）合并，生成 `frontend/docs/ui-manifest.md`；`--check` 断言"每个导出都有元数据、生成结果与入库文件一致"。**不追求自动渲染变体**——ui-kit 现有注释已说明变体布局各异、"自动列表价值不大"，所以变体展览仍手工，脚本只负责"有什么、在哪、干什么"。
- **REQ-002**：新包 `frontend/packages/patterns/`，`exports "." → ./src/index.ts`，与现有包一样直接消费源码、无构建步骤。三个模板**只吃 props、只组合 `@paideia/ui` 基元**，不引 `@paideia/core` / `react-router` / 平台 API，保持"纯展示"。
- **REQ-003**：纯文档 + 评估结论，不改运行时。

### 受影响归属

新增一个 workspace 包（`patterns`）、给 `@paideia/ui` 加一份元数据文件与样式来源登记、给 ui-kit 加展示、给 admin 做一页试点。**不改**现有皮肤契约（WORK-079/080）。

## 范围

- 新增：`frontend/packages/patterns` 包、`scripts/gen-ui-manifest.mjs`、`docs/ui-manifest.md`、`docs/ui-api-principles.md`、`component-meta.ts`。
- 修改：`globals.css` 的 `@source`、`source-coverage.test.ts`、`frontend/package.json` 脚本、ui-kit 页面、一个 admin 列表页试点。
- 非目标：引 Astryx 运行时 / StyleX；建第二套主题；改皮肤令牌契约；大规模迁移 admin 页面（只做一页试点）。

## 备选方案

| 方案 | 最有力的理由 | 为什么不选 |
| --- | --- | --- |
| 直接把 Astryx 作为依赖引入 | 150+ 组件、7 主题、现成 patterns，省自研 | 与现有 17 套皮肤形成两套主题；StyleX 与 Tailwind 两套范式互斥；Beta；把桌面产物重新拉大。 |
| patterns 塞进 `packages/ui` | 少一个包，随皮肤一起走 | 用户已决定新开包；`ui` 应保持原子基元，组合型模板混入会让"单一出口"继续膨胀、边界变糊。 |
| 清单手写 | 更可控 | 源码一改就失真、维护成本高；用户已决定脚本生成 + CI 校验。 |
| 只做 REQ-001，暂不碰 patterns | 最低风险 | 用户已决定三条全纳入；patterns 才是 Astryx "组合层"经验的主体，缺它等于只借了一半。 |

**所选方案放弃了**：省自研的一次性便利（拒绝引依赖）、以及"最小改动"的舒服（接受新增一个包与样式来源登记的固定成本）。

## 仓库影响

- 前端 workspace 多一个包；`packages/ui` 多一个元数据文件，`index.ts` 出口不变。
- **样式风险最高的一处**：Tailwind v4 下新包 `.tsx` 里的类名，若不登记 `@source` 会被静默丢弃（`always.md` 已记此坑，`source-coverage.test.ts` 强制）。新包必须同步登记。
- 桌面产物不受影响（patterns 只被 ui-kit / admin 引用，不进 `apps/app`，故不进安装包）。

## 交付拆分

按 REQ-001 → REQ-002 → REQ-003 三个纵向切片，每个切片可独立验证：

| 切片 | 内容 | 独立完成判据 |
| --- | --- | --- |
| S1（REQ-001） | 元数据 + 生成脚本 + `ui-manifest.md` + `--check` | `pnpm ui:manifest:check` 退出码 0；改一个组件说明后 check 变红 |
| S2（REQ-002） | `@paideia/patterns` 三模板 + `@source` + ui-kit 展示 + admin 一页试点 | `pnpm -r typecheck`、patterns 渲染测试、ui-kit/admin e2e 绿 |
| S3（REQ-003） | `ui-api-principles.md` | 文档经评审，无待定 |

## 风险与缓解措施

- **清单漂移**：`--check` 进 CI/提交前；生成文件入库，diff 可见。
- **patterns 混入业务逻辑**：模板签名只接受 props / 渲染函数，code review 卡"引 core/router"；`packages/ui` 的边界测试思路可复用。
- **`@source` 漏登记导致样式静默丢失**：`source-coverage.test.ts` 加断言，先红后绿。
- **试点页行为回归**：admin 试点选低风险列表页，保留原 e2e 断言。

## 验收映射

| 需求 | 验收 | 由谁证明 |
| --- | --- | --- |
| REQ-001 | AC-001 | `ui-manifest.md` 存在且 `--check` 通过；反向验证：改一处说明后 check 失败 |
| REQ-002 | AC-003 | 三模板在 ui-kit 可渲染；admin 试点页 e2e 通过 |
| REQ-003 | AC-002 | `ui-api-principles.md` 含三原则与"暂缓 primitives 双出口"的理由 |

## 待决定事项

- patterns 试点选哪一页：**建议 `AiRunsListPage.tsx`**（结构简单、无编辑弹窗），若不合适由用户改指一页。
