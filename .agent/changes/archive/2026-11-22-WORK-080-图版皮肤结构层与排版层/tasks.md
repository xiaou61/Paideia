---
artifact: tasks
work_id: WORK-080
work: 图版皮肤：结构层与排版层（三个激进样板）
status: approved
created: 2026-11-13
updated: 2026-11-15
mode: managed
workflow: compact
approved_by: xiaou61
approved_at: 2026-11-22 14:45:17 +0800
approver_role: owner
---

# 实施任务：图版皮肤（结构层与排版层）

## 依据

- 需求：`requirements.md`（REQ-001…REQ-008 / AC-001…AC-007），2026-11-15 由 xiaou61（owner）签署。
- `compact` 模式不写 `design.md`，变更清单按 `references/design.md` 的硬规则放在下面。
- 本项是 WORK-079 的增量：不改令牌体系，只**加一层可以改形状与排版的能力**，并用白名单测试把边界钉死。

### 变更清单

| # | 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `frontend/packages/ui/src/styles/globals.css` 的 `:root` | 新增 | 只有第一层装饰令牌 `--decor-*` | 追加 `--decor2-image: none`、`--decor2-size: auto`、`--decor2-repeat: repeat`、`--decor2-opacity: 0`、`--decor2-blend: normal`（默认全关，保证默认主题逐像素不变） | REQ-003 / REQ-005 |
| 2 | `frontend/packages/ui/src/styles/theme/decor.css` | 修改 | 只有 `body::before`（`z-index:-1`，画在内容之下） | 新增 `body::after`（`z-index:1`，`pointer-events:none`，走 `--decor2-*`，用于网格/扫描线这类必须横穿内容的装饰） | REQ-003 |
| 3 | `frontend/packages/ui/src/styles/theme/decor.css` 的 `@media print` | 修改 | 只把令牌强制回纸张语义 | 额外 `--decor2-opacity: 0` 与 `body::before, body::after { display: none }` | REQ-005 |
| 4 | `frontend/packages/ui/src/styles/theme/skins/graphic.css` | 新增 | 无 | 三个皮肤 `brutal` / `editorial` / `win98`（每个 `:root[data-skin='x']` + `.dark` 两块，令牌集与 `terminal.css` 同构）+ 各自的结构层与排版层规则 | REQ-001 / REQ-002 / REQ-004 |
| 5 | `frontend/packages/ui/src/styles/theme/skins.css` | 修改 | 逐族 `@import`，最后一族是 contrast | 末尾追加 `@import './skins/graphic.css'` | REQ-004 |
| 6 | `frontend/packages/ui/src/theme/skins/graphic.ts` | 新增 | 无 | `GRAPHIC_SKINS` 三条元数据（brutal/editorial/win98 各带 family `brutalist`/`editorial`/`retro`） | REQ-004 |
| 7 | `frontend/packages/ui/src/theme/skins.ts` | 修改 | 8 族 / 17 皮肤（default + 16） | 新增三族 `粗野主义`/`杂志排版`/`复古系统`，`SKINS` 展开 `GRAPHIC_SKINS` → **11 族 / 20 皮肤** | REQ-004 |
| 8 | `frontend/packages/ui/src/theme/skin-structure.test.ts` | 新增 | 无（此前只约束令牌：数值合法、亮暗齐全、不含未知令牌） | 结构层白名单测试 7 条：属性白名单、`!important` 禁止、选择器必须以 `[data-skin=…]` 打头、皮肤必须已登记、禁止改布局属性、禁止引用 `--paper*`、`var()` 必须指向真实令牌 | REQ-001 / AC-001 |
| 9 | `.agent/specs/platform-contracts.md` | 修改 | 「设计令牌与皮肤契约」只讲令牌三层 | 三层结构表加「结构」行与 `body::after`；新增「结构层白名单（WORK-080）」小节；联动点补「`@theme inline` 把字号内联成字面量，改 `--text-3xl` 无效」这条坑 | REQ-007 |

### TASK-001 | done | 第二装饰层 `--decor2-*` + `body::after`

- 对应：REQ-003、REQ-005、AC-004、AC-005
- 依赖：无
- 新建/修改：`styles/globals.css`（`:root` 五条 `--decor2-*`，默认 opacity 0 / image none）、`styles/theme/decor.css`（`body::after`，`z-index:1`、`pointer-events:none`；`@media print` 关掉两层）
- 验证：`skin-contract.test.ts` 断言默认 `--decor2-opacity` 为 0；CDP 实测 `editorial` 下 `body::after` 的 `background-image` 是网格渐变

