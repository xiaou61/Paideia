---
artifact: tasks
work_id: WORK-083
work: 试卷与智能组卷
status: approved
mode: strict
workflow: full
created: 2026-11-18
updated: 2026-11-18
approved_by: xiaou61
approved_at: 2026-11-18 15:44:39 +0800
approver_role: CTO
---

# 试卷与智能组卷实施任务

## 依据

- `requirements.md`（approved 2026-11-18 15:29:52）
- `proposal.md`（approved 2026-11-18 15:36:17）
- `design.md`（approved 2026-11-18 15:41:20）

任务只把上述已批准的决定转成执行顺序，不新增产品范围或架构。接口设计详见 `design.md` 的「接口与数据」，此处只写相邻任务互用的字面签名。

## 全局约束

- 技术栈：Spring Boot 4 / JDK 25 / 手写 MyBatis（禁 MyBatis-Plus）/ 仅 MySQL / Flyway / React 19。不引入向量检索、不新增前端依赖（除已批准者）。
- 迁移：`V2026MMDDHHmmss__描述.sql` 14 位时间戳；只建结构不插业务数据；不建跨模块外键；写前 `git log --all -- <迁移目录>` 查冲突。
- 接口：新增 `/api/v1/**` 一律 `@ApiAccess(ApiAccess.Access.AUTHENTICATED)`（管理面走 `PaideiaTool` + `ToolScope.ADMIN`），并入 `ApiContractTest` / `AuthorizationIsolationTest`。
- 不泄题：卷面 / 刷题响应不得含 `answerIndex` 或解析；解析仅在作答后返回。
- 测试只走 `scripts/test-db.sh`（不直连库、不 `mvn test`）；内环切片不跑全量；e2e 先选文件。
- 加页面必须加入口：`router.tsx` 每条路由在 `LearnerHeader` 的 `NAV` 或账号菜单有对应项，并同步 `@paideia/identity` 的 `preferredNav` 联合字面量。
- 秘密不进仓库；改动与提交只含本项范围。

## 接口契约（跨任务字面一致）

- `QuestionIndexService.reconcile(long entryId): int`（增删改行数合计）；`QuestionIndexService.refreshAll(): RebuildResult`，`RebuildResult(int created, int updated, int removed, int skippedBlocks)`。
- `DifficultyLabel.estimate(Integer rating, int attempts): String` → `"easy" | "medium" | "hard"`。
- `PaperAssembler.assemble(List<Candidate> candidates, PaperRule rule): AssemblyResult`；`Candidate(long entryId, String blockKey, String questionKey, String fingerprint, int optionCount, String difficultyLabel, List<Long> conceptIds)`；`PaperRule(String title, List<Long> conceptIds, Long categoryId, Map<String,Integer> difficultyDistribution, int count, List<String> excludeFingerprints)`；`AssemblyResult(List<Candidate> items, int shortfall)`。
- `PaperScorer.score(List<PaperItemSnapshot> items, Map<Long,Integer> answersByItemId): ScoreResult`；`ScoreResult(int correct, int total, List<ConceptStat> concepts, List<DifficultyStat> difficulties, List<Long> weakConceptIds)`。
- `QuestionAttemptService.recordJudged(long userId, long entryId, String blockKey, String questionKey, String fingerprint, int optionCount, boolean correct, List<Long> conceptIds, java.time.Instant now)`（供试卷交卷复用；`record()` 行为不变）。
- `PracticeService.listQuestions(long userId, PracticeFilter filter): List<PracticeQuestion>`；`PracticeFilter(Long categoryId, List<Long> conceptIds, String difficultyLabel, boolean onlyWrong, boolean onlyUndone, int limit)`；`PracticeQuestion(String blockKey, String questionKey, long entryId, String stem, List<String> options, String difficultyLabel)`。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 坏块/被删题目身份残留索引 | TASK-001 | `QuestionIndexIntegrationTest#reconcile_dropsRemovedIdentity` |
| 同规则并发两次组卷出现重复 seq/重复题 | TASK-002 | `PaperIntegrationTest#assemble_sameRuleTwiceIdentical` |
| 交卷前响应泄露答案 | TASK-002 / TASK-004 | `PaperIntegrationTest#noAnswerBeforeSubmit`、`PracticeIntegrationTest#noAnswerInSessionResponse` |
| 重复交卷覆盖成绩 | TASK-003 | `PaperIntegrationTest#doubleSubmitConflict` |
| 组卷后正文被改，判分与题干漂移 | TASK-003 | `PaperIntegrationTest#gradeUsesSnapshotAfterBodyEdit` |
| 候选不足被静默少给 | TASK-002 | `PaperAssemblerTest#shortfallReported` |
| 只刷错题/只刷未做过滤错 | TASK-004 | `PracticeFilterTest#onlyWrongAndOnlyUndoneRespectLatestAttempt` |
| 他人 paper/错题 id 越权 | TASK-003 / TASK-005 | `PaperIntegrationTest#foreignPaper404`、`WrongBookIntegrationTest#isolation404` |
| 无 AI 标注端口时组卷/难度不可用 | TASK-008 | `DifficultyLabelTest#portAbsentFallsBackMedium` |
| 注销后试卷相关表残留 | TASK-009 | `AccountDeletionCascadeIntegrationTest#deletesPaperTables` |
| 窄屏作答/结果页横向滚动 | TASK-006 / TASK-007 | `e2e/practice.spec.ts`、`e2e/papers.spec.ts` 的 390px 用例 |

