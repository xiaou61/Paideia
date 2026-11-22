---
artifact: design
work_id: WORK-020
work: 从零开始学编程（首期 Python・Java）
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-009, WORK-010, WORK-013, WORK-019]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 13:32:19 +0800
approver_role: CTO
---

# 设计 · 从零开始学编程（首期 Python・Java）

## 系统上下文

```text
content/courses/**  ──┐
                      ├─ scripts/verify_course.py（结构 + 真跑代码）
                      └─ scripts/import-course.mjs ─→ /api/v1/knowledge/admin/**
                                                        │
                                              knowledge_categories (+line_kind)
                                              knowledge_entries   (+sort_order)
                                                        │
学习者 /learn ── GET /api/v1/learning/lines ──────┐      │
条目页        ── GET /api/v1/learning/entries/{id} ──┤   │
做完题        ── PUT  /api/v1/learning/entries/{id} ──┤   │
                                learning_progress (新表) │
                                                        │
助手（TUTOR） ── 服务端注入学情 + entryBody ─────────────┘
              └─ 工具 learning_progress（只读本人数据）
```

## 线的识别与排序（本项的可扩展性核心）

### 数据

两个**可空**列，迁移不碰任何存量行：

```sql
ALTER TABLE knowledge_categories
  ADD COLUMN line_kind VARCHAR(16) NULL;      -- course | reading | NULL（普通分类）

ALTER TABLE knowledge_entries
  ADD COLUMN sort_order INT NULL;             -- 同线内唯一；NULL = 未声明

-- 只给真线建索引；NULL 的行不占索引空间
CREATE INDEX idx_knowledge_categories_line ON knowledge_categories (line_kind);
CREATE INDEX idx_knowledge_entries_line_order ON knowledge_entries (category_id, sort_order);
```

**为什么不新增 `learning_lines` 表**：线的身份是分类的 `id/slug`，线名是分类的 `name`，线的顺序是分类的 `sort_order`。再建一张表就出现两份真相，且要迁移存量分类。`line_kind` 是"这个分类是一条线"的**唯一标记**。

### 一条线怎么算出来

```
lines = SELECT 分类 WHERE line_kind IS NOT NULL AND 该分类下已发布条目数 > 0
  对每条线：
    stations = SELECT 条目 WHERE category_id = 线.id AND status='published'
               ORDER BY (sort_order IS NULL), sort_order ASC, published_at ASC, id ASC
```

排序键写成三级：`sort_order` 为 NULL 的排到**最后**（既有序也没有声明时保持稳定）；声明过的按声明排；同值按 `published_at, id` 兜底。**只有这一处实现**，`EntryMapper` 现有的 `findPreviousInCategory` / `findNextInCategory` / 列表查询继续按 `(published_at, id)`——它们是"同分类相邻内容"，不承担线的顺序。

**已知取舍**：这意味着"上一篇/下一篇"在声明了 `sort_order` 的线里可能与课号不一致。处理方式：导入时也按 `sort_order` 顺序发布，使 `published_at` 单调，于是两种顺序一致；手工插入的条目仍可能错位（已登记为缺口）。

### 源码解读作为 `reading` 线

- 迁移只把分类 `source-reading` 的 `line_kind` 设为 `'reading'`，**不动它下面的任何条目**。
- 它的站顺序：优先读总览条目 `learning-path` 里显式写的 `entryId` 顺序（服务端不解析 markdown，所以这条走**导入脚本**：导入源码解读时把顺序写进 `sort_order`）；没有 `sort_order` 时退回 `(published_at, id)`。
- 它的站数：若总览有 `learning-path`，`GET /lines` 返回 `declaredStations`（导入时记在 `sort_order` 的最大值）与 `stations` 实际条目数，两个数不一致时以实际条目为准并打 warning 日志——**不静默**。

## 数据模型

### 新表 `learning_progress`（迁移编号取执行时的下一个，**不要写死 V17**）

