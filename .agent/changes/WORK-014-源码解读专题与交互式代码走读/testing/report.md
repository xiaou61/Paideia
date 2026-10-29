---
artifact: test-report
work_id: WORK-014
work: 源码解读专题与交互式代码走读
status: passed
evidence: required
verified_commit: 30f96fe
verified_at: 2026-10-18
accepted_by: xiaou61
accepted_at: 2026-10-29 13:17:43 +0800
---

# 源码解读专题验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 机器 | 本机 Windows / Git Bash，Node 24、pnpm 12 |
| 被验提交 | 本轮实现的提交（本报告与它同批提交） |
| 数据库 | 服务器 MySQL，公网直连 `36.212.5.130:3306`，账号 `paideia_test`（只授权 `paideia_test.*`） |
| 前置 | `PAIDEIA_TEST_DB_PASSWORD` 取自 `backend/config/remote-db.local.env` 中被注释的那行示例值，不是文件末尾未注释的占位值 |
| 内容 | `db/devdata/V953__seed_source_reading.sql`；代码取自 `facebook/react@d75b0697`（MIT），行号逐条对照过原文件 |

## 结论：**通过**

五种块、地图与走读双向联动、预测式步骤、状态快照、三种练习、小测、本地进度与刷新恢复、AI 预填、首页入口、窄屏与语法高亮都已落地并通过测试。三处机制值得单独记一句：

1. **Shiki 必须懒加载，且只在有围栏时才下载**。静态 `import { code } from '@streamdown/code'` 让主 chunk 从 736 kB 涨到 **951 kB**；改成 `useSyncExternalStore` + 动态 import 后主包回到 736.41 kB（+0.05 kB），Shiki 独立成 196 kB chunk。
2. **进度要"读一次当默认值"，不能"挂载后 setState"**。后者会先渲染第 0 步并立刻把 0 写进存储，等于把上次位置抹掉——这条第一次实现时是真红的。
3. **地图与走读共享页级 context**，点节点与切步骤都能驱动对方；节点用原生 `<button>` 而不是 SVG 图元，键盘与读屏天然可用。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 路径状态与跳转 | `pnpm --filter @paideia/ui test` | 0 | passed | `LearningPath` 三条：全未开始、读其他条目进度标已完成、点击回调带 entryId |
| AC-002 走读切步 | `pnpm --filter @paideia/ui test`、`pnpm --filter @paideia/app test:e2e` | 0 | passed | 组件断言行号 10/11 与解释同时切换；e2e「走读：切步同时改变高亮行与解释」 |
| AC-003 地图双向联动 | 同上 | 0 | passed | 组件：点节点写 activeStep、entryId 走跳转；e2e 两个方向都断言 `data-active` 由 true→false |
| AC-004 预测先于解释 | `pnpm --filter @paideia/ui test` | 0 | passed | 未答时 `code-tour-explain` 不存在；作答后出现；答错显示「正确：…」 |
| AC-005 状态快照 | 同上 | 0 | passed | 第 1 步无 `code-tour-state`；第 2 步有变量、调用栈、输出 |
| AC-006 三种练习判定 | `pnpm --filter @paideia/ui test`、`app e2e` | 0 | passed | 填空/排序/找 bug 各含对与错路径；e2e 排序与找 bug 判到「全对」 |
| AC-007 小测得分 | 同上 | 0 | passed | 逐题即时反馈；`quiz-score` 显示 3/3；已答题禁用（不能改到对为止） |
| AC-008 进度刷新恢复 | `pnpm --filter @paideia/ui test`、`app e2e` | 0 | passed | 组件：从本地读回第 2 步；e2e：刷新后仍停在第 3 步 |
| AC-009 继续学习 | `pnpm --filter @paideia/knowledge test` | 0 | passed | `recentReading` 按时间倒序、条数上限、标题缺省兜底三条 |
| AC-010 预填不发送 | `pnpm --filter @paideia/assistant test`、`app e2e` | 0 | passed | 单测断言 `sendMessage` 零调用；e2e 监听消息 POST 计数为 0 |
| AC-011 助手带当前条目 | 复用 WORK-006 既有链路 | 0 | passed | 请求已携带 `entryBody`（既有集成测试覆盖），本项只加预填 |
| AC-012 坏块不炸页 | `pnpm --filter @paideia/ui test` | 0 | passed | 五种块各自的 fallback 用例；错误边界只降级自身 |
| AC-013 出处与 permalink | 同上 | 0 | passed | permalink 精确等于 `...#L10-L11`；页脚含 `repo@commit`、行范围、MIT |
| AC-014 语法高亮 | `app e2e`、`pnpm --filter @paideia/app build` | 0 | passed | e2e 断言 `--sdm-c` token span > 10（懒加载到达后）；主包 736.36→736.41 kB，Shiki 独立 196 kB chunk |
| AC-015 预览一致 | `pnpm --filter @paideia/admin test:e2e` | 0 | passed | 预览里 `code-tour`、`quiz` 可见；`block-ask` 计数 0（预览无问答能力） |
| AC-016 首页入口 | `app e2e` | 0 | passed | `home-entry-source-reading` 可见 |
| AC-017 零外部请求 | `app e2e` | 0 | passed | 监听 `github.com` / `githubusercontent.com`，为空 |
| AC-018 窄屏与可访问 | `app e2e` | 0 | passed | 360px 下文档无横向溢出（≤1px）；代码与解释上下堆叠；键盘左右切步、文字化对错 |
| AC-019 既有行为不变 | `pnpm -r test`、两端 e2e | 0 | passed | 单测 227 passed；学习者端 44 passed；管理端 9 passed |
| AC-020 样板内容成立 | 种子 + 人工核对 | 人工 | passed | 4 步走读（1 预测 + 1 状态）、2 道练习、3 题小测、3 个误解、3 个面试追问、许可证已核 |

