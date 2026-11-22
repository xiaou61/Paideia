---
artifact: tasks
work_id: WORK-024
work: 学习轨道与闯关地图
status: approved
depends_on: [design]
related_to: [WORK-004, WORK-020, WORK-021]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 13:42:23 +0800
approver_role: CTO
---

# 任务 · 学习轨道与闯关地图

> 补账件。以下任务均已于 2026-10-20 完成并上线，本件按实际交付反向记录，便于后续变更与验收对照。

## 任务

### T-01 | done | 迁移 V22：轨道与关卡两张表

`learning_tracks` + `learning_track_stages`，含 `uk_learning_tracks_slug`、`uk_learning_stage`、`uk_learning_stage_entry`。

**验收**：空库跑 Flyway 通过；`SHOW CREATE TABLE` 见到三个唯一键。

### T-02 | done | `TrackMapper`：upsert 与查询

`upsertTrack` / `upsertStage`（`ON DUPLICATE KEY UPDATE`）、`findAll`、`findBySlug`、`findStages`。

**验收**：同 slug 重复 upsert 不产生第二条轨道（AC-007）。

### T-03 | done | `TrackService`：列表投影与地图状态

列表带进度与 `nextEntryId`；地图按章节分组、算 `done/current/locked`。

**验收**：状态由 `learning_progress` 派生；有前置且前置未通过时为 `locked`（AC-003）。

### T-04 | done | `LearnTrackController`：两个读端点

`GET /api/v1/learn/tracks`、`GET /api/v1/learn/tracks/{slug}`，`@ApiAccess(AUTHENTICATED)`，归属取自令牌。

**验收**：未登录 401；不存在 slug 返回 404（`没有这条学习轨道`）；`ApiSurfaceTest` 无未声明端点。

### T-05 | done | 写入口 `POST /api/v1/knowledge/admin/tracks`

仅 upsert；轨道 + 关卡一次提交。

**验收**：ADMIN 可写、学习者 403；重复调用幂等。

### T-06 | done | 知识库浏览排除学习轨道分类

`CategoryService` 过滤 `line_kind` 非空的分类。

**验收**：学习者端知识库分类树里看不到 `java-from-zero` 等（AC-005）。

### T-07 | done | 前端 `/learn` 轨道列表

`LearnPage.tsx` + 路由；空轨道也列；显示进度与下一篇。

**验收**：首页 `home-entry-learn` 与页头导航都能进（AC-001）。

### T-08 | done | 前端 `/learn/:slug` 关卡地图

`TrackMapPage.tsx`：章节分组、三状态、连线（CSS）、难度徽章。

**验收**：`locked` 不可点，`current` 点进正文（AC-002、AC-003）。

### T-09 | done | 课程内容扩充到 24 关

Java 课程从 13 关扩到 24 关，每篇 5400–7900 字，7 章节。

**验收**：`verify_course.py` 37 文件通过（示例真执行、输出逐字一致）。

### T-10 | done | 迁移 V23 + 难度分级

`level` 列 + 索引；脚本按章节推默认值；地图显示徽章与筛选。

**验收**：线上分布 5 入门 / 11 进阶 / 8 深入；地图筛选生效（AC-004）。

### T-11 | done | e2e `learn.spec.ts`

6 条：首页入口、进地图、状态可辨、点进正文、难度筛选、360px 不溢出。

**验收**：本地与线上均通过（线上 2026-10-21 实测 6/6）。
