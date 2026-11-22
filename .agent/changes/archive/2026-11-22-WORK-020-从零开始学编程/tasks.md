---
artifact: tasks
work_id: WORK-020
work: 从零开始学编程（首期 Python・Java）
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-009, WORK-010, WORK-013, WORK-019]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 13:32:19 +0800
approver_role: CTO
---

# 任务 · 从零开始学编程（首期 Python・Java）

**排序原则**：先把"线"这一层立起来（否则后面全按两门课写死，再改就是返工），再让内容能被机器校验（否则 24 课写完才发现结构错），再落库、再做服务端记录、再接页面、最后才是导师与部署。每个切片都能单独演示。

**全局约束**：
- 不新建后端模块；学习记录放 `paideia-knowledge`。
- **不建 `learning_lines` 表**：线是分类的投影（`line_kind`）。
- 只新增一张表、两个可空列、五个端点、一个新 harness、一个新工具。
- 迁移编号取执行时仓库的下一个，**不写死**。
- 课程内容按语言分两次提交，先 Python 后 Java。
- 每笔提交只含本次范围；提交前扫 `git diff --cached`。

## 任务

### TASK-000 | done | 线原语：两个可空列 + 线清单端点

- 迁移：`knowledge_categories.line_kind`、`knowledge_entries.sort_order`（都可空）+ 两个索引；把 `source-reading` 标成 `reading`。
- `LearningLine` 端口与 `GET /api/v1/learning/lines` 实现（先只返回线清单与站数，进度统计留到 TASK-008 接上）。
- 排序规则**只写一处**：`sort_order ASC NULLS LAST, published_at ASC, id ASC`（MySQL 用 `ORDER BY (sort_order IS NULL), sort_order, published_at, id`）。

**验收**：迁移在空库与既有库都跑通；`/lines` 返回 Python/Java（标完 `course` 后）与 `source-reading` 三条；既有分类不出现在清单里；未标 `sort_order` 的分类顺序与改动前一致。

### TASK-001 | done | 课程源目录与结构检查脚本

产出 `content/courses/README.md`（作者指南）、`content/courses/{python,java}/` 目录骨架、`scripts/verify_course.py`。

- 校验 frontmatter 键（`title/category/order/minutes/prerequisite/status`）与八节标题顺序。
- 校验围栏块 JSON 与关键上限（id ≤ 64、steps ≤ 20、quiz ≤ 10 题）。
- 空目录下运行应通过（0 文件 = 0 失败），保证先能跑再加内容。

**验收**：`python scripts/verify_course.py` 在空目录退出 0；故意写坏一个文件时报错点名到文件与小节。

### TASK-002 | done | 代码执行校验（`<!-- run -->`）