### TASK-002 | done | `graphic.css`：三个皮肤 + 结构层 + 排版层

- 对应：REQ-001、REQ-002、REQ-004
- 依赖：无
- 新建/修改：`styles/theme/skins/graphic.css`（brutal/editorial/win98 各亮暗两块令牌，令牌集与 `terminal.css` 同构；随后是各自的结构层与排版层规则）、`styles/theme/skins.css`（末尾 `@import './skins/graphic.css'`）
- 验证：`pnpm --filter @paideia/ui exec vitest run src/theme` → 228 passed（含 20 皮肤 × 2 明暗 × 5 项对比度）

### TASK-003 | done | 注册表与三个新族

- 对应：REQ-004
- 依赖：TASK-002
- 新建/修改：`theme/skins/graphic.ts`（`GRAPHIC_SKINS`）、`theme/skins.ts`（三族 `brutalist`/`editorial`/`retro`，`SKINS` 展开 → 11 族 / 20 皮肤）
- 验证：`skin-contract.test.ts` 的「注册表与皮肤 CSS 双向一致」通过

### TASK-004 | done | 结构层白名单测试

- 对应：REQ-001、AC-001
- 依赖：TASK-002
- 新建/修改：`theme/skin-structure.test.ts`（7 条：属性白名单 / 无 `!important` / 选择器必须 `[data-skin=…]` 打头 / 皮肤已登记 / 禁改布局属性 / 不引用 `--paper*` / `var()` 指向真实令牌）
- 验证：7 passed；括号感知的 `splitTopLevel` 保证 `:is(h1, h2, h3)` 不被逗号拆断

### TASK-005 | done | 反向验证白名单会红

- 对应：AC-001
- 依赖：TASK-004
- 步骤：临时追加 `[data-skin='brutal'] [data-slot='card'] { position: relative; width: 999px; }`
- 验证：1 failed，逐属性点名 position 与 width；还原后 228 全绿

### TASK-006 | done | 全量回归与产物核对

- 对应：REQ-006、AC-003、AC-006
- 依赖：TASK-002、TASK-003
- 命令：`pnpm --filter @paideia/ui test`、`pnpm --filter @paideia/ui typecheck`、`pnpm --filter @paideia/app typecheck`、`pnpm --filter @paideia/ui-kit build`
- 验证：476 passed / 两个 typecheck 无输出 / CSS 167 629 字节且含 `[data-skin=brutal] [data-slot=card]` 与 `outset`

### TASK-007 | done | CDP 截图抽查并修掉 editorial 网格切字

- 对应：REQ-008、AC-002
- 依赖：TASK-002
- 步骤：起 ui-kit dev（5185）→ Chrome headless（9336）→ `shot-graphic.mjs` 点真实皮肤卡与明暗按钮 → 18 张截图 + `graphic-report.json`
- 验证：brutal `cardRadius=0px` + 4px 实心投影；editorial `bodyAfter` 为网格；win98 `cardShadow=none` + outset；console 错误 0；网格从 24px/7%/0.6 调成 32px/6%/0.5

### TASK-008 | done | 契约文档更新

- 对应：REQ-007、AC-007
- 依赖：TASK-002
- 修改：`.agent/specs/platform-contracts.md`（三层结构表加「结构」行与 `body::after`、「结构层白名单（WORK-080）」小节、字号被 `@theme inline` 内联的坑）
- 验证：人工评审（`testing/report.md` 的 AC-007 行）

### TASK-009 | done | 提交（工作树清理随合并做）

- 对应：—
- 依赖：TASK-001…TASK-008
- 步骤：删除临时 CDP 脚本（`shot-graphic.mjs` 与 `diag.mjs` 已移出仓库）→ 提交（含本工件，见分支 work/WORK-080 首个提交）→ 合并进 master 后删 `.worktrees/WORK-080` 与分支 `work/WORK-080`
### 不做

- 不批量补其余 9–12 个候选皮肤（`mac-classic` / `swiss` / `notebook` / `cel` / `cyber` / `typewriter` / `bauhaus` / `highlighter` / `compact`）：本项先证明机制可行，批量扩是下一项。
- 不给组件加新 `data-slot`，不引入 CSS-in-JS，不改令牌命名。
