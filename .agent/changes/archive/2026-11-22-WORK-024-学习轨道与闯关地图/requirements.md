---
artifact: requirements
work_id: WORK-024
work: 学习轨道与闯关地图
status: approved
mode: strict
mode_reason: 新增两张表与三个端点（数据与接口面）；推翻 WORK-020 的「学习线=分类标记」裁决；导入脚本写内容（内容面）
workflow: full
related_to: [WORK-004, WORK-020, WORK-021, WORK-025]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 13:42:23 +0800
approver_role: CTO
---

# 学习轨道与闯关地图需求

> **本件是补账**。功能已于 2026-10-20 实现并上线（`af21345`、`0c1aa68`、`bb4c858` 三笔），但当时的编号被 `WORK-025` 占用（状态脚本报 `WORK-024`，而工作区在飞的八股文已经把 025 用掉），学习轨道这一路直接用 024 落了代码却没建工件目录。2026-10-21 核对时发现 `.agent/changes/` 下从来没有 `WORK-024-*`，`validate` 因此对这项完全静默——**代码在跑、治理上看不见**。
>
> 本件按已实现的行为反向记录，不改代码。签署后 `proposal` / `design` / `tasks` 三件才能生效。

## 已确认事实

| 项 | 事实 | 依据 |
| --- | --- | --- |
| 线上轨道 | 4 条：Java 从零开始（24 关）、Python 从零开始（13 关）、源码解读（1 关）、计算机网络（2 关） | 2026-10-21 `GET /api/v1/learn/tracks` 实测 |
| 表 | `learning_tracks`、`learning_track_stages`，迁移 `V22` | `V22__create_learning_tracks.sql` |
| 难度列 | `learning_track_stages.level`，迁移 `V23`，取值 beginner / intermediate / advanced | `V23__add_stage_level.sql` |
| 正文载体 | 仍是 `knowledge_entries`——关卡不新造一张正文表 | `V22` 注释、`TrackService` 类注释 |
| 接口 | `GET /api/v1/learn/tracks`、`GET /api/v1/learn/tracks/{slug}`、`POST /api/v1/knowledge/admin/tracks`（upsert） | `LearnTrackController`、`KnowledgeAdminController` |
| 前端 | `/learn`（轨道列表）、`/learn/:slug`（关卡地图）；首页 `home-entry-learn` 卡与页头导航都有入口 | `router.tsx`、`LearnPage.tsx`、`TrackMapPage.tsx`、`HomePage.tsx` |
| 课程源 | `content/courses/java/` 24 个 md、`content/courses/python/`；`scripts/verify_course.py` 校验结构与示例可执行 | 仓库目录与脚本 |
| 知识库排除 | `line_kind` 非空的分类不出现在学习者端知识库浏览里 | `CategoryService` 类注释 |
| e2e | `learn.spec.ts` 6 条：首页入口、进地图、状态可辨、点进正文、难度筛选、360px 不溢出 | `frontend/apps/app/e2e/learn.spec.ts` |
| 线上验证 | 2026-10-21 学习者端 e2e 打线上 64 passed / 0 failed，其中含上述 6 条 | 本轮实测 |

## 用户决定

| 编号 | 决定 | 日期 |
| --- | --- | --- |
| D-01 | **「从零开始」不要做成知识库的一部分**，要闯关/图谱那种形态，关卡之间一个一个关联 | 2026-10-20 |
| D-02 | 内容太少、要非常详细，**每篇至少 1000 字** | 2026-10-20 |
| D-03 | 要体现难度：哪些适合初学者、哪些适合有基础的，**可按难度筛选** | 2026-10-20 |

## 待确认问题

无。本项的取材于已上线代码与既有更新历史，三处实现选择（I-01/I-03/I-04）的取舍依据都能在 `V22`/`V23` 迁移注释与 `TrackService` 类注释里找到原始记录。

## 目标

