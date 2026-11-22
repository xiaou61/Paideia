---
artifact: design
work_id: WORK-024
work: 学习轨道与闯关地图
status: approved
depends_on: [proposal]
related_to: [WORK-004, WORK-020, WORK-021]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 13:42:23 +0800
approver_role: CTO
---

# 设计 · 学习轨道与闯关地图

> 补账件，反向记录已实现的设计。

## 数据模型

### `learning_tracks`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED PK | |
| `slug` | VARCHAR(64) NOT NULL | 路径用的稳定标识（`/learn/java`），`uk_learning_tracks_slug` |
| `name` | VARCHAR(128) NOT NULL | |
| `summary` | VARCHAR(500) NULL | 一句话说清讲什么 |
| `kind` | VARCHAR(16) NOT NULL DEFAULT 'course' | `course`（从零学）/ `reading`（读源码），与既有 `line_kind` 同名 |
| `sort_order` | INT NOT NULL DEFAULT 100 | |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 应用写 UTC，不用 `DEFAULT CURRENT_TIMESTAMP` |

索引：`uk_learning_tracks_slug (slug)`、`idx_learning_tracks_kind (kind, sort_order)`。

### `learning_track_stages`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED PK | |
| `track_id` | BIGINT UNSIGNED NOT NULL | |
| `entry_id` | BIGINT UNSIGNED NOT NULL | 正文与通过判定都在这条 entry 上 |
| `seq` | INT NOT NULL | 轨道内顺序，从 1 开始；`uk_learning_stage (track_id, seq)` |
| `chapter` | VARCHAR(64) NOT NULL DEFAULT '' | 地图分组，空串表示不分章 |
| `requires_entry_id` | BIGINT UNSIGNED NULL | **前置关卡的入口边**；为空表示第一关 |
| `level` | VARCHAR(16) NOT NULL DEFAULT 'beginner' | `V23` 新增：beginner / intermediate / advanced |
| `created_at` | DATETIME(3) NOT NULL | |

约束与索引：`uk_learning_stage (track_id, seq)`、`uk_learning_stage_entry (track_id, entry_id)`——同一轨道里同一个条目只能出现一次，否则进度会指向两关；`idx_learning_stage_level (track_id, level)`（`V23`）。

### 为什么正文不搬家

`knowledge_entries` 已经提供 markdown 渲染、`quiz` / `code-challenge` / `learning-path` 围栏块、`learning_progress` 的通过判定与复习排期（`reviewStage` / `reviewDueAt`）。自建正文体系等于把这四件事各写一遍。所以本项**只加导航层**，`learning_track_stages.entry_id` 指向既有条目。

## 状态计算

`TrackService.map()` 两遍：

1. 先算每一关是否 `passed`（查 `learning_progress`，`status = passed`）；
2. 再判 `locked`：`requiresEntryId != null && !passed(requiresEntryId)`；
3. `status = done ? "done" : locked ? "locked" : "current"`。

**只看直接前置，不递归**——地图上用连线表达了顺序，递归会让"跳着学"变成不可能。

状态不落冗余列：每次读都从 `learning_progress` 派生，避免两处真相。

## 接口

| 方法 | 路径 | 访问 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/learn/tracks` | AUTHENTICATED | 全部轨道 + 我的进度。空轨道也返回 |
| GET | `/api/v1/learn/tracks/{slug}` | AUTHENTICATED | 关卡地图；按章节分组；不返回正文 |
| POST | `/api/v1/knowledge/admin/tracks` | ADMIN | 轨道与关卡的 **upsert**，仅供导入脚本 |

**为什么单独一个前缀 `/api/v1/learn`**：它不是知识库的视图。知识库是"挑一篇读"，这里是"沿着一条轨道往下走"，入口、路径与响应形状都另起一套。

**为什么只有一个写入口且只做 upsert**：轨道与关卡是内容的一部分，随 markdown 进版本库。管理端维护界面本项不做。

## 前端

| 页面 | 路径 | 要点 |
| --- | --- | --- |
| `LearnPage` | `/learn` | 只列轨道；空轨道也列出（"待更新"）；进度条 |
| `TrackMapPage` | `/learn/:slug` | 按章节分组；三种状态；难度徽章与筛选；连线用 CSS |
| 入口 | 首页 `home-entry-learn` 卡 + 页头导航 | 两处都能进 |

知识库浏览排除 `line_kind` 非空的分类（`CategoryService`），两个入口不混。

## 内容与导入

- 课程源：`content/courses/java/`（24 篇，每篇 5400–7900 字）、`content/courses/python/`。
- frontmatter：`title` / `order` / `chapter` / `level` / `prerequisite` / `category` / `minutes` / `status`。
- 脚本：`add-course-chapters.py`（补章节）、`add-course-levels.py`（按章节推难度默认值）、`reorder-java-course.py`（重排）、`verify_course.py`（结构与示例可执行校验，`<!-- run -->` 后的代码真跑一遍、输出逐字比对）。
- 导入走 `POST /api/v1/knowledge/admin/tracks`（upsert），重复调用不产生重复轨道。

## 已知缺口

| 缺口 | 影响 | 何时处理 |
| --- | --- | --- |
| 非相邻关卡之间的依赖画不出 | 当前内容里没有这种依赖 | 出现时再考虑 |
| 难度默认值按章节推 | 落在无约定章节的新课程会默认 `beginner` | 写 frontmatter 显式声明 |
| 管理端没有轨道维护界面 | 改轨道要跑脚本 | 需要人工高频调整时再做 |
| 关卡数量依赖内容量 | 内容少时地图显得空 | 分批补课 |
