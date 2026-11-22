---
artifact: testing_plan
work_id: WORK-079
work: 全站换肤与多皮肤体系
status: approved
updated: 2026-11-13
---

# 验证计划

## 内环（每次改动都跑，约 3 秒）

| 检查 | 命令 | 判据 |
| --- | --- | --- |
| 皮肤契约与对比度 | `pnpm --filter @paideia/ui exec vitest run src/theme` | 全绿；新增皮肤自动进入对比度遍历 |
| UI 包类型 | `pnpm --filter @paideia/ui typecheck` | 0 error |
| 学习者端类型 | `pnpm --filter @paideia/app typecheck` | 0 error |

## 外环（改到页面/样式时）

| 检查 | 命令 | 判据 |
| --- | --- | --- |
| 生产构建能吞下新 CSS | `pnpm --filter @paideia/ui-kit build` | 构建成功；`dist/assets/*.css` 里含 `data-skin=paper` 一类的皮肤块 |
| 外观页 e2e | `bash ../scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/appearance.spec.ts` | 全绿 |
| 既有主题用例没被破坏 | `pnpm --filter @paideia/ui-kit test:e2e`、`pnpm --filter @paideia/app test:e2e e2e/markdown-render.spec.ts` | 全绿 |

## 反向验证（每个守卫断言都要做一次）

| 断言 | 怎么弄坏 | 期望 |
| --- | --- | --- |
| 皮肤亮暗令牌一致 | 删掉某个皮肤的 `.dark` 块 | 红，信息点名皮肤 |
| 只覆盖已知令牌 | 把 `--muted-foreground` 改成 `--muteds-foreground` | 红，信息点名令牌 |
| 注册表与 CSS 一致 | CSS 里加一个未登记的 `[data-skin="ghost"]` | 红，信息列出 ghost |
| 装饰层默认关闭 | 把 `--decor-opacity` 默认值改成 `0.2` | 红 |

## 人工核对（自动化覆盖不到的观感）

- 三个极端皮肤（`contrast`、`terminal`、`glass`）在首页 / 知识库 / 外观页各看一次：无不可读文字、无错位、无横向滚动。
- 换肤后 `/graph` 节点色与 `/practice/sql` 编辑器配色跟随（TASK-011 完成后）。