让「从零开始」成为一条**能一路走完的路**，而不是知识库列表里的一个筛选条件：

1. 学习者从独立入口进入，看到自己在一整条轨道上的进度；
2. 关卡有顺序、有章节分组、有前置依赖，地图上能一眼看出「该点哪一格」；
3. 每条轨道带难度，能按难度挑；
4. 正文不重写——复用知识库的渲染、练习与进度，只新增导航这一层。

## 实现选择

| 编号 | 选择 | 理由 |
| --- | --- | --- |
| I-01 | 轨道独立成表，**推翻** WORK-020 的「学习线=分类标记」 | 分类标记表达不出顺序、章节与前置依赖；地图的「锁住/可进入/已通关」需要真实边 |
| I-02 | 关卡正文复用 `knowledge_entries` | markdown 渲染、quiz/code-challenge 块、`learning_progress` 的通过判定与复习排期全部现成，自建等于四件事各写一遍 |
| I-03 | 锁只看**直接前置**，不递归 | 递归会让"跳着学"不可能；顺序已经用连线表达 |
| I-04 | 难度是**关卡属性**不是轨道属性，用字符串不用枚举 | 同一条轨道里既有入门也有深入；档位以后可能细分（加 expert），改表比重建枚举便宜 |
| I-05 | 地图连线用纯 CSS，不引图库 | 只是相邻节点的折线，为它加一个画图依赖不划算 |
| I-06 | 轨道只有一个写入口 `POST /knowledge/admin/tracks`，且只做 upsert | 轨道与关卡是内容的一部分，随 markdown 进版本库；管理端维护界面本项不做 |

## 需求

- **REQ-001**：学习者端有独立的「从零开始」入口，列出全部轨道及各自进度；空轨道也列出（显示"待更新"）。
- **REQ-002**：点轨道进入关卡地图，按章节分组，每格可见 `done` / `current` / `locked` 三种状态。
- **REQ-003**：有前置且前置未通关的关卡不可点；看得到的关卡能点到正文。
- **REQ-004**：每关带难度（入门 / 进阶 / 深入），地图上可见且可按难度筛选。
- **REQ-005**：课程内容随 markdown 进版本库，导入用脚本 upsert，不靠人在管理端手点。
- **REQ-006**：属于学习轨道的分类不再出现在知识库浏览里，两个入口不混。
- **REQ-007**：窄屏（360px）下轨道列表与关卡地图不横向溢出。

## 非目标

- 不做管理端的轨道/关卡图形化维护界面（只有 upsert 端点，供脚本用）。
- 不做关卡内的答题判分新机制——复用既有 `learning_progress` 与 quiz 块。
- 不做轨道之间的依赖（只有轨道内部的关卡前置）。

## 约束

- 迁移只做增量：`V22` 已上线，改它会让 Flyway 校验失败、整应用起不来（2026-10-20 真的踩过，改为新增 `V23`）。
- 关卡状态一律按调用者本人计算，路径里没有 userId。

## 风险

- **关卡数量靠内容支撑**：内容少的时候地图看起来空。这是内容问题不是结构问题，但会直接影响观感。
- **难度靠章节推默认值**：没有显式 `level` 的旧课程按章节推，新课程要自己写 frontmatter。

## 验收标准

- **AC-001**：学习者端从首页卡与页头导航都能进 `/learn`，列表出现全部轨道。
- **AC-002**：点任一轨道进关卡地图，地图按章节分组且有至少一个 `current` 或 `done`。
- **AC-003**：`locked` 的关卡不可点；能点的关卡点进去看到正文。
- **AC-004**：地图上能看到难度徽章，按难度筛选后列表随之变化。
- **AC-005**：知识库浏览里看不到学习轨道下的分类。
- **AC-006**：360px 下轨道列表与关卡地图无横向溢出。
- **AC-007**：`POST /api/v1/knowledge/admin/tracks` 对同一 slug 重复调用不产生重复轨道（upsert）。
