---
artifact: tasks
work_id: WORK-033
status: approved
created: 2026-10-22
updated: 2026-10-22
approved_by: xiaou61
approved_at: 2026-10-22 16:30:26 +0800
approver_role: CTO
---

# 题目知识点关联与掌握诊断任务

## 任务状态

| 任务 | 名称 | 状态 |
| --- | --- | --- |
| T-01 | 迁移与 Mapper | done |
| T-02 | 题目解析与指纹 | done |
| T-03 | 掌握度与难度纯函数 | done |
| T-04 | 作答端点（服务端判定） | done |
| T-05 | 概念标注与候选 | done |
| T-06 | 前置诊断与接口 | done |
| T-07 | 图谱接入 BKT | done |
| T-08 | 注销级联与前端逐题上报 | done |
| T-09 | 信息增益选题（REQ-008） | done |
| T-10 | 答错联动复习（REQ-009） | done |
| T-11 | 批量 AI 打标（REQ-010） | done |
| T-12 | 评估导出（REQ-012） | done |


## 计划质量门

- 每个任务都是可独立验证的纵向切片：迁移 + 纯函数 + 接口 + 一条能红的检查。
- 相邻任务的接口在下面逐字给出；实现时不得改名。
- 所有纯函数放在 `io.github.xiaou61.knowledge.internal.question`，不依赖数据库与时钟（时钟由调用方传）。
- 测试库一律走 `scripts/test-db.sh`。

## T-01 迁移与 Mapper

- 文件：`backend/paideia-knowledge/src/main/resources/db/migration/V20261022170000__create_question_attempts_and_mastery.sql`
  与 `internal/question/QuestionAttemptMapper.java`。
- 接口：

```java
@Mapper interface QuestionAttemptMapper {
  int insertAttempt(long userId, long entryId, String blockKey, String questionKey,
                    String fingerprint, Integer choiceIndex, boolean correct,
                    Integer latencyMs, String judgedBy, Instant now);
  List<Row> findByUser(long userId, int limit);
  List<LinkRow> findConcepts(String blockKey, String questionKey, long entryId);
  void replaceConcepts(long entryId, String blockKey, String questionKey,
                       List<LinkRow> links, Instant now);
  List<MasteryRow> findMastery(long userId);
  MasteryRow findMastery(long userId, long conceptId);
  void upsertMastery(long userId, long conceptId, double mastery, int attempts, int correct, Instant now);
  RatingRow findRating(String fingerprint);
  void upsertRating(String fingerprint, long entryId, String blockKey, String questionKey,
                    double rating, int attempts, int correct, Instant now);
  List<EdgeRow> findPrerequisiteEdges();
}
```

- 验收：在独占测试库上跑迁移成功；`describe` 四张表字段与 design 一致。

## T-02 题目解析与指纹

- 文件：`internal/question/QuestionCatalog.java` + `QuestionCatalogTest`。
- 接口：

```java
record Question(String blockKey, String questionKey, String fingerprint, String stem,
                List<String> options, int answerIndex) {}
static List<Question> parse(long entryId, String body);
static Optional<Question> find(long entryId, String body, String blockKey, String questionKey);
static String fingerprint(long entryId, String blockKey, int index, String stem,
                          List<String> options, int answerIndex);
```

- 验收：解析 quiz 围栏；改题干后指纹变化；缺 id 的块被忽略；坏 JSON 不抛异常（返回该块空）。

## T-03 掌握度与难度纯函数

- 文件：`internal/question/MasteryModel.java`、`internal/question/ItemRating.java` + 两个测试。
- 接口：

```java
static double update(double prior, boolean correct, double guess);
static String tier(double mastery, int attempts);

static double expected(double rating, double ability);
static double updateRating(double rating, boolean correct, double ability);
```

- 验收：答对使 mastery 上升、答错下降；上下界夹在 (0,1)；tier 四档边界；Elo 方向正确（难题答对→能力相对更强→rating 下调更少）。

## T-04 作答端点（服务端判定）

- 文件：`internal/question/QuestionAttemptService.java`、`QuestionAttemptController.java`。
- 接口：

```java
record AttemptRequest(long entryId, String blockKey, String questionKey, int choice, Integer latencyMs) {}
record ConceptMasteryView(long conceptId, double mastery, String tier) {}
record AttemptResult(boolean correct, String fingerprint,
                     List<ConceptMasteryView> concepts, List<Suggestion> suggestions) {}
AttemptResult record(long userId, AttemptRequest request);

// POST /api/v1/learning/attempts   AUTHENTICATED
```

