---
artifact: design
work_id: WORK-038
work: 从零开始分层：理论线与实战线
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 11:03:31 +0800
approver_role: CTO
---

# 从零开始分层：详细设计

## 系统上下文

```text
content/reading|courses/*.md ──import-course.mjs──> knowledge_entries + learning_tracks(+mode)
                                                          │
学习者 /learn ──GET /learn/tracks──> TrackService.list（带 mode）──> 两栏渲染
学习者 /learn/:slug ──GET /learn/tracks/:slug──> TrackService.map（不产出 locked）
                     └─ 关卡正文 ── quiz / code-challenge / sql-challenge / code-tour
                                      │
                                      ├─ POST /learning/attempts（客户端上报块得分）
                                      └─ GET/POST /sandbox/*（执行 python/java/sql）
```

## 数据模型

一处迁移：`V20261026120000__add_track_mode.sql`

```sql
ALTER TABLE learning_tracks
  ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'theory'
    COMMENT 'theory=读完能解释；practice=做完能验收',
  ADD COLUMN theory_track_slug VARCHAR(64) NULL
    COMMENT '实战轨道指向它假定的理论轨道；只用于提示，不构成限制';

CREATE INDEX idx_learning_tracks_mode ON learning_tracks (mode);
```

- 存量行自动成为 `theory`（默认值），不需要 UPDATE。
- 迁移只加结构，不插业务数据。
- `theory_track_slug` 不用外键：它允许指向尚未导入的线（先写内容后导入的顺序），校验放在导入脚本里。

## 通过判定

`LearningProgressRules` 增加**按 mode 分支**的重载，旧的三参签名保留给课程：

```text
theory  ：quizzes 非空 且 每块 ≥80%           （动手题不参与）
practice：challenges 非空 且 每块 ≥80%         （实战关卡必须有可验收的块）
course  ：challenges 非空 且 quizzes 非空 且 每块 ≥80%（既有语义，不变）
```

判定入口在 `LearningProgressService.update()`：它需要知道这一关属于哪条轨道、轨道是什么 mode。新增一个查询：按 `entryId` 反查 `learning_track_stages` + `learning_tracks.mode`；一条 entry 可能属于多条轨道时，**取要求最严的那个**（有 course 就按 course 判），避免同一关在不同轨道里一个过一个不过。

已 `passed` 的记录不因本项被降级：判定只在有新的上报时发生，历史行不动。

## 接口

- `TrackService.TrackView` 增加 `mode`、`theoryTrackSlug`（列表页两栏要用）。
- `TrackService.TrackMap` 增加同样两个字段（地图页显示"建议先看 X"）。
- 不新增端点，不改 `/learning/*` 契约形状（只改判定内部）。

## 导入脚本

`import-course.mjs` 的 `lineSpecOf()` 读三个新 frontmatter 键：

| 键 | 取值 | 缺省 |
| --- | --- | --- |
| `line_mode` | theory / practice | theory |
| `line_theory` | 理论轨道的 slug | 空 |
| `verify` | 实战的验收方式：`sql` / `run` / `project` | 空（theory 不需要） |

`mode=practice` 且 `verify` 为空的轨道，脚本直接报错退出——REQ-006 的"未声明验收方式不允许保存"在导入期就挡住，而不是等运行时。

## 前端

**`/learn`**：两栏（理论 / 实战）。复用现有卡片组件，只是分组；每栏头部显示"共 N 条 · 已通关 M"。实战栏为空时给明确空状态（"实战线正在准备中"）而不是空白。

**`/learn/:slug`**：标题下加一行「建议先看 X」链接（有 `theoryTrackSlug` 时）。进度与难度筛选不变。

**导航**：`/learn` 已经在导航里，本次不新增顶层入口；若实战线单独开页，必须同时进导航并给 `preferredNav` 补 key。本项定为**不开新页**，两栏都在 `/learn` 内，因此不需要动 `preferredNav`（少一处联合字面量改动）。

## 沙箱多文件（S5）

现有协议是"一段代码 + 语言"，`DockerCodeRunner` 把 `Main.java` 或脚本写进临时目录。

新增**项目模式**（只对 `language=java|python` 生效）：

```text
提交：{ files: [{ path, content }], entry: "Main.java", testCommand: 预置 }
```

约束与实现：

- `path` 只允许相对路径，拒绝 `..`、绝对路径、符号链接；文件名白名单字符集。
- 文件数 ≤ 20、单文件 ≤ 64 KB、总计 ≤ 256 KB。
- 构建与运行**命令由服务端按语言预置**，不接受客户端传任意命令——这是与现有单文件模式一致的口径（客户端只给代码，不给命令）。
- 超时沿用现有 CPU 时间上限；内存与输出上限按项目模式单独设。
- **不允许运行时联网**：容器 `--network none`；第三方依赖只能用镜像预装的，镜像构建时固定版本。
- 判题：预置测试随题目内容提供，测试文件与用户文件分开放，运行后按测试报告判定；失败要返回失败的用例名。

风险点：`--network none` 下 Maven/Gradle 无法解析依赖，所以 Java 项目式只能用 JDK 自带 + 镜像里预先 `mvn dependency:go-offline` 缓存的依赖。**S5 第一期只做"纯 JDK + 若干预装库"，不承诺任意第三方依赖。**

## 内容形态

| 形态 | 判题块 | 依赖沙箱 |
| --- | --- | --- |
| SQL 实战 | `sql-challenge`（结果哈希） | 是（sql） |
| 读代码实战 | `code-tour` / `code-map` + `quiz` | 否 |
| 调试实战 | `code-challenge` 的 `run`（改到输出一致） | 是（java/python） |
| 项目式实战 | 新增 `project-challenge` 块（S5） | 是（多文件） |

## 限流

- `application.yml`：`paideia.sandbox.daily-limit` 50 → **1000**；`sql-daily-limit` 500 → **1000**。
- 线上 `application.yml` 的 `sql-daily-limit: 20` 是历史漂移，部署时一并改成 1000。
- 真正的容量闸是"每人并发 1"，日限只是熔断器；页面上仍不常驻显示剩余次数。

## 已知限制

- `theory_track_slug` 不做外键，指向不存在的 slug 时前端静默不显示（导入脚本会警告）。
- 同一 entry 属于多条不同 mode 的轨道时按最严判定，可能出现"在实战轨道里看是未通过，因为它在理论轨道里小测没过"的困惑——本期接受，靠"一个关只属于一条轨道"的导入纪律规避。
- 项目式实战只支持 Java / Python；SQL 项目由 S3 的 SQL 实战线覆盖。