在 `verify_course.py` 里加：找到标记块 → 写临时目录 → 执行 → 比对紧随的 ```text 输出。

- Python 用 `python`；Java 写 `Main.java` 后 `javac` + `java`。
- 超时 15 秒；任何失败退出码 1 并打印实际/期望。

**验收**：一条故意算错的示例被抓出；一条正确示例通过。

### TASK-003 | done | Python 课程内容（12 课 + 总览）

按 REQ-004 的 12 课大纲与 REQ-005 的误区矩阵写内容（矩阵见下）。

- 每课八节齐全；`code-tour` 一个、`code-challenge` 至少一个、`quiz` 三题、误区 2–3 条。
- 至少一个 `<!-- run -->` 示例。
- 总览含 12 站 `learning-path`。

**验收**：TASK-001/002 的全部检查对 Python 13 个文件全绿。

### TASK-004 | done | Java 课程内容（12 课 + 总览）

同上，按 Java 12 课大纲；示例是单文件 `Main` 类；误区覆盖 Java 八条。

**验收**：全部检查对 Java 13 个文件全绿。

### TASK-005 | done | 导入脚本

`scripts/import-course.mjs`：登录 → 建分类（含 `line_kind`）→ 幂等建/更条目（含 `sort_order`）→ 按 order 顺序发布 → 建前置关系；带 `--dry-run` 与 `--dir`（默认 `content/courses`，可只导入一条线）。

**验收**：对本地环境连跑两次，第二次 `created 0 / updated 0 / relations 0`；条目总数不变；同分类既有条目未被误改（用一条哨兵条目验证）；`--dir content/courses/go` 在只写好该目录时能独立导入（为 AC-017 铺路）。

### TASK-006 | done | `learning_progress` 迁移与 Mapper

迁移文件（下一个编号）建表；`LearningProgressMapper` 写全部 SQL（upsert / select by user / select by user+entry / delete / list by user）。

**验收**：迁移在空库与既有库都能跑；Mapper 有集成测试（两级环境都过）。

### TASK-007 | done | 通过判定与复习阶梯（纯逻辑）

`LearningProgressService` 的纯函数部分：合并分数、算 `passed`、算下一档复习时间。

**验收**：单测覆盖——不足 80%、恰好 80%、全对、缺小测、缺练习、`total=0`、重复通过阶梯 +1、到顶不再加。

### TASK-008 | done | 学习记录端口与五个端点

`LearningProgress`（包根端口）+ `LearningController`：
`GET /learning/lines`（补齐进度统计/下一课/到期数）、`GET /learning/progress`、`GET/PUT/DELETE /learning/entries/{id}`。

- 不接收 `user_id`；条目必须存在且已发布（PUT/GET 单条）。
- PUT 返回更新后的完整视图；DELETE 204。
- 入参校验（`score ≤ total`、块数 ≤ 50、id ≤ 64）。

**验收**：集成测试——通过/不通过、越权访问他人记录、未发布条目 404、非法分数 400、DELETE 后回初态、`/lines` 的 `nextEntry` 等于第一个非 passed 的站。

### TASK-009 | done | 注销级联

把 `learning_progress` 加进 `AccountDeletionService` 的清理清单。

**验收**：注销集成测试断言该用户的行被删除、他人的行不受影响。

### TASK-010 | done | `learning_progress` 工具

`knowledge/internal/tools/LearningTools.java`：返回通过数/总数、最近一课、最弱三门、今天到期。

**验收**：单测（桩 Mapper）断言只读本人；工具目录启动期校验通过。

### TASK-011 | done | `/learn` 页（按线清单渲染）

一次 `GET /learning/lines`，按 `kind` 分组（`course` / `reading`），**不硬编码分类 id 或课名**；空清单不白屏。

**验收**：组件测试（有/无记录、全通过、到期复习、空清单、多条线）；360px 无溢出；**加一条假线（桩数据）后卡片数跟着变**（证明不是写死两张）。

### TASK-012 | done | 首页入口与"继续学习"接服务端

`QuickLinkKey` 加 `learn`；`configFor` 默认 `preferredNav` 含 `learn`；首页"继续学习"优先服务端、失败退回 `recentReading`。

**验收**：e2e——首页可见「从零开始」并进入 `/learn`；后端不可用时旧卡片仍显示。

### TASK-012b | done | 可扩展性验收：真加一条线

在测试环境**只动内容**新增第三条线（例如 `algorithms-from-zero`，3 个条目），跑 `import-course.mjs --dir`；确认 `/learn` 出现第三条线、能进入、能做题、能通过。

**验收**：`git status` 显示无源码改动（只有 `content/` 新文件）；AC-017 通过。这条任务的意义是**先证明原语成立**，再做剩下的课程内容。

**实施结果**：第一次跑这条验收**没通过**——`import-course.mjs` 顶部把 python/java 写死在 `CATEGORIES`
常量里，加第三条线必须先改脚本。已改成从总览 frontmatter 读 `category / line_name / line_kind /
line_sort`，脚本里不再出现任何一门课的名字；重跑后新增一条 4 站线，导入前后工作区改动数不变（65）。
验收用的假线已删除。

### TASK-013 | done | 条目页接入服务端进度

页面先取单课记录，构造同步 `ProgressPort`（内存 + 去抖 PUT + DELETE）；非课程条目走原实现。

**验收**：e2e——做完小测刷新后仍为已通过；重置后回未通过；非课程条目行为不变（既有 knowledge e2e 全绿）。

### TASK-014 | done | 导师提示词与 harness

`Harness.TUTOR`（key `tutor`）+ `TutorPrompt`（版本号、四级提示、默认不给完整答案）；`HarnessPrompts` 穷尽 switch 补分支。

**验收**：单测断言四条硬规则文本存在、版本号非空、`ofOrDefault("tutor")` 返回 TUTOR、未知值仍退回 ASSISTANT。

### TASK-015 | done | 学情注入与前端使用导师模式

`ConversationPrompt` 增学情段落；仅 `TUTOR` + 有 `entryId` 时注入；课程条目页的「问 AI」创建会话时传 `harness: 'tutor'`。

**验收**：集成测试断言 TUTOR 请求的系统提示含学情数字、ASSISTANT 请求不含；前端单测断言 `createConversation` 收到 `tutor`。

### TASK-016 | done | 内容导入线上并验收
2026-10-19 完成：V17/V18/V19 已随发版应用到生产库（`Current version 16 → 19`）；
`node scripts/import-course.mjs` 导入 26 课（Python 13 + Java 13）与 22 条前置关系，
第二次运行 `created 0 / updated 0` 证明幂等。线上 `/api/v1/learning/lines` 返回两条线各 13 站；
提交小测 + 挑战后 `status=passed`、`passedCount 1/13`、下一站推进到「01 · 环境与第一行代码」。
线上学习者端 e2e 60 passed / 22 skipped / 0 failed（含 learn.spec 3 条）；
管理端 e2e 20 passed。

线上跑导入脚本（凭据只从环境变量）；在线上真实完成一课（做题 → 通过 → 下一课）；记录耗时与截图口径。

**验收**：线上 `/learn` 显示 2 门课；完成一课后状态与"下一课"正确；`journalctl` 无异常。

### TASK-017 | done | 文档与已知缺口

`dev-workflow.md` 新增「从零开始课程」一节：导入命令、内容门禁、通过判定、复习阶梯、四条已知缺口；`always.md` 只加"课程内容源在 `content/courses`，改内容走 PR + 导入"一条。

**验收**：照文档跑一遍命令，结果与实际一致。

## 误区 → 课程映射（REQ-005）

| 误区 | Python 课 | Java 课 |
| --- | --- | --- |
| 可变默认参数 | ④ 函数 | — |
| `is` vs `==` | ② 运算 | — |
| 浅拷贝 vs 深拷贝 | ⑤ 列表 | — |
| 缩进是语法 | ③ 循环 | — |
| 整除与浮点 | ② 运算 | — |
| 闭包晚绑定 | ④ 函数 | — |
| 遍历中修改集合 | ③ 循环 / ⑥ 字典 | — |
| `==` vs `equals` | — | ② 运算符 / ④ 字符串 |
| String 不可变、`final` | — | ⑤ 类与封装 |
| 值传递 | — | ⑥ static 与值传递 |
| static 与 this | — | ⑥ static 与值传递 |
| 数组协变 | — | ④ 数组 |
| checked vs unchecked | — | ⑨ 异常 |
| 装箱缓存 | — | ⑧ 集合与泛型 |
| 泛型擦除 | — | ⑧ 集合与泛型 |

## 评审焦点

规格暗示、但普通"功能能跑"测试覆盖不到的失败模式：

| 失败模式 | 归属任务 | 测试名 |
| --- | --- | --- |
| 客户端伪造 `passed` | TASK-008 | `passingIsComputedFromScores` |
| 分数越界 / `total=0` 除零 | TASK-007/008 | `zeroTotalIsNeverPassed`、`scoreAboveTotalRejected` |
| 用户 A 读/写用户 B 的记录 | TASK-008 | `otherUsersProgressIsInvisible` |
| 重置后仍显示已通过 | TASK-008/013 | `resetReturnsToInitialState` |
| 复习阶梯无限增长 | TASK-007 | `reviewStageStopsAtTwentyOneDays` |
| 导入脚本改到别的分类 | TASK-005 | dry-run 哨兵条目断言 |
| 导入非幂等（重复条目） | TASK-005 | 连跑两次计数为 0 |
| 课程顺序被打乱（published_at 相同） | TASK-008 | 同时间戳时按 id 兜底排序 |
| 课程正文里的块 JSON 线上才炸 | TASK-001 | 结构脚本对 26 文件全绿 |
| 示例代码在 Windows 上路径失败 | TASK-002 | 临时目录 + `shutil`，不写死分隔符 |
| 沙箱关闭时运行题让通道卡住 | TASK-013 | `pendingWithoutSandboxStillPasses` |
| 导师首答直接贴完整答案 | TASK-014 | 提示词文本断言（存在性）+ 人工验收 |
| 非课程条目因新端口白屏 | TASK-013 | 既有 `knowledge.spec.ts` 全绿 |
| 注销留下孤儿学习记录 | TASK-009 | `deletionRemovesLearningProgress` |
| `sort_order` 为 NULL 的行被排到最前（把没声明的线打乱） | TASK-000 | `nullSortOrderGoesLast` |
| 未标 `line_kind` 的旧分类被当成线 | TASK-000 | `ordinaryCategoryIsNotALine` |
| 源码解读被重复建一条线/复制条目 | TASK-000 | `existingReadingLineIsReused` |
| "零代码扩展"其实偷偷改了前端 | TASK-012b | AC-017 的 `git status` 证据 |

## 未包含

视频、动画、证书、排行榜、用户投稿、自适应出题、更多语言、跨设备"读到第几步"、复习提醒通知。这些都不在 REQ 里，也不预留代码。
