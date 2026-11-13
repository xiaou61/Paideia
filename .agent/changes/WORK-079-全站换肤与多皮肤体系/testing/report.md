---
artifact: testing_report
work_id: WORK-079
work: 全站换肤与多皮肤体系
status: in_progress
updated: 2026-11-13
commit: 未提交（本轮改动仍在工作区）
---

# 验证报告

阶段：**骨架 + 外观页 + 首批 17 个皮肤 + 联动与文档**。剩余项见 `tasks.md`（主要是 TASK-012 的 app 级 e2e 未能在本机执行）。

## 检查证据

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

## 未覆盖（未做就不算通过）

- `AC-004` / `AC-006` 的 **app 级** e2e（`apps/app/e2e/appearance.spec.ts` 已写但未执行）：本机
  `scripts/test-db.sh` 因 `remote-db.local.env` 指向远程 MySQL 而拒绝派生库，且 Playwright 的
  `chromium_headless_shell-1248` 缺失（`playwright install chromium-headless-shell` 只下到 ffmpeg）。
  两条都是环境问题，不是代码问题；可重复的命令写在 `testing/plan.md`。
- `AC-005` 的"第一次绘制前"严格形式：CDP 在 700ms 时读到的属性已经是 `paper`，但那一刻 React 已挂载，
  不能单独证明它来自内联脚本。内联脚本本身由 `skin-contract.test.ts` 的两条用例执行验证。
- `AC-007` 图谱换肤跟随：代码已改（订阅事件 + 只换色不重排），**没有跑过图谱用例**，属未验证。
- 既有的 `markdown-render.spec.ts` 主题用例未跑（同一 e2e 阻塞）。