## 任务

### TASK-001 | done | 题库索引层与重建

- 对应：`REQ-001`、`REQ-002`（难度规则部分）、`REQ-009`（派生标签部分）、`AC-001`
- 依赖：无
- 新建/修改：
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V<ts>__create_question_index.sql`（列见 design.md「数据表」；派生表，不存题干/答案）。
  - 新建 `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/question/QuestionIndexService.java`、`QuestionIndexMapper.java`、`DifficultyLabel.java`（规则部分）。
  - 修改条目保存/发布路径（`internal/entry` 的保存服务，实施时定位具体方法）：保存成功后调用 `QuestionIndexService.reconcile(entryId)`。
  - 新建管理工具（`internal/tools/LearningAdminTools` 风格）`admin_learning_reindex_questions`：`ToolScope.ADMIN`，调 `refreshAll()`，返回 created/updated/removed 计数。
- 接口：产出 `QuestionIndexService.reconcile/refreshAll`、`DifficultyLabel.estimate`；消费 `QuestionCatalog.parse(entryId, body)`、`EntryMapper` 列已发布条目。
- 测试：新建 `backend/paideia-knowledge/src/test/java/io/github/xiaou61/knowledge/internal/question/DifficultyLabelTest.java`；新建 `backend/paideia-app/src/test/java/io/github/xiaou61/QuestionIndexIntegrationTest.java`。
- 步骤：
  1. 写 `question_index` 迁移；跑 `MigrationVersionFormatTest` 确认命名通过。
  2. 写 `QuestionIndexMapper`（upsert/deleteByEntry/listByEntry/summary）与 `QuestionIndexService.refreshAll/reconcile`，`reconcile` 按 `fingerprint` 差异增改删。
  3. 写 `DifficultyLabel.estimate`：`attempts >= 5` 按 Elo 分桶（`<=1100 easy / >=1300 hard / 其余 medium`），否则 `medium`。
  4. 挂条目保存钩子与 `admin_learning_reindex_questions` 工具。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=QuestionIndexIntegrationTest,MigrationVersionFormatTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望全绿；断言 `question_index` 题数 == 全库 `QuestionCatalog.parse` 计数；坏块不收录；改内容后 `reconcile` 更新该身份、删掉不存在身份。
- 结果记录（2026-11-18）：`DifficultyLabelTest` 3 条绿；`QuestionIndexIntegrationTest` 4 条绿；Flyway 已在远程测试库 `paideia_e2e` 应用 `V20261118160000__create_question_index.sql`（schema 前进到该版本）。已挂 `EntryAdminService` create/update/delete 的 `reconcile` 钩子与 `admin_learning_reindex_questions` 工具。

### TASK-002 | done | 规则组卷与试卷读取

- 对应：`REQ-004`、`REQ-005`（不泄题部分）、`AC-002`、`AC-003`
- 依赖：TASK-001
- 新建/修改：
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V<ts>__create_papers.sql`（`papers`/`paper_items`/`paper_answers`，列见 design.md）。
  - 新建 `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/paper/PaperAssembler.java`（纯函数）、`PaperMapper.java`、`PaperService.java`、`backend/.../internal/web/PaperController.java`（`POST/GET /api/v1/learning/papers`、`GET/{id}`、`DELETE/{id}`）。
