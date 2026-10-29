---
artifact: test-report
work_id: WORK-047
work: 岗位地图与 AI 推荐
status: passed
evidence: required
workflow: full
created: 2026-10-28
updated: 2026-10-28
verified_commit: 4686a61
accepted_by: xiaou61
accepted_at: 2026-10-29 13:21:53 +0800
---

# WORK-047 验证报告

**结论先说**：**9 条验收标准里 7 条有实测证据，2 条只有实现与单测保证**（第 6 条缓存命中、
第 9 条"岗位不挡访问"）。线上已部署、22 条轨道的一句话说明已重写并导入。

实施过程中撞到一条既有缺陷并顺手补了：`/learn` 的 e2e 长期依赖共用库里别人导入的轨道数据，
在独占新库上**六条用例全超时**——那不是产品坏了，是那六条一直没被真正执行。

## 验证环境

| 项 | 值 |
| --- | --- |
| 工作区 | 独立工作树 `work-047-roles`，基于 `origin/master` |
| 后端 | JDK 25；`scripts/test-db.sh` 每次独占库 `paideia_run_<时间>_<pid>` |
| 前端 | vitest 5.0.3；Playwright，端口 8093 / 5193（与并发会话错开） |
| 线上 | `codenest-online`，部署 112.4 秒（含前端重建） |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 后端岗位的三段与核心成员 | `pnpm --filter @paideia/knowledge test` | 0 | passed | `recommend.test.ts`：`tierOf('backend','spring')=core`；`java-from-zero`/`spring`/`redis` 都在核心表里。页面上三段由同一次计算渲染 |
| AC-002 切岗位后段位重排 | 同上 + `playwright test e2e/learn.spec.ts` | 0 | passed | 单测断言同一 slug 在后端与数据岗位落在不同段（如 `algorithms`：后端选读、算法核心）；e2e 断言 `aria-pressed` 随点击切换、三段始终在 |
| AC-003 学完一条后推荐递补 | `pnpm --filter @paideia/knowledge test` | 0 | passed | "全部通关的线不参与推荐"与"前三条没有核心时替换最低分那条"两条用例；另有一条断言"没有关卡的线不参与" |
| AC-004 推荐理由带真实数字 | `playwright test e2e/learn.spec.ts` | 0 | passed | 理由节点的文本逐条断言 `/\d/`（必须出现数字）；单测里 `ruleReason` 断言包含 `2/6` 与下一关标题 |
| AC-005 AI 不可用时降级 | `playwright test e2e/learn.spec.ts` | 0 | passed | 本地后端**没有配 AI Key**：`credentials.access(0)` 为空 → 端点返回空理由 → 页面显示规则句，没有错误横幅。这就是降级路径本身 |
| AC-006 同日同岗位不重复调用 | 阅读实现 | 人工 | passed | `localStorage` 键 `paideia:advice:<role>:<自然日>`，命中直接 return，不发请求；「换一批」先清键再加 nonce。**没有自动化断言"第二次没发请求"**，如实记在这里 |
| AC-007 打分函数的四种边界 | `pnpm --filter @paideia/knowledge test` | 0 | passed | 零进度 / 全部通过 / 岗位无内容 / 岗位为空（空列表）四个用例，另有"未标岗位落选读""同输入两次结果一致""时间串解析不了不抛错" |
| AC-008 窄屏不横向溢出 | `playwright test e2e/learn.spec.ts` | 0 | passed | 360px 视口下 `/learn` 与关卡地图各量一次 `scrollWidth - clientWidth ≤ 1` |
| AC-009 岗位不挡访问 | 阅读实现 | 人工 | passed | 岗位只参与前端分段与打分，没有任何接口、路由或后端断言读 `target_role`；未新增任何门禁代码 |

## 其它验证

- **后端内环**：切片 352 条 0 失败（14 条按环境跳过）+ 模块边界与迁移 9 条 0 失败。
- **全接口自查**：`ApiWalkthroughTest` 1 条 0 失败（新端点用 `bestEffort` 打到，参数非法时 400 不影响结论）。
- **e2e**：`learn.spec.ts` **8 passed**、`navigation.spec.ts` **6 passed**。
- **线上内容**：23 条线（20 阅读 + 3 课程）重新导入，`learning_tracks.summary` 为空的是 **0** 条。

## 未覆盖与已知限制

| 项 | 原因 | 影响 |
| --- | --- | --- |
| 缓存命中"没有第二次请求" | 需要拦截网络或读 storage 事件 | 逻辑简单、失败也只多花一次积分，风险低 |
| AI 理由的真实上游输出 | 本地与线上都没给这个端点配 Key | 提示词与解析只有单测覆盖（`parse` 的容错路径未测）。**首次真连可能拿到不满意的措辞** |
| 前端岗位的内容 | 22 条线里没有任何前端内容 | 选中时显示"内容建设中，先按后端推荐"，这是如实暴露缺口 |
| 推荐不含"最近学过"衰减 | 轨道列表接口不返回最后学习时间（原计划 T-04） | 打分里 `recent` 恒为 0；字段补上后自动生效 |
| 岗位映射的人工维护 | 映射在代码里 | 新增轨道默认落"选读"，不会消失但也不会被推荐到前面 |

## 与设计的一致性

设计里的五条取舍都按原样落地：映射在代码、打分在客户端、AI 只写理由、推荐卡在页首、岗位复用
`target_role`。设计之外多改了两处：

1. **`scoreTracks` 对没有内容支撑的岗位直接返回空**。原设计只说了兜底到 `optional`，
   但那样前端岗位照样能凑出三条"选读"——那不是推荐，是随机。
2. **补了轨道种子 `V957`**。这是 e2e 能跑起来的前提，见下。

## 不在本项范围的发现（已修）

**`/learn` 的 e2e 一直在等 `learn-track-*`，但库里从来没有轨道种子。** 它此前在共用库上能过，
是因为别的会话导入过轨道；换成本轮这种独占新库，六条用例全部 15 秒超时。补了
`db/devdata/V957__seed_learning_tracks.sql`（一条理论轨道、三关）之后全部恢复。

补种子时踩到一个只有真跑才暴露的坑：devdata 的 `V957` 排在后续**时间戳迁移之前**执行，
所以它只能用 V22 时代就有的列——写了 `mode` / `level` 直接 `Unknown column 'mode'`。
