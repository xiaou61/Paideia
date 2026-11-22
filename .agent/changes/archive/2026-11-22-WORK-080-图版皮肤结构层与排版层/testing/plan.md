---
artifact: testing_plan
work_id: WORK-080
work: 图版皮肤：结构层与排版层（三个激进样板）
status: approved
updated: 2026-11-15
---

# 验证计划

## 判据

本项的风险不是"算错"，而是**形状与排版跑出去把功能撞坏**（按钮被撑破、对话框被裁掉、表格错位）。所以判据分三层：

1. **自动**：结构层白名单（不许改布局属性、不许 `!important`、不许引用未知令牌）+ 对比度（20 皮肤 × 2 明暗 × 5 项）+ 契约（亮暗令牌集合相等）。
2. **反向**：把守卫能拦的东西**真弄坏一次**，确认它红。守卫不验红等于没有守卫。
3. **人眼**：白名单能证明"没碰布局属性"，证明不了"好不好看、会不会切字"，必须看截图。

## 内环（每次改样式都跑）

```powershell
cd frontend
pnpm --filter @paideia/ui exec vitest run src/theme   # 皮肤相关 4 个文件
pnpm --filter @paideia/ui test                        # ui 包全量
```

## 外环（提交前一次）

```powershell
cd frontend
pnpm --filter @paideia/ui typecheck
pnpm --filter @paideia/app typecheck
pnpm --filter @paideia/ui-kit build
# 产物里必须能找到结构规则：grep data-skin= + data-slot= + outset + --decor2-image
```

e2e 说明：`apps/app/e2e/appearance.spec.ts`（WORK-079 写的五条）在本机仍无法执行——Playwright 的 `chromium_headless_shell-1248` 缺失、`scripts/test-db.sh` 拒绝远程测试库。本项因此**改用系统 Chrome + CDP 脚本做运行时与截图证据**（与 WORK-079 同一套办法），并在 `testing/report.md` 里写明这属于人工/脚本证据而非 Playwright 证据。

## 反向验证清单

| # | 怎么弄坏 | 期望 |
| --- | --- | --- |
| R1 | 在 `graphic.css` 末尾追加 `[data-skin='brutal'] [data-slot='card'] { position: relative; width: 999px; }` | `skin-structure.test.ts` 变红，并**按属性逐条**点名（position、width），信息里写清"布局必须在所有皮肤下一致，否则某些皮肤下功能会消失" |
| R2 | 删掉 `brutal` 的 `.dark` 整块 | 契约测试报「brutal 缺少暗色块」（同 WORK-079 已验证的机制，本项不重复跑） |
| R3 | 把 `--decor2-opacity` 默认值从 0 改成 0.2 | 默认主题会被染色；靠截图对比默认皮肤确认（`skin-contract.test.ts` 断言默认 opacity 为 0） |

## 截图与运行时证据

- dev server：`pnpm --filter @paideia/ui-kit dev --port 5185 --strictPort`（冷启动约 100 秒，等依赖预构建完成）。
- Chrome：`--headless=new --remote-debugging-port=9336 --remote-allow-origins=*`，脚本 `shot-graphic.mjs` 走 CDP。
- 驱动方式：**点真实皮肤卡片与真实明暗切换按钮**（不直接改 `data-skin` / `class`），这样同时验证 React 那条路。
- 采集：每个（皮肤 × 明暗）取首屏、控件区、表格区三张，共 18 张；同时 JSON 记录 `--radius-lg`、卡片 `border-radius`、卡片 `box-shadow`、`body::after` 的 `background-image`、`--background`、localStorage 里的 `paideia:skin`、console 错误。
- 产物目录：`F:\Paideia\.worktrees\WORK-080-shots\`（worktree 之外，不进 Git）。

## 人工核对

- 三个皮肤在亮/暗下：按钮点击区域没被撑破、输入框高度一致、表格没横向溢出、对话框标题没被裁、徽标文字对比够。
- 默认皮肤（`default`）与 WORK-079 上线时**看不出差别**。
- 打印预览：纸张语义、无装饰层。