- 接口：`PaperAssembler.assemble`、`PaperRule`、`Candidate`、`AssemblyResult`（见「接口契约」）；`PaperService.create(long userId, PaperRule rule): PaperView`、`list/get/delete`。
- 测试：新建 `backend/paideia-knowledge/.../question/../paper/PaperAssemblerTest.java`（放 `internal/paper` 包）与 `backend/paideia-app/src/test/java/io/github/xiaou61/PaperIntegrationTest.java`。
- 步骤：
  1. 写 `papers` 迁移（三表）。
  2. 写 `PaperAssembler`：按难度分布选候选、稳定排序（`entry_id, block_key, question_key`）、去重、不足计 `shortfall`；纯函数无 DB/时钟。
  3. 写 `PaperService.create`：查候选（概念→`question_concepts`；分类→子树→`question_index`）→ assemble → 落 `papers`+`paper_items`（含 `fingerprint`、`stem_snapshot`、`options_json`、`answer_index_snapshot`、`explanation_snapshot`）。
  4. 写 `PaperController` 读取：`PaperItemView` **不含** `answerIndex`/解析；他人 id 404。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=PaperIntegrationTest,PaperAssemblerTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望同规则两次逐题相同、seq 连续无重复；组卷响应字段无 `answerIndex`；候选不足 `shortfall>0` 如实返回。
- 结果记录（2026-11-18）：`PaperAssemblerTest` 4 条绿；`PaperIntegrationTest` 5 条绿（卷面无 answerIndex + seq 连续、shortfall 如实、同规则两次一致、取/删卷、他人 404）；Flyway 应用 `V20261118161000__create_papers.sql`（schema 前进到该版本）。`DifficultyLabel.estimate` 落为 `(Double rating, int attempts)`（null→medium，与契约 `Integer` 等价）。

### TASK-003 | done | 交卷判分与成绩分析

- 对应：`REQ-005`、`REQ-006`、`AC-004`、`AC-006`
- 依赖：TASK-001、TASK-002
- 新建/修改：
  - 新建 `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/paper/PaperScorer.java`（纯函数）。
  - 修改 `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/question/QuestionAttemptService.java`：抽 `recordJudged(...)`，`record()` 委托之且行为不变。
  - 修改 `PaperService` 增 `submit/result`；`PaperController` 增 `POST/{id}/submit`、`GET/{id}/result`。
- 接口：`PaperScorer.score`、`ScoreResult`、`ConceptStat`、`DifficultyStat`（见「接口契约」）；`QuestionAttemptService.recordJudged(...)`。
- 测试：新建 `PaperScorerTest.java`（`internal/paper` 包）；扩 `PaperIntegrationTest`；回归 `backend/paideia-app/src/test/java/io/github/xiaou61/QuestionAttemptIntegrationTest.java` 必须原样绿。
- 步骤：
  1. 写 `PaperScorer`：用 `paper_items` 快照判定，按概念/难度聚合，薄弱判定用 <80% / 错≥2 / 难档全错。
  2. 抽 `recordJudged`：插入 `question_attempts`（`judged_by=server`）+ Elo（按 fingerprint）+ BKT（按概念列表）；`record()` 改为解析后调它。
  3. `PaperService.submit`：校验归属/未交/项齐全 → score → 写 `paper_answers` → 每项 `recordJudged` → 置 `status=submitted` 与计数（同事务）。
  4. `result`：返回按概念/难度聚合，**不出 0–100 总分**。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=PaperIntegrationTest,PaperScorerTest,QuestionAttemptIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望全绿（全对=概念 100% 无弱项；错 2 题计数正确；重复交卷 409；改正文后仍按快照判分）。
