---
artifact: tasks
work_id: WORK-046
work: 书籍详解专题
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-014, WORK-020, WORK-040]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 09:55:53 +0800
approver_role: CTO
---

# 任务 · 书籍详解专题

**排序原则**：S1 先把数据链路打通（导入能建两层分类、读接口能拿到顺序），S2 才做入口，
S3 才写内容，S4 收口。S1 与 S2 可并行，但 S3 必须等 S1——内容没地方导就看不到效果。

**全局约束**：

- 不改 schema、不加迁移、不新增后端接口。
- 分类创建**绝不带 `lineKind`**，否则书籍会出现在 `/learn`（REQ-004）。
- 前端不硬编码任何分类 id，一律按 slug 从分类树解析。
- 评估测试走 `scripts/test-db.sh`，不直连共用库。

## 任务状态

| 任务 | 名称 | 阶段 | 状态 |
| --- | --- | --- | --- |
| TASK-001 | 导入脚本支持一级父分类 | S1 | done |
| TASK-002 | `EntrySummary` 暴露 `sortOrder` | S1 | done |
| TASK-003 | 内容目录骨架与幂等导入验证 | S1 | done |
| TASK-004 | `/books` 书列表页 | S2 | done |
| TASK-005 | `/books/:slug` 章节页 | S2 | done |
| TASK-006 | 路由与导航入口 | S2 | done |
| TASK-007 | 《人月神话》总览 | S3 | done |
| TASK-008 | 《人月神话》四篇正文 | S3 | done |
| TASK-009 | `books.spec.ts` e2e | S4 | done |
| TASK-010 | 验收报告与线上导入 | S4 | done |

## S1 · 数据侧

### TASK-001 | done | 导入脚本支持一级父分类

- 文件：`scripts/import-course.mjs`。
- 内容：
  - `lineSpecOf()` 读 `line_parent` / `line_parent_name`。
  - 新增 `ensureParent()`：按 slug 在已取的树里找父分类，缺失则 `POST /categories`
    建一个（`parentId: null`、`lineKind: null`、`sortOrder: 0`）。
  - `ensureCategory()` 的 `parentId: null` 改为解析出的值。
- 验收：
  - 用 `--dry-run` 跑一个带 `line_parent` 的目录，输出里能看到"将创建父分类 书籍详解"。
  - 真实跑一次后，子分类行的 `parent_id` 指向 `books` 的 id；父分类 `line_kind` 为 `NULL`。
  - 再跑一次输出 `created 0 / updated 0`，分类数不增加（AC-006 / AC-007）。
- 评审焦点：`ensureParent` 必须在**同一次**树快照里判断存在性，不能每建一本书都重新取树；
  父分类创建失败时不能让子分类静默挂空。

### TASK-002 | done | `EntrySummary` 暴露 `sortOrder`

- 文件：`EntryMapper.java`、`EntryService.java`、`frontend/packages/knowledge/src/types.ts`、
  `backend/paideia-app/src/test/resources/api-shape.json`。
- 内容：`pagePublished` 的 SELECT 增加 `sort_order`；`EntrySummary` 记录加 `Integer sortOrder`；
  `listPublished` 映射时传入；前端类型加 `sortOrder?: number | null`。
- **还要重生成接口形态快照**：`ApiWalkthroughTest` 逐字段比对 `api-shape.json`，
  多一个字段就会红（`ApiWalkthroughTest.java:877`）。确认新增字段是有意的之后用
  `-Dapi.shape.update=true` 重跑一次，再 review 快照 diff。
- 验收：
  - `GET /api/v1/knowledge/entries?categoryId=<书分类>` 的每一项带 `sortOrder`。
  - 现有分类页与全部条目页的返回顺序**不变**（仍是 `published_at DESC`）——加字段不改 ORDER BY。
  - `api-shape.json` 的 diff 只多出 `data.items[].sortOrder: number` 一行（或该字段可空时对应的形态）。
- 评审焦点：这是**公共读接口的响应体变更**；快照 diff 必须肉眼过一遍，不能整文件覆盖了事。

### TASK-003 | done | 内容目录骨架与幂等导入验证

- 文件：`content/reading/books-man-month/00-总览.md`（占位）、`01-占位.md`（占位）。
- 内容：按 DESIGN 的 frontmatter 契约写两个占位文件，跑一次导入，确认两层分类成立。
  占位正文在 TASK-007/008 被真内容替换。
- 验收：
  - 导入日志里出现父分类与子分类两条创建记录。
  - `content/reading/` 下其他 20 条线**未被触碰**（导入脚本只处理 `--dir` 指定的目录）。
  - 第二次导入 `created 0 / updated 0`。
- 评审焦点：占位文件必须带完整 frontmatter（含 `line_parent`），否则 TASK-001 的路径没被真正走过。

## S2 · 入口侧

### TASK-004 | done | `/books` 书列表页

- 文件：`frontend/apps/app/src/pages/BooksPage.tsx`（新建）。
- 内容：分类树找 slug `books`；一次 `entries({categoryId: root.id, size: 100})` 取全部条目；
  按 `categoryId` 分组；每本书取 `sortOrder === 0` 的条目作总览；渲染卡片网格。
  三种空态按 DESIGN 的文案分开。