```sql
CREATE TABLE learning_progress (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id           BIGINT UNSIGNED NOT NULL,
  entry_id          BIGINT UNSIGNED NOT NULL,
  -- 最远走读步骤（供"接着读"；与 localStorage 的值取更大者由前端合并，服务端只存）
  tour_step         INT             NOT NULL DEFAULT 0,
  -- JSON：{"<blockId>":{"score":8,"total":10}}，练习与小测各一组
  challenges_json   JSON            NOT NULL,
  quizzes_json      JSON            NOT NULL,
  -- in_progress | passed；**只由服务端按分数算**
  status            VARCHAR(16)     NOT NULL DEFAULT 'in_progress',
  passed_at         DATETIME(3)     NULL,
  -- 复习阶梯档位 0..3（1/3/7/21 天）与到期时间
  review_stage      TINYINT         NOT NULL DEFAULT 0,
  review_due_at     DATETIME(3)     NULL,
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_learning_progress (user_id, entry_id),
  KEY idx_learning_progress_user_due (user_id, review_due_at),
  KEY idx_learning_progress_entry (entry_id),
  CONSTRAINT fk_learning_progress_entry FOREIGN KEY (entry_id)
    REFERENCES knowledge_entries (id) ON DELETE CASCADE
) ENGINE = InnoDB;
```

不建外键到 `users`：项目现有表（如 `knowledge_self_assessments`）同样只存 `user_id`，注销级联由 `AccountDeletionService` 的清单负责；本表必须**加进那份清单**并有集成测试。

**为什么分数存 JSON 而不是一张作答明细表**：本项需要的是"这一课各块最高分"，不是逐次作答历史；逐次历史属于将来的掌握度/错题本，需要时再建表，现在建等于先写一张没有读者的表。`tour_step` 等服务端不知道块 id 的字段单独成列，便于索引与复习排序。

### 通过判定（唯一实现）

```
passed = 至少 1 条挑战记录 且 至少 1 条小测记录
         且 每条记录的 score / total >= 0.8
```

- `total == 0` 的记录视为不通过（防除零与"空块"）。
- 分数合并规则：`max(旧, 新)`（重做不会因为一次手滑把已通过降级）；**重置**才清空。
- 变为 `passed` 时：写 `passed_at`（首次有效时间），`review_stage = 0`，`review_due_at = passed_at + 1 天`。
- 已是 `passed` 再次提交：`review_stage = min(stage + 1, 3)`，`review_due_at = now + 阶梯[stage]`。
- 已 `passed` 后分数变低（理论上不会，因为取 max）不降级；**重置**会降级（REQ-014）。

## 组件与职责

| 组件 | 位置 | 职责 | 不做什么 |
| --- | --- | --- | --- |
| `LearningProgress`（API） | `paideia-knowledge` 包根 | 对外端口：按用户读/写/清、算统计 | 不认识 HTTP |
| `LearningProgressService` | `internal/learning` | 合并分数、判定 passed、排复习 | 不拼 SQL |
| `LearningProgressMapper` | 同上 | 全部 SQL（唯一 INSERT/UPDATE/SELECT 处） | — |
| `LearningController` | `internal/web` | 四个端点、取当前用户、校验条目已发布 | 不接受 `user_id` |
| `TutorPrompt` | `paideia-ai` | TUTOR 的 system prompt 与版本号 | 不碰工具 |
| `Harness.TUTOR` | `paideia-ai` 包根 | 枚举值 + `key() = "tutor"` | — |
| `LearningTools` | `knowledge/internal/tools` | `learning_progress` 工具 | 只读本人 |
| `/learn` | `frontend/apps/app` | 两门课进度、下一课、到期复习 | 不复制阅读页 |
| `verify_course.py` | `scripts/` | 结构与代码执行校验 | 不连数据库 |
| `import-course.mjs` | `scripts/` | 幂等导入 | 不删除条目 |

**模块归属**：学习记录跟着知识库走，因为它只引用条目、并且和自评是同一类"用户对条目状态"。放 `paideia-ai` 会让 AI 再次依赖数据库业务表；放新模块会让 Modulith 多一个几乎空的模块。

## 端点

`@RequestMapping("/api/v1/learning")`，全部要求登录（沿用 `anyRequest().authenticated()`，无角色限制）。

### `GET /api/v1/learning/lines`