- 结果记录（2026-11-18）：`PaperScorerTest` 5 条绿；`PaperIntegrationTest` 7 条绿（新增 doubleSubmitConflict→409、gradeUsesSnapshotAfterBodyEdit 正文改动后仍按快照判对 2/2、GET /result 可读）；`QuestionAttemptIntegrationTest` 回归绿（`record()` 抽出 `recordJudged(...)` 后行为不变）。交卷走 `recordJudged` 写 `question_attempts`+Elo+BKT，判分只读 `paper_items` 快照。

### TASK-004 | done | 刷题会话（按标签选题）

- 对应：`REQ-010`、`REQ-009`、`AC-003`、`AC-007`、`AC-008`
- 依赖：TASK-001
- 新建/修改：
  - 新建 `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/question/PracticeService.java`。
  - 修改 `backend/.../internal/question/QuestionAttemptMapper.java`：新增 `findCandidatesByFilter`（未做/错题过滤）。
  - 新建 `backend/.../internal/web/PracticeController.java`：`POST /api/v1/learning/practice/sessions`。
- 接口：`PracticeService.listQuestions`、`PracticeFilter`、`PracticeQuestion`（见「接口契约」）。作答复用既有 `POST /api/v1/learning/attempts`。
- 测试：新建 `backend/paideia-knowledge/.../internal/question/PracticeFilterTest.java`；新建 `backend/paideia-app/src/test/java/io/github/xiaou61/PracticeIntegrationTest.java`。
- 步骤：
  1. 写 `PracticeFilterTest`：只刷错题=最新一次 `correct=false`；只刷未做=无 `question_attempts`；组合取交集。
  2. 写 `PracticeService.listQuestions`：查 `question_index`（+概念/难度/分类过滤）+ 用户作答过滤 → 按条目分组解析正文返回 `stem/options`（无答案）。
  3. 写 controller；断言响应无 `answerIndex`；作答走既有 attempts，落 `question_attempts` 且**无 paper 行**。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=PracticeIntegrationTest,PracticeFilterTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望按标签取交集题数一致、响应无答案、交卷后 `papers` 表 0 行。
- 结果记录（2026-11-18）：`PracticeFilterTest` 4 条绿；`PracticeIntegrationTest` 1 条绿（按标签取交集、响应无 answerIndex、交卷后 `papers` 0 行）。契约微调：`PracticeFilter` 的布尔/整数改用包装类型 `Boolean onlyWrong, Boolean onlyUndone, Integer limit`（缺省即 false/20）——原始类型缺字段会被 Jackson 判 400，前端不应被迫传全字段。`QuestionAttemptMapper.findPracticeCandidates` 承接 `question_index` 的 latest-correct 与 attempt 计数子查询。

### TASK-005 | done | 错题本

- 对应：`REQ-007`、`REQ-003`（关系展示）、`REQ-009`、`AC-005`、`AC-006`
- 依赖：TASK-001、TASK-004
- 新建/修改：
  - 修改 `QuestionAttemptMapper` 增 `findWrongLatest(userId, categoryId, conceptId)`。
  - 新建 `backend/.../internal/web/WrongBookController.java`：`GET /api/v1/learning/wrong-questions`。
