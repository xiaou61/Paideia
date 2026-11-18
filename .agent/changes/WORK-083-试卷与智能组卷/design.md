---
artifact: design
work_id: WORK-083
work: 试卷与智能组卷
status: approved
mode: strict
workflow: full
created: 2026-11-18
updated: 2026-11-18
approval_ref: 2026-11-18 用户确认「按推荐方案进入设计」（proposal 已签署）
approved_by: xiaou61
approved_at: 2026-11-18 15:41:20 +0800
approver_role: CTO
---

# 试卷与智能组卷设计

> 依据：已批准的 `requirements.md`（approved 2026-11-18 15:29:52）与 `proposal.md`（approved 2026-11-18 15:36:17）。本设计不新增产品范围。
> 现有事实以读码为准：题目身份由 `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/question/QuestionCatalog.java` 维护（`entryId + blockKey + questionKey + fingerprint`），正文是唯一权威；判分/留痕在 `question_attempts`；题目↔概念在 `question_concepts`；BKT 掌握度在 `concept_mastery_state`；Elo 难度在 `question_difficulty`；概念前置边在 `knowledge_concept_edges`；条目分类树在 `knowledge_categories`。

## 变更清单

| # | 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `backend/paideia-app/src/main/resources/db/migration/V<ts>__create_question_index.sql` | 新增 | 无 | `question_index` 表（派生索引，不存题干/答案） | REQ-001/002/009，AC-001 |
| 2 | `backend/paideia-app/src/main/resources/db/migration/V<ts>__create_papers.sql` | 新增 | 无 | `papers` / `paper_items` / `paper_answers` 表 | REQ-004/005/006/008，AC-002/003/004/006 |
| 3 | `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/question/QuestionIndexService.java` | 新增 | 无 | 索引刷新/对账/查询（计划名） | REQ-001/011，AC-001 |
| 4 | `.../internal/question/QuestionIndexMapper.java` | 新增 | 无 | `question_index` 增删查（计划名） | REQ-001 |
| 5 | `.../internal/question/DifficultyLabel.java` | 新增 | 无 | 纯函数：Elo/规则→易·中·难档（计划名） | REQ-002/009 |
| 6 | `.../internal/question/QuestionLabelingPort.java` | 新增 | 无 | 难度 AI 标注端口（可选，缺失走规则；计划名） | REQ-002 |
| 7 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/question/QuestionLabelingAdapter.java` | 新增 | 无 | 实现 `QuestionLabelingPort`（复用既有 `QuestionTaggingPort` 同款接法；计划名） | REQ-002 |
| 8 | `.../internal/paper/PaperAssembler.java` | 新增 | 无 | 纯函数组卷（规则×去重×确定性排序；计划名） | REQ-004，AC-002 |
| 9 | `.../internal/paper/PaperScorer.java` | 新增 | 无 | 纯函数判分与按概念/难度聚合（计划名） | REQ-005/006，AC-003/004 |
| 10 | `.../internal/paper/PaperService.java` | 新增 | 无 | 建卷/取卷/交卷/结果（计划名） | REQ-004/005/006/008 |
| 11 | `.../internal/paper/PaperMapper.java` | 新增 | 无 | `papers`/`paper_items`/`paper_answers` 读写（计划名） | REQ-004/008 |
| 12 | `.../internal/question/PracticeService.java` | 新增 | 无 | 按标签选题、批量取题干选项（不含答案；计划名） | REQ-010，AC-007/008 |
| 13 | `.../internal/web/PaperController.java` | 新增 | 无 | `/api/v1/learning/papers/**`，`@ApiAccess(AUTHENTICATED)`（计划名） | REQ-004/005/006/008 |
| 14 | `.../internal/web/PracticeController.java` | 新增 | 无 | `/api/v1/learning/practice/**`（计划名） | REQ-010 |
| 15 | `.../internal/web/WrongBookController.java` | 新增 | 无 | `/api/v1/learning/wrong-questions`（计划名） | REQ-007 |
| 16 | `.../internal/question/QuestionAttemptService.java:52` | 修改 | `record()` 内联"插入作答 + 更新 Elo + 更新 BKT" | 抽出 `recordJudged(...)` 供试卷复用；`record()` 改为解析后委托，**行为不变** | REQ-005/006 |
| 17 | `.../internal/question/QuestionAttemptMapper.java` | 修改 | 无"按标签/未做/错题"查询 | 新增 `findWrongLatest`、`findCandidatesByFilter` | REQ-007/010，AC-007 |
| 18 | 条目保存/发布路径（`internal/entry` 的保存服务，实施时定位具体方法） | 修改 | 保存后无索引动作 | 保存/发布后调用 `QuestionIndexService.reconcile(entryId)` | REQ-001/011 |
| 19 | `frontend/packages/knowledge/src/practice-session.tsx` | 新增 | 无 | 刷题会话视图（标签筛选、逐题即时反馈） | REQ-010，AC-007/008 |
| 20 | `frontend/packages/knowledge/src/paper.tsx` | 新增 | 无 | 组卷/试卷/成绩视图 | REQ-004/005/006 |
| 21 | `frontend/apps/app/src/pages/PracticePage.tsx` 等（`PaperListPage`/`PaperPage`/`PaperResultPage`/`WrongBookPage`） | 新增 | 无 | 五个页面 | REQ-006/007/010 |
| 22 | `frontend/apps/app/src/router.tsx` + `LearnerHeader` NAV + `@paideia/identity` 的 `preferredNav` 联合字面量 | 新增 | 无入口 | 新增 `/practice`、`/papers`、`/papers/:id`、`/wrong-book` 路由与导航入口 | 项目规则「加页面必须加入口」 |
| 23 | `frontend/packages/knowledge/src/api.ts`（既有客户端，实施时定位） | 修改 | 无试卷/刷题客户端方法 | 新增会话/试卷/错题接口封装 | REQ-004/010 |

> 删除项：无。本设计不删除既有表、接口或行为。

## 系统上下文

- **角色**：学习者（做题、组卷、看分析、错题本）；管理员/内容作者（维护分类树与概念、触发难度标注与索引重建）。
- **涉及模块**：`paideia-knowledge`（本项全部新代码）、`paideia-app`（迁移与装配）、`paideia-ai`（仅实现可选难度标注端口）、`paideia-platform`（`ApiAccess`/`ApiResponse`/`ErrorCode`/`Hashes`）。
- **不涉及**：`paideia-ai` 的训练营（`internal/camp`）行为不变；`paideia-sandbox`、计费不改。
- **依赖方向**：`paideia-ai → paideia-knowledge`（既有方向，训练营已如此）；难度标注端口定义在 knowledge、实现在 ai，因此不新增反向依赖。

## 组件与职责

- **QuestionIndexService（新增）**：扫描已发布条目的正文 → `QuestionCatalog.parse` → 写 `question_index`；对某一条目 `reconcile(entryId)`（diff 身份增删改）；读取前用 `fingerprint` 校验，不一致即重解析该条目并回写。**不写题干/答案**。
- **DifficultyLabel（新增，纯函数）**：`estimate(rating, attempts, orderInBlock)` → `easy|medium|hard`：`attempts >= CALIBRATED_ATTEMPTS(5)` 时按 Elo 分桶（`<=1100 easy` / `>=1300 hard` / 其余 `medium`）；未校准时用 AI 预标注值；都缺失时兜底 `medium` 并在视图标「难度未校准」。
- **QuestionLabelingPort（新增端口）+ ai 实现**：一次性把题目题干+选项交模型打难度标签，写回 `question_index.difficulty_label`；无 ai 模块/凭据时返回空、不报错（与 `QuestionTaggingPort` 同款纪律）。
- **PaperAssembler（新增，纯函数）**：输入候选（`question_index` 行 + `question_concepts` 概念）与 `PaperRule`，输出题目身份列表；负责难度分布、去重、稳定排序、题量不足如实计数。无 DB、无时钟。
- **PaperScorer（新增，纯函数）**：给定 `paper_items` 快照与作答，逐题判定、按概念/难度聚合、判定薄弱项（沿用 WORK-029 的 <80% / 错≥2 / 难档全错口径，不造第二套）。
- **PaperService（新增）**：建卷（调 Assemble + 落快照）、列卷、取卷（**剥答案**）、交卷（用快照判分 + 落 `paper_answers` + 经 `recordJudged` 更新 BKT/Elo 与留痕）、结果分析。
- **PracticeService（新增）**：按标签/概念筛选题身份（排除已做/仅错题），批量解析条目正文返回题干选项（**不含答案**）；作答仍走既有 `POST /api/v1/learning/attempts`。
- **QuestionAttemptService（修改）**：抽出 `recordJudged(userId, entryId, blockKey, questionKey, fingerprint, optionCount, correct, conceptIds, now)`，被 `record()` 与试卷交卷共用；新增错题查询与按过滤选题。
- **条目保存路径（修改）**：保存/发布后触发 `QuestionIndexService.reconcile(entryId)`。

## 请求或事件流程

**组卷（学名 Learner）**
1. `POST /api/v1/learning/papers` body=`PaperRule{title, scope, difficultyDistribution, count}`。
2. `PaperService` 查候选：概念范围 → `question_concepts` 取身份；分类范围 → `knowledge_categories` 子树取 `entry_id` 集合 → `question_index`；难度 → `question_index.difficulty_label`。
3. `PaperAssembler.assemble(candidates, rule)` → 确定性结果（不足则返回 `shortfall`）。
4. 落 `papers`（`rule_json` 快照、`status=ready`）+ `paper_items`（含 `fingerprint` 与题干/选项/答案**快照**）。
5. 返回卷面：**仅** `seq/stem/options/difficulty_label/concept`，**无 answer_index、无解析**。

**交卷**
1. `POST /api/v1/learning/papers/{id}/submit` body=`[{itemId, choice}]`。
2. 校验：项数/归属/已交（重复交卷 409）。
3. `PaperScorer.score(items, answers)` → 逐题 correct；写 `paper_answers`。
4. 对每个 item 以**快照 fingerprint** 调 `recordJudged(...)`：写 `question_attempts`（`judged_by=server`）、`updateDifficulty`（按 fingerprint）、`updateMastery`（按概念；多概念各更新一次）。
5. `papers.status=submitted`、写 `correct_count/total_count`；返回按概念/难度聚合（**不出 0–100 总分**）。

**刷题**
1. `POST /api/v1/learning/practice/sessions` body=`Filter{categoryId?, conceptIds?, difficultyLabel?, onlyWrong?, onlyUndone?, limit}`。
2. `PracticeService` 查 `question_index`（+用户 `question_attempts` 过滤已做/错题）→ 返回题目身份与题干选项（无答案）。
3. 逐题作答走既有 `POST /api/v1/learning/attempts`，即时返回对错与解析（解析仅在作答后），并更新 BKT/Elo。**不生成 paper 记录**。

**错题本**
1. `GET /api/v1/learning/wrong-questions?conceptId=&categoryId=` → `question_attempts` 每身份最新一次 `correct=false`。

**索引刷新（管理员/发布事件）**
1. 条目保存/发布 → `QuestionIndexService.reconcile(entryId)`。
2. 全量重建：`POST /api/v1/admin/learning/questions/reindex`（`ToolScope.ADMIN`），计数返回 created/updated/removed。

## 接口与数据

### 新增接口（全部用户私有，`entryId`/`paperId` 均按令牌校验，他人 id 返回 404）

| 方法 | 路径 | 说明 | 关键校验 |
| --- | --- | --- | --- |
| POST | `/api/v1/learning/papers` | 组卷 | 规则合法；候选不足如实 `shortfall` |
| GET | `/api/v1/learning/papers` | 我的试卷列表 | 按令牌过滤 |
| GET | `/api/v1/learning/papers/{id}` | 卷面（无答案） | 归属；ready 才可取 |
| POST | `/api/v1/learning/papers/{id}/submit` | 交卷 | 未交过/项齐全/不含伪造 correct |
| GET | `/api/v1/learning/papers/{id}/result` | 成绩分析 | 已交卷 |
| POST | `/api/v1/learning/papers/{id}`（DELETE 语义用 `@DeleteMapping`） | 删卷 | 归属；连带 items/answers |
| POST | `/api/v1/learning/practice/sessions` | 刷题选题 | 过滤合法；返回无答案 |
| GET | `/api/v1/learning/wrong-questions` | 错题本 | 按令牌过滤 |
| POST | `/api/v1/admin/learning/questions/reindex` | 全量重建索引 | `ADMIN` |

- 请求/响应类型（记录类，计划名）：`PaperRule`、`PaperView`、`PaperItemView`、`SubmitRequest`、`PaperResult`、`PracticeFilter`、`PracticeQuestion`、`WrongQuestionView`。
- **不泄题**：`PaperItemView` / `PracticeQuestion` 不含 `answerIndex` 与解析；解析只在作答/交卷响应里返回（复用 `AttemptResult` 口径）。

### 数据表（新增，14 位时间戳命名，只建结构不插业务数据）

`question_index`
```
id BIGINT PK, entry_id BIGINT, category_id BIGINT, block_key VARCHAR(64),
question_key VARCHAR(128), fingerprint CHAR(64), option_count INT,
difficulty_label VARCHAR(8), status VARCHAR(16) DEFAULT 'active', refreshed_at DATETIME(3)
UNIQUE(entry_id, block_key, question_key); KEY(entry_id); KEY(category_id); KEY(difficulty_label)
```
> 派生表：不存题干/选项/答案，正文仍唯一权威；`category_id` 由条目反范式化以支持子树筛选。

`papers`
```
id BIGINT PK, user_id BIGINT, title VARCHAR(200), rule_json MEDIUMTEXT,
status VARCHAR(16), submitted_at DATETIME(3) NULL, correct_count INT NULL, total_count INT NULL, created_at DATETIME(3)
KEY(user_id, created_at)
```

`paper_items`（**试卷是历史快照，允许复制题干/选项/答案**）
```
id BIGINT PK, paper_id BIGINT, user_id BIGINT, seq INT, entry_id BIGINT,
block_key VARCHAR(64), question_key VARCHAR(128), fingerprint CHAR(64),
concept_id BIGINT NULL, difficulty_label VARCHAR(8),
stem_snapshot TEXT, options_json TEXT, answer_index_snapshot INT, explanation_snapshot TEXT NULL
UNIQUE(paper_id, seq); KEY(paper_id)
```

`paper_answers`
```
id BIGINT PK, paper_id BIGINT, user_id BIGINT, item_id BIGINT,
choice_index INT, correct TINYINT(1), created_at DATETIME(3)
UNIQUE(paper_id, item_id); KEY(paper_id)
```

## 持久化与迁移

- 新增两条迁移（`question_index` 与 `papers` 分文件或合一，按实施）：**仅建结构**，不插业务数据（`MigrationSeedOverlapTest` 守卫）。
- 时间戳命名 `V2026MMDDHHmmss__描述.sql`，执行前用 `git log --all -- <迁移目录>` 看是否有人改同一批表；不写死递增号。
- 不建跨模块外键；`user_id` 归属无 FK（与既有 `question_attempts` 一致）。
- 注销级联：`papers`/`paper_items`/`paper_answers`/`question_index` 中与用户相关的行——前三者按 `user_id` 在注销流程中删除；`question_index` 是全局派生表**不含用户**，不随注销删除（需在注销用例里确认派生表不受影响）。**注**：`question_index` 无 `user_id`，故注销只删 `papers`/`paper_items`/`paper_answers` + 既有 `question_attempts`/`concept_mastery_state`。
- 兼容：`question_index` 可随时重建（幂等），不迁移既有数据；首次上线执行一次全量重建即可。

## 失败处理与恢复

- 组卷候选不足：`shortfall` 如实返回，不静默少给；不足 80% 时在响应标「题量不足」。
- 内容在组卷后修改：`paper_items` 用快照判分；`result` 标「来源已更新，建议重做」。
- `recordJudged` 与 `record` 共用同一事务语义；试卷交卷失败不落半条数据（`paper_answers` 与 `papers.status` 同事务）。
- 索引重建失败：保留旧索引（先写后切换/或按条目 reconcile 局部失败跳过并计数），不阻塞发布；`status` 只增不误删。
- AI 难度标注失败/无凭据：跳过，回退规则/默认 `medium`，不阻断组卷。

## 安全与权限

- 所有学习者接口 `@ApiAccess(ApiAccess.Access.AUTHENTICATED)`，用户取自令牌；`paperId` 归属不匹配一律 404（不是 403，防探侧）。
- `question_index` 重建接口 `ToolScope.ADMIN`。
- 判分只在服务端：交卷请求体无 `correct`；卷面/刷题响应无 `answerIndex`/解析。
- 不可信内容：题干/选项来自正文，是数据不是指令；显示转义。
- 新增 `/api/v1/**` 必须进 `ApiContractTest`（通行证/401/403 矩阵）与 `AuthorizationIsolationTest`。

## 可观测性

- 索引重建日志：`created/updated/removed/shortBlock` 计数；组卷日志：规则摘要 + `shortfall`。
- AI 难度标注是离线/管理触发；若走模型，新增一条 `RunKind` 常量（对齐 WORK-022），让消耗可单独筛。
- 不新增运行时监控面；沿用既有 `RunRecorder`（若标注走 AI）。

## 实施顺序

1. **索引与标签**：迁移 #1 + `QuestionIndex*` + `DifficultyLabel` + 条目保存钩子 + 重建接口；`QuestionLabelingPort`/ai 实现（可后置）。
2. **试卷**：迁移 #2 + `PaperAssembler`/`PaperScorer`/`PaperService`/`PaperMapper` + `recordJudged` 抽取 + 试卷接口。
3. **刷题**：`PracticeService` + 接口 + `preferredNav`/路由/导航。
4. **错题本 + 成绩页**：派生查询 + 前端页面。
5. **收尾**：BKT/Elo 联动校验、窄屏、契约/隔离测试、路由↔NAV 自查、迁移与部署验证。

## 测试策略

- **纯函数单测**：`PaperAssembler` 确定性（同输入两次逐题相同；乱序候选仍按稳定键）；`DifficultyLabel` 分桶边界；`PaperScorer` 聚合与薄弱判定；`PracticeService` 过滤谓词（只刷错题/未做）。
- **后端集成（走 `scripts/test-db.sh`，不直连库）**：
  - 抽取/索引：对现有内容重建 → `question_index` 题数与 387 篇/1147 题一致（或与实际计数一致）；坏块不收录、计数正确（AC-001）。
  - 组卷：同规则两次逐题相同（AC-002）；交卷前响应无 `answer`（AC-003）；全对=概念 100% 无弱项、故意错 2 题计数正确（AC-004）。
  - 关系/隔离：概念列出其题与前置概念题（AC-005）；他人 paper id 404（AC-006）；注销后 `papers`/`paper_items`/`paper_answers`/`question_attempts`/`concept_mastery_state` 逐表 0 行。
  - 刷题：`课程线=python 且 难度=中` 题数一致、组合取交集（AC-007）；单题即时判定并落 `question_attempts`、不产生 paper（AC-008）。
  - 守卫：`ApiContractTest` / `AuthorizationIsolationTest` / 迁移三件（命名/校验和/种子重叠）。
- **前端 e2e**：刷题页按标签筛选与即时反馈；组卷→交卷→结果；路由↔NAV 入口存在（`playwright test e2e/<对应文件>`，不全量）。
- **内环切片**（AGENTS.md）：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` 与 `paideia-app` 守卫子集，不跑全量；跨共享包改动才扩到 e2e 全量。

## 需求追踪

| 需求 | 设计元素 | 测试 |
| --- | --- | --- |
| REQ-001 抽取 | `QuestionIndexService.refreshAll/reconcile` + 迁移 #1 | AC-001 |
| REQ-002 元数据 | `question_index` 列 + `DifficultyLabel` | AC-004 |
| REQ-003 概念关系 | 复用 `question_concepts` + `knowledge_concept_edges` | AC-005 |
| REQ-004 规则组卷 | `PaperAssembler` + `papers/paper_items` | AC-002 |
| REQ-005 判分 | `PaperScorer` + `recordJudged` + 无答案下发 | AC-003/004 |
| REQ-006 成绩分析 | `PaperResult` 按概念/难度聚合 | AC-004 |
| REQ-007 错题回流 | `findWrongLatest` + `WrongBookController` | AC-005/006 |
| REQ-008 归属生命周期 | 令牌过滤/404/注销级联 | AC-006 |
| REQ-009 分类标签 | `question_index.category_id` + 概念 + `DifficultyLabel` | AC-007 |
| REQ-010 刷题界面 | `PracticeService` + `PracticeController` + 前端 | AC-007/008 |
| REQ-011 共用题库 | 刷题/组卷/错题同读 `question_index`+概念+判分 | AC-008 |

## 待决定事项

- **P1 难度初值来源**（已按推荐）：规则推断 + AI 一次性标注得 `difficulty_label`，运行期用 Elo 校准；若不做 AI 标注，则大量题落 `medium`，组卷难度分布失真——建议保留 AI 标注步骤。
- **P2 试卷交卷是否也写 `question_attempts`**（建议是）：让错题本/只刷未做统一；`judged_by=server`。
- **P3 索引刷新触发点**：保存即 `reconcile(entryId)`（推荐）vs 仅定时/手动全量重建。
- **P4 `question_index` 是否再冗余 `stem`**（建议否）：会引入第二真相；刷题题干按需解析条目正文。
- **P5 前端归属**（已按推荐）：扩展 `frontend/packages/knowledge`；未来体量大再拆包。
