---
artifact: tasks
work_id: WORK-034
work: 学习闭环与复习入口
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-020, WORK-021, WORK-024, WORK-026, WORK-029, WORK-033]
created: 2026-10-22
updated: 2026-10-22
approved_by: xiaou61
approved_at: 2026-10-23 09:44:49 +0800
approver_role: CTO
---

# 任务 · 学习闭环与复习入口

**排序原则**：S1 全部不依赖 WORK-033，先做；S2 的 TASK-005 是一次探测，只在 033 合并后执行，结论冻结掌握度端口，后续三个任务才解除 `blocked`。不在 033 合并前动任何作答/掌握度数据。

## 依据

- `requirements.md`（draft）：REQ-001..012、AC-001..011。
- `proposal.md`（draft）：两阶段、会话快照表、不建题库、不接 Laya。
- `design.md`（draft）：接口、`review_sessions`、降级矩阵、测试策略。

## 全局约束

- 工件未批准前不改代码、不建迁移、不部署。
- S1 不新增表、不新增迁移、不调用 AI。
- S2 的迁移只在 WORK-033 合并后写；不修改 `.worktrees/work-033` 的任何文件。
- 不接 Laya、不接 embedding、不新增 Maven/npm 依赖。
- 复用 `AppShell`、`Button`、`Card`、`PageHeader`、`Spinner`、`Alert`；不新造组件库。
- 只读本人数据；响应不含答案键；重复提交 409。
- 375px 无横向滚动；键盘可完成会话；尊重 `prefers-reduced-motion`。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 生成超时/积分不足时仍然扣费或返回 5xx | TASK-006 | `reviewGenerateFailureDoesNotChargeOrThrow` |
| 同一会话重复交卷推进两次排期 | TASK-007 | `duplicateAnswerSubmissionReturns409` |
| 生成题与用户已答题干重复 | TASK-006 | `repeatedStemIsSkipped` |
| 题干指纹相同但不同概念被误判为重复 | TASK-006 | `sameStemDifferentConceptIsAllowed` |
| 他人会话可被读取或提交 | TASK-007 | `otherUsersSessionIsNotFound` |
| 答案键出现在响应或日志 | TASK-006 | `sessionResponseHasNoAnswerKey` |
| 过期会话仍可提交 | TASK-007 | `expiredSessionRejectsSubmit` |
| 没有到期项时页面空白/报错 | TASK-003 | `reviewEmptyStateShowsTwoActions` |
| 排序平局或零信号时顺序不稳定 | TASK-001 | `rankingFallsBackToNewest` |
| 首次路径关闭后刷新又出现 | TASK-004 | `firstRunDismissPersists` |
| 375px 下 `/review` 横向溢出 | TASK-010 | `reviewMobileHasNoHorizontalScroll` |
| 另一个标签页已更新排期后被旧值覆盖 | TASK-007 | `staleScheduleUsesCurrentDatabaseValue` |

## 任务

### TASK-001 | done | 首页排序纯函数

- 对应：`REQ-006`、`AC-006`
- 依赖：无
- 新建/修改：新建 `frontend/packages/knowledge/src/home-ranking.ts`、`frontend/packages/knowledge/src/home-ranking.test.ts`；在 `frontend/packages/knowledge/src/index.ts` 导出。放在 knowledge 包是因为它已有 vitest，app 包没有单测运行器。
- 接口：`rankHomeEntries(candidates: HomeCandidate[], signals: HomeSignals): RankedEntry[]`；`HomeCandidate` 含 `entryId/title/publishedAt/categoryId`；`HomeSignals` 含 `dueEntryIds/conceptByEntryId/weakConceptIds/inProgressEntryIds/preferredCategoryIds`；`RankedEntry` 含 `entryId/reason`。
- 测试：`frontend/packages/knowledge/src/home-ranking.test.ts`
- 步骤：
  1. 写失败用例：到期 > 薄弱 > 进行中 > 身份 > 最新；平局按 `publishedAt` 降序。
  2. 实现评分与理由字符串；零信号时保持输入顺序。
  3. 跑 `pnpm --filter @paideia/knowledge test`，全绿。
