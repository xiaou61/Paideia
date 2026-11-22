---
artifact: test-report
work_id: WORK-079
work: 全站换肤与多皮肤体系
status: passed
evidence: required
mode: managed
workflow: compact
created: 2026-11-13
updated: 2026-11-22
accepted_by: xiaou61
accepted_at: 2026-11-22 14:46:44 +0800
---

# 验证报告

阶段：**骨架 + 外观页 + 20 个皮肤 + 联动与文档 + app 级 e2e**（2026-11-22 收尾）。
TASK-012 的 app 级 e2e 已在本机跑通：`e2e/appearance.spec.ts` → `6 passed (43.9s)`。

## 检查证据

> 本节前半是 2026-11-13 首次验证的快照（当时 17 个皮肤）；注册表后来涨到 20 个（WORK-080
> 追加 graphic 族等），主题单测与产物 CSS 的数字已变——2026-11-22 重跑为 `Tests 228 passed`，
> 见「app 级 e2e」小节。AC-005 / AC-006 / AC-007 三条是 2026-11-22 补的，表内已注明。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-002 / AC-003 皮肤契约（4 类错误）与全皮肤对比度（17 皮肤 × 2 模式 × 5 项 = 170 条） | `pnpm --filter @paideia/ui exec vitest run src/theme` | 0 | passed | `Test Files 3 passed (3)`、`Tests 191 passed`（contrast 170 / skin-contract 12 / theme-context 9） |
| AC-009 共享包类型检查 | `pnpm --filter @paideia/ui typecheck` | 0 | passed | 输出仅 `$ tsc -p tsconfig.json`，无诊断 |
| AC-009 应用类型检查（含图谱联动的改动） | `pnpm --filter @paideia/app typecheck` | 0 | passed | 输出仅 `$ tsc -p tsconfig.json`，无诊断 |
| AC-004 皮肤样式真的进了生产产物 | `pnpm --filter @paideia/ui-kit build` | 0 | passed | `✓ built in 1.52s`；CSS 156 149 字节，`data-skin` 规则 64 条（16 皮肤 × 4 选择器） |
| AC-004 切换即时生效 + 刷新保持 + 筛选与空状态 | 系统 Chrome + CDP（`data-skin`/令牌/localStorage/卡片数实测，见下「运行时行为证据」） | 0 | passed | `before=hsl(220 20% 98%)` → `after=hsl(38 45% 96%)`；`paideia:skin=paper`；重载后属性与令牌都保持；筛选「终端」得 1 张卡且是 `skin-card-terminal`；无匹配时出现 `skin-empty` |
| AC-008 深色/彩色皮肤打印回到纸色 | CDP `Emulation.setEmulatedMedia({media:'print'})` | 0 | passed | `body` 计算背景 = `rgb(255, 255, 255)`，而该皮肤屏幕上是 `hsl(38 45% 96%)` → 打印覆盖规则确实生效 |
| AC-001 加皮肤只动样式与注册表，不动组件 | `git diff --stat` 评审 | 人工 | passed | 17 个皮肤的实现都在 `styles/theme/skins/*.css` + `theme/skins/*.ts`；页面侧只新增了外观页与一个菜单项 |
| AC-010 观感抽查（`pixel`/`contrast`/`glass`/`forest`/`candy`/`midnight`） | Chrome headless 截图（1440×1000） | 人工 | passed | `F:\Paideia\.worktrees\WORK-079-shots\`（v1/v2）：17 张卡片的网格三段 + 5 个亮色皮肤整页 + 3 个暗色皮肤整页 |
| AC-005 首屏不闪：第一次绘制前 `data-skin` 已落 | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/appearance.spec.ts`（2026-11-22 补） | 0 | passed | `e2e/appearance.spec.ts:57` 在 `waitUntil: 'domcontentloaded'` 且**无任何交互**时断言 `html[data-skin]='terminal'`（React 尚未挂载，属性只能来自 `<head>` 内联脚本）；日志 `testing/logs/e2e-appearance.log` |
| AC-006 账号菜单里有「外观」入口并进得去 | 同上 e2e（2026-11-22 补） | 0 | passed | `e2e/appearance.spec.ts:31`：点 `account-menu-trigger` → `account-appearance` → URL 命中 `#/settings/appearance`、`skin-filter` 可见、皮肤卡数量 = `SKINS.length` |
| AC-007 换肤后图谱页节点颜色跟着变，四档语义不混淆 | 同上 e2e（2026-11-22 补，新增用例） | 0 | passed | `e2e/appearance.spec.ts:101`：图谱节点填充色来自 `var(--tier-*)`，换成 `brutal` 后同一节点的计算 `fill` 不同（全程只改 hash、无整页刷新），且 `--tier-unknown/learning/familiar/mastered` 四值互不相同 |

## 反向验证（证明守卫真的会拦住）