- 逻辑：读条目正文 → 解析题目 → 判定 → 指纹 → 写 attempts → 更新题难度 → 对每个关联概念更新 BKT → 返回概念状态与诊断。
- 验收（集成测试）：答对/答错各一条，`concept_mastery_state` 的 attempts 递增；越界 choice 返回 400 且不落库；无关联概念时返回空列表不报错。

## T-05 概念标注与候选

- 文件：`internal/question/QuestionConceptSuggester.java`、`QuestionConceptAdminService.java`、`QuestionConceptAdminController.java` + 测试。
- 接口：

```java
record Suggestion(long conceptId, String name, double score, List<String> hits) {}
List<Suggestion> suggest(long entryId, String blockKey, String questionKey, int limit);
void replace(long entryId, String blockKey, String questionKey,
             List<LinkRow> concepts, String source);

// GET  /api/v1/learning/admin/questions?entryId=
// PUT  /api/v1/learning/admin/question-concepts   ADMIN
// GET  /api/v1/learning/admin/question-concepts/suggestions   ADMIN
```

- 验收：候选只读不写；确认接口幂等（重复 PUT 不产生重复行）；`source` 只能是 author/ai/inferred。

## T-06 前置诊断与接口

- 文件：`internal/question/PrerequisiteDiagnosis.java`、`GET /api/v1/learning/mastery` 与 `GET /api/v1/learning/diagnosis?conceptId=`。
- 接口：

```java
record Edge(long from, long to) {}
record Suggestion(long conceptId, double weight, List<Long> path) {}
static List<Suggestion> suggest(long failedConceptId, Map<Long, Double> mastery,
                                List<Edge> edges, int maxDepth);
```

- 验收：链 A→B→C（A 前置）时，C 失败且 B/A 已掌握 → 建议 B；B 未掌握且 A 掌握 → 建议 B（不是 A）；无前置返回空。

## T-07 图谱接入 BKT（带回退）

- 文件：`internal/concept/ConceptGraphService.java`（注入 mapper）。
- 逻辑：有 `concept_mastery_state` 行时用 BKT 值；否则保留旧 `ConceptMastery`。`Node` 结构不变。
- 验收：单测两条路径（有状态/无状态）；前端 `tsc` 不改仍通过。

## T-08 注销级联与前端逐题上报

- 文件：`AccountDeletionService` 的表清单；`frontend/packages/ui/src/markdown/renderers/context.tsx` 增加
  `recordAttempt?: (input: {blockKey: string; questionKey: string; choice: number; latencyMs: number}) => void`，
  `quiz.tsx` 在首次选择时调用；`KnowledgeEntryPage` 注入实现（POST 到新端点）。
- 验收：注销后 `question_attempts`/`concept_mastery_state` 无该用户行（SQL 断言）；e2e：答一道题后数据库 attempts +1。

## 执行状态（2026-10-22 第一轮）

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| T-01 迁移与 Mapper | 完成 | 迁移 `V20261022170000` 在独占库跑通；`MigrationChecksumTest` 已登记 |
| T-02 题目解析与指纹 | 完成 | `QuestionCatalogTest` 4 条全绿 |
| T-03 BKT + Elo | 完成 | `MasteryAndDiagnosisTest` 6 条全绿（Elo 方向用例曾红，修正公式后转绿） |
| T-04 作答端点 | 完成 | `QuestionAttemptIntegrationTest` 3 条全绿（答对/答错、越界、未知题） |
| T-05 概念标注与候选 | **部分**：纯函数与三个端点已实现，`QuestionConceptSuggesterTest` 3 条全绿；管理端点只经 `ApiWalkthroughTest` 覆盖（真实成功路径未测） |
| T-06 前置诊断 | 完成（纯函数 + 端点），`MasteryAndDiagnosisTest` 覆盖前沿与继续回溯；HTTP 层未单独测 |
| T-07 图谱接入 BKT | 完成，`ConceptGraphIntegrationTest` 通过（有状态走高概率、无状态回退） |
| T-08 注销级联与前端上报 | **部分**：级联已进 `AccountDeletionCascadeIntegrationTest` 并全绿；前端已接 `recordAttempt`、typecheck 通过、UI 单测 209 全绿；线上已真作答验证端点；逐题上报的 e2e 仍未跑 |

**全量与部署**：后端 276/0/4；线上迁移 `20261022170000` 已执行；部署后接口与真作答已验。

**尚未实现**（下一轮）：REQ-008 信息增益选题、REQ-009 复习联动、REQ-010 批量 LLM 打标、REQ-012 评估导出。