- 验证：`cd frontend && pnpm --filter @paideia/knowledge test` → 期望新增用例全部通过，旧用例不回归。

### TASK-002 | done | 到期列表接口

- 对应：`REQ-002`、`AC-002`
- 依赖：无
- 新建/修改：`backend/paideia-knowledge/src/main/java/.../web/LearningController.java` 加 `GET /learning/reviews`；`.../learning/LearningReviewService.java`（新建）；概念名批量查询复用 `ConceptMapper`，不逐条查。
- 接口：`GET /learning/reviews` → `{ items: ReviewDueView[], total: number }`；`ReviewDueView(entryId, title, conceptId, conceptName, reviewDueAt, stage)`。
- 测试：`backend/paideia-app/src/test/java/.../LearningReviewIntegrationTest.java` 新增 `dueReviewsAreOrderedByDueDate`、`dueReviewsIncludeConceptName`。
- 步骤：
  1. 写集成测试：造 2 条到期、1 条未到期，断言只返回 2 条且按到期日升序。
  2. 实现服务与控制器；概念 id 为空时 `conceptName=null`。
  3. 用 `scripts/test-db.sh` 跑该测试类。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=LearningReviewIntegrationTest test` → 期望 2 passed，0 skipped。

### TASK-003 | done | 复习页列表与路由

- 对应：`REQ-001`、`REQ-002`、`AC-001`、`AC-002`
- 依赖：TASK-002
- 新建/修改：`frontend/apps/app/src/pages/ReviewPage.tsx`（新建）；`router.tsx` 加 `/review`；`HomePage.tsx` 的「待复习」改指向 `/review`；`LearnerHeader.tsx` 导航加「复习」。
- 接口：消费 `GET /learning/reviews`；无 AI 调用。
- 测试：`frontend/apps/app/e2e/review.spec.ts` 新增 `reviewEmptyStateShowsTwoActions`、`reviewListsDueEntries`；更新 `e2e/home.spec.ts` 的出口断言。
- 步骤：
  1. 写 e2e：先断言首页「待复习」链接是 `/review`。
  2. 实现列表、空状态、加载/错误态；空状态两个按钮分别去 `/learn` 与 `/knowledge`。
  3. 跑两个 e2e 文件。
- 验证：`cd frontend && pnpm --filter @paideia/app exec playwright test e2e/home.spec.ts e2e/review.spec.ts` → 期望全绿。

### TASK-004 | done | 零数据三步路径与训练营入口

- 对应：`REQ-007`、`REQ-008`、`AC-007`、`AC-008`
- 依赖：无
- 新建/修改：`frontend/apps/app/src/components/HomeFirstRun.tsx`（新建）；`HomePage.tsx` 接入并加训练营固定卡片；`QUICK_LINKS` 加 `key: null` 的 camp 项。
- 接口：三步条件 = `assessments.length === 0 && progress.length === 0 && graph.stats.touched === 0`；关闭状态键 `paideia:first-run-dismissed`。
- 测试：`e2e/home.spec.ts` 新增 `firstRunShowsThreeSteps`、`firstRunDismissPersists`、`homeShowsCampEntry`。
- 步骤：
  1. 写 e2e：零数据账号看到三步；关闭后刷新不再出现；训练营卡片存在且指向 `/camp`。
  2. 实现组件与卡片；三步链接分别是 `/profile`、`/learn`、第一条学习线的第一关。
  3. 跑 `home.spec.ts`。
- 验证：`cd frontend && pnpm --filter @paideia/app exec playwright test e2e/home.spec.ts` → 期望全绿。

### TASK-005 | done | 冻结 WORK-033 掌握度端口

- 对应：`REQ-005`、`REQ-011`
- 依赖：WORK-033 合并（硬依赖）
- 新建/修改：不改代码；结论写入 `.agent/notes/review-mastery-contract.md`。
- 接口：确认 033 是否提供按「用户 × 概念 × 对错」写入的端口；若只有读接口，记录回退方案「只写 `learning_progress`，掌握度等 033 后续接入」。
- 测试：无（探测任务，交付物是决策记录）。
- 步骤：
  1. 读 033 合并后的 `question` 包与掌握度服务，列出可用方法签名。
  2. 在笔记里写清本项消费的接口、失败语义、是否需要迁移。
  3. 若接口不满足，回到本项 `design.md` 的「待决定事项 1」并停。
- 验证：笔记存在且被 TASK-006/007 的依据引用；`rg "review-mastery-contract" .agent/changes/WORK-034-*` 有命中。

### TASK-006 | done | 出题端口与会话创建

- 对应：`REQ-003`、`REQ-004`、`REQ-009`、`REQ-010`、`AC-004`、`AC-005`、`AC-009`
- 依赖：TASK-005、TASK-002
- 新建/修改：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/ReviewQuestions.java`（新建端口）；`.../internal/review/ReviewQuestionGenerator.java`（新建）；`.../internal/camp/CampItemGenerator.java` 提取可复用的出题方法（不改行为）；`LearningReviewService` 加会话创建；`ReviewSessionsMapper`（新建）；迁移 `V20261022xxxxxx__create_review_sessions.sql`。
- 接口：`ReviewQuestions.generate(long userId, List<ReviewConcept> concepts, int perConcept, Set<String> previousStems): Optional<ReviewQuestionSet>`；`POST /learning/reviews/sessions` → `{ sessionId, items[], expiresAt, fallback }`。
- 测试：`LearningReviewIntegrationTest` 新增 `reviewGenerateFailureDoesNotChargeOrThrow`、`repeatedStemIsSkipped`、`sameStemDifferentConceptIsAllowed`、`sessionResponseHasNoAnswerKey`；`ReviewQuestionGeneratorTest` 校验选项/答案下标/指纹。
- 步骤：
  1. 写失败用例：上游超时不扣费且返回降级；重复指纹被跳过。
  2. 实现端口与生成器；复用 `ItemValidator`；一次调用最多 5 题。
  3. 写 `review_sessions` 迁移与会话端点；答案键只进 `items_json`。
  4. 跑集成测试与生成器测试。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=LearningReviewIntegrationTest test` → 期望新增用例全过；`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=ReviewQuestionGeneratorTest test` → 全过。

### TASK-007 | done | 交卷与排期更新

- 对应：`REQ-005`、`REQ-009`、`REQ-010`、`AC-003`、`AC-005`、`AC-009`、`AC-011`
- 依赖：TASK-006
- 新建/修改：`LearningProgressRules.java` 加 `afterReview(int stage, boolean allCorrect, Instant now)`；`LearningReviewService` 加交卷；`ReviewSessionsMapper` 加条件完成；`KnowledgeUserDeletionListener` 加 `review_sessions`。
- 接口：`POST /learning/reviews/sessions/{id}/answers` → `{ results[], schedule[] }`；`afterReview` 返回 `{ stage, dueAt }`。
- 测试：`LearningProgressRulesTest` 新增 `reviewFailureKeepsStageAndDueTomorrow`、`reviewSuccessAdvancesStage`；`LearningReviewIntegrationTest` 新增 `duplicateAnswerSubmissionReturns409`、`expiredSessionRejectsSubmit`、`otherUsersSessionIsNotFound`、`staleScheduleUsesCurrentDatabaseValue`、`accountDeletionRemovesReviewSessions`。
- 步骤：
  1. 写纯函数失败用例：答错档位不变、到期为次日；答对前进一档。
  2. 实现 `afterReview` 与交卷服务；条件更新 `completed_at` 做幂等闸。
  3. 接 033 掌握度端口（按 TASK-005 结论）。
  4. 跑规则测试与集成测试。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-knowledge -am -Dtest=LearningProgressRulesTest test`；`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=LearningReviewIntegrationTest test` → 全绿。

