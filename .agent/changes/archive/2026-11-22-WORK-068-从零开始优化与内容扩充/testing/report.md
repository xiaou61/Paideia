---
artifact: test-report
work_id: WORK-068
work: 从零开始体验优化与内容扩充
status: passed
evidence: required
created: 2026-11-04
updated: 2026-11-04
verified_commit: 2cecb29d
verified_at: 2026-11-04 15:26 +0800
---

# 验证报告

## 验证环境

- 前端：本机 `pnpm`（Vite dev + Playwright）
- 后端/数据库：`scripts/test-db.sh` 独占库
- 说明：本报告收口历史证据；`AC-002/005/006/008` 无独立 e2e 的部分用人工核对，均标明。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 选读默认折叠、岗位总览、继续卡前提 | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/learn.spec.ts`（端口 18080/15173，`E2E_CHANNEL=chrome`） | 0 | passed | 9 通过 / 1 跳过（跳过项需推荐卡有 pick，测试库没有）；覆盖折叠、岗位总览、继续卡前提 |
| AC-002 轨道卡显示下一关 | `manual`（`LearnPage` 代码核对） | manual | passed | `learn-track-*` 底部渲染 `下一关：{title}`；无独立 e2e |
| AC-003 岗位总览行 | 同 AC-001 e2e | 0 | passed | `learn-role-progress` 出现/不出现双向断言；核心+进阶计数正确 |
| AC-004「继续上次」只在有进度时出现 | 同 AC-001 e2e | 0 | passed | 无已开始轨道时 `learn-continue` 不渲染 |
| AC-005 轨道页章节跳转 | `manual`（结构断言 + 手工检查） | manual | passed | 章节 chip 存在；滚动位置在 headless 不稳定，未写 e2e |
| AC-006 轨道页到期复习提示 | `manual`（按 entryId 过滤代码核对） | manual | passed | 复用 `/learning/reviews`，按本线 `entryId` 求交集 |
| AC-007 只看未通关 | 同 AC-001 e2e | 0 | passed | `hide-done` 切换后关卡数不增；与难度筛选叠加 |
| AC-008 条目页关卡定位与通关状态 | `manual`（`trackMap` 数据推导 + 手工检查） | manual | passed | 地图进入显示「第 N/M 关」；直接打开不显示来源 state |
| AC-009 练习块盘点与 quiz JSON 校验 | `node scripts/check-practice-coverage.mjs` | 0 | passed | 当前 **40 条线 / 352 关 / 498 练习块 / 纯阅读 0**；quiz JSON 全部可解析 |
| AC-010 内容线导入与岗位映射 | `import-course.mjs --dir ...` + ego | 0 | passed | `frontend-from-zero` created 13；data-analysis created 12、web-security 9、testing-from-zero 9；Python/Linux 各 created 5；线上 `/learn` 前端岗位显示核心 1 条/12 关 |
| 前端类型检查（全包） | `cd frontend && pnpm -r typecheck` | 0 | passed | 9 个包全部 Done，0 错 |
| 知识库 e2e（条目页改动） | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/knowledge.spec.ts` | 0 | passed | 10/10：分类→条目→正文、目录、上下篇、搜索、自评、浏览计数、AI 出题 |
| Python/Linux/Docker 实战 | ego：第 1 关沙箱与容器判题 | 0 | passed | Python 5 用例前两例 ok、坏行按预期失败；Linux 容器 1636ms 返回 644/期望 600；Docker 结构判定 997ms 返回 COPY 提示 |

## 未覆盖与已知限制

- **岗位总览行在测试库没有数据**：e2e 种子轨道全在选读里，核心+进阶为 0，测试按“没有可数关就不渲染”双向断言；真实数字已线上核验。
- **章节跳转没有 e2e**：断言滚动位置在 headless 下不稳定，只做了结构断言与手工检查。
- **entry-place 的“第 N/M 关”**：由 `trackMap` 数据推导，测试库能覆盖（learn 用例链），但“直接开链接不显示”这一分支没有单独用例。
- **Docker 实战只做结构判定**：判题容器无 docker（`no-docker`/`daemon-no`），不真构建；总览已写明边界。
