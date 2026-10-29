---
artifact: test-report
work_id: WORK-024
work: 学习轨道与闯关地图
status: passed
evidence: required
workflow: full
created: 2026-10-21
updated: 2026-10-21
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:02 +0800
---

# 学习轨道与闯关地图验证报告

> 补账件。功能于 2026-10-20 实现并上线（`af21345`、`0c1aa68`、`bb4c858`），本报告汇总当时的验收记录与 2026-10-21 的线上复核。

## 验证环境

| 项 | 值 |
| --- | --- |
| 本地 | JDK 25 + `scripts/test-db.sh`（独占 `paideia_run_*` 库） |
| 线上 | `http://36.212.5.130:8081`，账号见被忽略的 `backend/config/application-local.yml` |
| e2e | `frontend/apps/app/e2e/learn.spec.ts`，`E2E_BASE_URL` 指向线上 |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 首页与导航都能进 `/learn` | `E2E_BASE_URL=… pnpm --filter @paideia/app test:e2e -- learn.spec.ts` | 0 | passed | 「首页入口进得去，轨道卡由服务端渲染」通过；`home-entry-learn` 卡存在 |
| AC-002 点轨道进地图、有可辨状态 | 同上 | 0 | passed | 「点轨道进入关卡地图，地图里有关卡」「关卡状态可辨：至少有 current 或 done，locked 不可点」通过 |
| AC-003 locked 不可点、可点的进得去正文 | 同上 | 0 | passed | 「从可进入的关卡点到正文」通过 |
| AC-004 难度可见且能筛选 | 同上 | 0 | passed | 「难度可见且能筛选：入门与深入分得开」通过；线上分布实测 5 入门 / 11 进阶 / 8 深入（2026-10-20 记录） |
| AC-005 知识库浏览排除轨道分类 | 线上 `GET /api/v1/knowledge/categories` | 0 | passed | 2026-10-21 实测分类树里无 `java-from-zero` / 源码解读等 `lineKind` 非空项 |
| AC-006 360px 不横向溢出 | 同上 e2e | 0 | passed | 「轨道与地图在 360px 下不横向溢出」通过 |
| AC-007 同 slug 重复 upsert 不产生重复 | `POST /api/v1/knowledge/admin/tracks` 连打两次 | 0 | passed | `uk_learning_tracks_slug` 保证；`ApiWalkthroughTest` 走查该端点时同样断言 200 |
| 内容结构 | `python scripts/verify_course.py` | 0 | passed | 2026-10-20 记录：37 文件通过，示例真执行、输出逐字一致 |
| 线上轨道可达 | `GET /api/v1/learn/tracks`（带令牌） | 0 | passed | 2026-10-21 实测 4 条：Python 0/13、Java 0/24、源码解读 0/1、计算机网络 0/2 |
| 线上关卡地图 | `GET /api/v1/learn/tracks/{slug}` | 0 | passed | 2026-10-21 实测返回章节分组与 `done/current/locked` |
| 接口覆盖 | `mvn -pl paideia-app -Dtest=ApiWalkthroughTest test` | 0 | passed | 2026-10-21：自查补上 `POST /knowledge/admin/tracks` 与两个 `learn/tracks` 端点，8 条全绿 |
| 鉴权 | 未带令牌请求两个读端点 | 0 | passed | 均返回 401 |

## 线上端到端

2026-10-21 学习者端 e2e 打线上：**64 passed / 0 failed / 21 skipped**（skipped 是按 `REMOTE` 跳过的、依赖"余额 0"的用例）。其中 `learn.spec.ts` 6 条全过。

## 失败与未验证项

| 项 | 原因 | 状态 |
| --- | --- | --- |
| 非相邻关卡依赖的连线 | 当前内容里没有这种依赖，CSS 折线只画相邻 | 未实现（设计已声明为非目标） |
| 管理端轨道维护界面 | 本项明确不做，只有 upsert 端点 | 未实现（设计已声明为非目标） |
| 难度默认值推断 | 按章节推，新章节下未写 `level` 会落到 `beginner` | 已知缺口，写 frontmatter 显式声明 |

## 结论

**通过。** `learn.spec.ts` 6 条本地与线上均过；两个读端点在线上返回真实数据（4 条轨道、Java 24 关）；知识库浏览已排除轨道分类；接口覆盖已纳入全接口自查。
