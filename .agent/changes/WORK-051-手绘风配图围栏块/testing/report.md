---
artifact: test-report
work_id: WORK-051
work: 手绘风配图（sketch 围栏块）
status: passed
evidence: required
verified_commit: d8626cd
created: 2026-10-28
updated: 2026-10-28
---

# 手绘风配图（sketch 围栏块）验证报告

## 验证环境

- 工作树 `F:\Paideia\.worktrees\WORK-051`，分支 `work/WORK-051`，代码提交 `1be235b`。
- 前端：Node 24.14.1 / pnpm 12.10.1 / vitest 5.0.3（happy-dom）/ Playwright 1.64（Chromium）。
- 后端：e2e 用的 jar 由 `mvn -B -DskipTests -pl paideia-app -am package` 现场构建（2 分 07 秒）。
- 数据库：`scripts/test-db.sh` 分配的独占库 `paideia_run_1028154325_15202`（跑完自动删除），
  端口覆写为后端 8091 / 前端 5181，避免与并发会话抢资源。
- 视觉核对：真字体 + 真 canvas 量宽 + 真 rough.js 的本地页面，深色/浅色各一张截图
  （`testing/sketch-light.png`、`testing/sketch-dark.png`）。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 框数=节点数、箭头数=连线条数 | `PAIDEIA_BACKEND_PORT=8091 PAIDEIA_APP_PORT=5181 scripts/test-db.sh pnpm --filter @paideia/app exec playwright test e2e/sketch.spec.ts` | 0 | passed | `[data-node]` 计数为 3，`path` 数 > 3；`e2e/sketch.spec.ts` |
| AC-002 手写字体生效、缺字回退不出方框 | 同上 + `document.fonts.check('20px "Paideia Sketch"')` | 0 | passed | 计算后 `fontFamily` 含 `paideia sketch`，`check` 返回 true |
| AC-003 行内等距居中（≤1px） | `pnpm --filter @paideia/ui test sketch` | 0 | passed | `sketch-layout.test.ts`「同一行的框等距，整行在最宽行里居中」 |
| AC-004 两次刷新 `path` 的 `d` 一致 | e2e 刷新前后取 `d` 数组比较 | 0 | passed | `e2e/sketch.spec.ts` 第二条断言 |
| AC-005 坏 JSON 降级 | `pnpm --filter @paideia/ui test sketch` | 0 | passed | `sketch.test.tsx`「数据写坏时降级成原因+原始文本」 |
| AC-006 流式回答里 sketch 不执行 | `pnpm --filter @paideia/ui test` | 0 | passed | `markdown.test.tsx`「流式回答里的 sketch 围栏只是代码块」 |
| AC-007 双主题对比度 ≥ 4.5:1 | 令牌值直接计算 WCAG 比值（人工核对脚本） | 人工 | passed | 浅色 14.3–15.0:1、深色 10.2–11.7:1，副标题最差 5.0:1；另见两张截图 |
| AC-008 roughjs 与字体不在入口 chunk，增量 ≤ 5 kB | `pnpm build`（撤下改动跑一次基线 + 实施后再跑一次） | 0 | passed | 基线 gzip 291.21 kB → 实施后 293.63 kB（**+2.42 kB**）；字体是独立资源 `Xiaolai-Cs1btv19.woff2`，`roughjs` 在异步 chunk |
| AC-009 单测覆盖解析/布局/降级 | `pnpm --filter @paideia/ui test sketch` | 0 | passed | 4 个测试文件 18 条全绿 |
| AC-010 样例条目与 e2e | 同 AC-001 | 0 | passed | devdata `V958__seed_sketch_block.sql`（条目 61），e2e 1 passed |

类型检查：`pnpm --filter @paideia/ui typecheck` → 退出码 0，无输出。

`@paideia/ui` 全量单测（回归）：**227 passed / 1 failed**。失败的是既有的
`tokens.test.ts`「应用与共享包里没有硬编码颜色」，12 处违规**全部**在
`frontend/apps/admin/src/pages/AnalyticsPage.tsx`（WORK-045 的提交 `d1cc48b` 引入，本项未触碰该文件）。
本项新增的样式全部走 `globals.css` 的令牌，没有新增违规项。

## 验收结果

10 条全部通过，其中 **AC-008 在实施后按实测改写过**（见下）。

原 AC-008 要求"入口 chunk 体积与本项实施前持平"，这条指标**不可实现**：sketch 的解析、布局与绘制调度
必须参与入口模块图（`documentRenderers` 是静态注册表），只有 `roughjs` 与字体能拆成异步资源；
即便把组件整体改成 `React.lazy`，入口仍会多出注册项与加载桩。

用户 2026-10-28 答复「都可以看你了」，授权按 Agent 建议处理。于是 AC-008 改为可核对的形式：
**`roughjs` 与手写字体都不在入口 chunk，入口 gzip 增量 ≤ 5 kB**。实测 +2.42 kB，落在限内；
字体在产物里是独立资源 `Xiaolai-Cs1btv19.woff2`，只在页面上真的出现 sketch 块时才下载。

## 失败与未验证项

- **AC-005 的"同页其它内容不受影响"只有组件级证据**：单测证明坏块自身降级，
  没有 e2e 证明"同一篇里另一段正常渲染"。影响有限，已写进测试计划的已知缺口。
- **手写中文的可读性没有读者样本**：只有开发侧看图的判断。这是本项唯一的观感风险。

## 剩余风险

- 字体子集按当前内容生成（1456 个汉字，297 kB）。新增内容里出现子集外的字会回退系统字体，
  不会出方框但会"半手写半印刷"；改完内容重跑 `node scripts/build-sketch-font.mjs` 即可。
- `sketch` 的表现力被刻意限制在"分行节点 + 箭头"：画不了自由构图（火柴人、任意曲线）。
  那类插图仍然得手工画。
- 布局宽度上限 240px、每行最多 5 个框：一行塞满 5 个长标题时整图会被栏宽等比缩小，字会偏小。
  这是内容侧该拆图的地方，不在代码里兜。