- 接口：`WrongQuestionView`（含 `blockKey/questionKey/entryId/stem/options/difficultyLabel/concepts`）。
- 测试：新建 `backend/paideia-app/src/test/java/io/github/xiaou61/WrongBookIntegrationTest.java`。
- 步骤：
  1. 写 `findWrongLatest`：每身份最新一次 `correct=false`；可按概念/分类过滤。
  2. 写 controller，归属按令牌，他人 id 404。
  3. 断言错题带其概念标签、可点回条目。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=WrongBookIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望只返回最新一错、过滤正确、隔离 404。
- 结果记录（2026-11-18）：`WrongBookIntegrationTest` 3 条绿（只返回最新一错、最新答对即移出、按概念过滤、按用户隔离、响应无 answerIndex）。实现：`QuestionAttemptMapper.findWrongLatest` 用 `la.id = MAX(qa2.id)` 取每身份最新一次且 `correct=0`；`WrongBookService` 取 `QuestionIndexService.snapshot` 出题面、`mapper.findLinks`+`findAllConcepts` 出概念名；`WrongBookController` `GET /api/v1/learning/wrong-questions?categoryId&conceptId&limit`。

### TASK-006 | pending | 前端刷题页与入口

- 对应：`REQ-010`、`REQ-009`、`AC-007`、`AC-008`
- 依赖：TASK-004
- 新建/修改：
  - 新建 `frontend/packages/knowledge/src/practice-session.tsx`、`frontend/apps/app/src/pages/PracticePage.tsx`。
  - 修改 `frontend/packages/knowledge/src/api.ts`（实施时定位）：新增练习会话/作答封装。
  - 修改 `frontend/apps/app/src/router.tsx` 增 `/practice`；`LearnerHeader` 的 `NAV` 增「刷题」；`frontend/packages/identity` 的 `preferredNav` 联合字面量增 key。
- 接口：消费 `PracticeController`（`POST /practice/sessions`）与既有 `/learning/attempts`。
- 测试：新建 `frontend/apps/app/e2e/practice.spec.ts`（按标签筛选、逐题即时反馈、390px 无横向滚动）。
- 步骤：
  1. 加 API 客户端方法与页面骨架。
  2. 接标签筛选与逐题作答反馈（解析仅在作答后显示）。
  3. 加路由、NAV、`preferredNav`。
- 验证：`cd frontend/apps/app && pnpm exec playwright test e2e/practice.spec.ts` → 期望通过；`rg "path: '/practice'" frontend/apps/app/src/router.tsx` 与 NAV 均命中。

### TASK-007 | pending | 前端试卷/成绩/错题页与入口

- 对应：`REQ-006`、`REQ-007`、`AC-004`（结果视图）、`AC-005`
- 依赖：TASK-003、TASK-005
- 新建/修改：
  - 新建 `frontend/packages/knowledge/src/paper.tsx`、`frontend/apps/app/src/pages/PaperListPage.tsx`、`PaperPage.tsx`、`PaperResultPage.tsx`、`WrongBookPage.tsx`。
  - 修改 `frontend/packages/knowledge/src/api.ts` 增试卷/错题封装；`router.tsx` 增 `/papers`、`/papers/:id`、`/wrong-book`；`NAV` 与 `preferredNav` 同步。
- 接口：消费 `PaperController`、`WrongBookController`。
- 测试：新建 `frontend/apps/app/e2e/papers.spec.ts`（组卷→作答→结果并排/聚合；390px 无横向滚动；错题本可重练）。
- 步骤：
  1. 组卷表单 + 试卷作答页。
  2. 结果页按概念/难度聚合（不显示百分制总分），窄屏堆叠。
  3. 错题本页；路由/NAV/`preferredNav`。
- 验证：`cd frontend/apps/app && pnpm exec playwright test e2e/papers.spec.ts` → 期望通过；路由↔NAV 自查一致。

### TASK-008 | done | 难度 AI 标注与回退