```json
{ "lines": [
  { "categoryId": 3, "slug": "python-from-zero", "name": "Python 从零开始",
    "kind": "course", "sortOrder": 10,
    "stationCount": 12, "passedCount": 3,
    "nextEntry": { "entryId": 21, "title": "04 · 函数", "index": 4 },
    "dueReviewCount": 1 },
  { "categoryId": 9, "slug": "source-reading", "name": "源码解读",
    "kind": "reading", "stationCount": 1, "passedCount": 0, "nextEntry": null, "dueReviewCount": 0 }
] }
```

- 线的集合、名字、顺序**全部来自分类**；前端不写死。
- `stationCount` = 该分类下已发布条目数。
- **没有"总时长"字段**：要让服务端知道每课多少分钟，就得再加一个列去存 frontmatter 里的 `minutes`，而前端本来就按"12 课"表达规模。一期不做，卡片的预计时长由前端拿站数乘一个固定值显示为"约"，不进契约。

### `GET /api/v1/learning/progress`

```json
{
  "items": [
    { "entryId": 12, "title": "循环", "categoryId": 3, "status": "passed",
      "tourStep": 4, "passedAt": "2026-10-19T02:00:00Z",
      "reviewStage": 1, "reviewDueAt": "2026-10-22T02:00:00Z",
      "challenges": {"py-03-order": {"score": 3, "total": 3}},
      "quizzes": {"py-03-quiz": {"score": 3, "total": 3}} }
  ]
}
```

只返回已发布条目（草稿被删/下架时记录自然不出现，但行保留）。

### `GET /api/v1/learning/entries/{id}`

单条；无记录时返回 `{"entryId":id,"status":"in_progress",...,"challenges":{},"quizzes":{}}`（**200，不是 404**——没有记录是正常初态）。

条目不存在或未发布返回 404。

### `PUT /api/v1/learning/entries/{id}`

```json
{ "tourStep": 4,
  "challenges": {"py-03-order": {"score": 3, "total": 3}},
  "quizzes": {"py-03-quiz": {"score": 3, "total": 3}} }
```

- 所有字段可选；缺省表示"这次不报这类分数"。
- 校验：`total in 1..100`、`score in 0..total`、块 id ≤ 64 字符、条目数 ≤ 50、`tourStep in 0..1000`。越界 400。
- 返回更新后的完整视图（前端据此刷新，不猜）。
- **节流**：前端每次提交做 400ms 去抖，并且同一分数不重复提交（已在 `ProgressPort.update` 的调用点内做）。

### `DELETE /api/v1/learning/entries/{id}`

删除本人该课记录，204。二次确认在界面。

## 复习阶梯

| stage | 间隔 |
| --- | --- |
| 0 | 1 天 |
| 1 | 3 天 |
| 2 | 7 天 |
| 3 | 21 天（到顶后仍是 21 天） |

`/learn` 的"今天该复习"= `status = passed AND review_due_at <= now`，按到期升序，最多 5 条。时间比较用 UTC（`Instant.now()`，Java 侧比较，不在 SQL 里 `NOW()`）。

## 前端接线

### 进度端口：保持同步接口，加一个写回适配器

`ProgressPort` 是同步的（`read` 立即返回值）。课程条目页必须在首次渲染前拿到服务端初值，否则块会用空进度渲染、再跳变。做法：

1. 条目页在渲染 `MarkdownBody` **之前** `await` 一次 `GET /learning/entries/{id}`（`useQuery` + `isPending` 时显示加载态）。
2. 用返回的记录构造一个内存 `ProgressPort`：
   - `read()` 返回服务端状态与本地 `localStorage` 中**更远**的 `tourStep` 合并结果（read 只读，不写）；
   - `update(patch)` 先合并进内存并通知当前页，再 400ms 去抖 `PUT`；
   - `reset()` 调 `DELETE` 并清本地该课。
3. 课程外的条目继续用现有 `createEntryProgressPort`（不受影响）。

**为什么不去改 `ProgressPort` 为异步**：六个渲染器与 20 多条单测都按同步接口写的；为一个页面把端口改成 Promise 会让预览与测试全部要包 loading。合并策略放在适配器里，块完全无感。