- 验收：
  - 卡片显示书名、`共 N 篇`、总览摘要首行。
  - 分类树里没有 `books` 时显示「还没有收录书籍」，页面不报错、不白屏。
  - 窄屏单列，无横向滚动。
- 评审焦点：`enabled: root !== undefined` 必须写，否则首帧会用 `undefined.id` 发请求；
  摘要必须走 `plainText()`，不能把 markdown 原样吐在卡片上。

### TASK-005 | done | `/books/:slug` 章节页

- 文件：`frontend/apps/app/src/pages/BookChaptersPage.tsx`（新建）。
- 内容：按 `useParams().slug` 在分类树里找分类；取该分类条目；按
  `(sortOrder ?? MAX) - (sortOrder ?? MAX) || a.id - b.id` 排序；渲染序号 + 标题。
- 验收：
  - 章节首条是总览，顺序与文件名的 `order` 一致（AC-003）。
  - `sortOrder` 为空的行显示 `·` 占位而不是数字。
  - slug 不存在时显示「这本书还没有收录」，不抛错。
- 评审焦点：排序函数要能被单测直接调用，不要在组件内部内联匿名比较器。

### TASK-006 | done | 路由与导航入口

- 文件：`router.tsx`、`components/LearnerHeader.tsx`。
- 内容：两条路由包在既有登录门里；`NAV` 增加 `books` 项到「知识」组。
- 验收：
  - 页头出现「书籍详解」，点击落到 `/books`（AC-001）。
  - 进入 `/books/man-month` 时该入口为激活态（既有前缀匹配生效）。
  - 移动端导航抽屉里同样可达。
- 评审焦点：**不动 `@paideia/identity`**；新增 key 不进 `preferredNav` 是刻意的，
  不要为了"排得靠前"去改共享包。

## S3 · 内容侧

### TASK-007 | done | 《人月神话》总览

- 文件：`content/reading/books-man-month/00-总览.md`。
- 内容：回答四件事（这本书要解决什么问题 / 作者怎么推 / 哪些结论有边界 / 它在跟谁争论）；
  第一段是出处头（作者、原版版次、中译本版次与译者、ISBN）；含一个 `learning-path` 或
  有序列表说明五篇的读法。
- 验收：`order: 0`；出处头在正文第一段；卡片摘要是该篇前 200 字的可读一句话（AC-002）。
- 评审焦点：不得出现原书整段译文；引用一句必须标章节与页码。

### TASK-008 | done | 《人月神话》四篇正文

- 文件：`01-人月神话.md`、`02-概念完整性.md`、`03-第二系统效应.md`、`04-没有银弹.md`。
- 内容：每篇一个论点，结构固定为「他主张什么 / 论证怎么走 / 边界在哪 / 2026 年还成立吗」。
  每篇至少一个可视块：至少一篇用 `code-map` 画论证链（`title` 写成"论证链"），
  至少一篇用 `quiz` 做先猜后看。
- 验收：每篇带出处头与 `order: N`；概念图在窄屏不横向溢出（AC-005 / AC-009）。
- 评审焦点：`code-map` 的 `nodes` 上限 24、`edges` 上限 48；论证链一张图不超过 8 个节点，
  超了说明这篇该拆。

## S4 · 验证

### TASK-009 | done | `books.spec.ts` e2e

- 文件：`frontend/apps/app/e2e/books.spec.ts`（新建）。
- 内容：三条用例——导航入口可达（`learner-nav-books` → URL 为 `/books`）；书卡可见且篇数正确；
  点进章节页首条为总览。
- 验收：`playwright test e2e/books.spec.ts` 全绿；不依赖本地种子数据的具体文案以外的内容
  （按 AGENTS.md 的 e2e 纪律，选文件跑而不是全量）。
- 评审焦点：不要断言"第一篇标题等于某个固定字符串"以外的脆弱选择器；用 `data-testid`。

### TASK-010 | done | 验收报告与线上导入

- 文件：`.agent/changes/WORK-046-书籍详解专题/testing/report.md`（新建）。
- 内容：跑真实导入、逐条核对 AC-001..AC-009，写验收报告（证据表四类标题 + 五列）。
- 验收：`project-lifecycle validate . --strict` 通过；报告里每条 AC 有命令与实测结果。
- **线上导入未做**（报告已写完、本地逐条验证过）：本项尚未部署，
  生产库还没有 `books` 分类。部署后跑一次
  `node scripts/import-course.mjs --dir content/reading/books-man-month --no-track` 才算闭环。
- 评审焦点：AC-004 要真的打 `/api/v1/learning/lines` 看返回，不能只看分类表 `line_kind` 为空。

## 未覆盖（明确不做）

- 书籍的结构化元信息（作者/版次/ISBN 成字段）。
- 多本书的批量管理界面。
- 章节级的阅读进度聚合（"读完这本书百分之几"）。
- 书籍内搜索。
- 第二个新围栏块（概念图/分步演示）。等 S3 写完再判断需不需要。