- 对应：`REQ-002`、`REQ-009`
- 依赖：TASK-001
- 新建/修改：
  - 新建 `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/question/QuestionLabelingPort.java`。
  - 新建 `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/question/QuestionLabelingAdapter.java`（实现端口，复用既有 `QuestionTaggingPort` 同款接法）。
  - 修改 `QuestionIndexService`：刷新时优先用端口返回的难度标签，缺失回退 `DifficultyLabel.estimate`。
- 接口：`QuestionLabelingPort.Result tag(List<Question> qs)`；`Question(string questionKey, string stem, List<String> options)`。
- 测试：扩 `DifficultyLabelTest`（`portAbsentFallsBackMedium`）；`QuestionIndexIntegrationTest` 增「有端口写标签、无端口回退 medium」。
- 步骤：
  1. 定义端口；ai 侧实现（无凭据返回空、不报错）。
  2. `QuestionIndexService` 接入：端口有值写 `difficulty_label`，否则规则回退。
  3. 若走模型，加一条 `RunKind` 常量（对齐 WORK-022）便于统计。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=QuestionIndexIntegrationTest,DifficultyLabelTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望无端口时落 `medium` 不报错、有端口时写入给定标签。
- 结果记录（2026-11-18）：`DifficultyLabelTest` 4 条绿（含 `portAbsentFallsBackMedium`）；`QuestionLabelingIntegrationTest` 2 条绿（`@MockitoBean QuestionLabelingPort`：端口给 "hard" 则写入；端口抛错回退 medium）。实现：`QuestionLabelingPort`（`record Question` / `record Result(Map<String,String> difficultyByQuestionKey)` / `Result tag(List<Question>)`）；`QuestionIndexService` 用 `ObjectProvider<QuestionLabelingPort>` 取端口，无端口/异常/非法标签一律回退 `DifficultyLabel.estimate`；ai 侧 `QuestionLabelingAdapter` v1 占位返回空（未接模型、不产生计费），后续接模型时补 `RunKind`。

### TASK-009 | pending | 收尾：注销级联、窄屏、契约与部署验证

- 对应：`REQ-008`、`AC-006`（注销级联）
- 依赖：TASK-001…TASK-008
- 新建/修改：
  - 修改 `backend/paideia-app/src/test/java/io/github/xiaou61/AccountDeletionCascadeIntegrationTest.java` 增 `papers/paper_items/paper_answers` 逐表 0 行断言。
  - 确认 `ApiContractTest` / `AuthorizationIsolationTest` 覆盖全部新端点；迁移三件（`MigrationVersionFormatTest`/`MigrationChecksumTest`/`MigrationSeedOverlapTest`）绿。
  - 前端窄屏用例补进 `e2e/practice.spec.ts` / `e2e/papers.spec.ts`（若 TASK-006/007 未覆盖）。
- 接口：无新增；只为既有接口补守卫与级联。
- 测试：上面四类守卫测试 + 两 e2e 文件。
- 步骤：
  1. 补注销级联断言并跑绿。
  2. 跑契约/隔离/迁移守卫。
  3. 跑两 e2e 的 390px 用例。
  4. 执行 `powershell -File scripts/release.ps1`（纯后端约 75 秒；前端有改动才 `-WithWeb`），核对部署后接口可访问、无答案泄露。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AccountDeletionCascadeIntegrationTest,ApiContractTest,AuthorizationIsolationTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` → 期望全绿；部署后 `GET /api/v1/learning/papers` 需登录、他人 id 404。

## 完成条件

- 全部 `AC-001..AC-008` 至少在一个任务的「对应」中出现并有可执行测试。
- 每个任务以「验证命令 → 期望输出」收尾，结果据实记录。
- `scripts/test-db.sh` 内环切片绿；迁移三件、契约/隔离守卫绿；两 e2e 文件绿。
- 路由与 `NAV`/`preferredNav` 逐条对齐；窄屏 390px 无横向滚动。
- 无答案泄露、无越权、注销级联逐表 0 行。
- 部署后线上接口行为与测试一致。