## 实际跑过的数

- 前端单测 `pnpm -r test`：**227 passed**（ui 135 / knowledge 28 / assistant 25 / core 22 / auth 13 / desktop 4）
- 学习者端 e2e：**44 passed**（`source-reading.spec.ts` 9 条：切步、联动、练习、进度、问 AI、无 GitHub、入口、窄屏、高亮）
- 管理端 e2e：**9 passed**（含预览用例）
- 构建：`pnpm --filter @paideia/app build` 成功；主 chunk 736.41 kB / gzip 230.28 kB
- 类型检查：ui / knowledge / assistant / app / admin / ui-kit 全 0

## 踩到的问题（真问题，防复发）

1. **Shiki 静态 import 会把主包撑大 215 kB**。改用动态 import + `useSyncExternalStore`，并在 `markdown` 里用正则判断有没有围栏，没有就不下载。
2. **进度只写不读**：e2e「刷新后回到上次那一步」第一版直接红。修成"读一次当默认值"，避免挂载瞬间把 0 写回去。
3. **360px 页头本来就溢出 70px**（用户名 + 主题开关被推出视口），是本项之前就存在的缺陷。窄屏 e2e 一验就露出来，顺手修：`<sm` 隐藏用户名文本。
4. **种子 SQL 两个坑**：`CONCAT(` 少了右括号（报错位置指向毫不相关的行 168），以及 MySQL 字面量里 `\n` 会变成真换行——JSON 需要 `\\n`。
5. **`remote-db.local.env` 里有两行同名变量**：未注释的那行是占位值（长度 4），真正可用的在被注释的示例行（长度 32）。直接 `source` 会拿到错口令，表现成 `Access denied`。
6. **Flyway 的失败记录会毒化后续启动**：必须先删掉 `flyway_schema_history` 里那一行再重跑。
7. **`@paideia/ui` 的 vitest 没有全局 cleanup**：两条用例之间的 DOM 会串，报 "Found multiple elements"。本包约定是每个测试文件自己 `afterEach(cleanup)`。
8. **streamdown 的着色不是内联 `color:`**，而是 `--sdm-c` 自定义属性；按 `span[style*="color"]` 找 token 会永远得到 0。

## 未覆盖（如实登记）

- **线上部署未做**：本轮只在本机连测试库验证，没有跑 `scripts/release.ps1`，也没有在线上实例验过。
- 读屏软件实测（只保证了 `aria-label`、`sr-only` 文本与文字化对错）。
- 深色主题下的高亮对比度（未做像素级/对比度测量）。
- 地图在节点很多时的视觉重叠（只断言坐标计算与联动）。