### `/learn` 页

一次 `GET /learning/lines`，按 `kind` 分组渲染（`course` → 「从零开始」区，`reading` → 「专题」区）。**前端没有任何分类 id 或课名是硬编码的**。

- 每张卡：名字、`已通过 N / stationCount`、进度条、`继续第 K 课`（第一个非 passed 的站）、`今天该复习 M 课`。
- 站顺序来自服务端（`sort_order` 优先，缺省 `(published_at, id)`），前端不排序。
- 空清单（一条线都没有）：显示"还没有课程"并给管理员一句话，不白屏。

### 首页

`QUICK_LINKS` 加一项 `{ key: 'learn', to: '/learn', testId: 'home-entry-learn', title: '从零开始', description: 'Python / Java 两门课，一课 20 分钟，边学边练。' }`。
`QuickLinkKey` 联合类型加 `'learn'`；`@paideia/identity` 的 `configFor` 里把 `learn` 放进默认 `preferredNav` 靠前位置（否则新卡永远排在最后）。首页"继续学习"优先用服务端数据，失败或无数据时退回 `recentReading`。

**这张卡是通用入口，不是某一门课的卡**：它指向 `/learn`，里面有几条线由服务端决定。加语言不改这一行文案以外的任何代码。

## 导师行为（`TUTOR`）

`TutorPrompt.system()` 的硬规则，按顺序：

1. 你是编程课的导师，面对零基础学习者。**默认不给完整答案。**
2. 第一步永远先让学习者说出自己的思路、贴出报错、或指出他认为哪一行有问题；他说不出来时才降低要求（"那你猜这段会输出什么？"）。
3. 提示分四级，每次只给一级：
   - L1 概念提醒（这个机制叫什么、解决什么问题）；
   - L2 定位（问题在第几行/哪个选项/哪条规则）；
   - L3 下一步伪代码（不含完整代码）；
   - L4 完整答案（**只有学习者明确要求且已经写过一次自己的尝试**才给，并说明"看懂后请自己重写一遍"）。
4. 他答错时先指出**具体错在哪**并解释为什么，再问一个能自检的问题。
5. 不重复整篇课文；引用时提到小节名。
6. 使用学习者当前学情：如果他在某个块上分数低，优先追问那一块。

**学情注入**：`ConversationPrompt` 增加一段 `learningContext(...)`，内容来自服务端 `LearningProgress` 端口（本课状态 + 各块得分 + 最近复习），并写明"这是平台记录的事实，不是学习者自述"。只在 `harness == TUTOR` 且请求带 `entryId` 时注入。

**完整性守卫**：提示词要求 L1–L3 不得输出完整代码块；单测对 `system()` 文本断言这四条存在（存在性断言，不是语义断言）。

`learning_progress` 工具返回：各门课通过数与总数、最近一课、得分最低的三门已学课、今天到期的复习。工具归属只取 `ToolContext` 的用户，与 `knowledge_my_marks` 同一纪律。

## 内容与导入

### 课程源文件格式

```markdown
---
title: 03 · 循环
category: python-from-zero
order: 3
minutes: 22
prerequisite: 02 · 运算与条件
status: published
---

## 这一课你要会
...

## 讲解
<!-- run -->
```python
...
```
```text
期望输出
```
```

- `order` 决定发布顺序与"下一篇"；导入时必须**按 order 顺序**逐条创建/更新，`published_at` 因此单调。
- `prerequisite` 用标题匹配同分类条目，导入后调 `POST /knowledge/admin/relations`（`prerequisite`）。
- `status` 只允许 `published`（草稿不导入，避免线上半成品）。

### `verify_course.py`（Python 3）

对每个课程文件：