| # | 怎么弄坏 | 结果 |
| --- | --- | --- |
| R1 | 把 `paper` 亮色块里的 `--muted-foreground` 改成 `--muteds-foreground` | `× 每个皮肤亮暗两套覆盖的令牌完全相同`、`× 皮肤只覆盖已知令牌名`（2 failed，信息带皮肤与令牌名）；恢复后全绿 |
| R2 | 删掉 `paper` 的 `:root[data-skin='paper'].dark` 整块 | 断言信息：`paper 缺少暗色块（暗色下会变成"半新半旧"）`；恢复后全绿 |

## 运行时行为证据（CDP 实测输出）

```json
{
  "cardCount": 17,
  "filteredCount": 1,
  "filteredId": "skin-card-terminal",
  "emptyShown": true,
  "before": "hsl(220 20% 98%)",
  "after": "hsl(38 45% 96%)",
  "attr": "paper",
  "storage": "paper",
  "attrEarlyAfterReload": "paper",
  "tokenAfterReload": "hsl(38 45% 96%)",
  "printBackground": "rgb(255, 255, 255)"
}
```

第一版脚本在这里踩了一个坑并已修正：直接给受控输入赋 `.value` 再派发 `input`，React 的 value tracker 会判定"没变化"，
`onChange` 不触发 → 筛选看起来"没生效"。改用原生 `value` setter 后再派发才测到真行为。

## app 级 e2e（2026-11-22 补齐）

`cd F:\Paideia && scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/appearance.spec.ts`
→ 退出码 0，`6 passed (43.9s)`，日志 `testing/logs/e2e-appearance.log`。六条用例逐条对应验收项：

| 用例 | 覆盖 | 结果 |
| --- | --- | --- |
| 账号菜单里的「外观」能进到外观页，并列出全部皮肤 | AC-004 入口 + 注册表与页面一致（卡片数 = `SKINS.length`） | passed (2.5s) |
| 选一个皮肤后立即全站生效，刷新后仍然记得 | AC-004 切换即时生效 + AC-006 持久化 | passed (3.0s) |
| 首屏脚本在第一次绘制前就落好皮肤（不闪一下默认主题） | AC-005：`domcontentloaded` 时 `html[data-skin]` 已是 `terminal` | passed (2.6s) |
| 关键词筛选能缩小范围，无匹配时给空状态 | AC-004 筛选与空状态 | passed (2.6s) |
| 深色皮肤下打印回到纸色 | AC-008 打印 | passed (2.6s) |
| 换肤后图谱页节点颜色跟着变，四档语义不混淆 | AC-007：换成 `brutal` 后同一节点的计算 `fill` 变了，且四个 `--tier-*` 互不相同 | passed (4.8s) |

同时重跑了主题单测（注册表已从 17 涨到 20 个皮肤，旧数字不再适用）：
`pnpm --filter @paideia/ui exec vitest run src/theme` → `Test Files 4 passed (4)`、`Tests 228 passed (228)`
（contrast 200 / skin-contract 12 / skin-structure 7 / theme-context 9），日志 `testing/logs/ui-theme-tests.log`。

**跑通过程中修了用例自身两处过期断言**（都不是产品缺陷，`tasks.md` TASK-012 有同样记录）：
卡片数硬编码 `17` 改为 `SKINS.length`；`getByTestId('theme-toggle')` 因顶栏与页头各有一个而 strict 冲突，
改为按 `#main-content` 限定。

**AC-007 那条是 2026-11-22 新写的**（此前只有代码评审与 CDP 抽查）：管理员造一条概念 → 进图谱读节点计算
`fill` → 经账号菜单进外观页换到 `brutal` → **只改 hash** 回图谱（不重载文档，这才叫"不刷新页面"）→
同一节点 `fill` 必须变，且 `--tier-unknown/learning/familiar/mastered` 四值互不相同。为此把 `graph.spec.ts`
里现造概念的夹具抽成 `e2e/graph-fixture.ts` 共用（两处重复的 45 行合并）。

**环境前置（已在仓库里修掉）**：本机 vite 8.3.4 的 dev server 默认只绑 `[::1]:5173`，而 `playwright.config.ts`
探活的是 `http://127.0.0.1:5173`，不先起 IPv4 实例就会 `Timed out waiting 60000ms from config.webServer`。
现在 `apps/app/vite.config.ts` 的 `server.host` 已写死 `'127.0.0.1'`，`pnpm dev` 直接被探活到。

## 未覆盖（未做就不算通过）

- `AC-007` 图谱换肤跟随：**2026-11-22 已补 e2e**（见上表最后一行），不再是缺口。仍未断言的是"换肤不触发重排"
  这一性能面（用例只验颜色跟随与四档可分辨）。
- `AC-010` 观感抽查只覆盖 6 个代表皮肤（`pixel`/`contrast`/`glass`/`forest`/`candy`/`midnight`），
  其余皮肤只有截图与对比度断言，没有逐个人工过目。
- 管理端（`apps/admin`）与门户端（`apps/public`）未截图：它们的骨架共用 `AppShell`/`AdminShell`，
  换肤靠同一套令牌，但没有端到端的视觉证据。