### TASK-008 | done | 复习会话页与结果页

- 对应：`REQ-003`、`REQ-012`、`AC-003`、`AC-005`、`AC-009`
- 依赖：TASK-007
- 新建/修改：`ReviewPage.tsx` 加会话态与结果态；复用 `Button`、`Card`、`Alert`、`Progress`（若已有）。
- 接口：消费 `POST /learning/reviews/sessions` 与 `.../answers`；降级时展示 `fallback`。
- 测试：`e2e/review.spec.ts` 新增 `reviewSessionCompletesAndShowsNextDue`、`reviewFallbackShowsCarrierLinks`。
- 步骤：
  1. 写 e2e：开始 → 选答案 → 提交 → 看到解析与下次到期日。
  2. 实现会话态：一次一题、进度、提交后解析；失败态显示承载条目链接。
  3. 跑 `review.spec.ts`。
- 验证：`cd frontend && pnpm --filter @paideia/app exec playwright test e2e/review.spec.ts` → 全绿。

### TASK-009 | done | 复习题金标与回归

- 对应：`REQ-003`、`REQ-004`、`AC-004`
- 依赖：TASK-006
- 新建/修改：`backend/paideia-ai/src/test/resources/review-golden/`（20–30 条输入与期望）；`ReviewGoldenTest`（新建，默认跳过真实上游，除非显式开）。
- 接口：无生产接口；测试用固定概念与题干，断言「不重复、4 选项、解析非空、答案下标合法」。
- 测试：`ReviewGoldenTest`
- 步骤：
  1. 收集 20–30 条真实到期概念与历史题干。
  2. 写断言；未开真实上游时用录制样本，开了再跑一次真连。
  3. 记录通过率到 `.agent/changes/WORK-034-*/testing/`。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=ReviewGoldenTest test` → 期望 0 重复、0 结构错误；真连结果单独记录。

### TASK-010 | done | 移动端与可访问性

- 对应：`REQ-012`、`AC-010`
- 依赖：TASK-003、TASK-004（S1 部分可先做；S2 部分等 TASK-008）
- 新建/修改：`e2e/layout.mobile.spec.ts` 新增 `/review` 与首页用例；必要时改 `ReviewPage` 的布局类。
- 接口：无。
- 测试：`reviewMobileHasNoHorizontalScroll`、`reviewKeyboardOnlyCompletesSession`、`reducedMotionHasNoTranslate`。
- 步骤：
  1. 写 375px 断言：`document.documentElement.scrollWidth <= innerWidth`。
  2. 用键盘走一遍会话；开启 reduced-motion 断言无 `translate` 动画。
  3. 修布局直到通过。
- 验证：`cd frontend && pnpm --filter @paideia/app exec playwright test e2e/layout.mobile.spec.ts` → 全绿。

### TASK-011 | done | 验收报告

- 对应：全部 `AC-*`
- 依赖：TASK-001..010
- 新建/修改：`.agent/changes/WORK-034-学习闭环与复习入口/testing/report.md`
- 接口：无。
- 测试：汇总上述命令的新鲜输出；未运行的写「未运行」，不得写通过。
- 步骤：
  1. 逐条 AC 填证据（命令、期望、实际）。
  2. 记录 S1/S2 的实际提交与部署边界。
  3. 跑 `project-lifecycle validate`。
- 验证：报告 frontmatter `status: passed` 且每条 AC 有证据；`validate` 无本项警告。

## 完成条件

- S1：TASK-001..004、010 的 S1 部分完成，`home.spec.ts`/`review.spec.ts`/`layout.mobile.spec.ts` 相关用例全绿，S1 无迁移。
- S2：TASK-005..009 完成，排期、幂等、降级、注销级联都有新鲜证据。
- 所有 AC 在报告中至少有一条证据；未验证的明确标记。
- 不改 WORK-033 的分支；不接 Laya；不新增依赖。
- 更新 `.agent/history/updates.md`，记录本地提交边界；推送与部署另行授权。