1. 前端解析 frontmatter（手写键值解析，不引依赖）。
2. 断言八个小节标题存在且顺序正确。
3. 解析围栏块：`code-tour`/`code-challenge`/`quiz`/`learning-path`/`run` 用 `json.loads` 校验；块内字段按 `schema.ts` 的上限检查（**复刻关键上限，不做全量镜像**：id ≤ 64、steps ≤ 20、quiz.questions ≤ 10）。
4. `<!-- run -->` 标记的下一个块：写进临时目录执行（`python` 直接跑；`java` 用 `javac Main.java && java Main`），比对紧随其后的 ```text 期望输出。逐块打印 `PASS/FAIL`，任一失败退出码 1。

**不连数据库、不连网络**；只用本机 `python` / `javac`。

### `import-course.mjs`（Node）

1. 环境变量取 `PAIDEIA_ADMIN_USER` / `PAIDEIA_ADMIN_PASSWORD`（可选 `PAIDEIA_BASE_URL`，默认线上）。
2. 登录 → 拿访问令牌（复用现有登录端点）。
3. 取分类树；缺 `from-zero` / `python-from-zero` / `java-from-zero` 就创建（父 id 依次）。
4. 取该分类下已有条目；按标题匹配：
   - 不存在 → `POST /entries`（先 `draft`，全部落库后再统一改 `published`，避免发布顺序错乱）；
   - 存在 → `PUT /entries/{id}`。
5. 最后按 `order` 依次 `published`，再补关系（已存在的边跳过）。
6. `--dry-run` 只打印将要做的事。
7. 第二次执行应输出 `created 0, updated 0, relations 0`（只有时间戳差异不算更新——比较标题/正文/状态，正文一致就不发请求）。

**安全**：凭据只从环境变量读；日志里不打印密码与令牌；不写任何文件。

## 内容口径（作者指南摘要，同时写进 `content/courses/README.md`）

- 一课 20 分钟左右：讲解 ≤ 1500 字，代码示例 2–4 段。
- 一节课只教**一个**新概念；新概念第一次出现给定义，第二次给例子，第三次才出题。
- 每课固定三段"以为/其实/怎么验证"的误区；误区必须来自高频清单。
- 每道题只有一个正确答案，且答案能从本课正文推出来。
- 不写"显然""很简单"；不假设读者会 Git、命令行、英文报错。

## 关键取舍

| 取舍 | 理由 |
| --- | --- |
| **线 = 分类 + `line_kind`**，不建线表 | 名字/slug/顺序分类里都有；多一张表就多一份要同步的真相 |
| **站顺序用可空 `sort_order`**，缺省 `(published_at, id)` | 存量条目零改动；声明过的线可精确排课 |
| **线清单由服务端投影出来**，前端不解析正文 | 正文里解 `learning-path` 等于在前端做第二次解析，草稿/顺序一变就错 |
| `ProgressPort` 保持同步，异步初始化放在页面层 | 六个块与全部单测依赖同步接口；改端口等于全仓返工 |
| 分数存 JSON 不建作答明细表 | 本项只消费"最高分"；明细表没有读者 |
| 复习读取时计算，不起调度任务 | 一期没有通知渠道，定时任务只会多一个要运维的进程 |
| 通过判定在服务端 | 客户端可伪造；阈值只能有一处 |
| 内容源在仓库、导入走管理端 API | 不新增写接口；内容可 diff、可评审、可回滚 |
| 运行题不是必经 | 线上沙箱状态未定；课程不能因为一个可选能力不可用就没法通过 |
| 契约里没有"每课时长" | 要多一列才能准确；一期用站数表达规模，避免为展示字段加列 |

## 已知缺口（实施完成后登记到 `dev-workflow.md`）

1. 复习只有"到 /learn 看"，**没有提醒**（无邮件/推送）。
2. 掌握度是"最高分"，不是知识追踪；答错后重做一次就"通过"——质量靠题库，不靠算法。
3. **"上一篇/下一篇"仍按 `(published_at, id)`**：声明了 `sort_order` 的线靠"导入时按顺序发布"保证两者一致；管理员事后手工插入条目会打乱课号与相邻关系。
4. 导师是否真不给答案由提示词保证，**没有自动检测完整答案的机制**。
5. 线的"类型"编码在 `line_kind` 里，新增科目时若需要新的入口分组，要么复用 `course`，要么加一个枚举值——**这仍是一次代码改动**。这是"零代码扩展"的边界：同构换内容零代码，新增一种**呈现形态**才需要代码。
