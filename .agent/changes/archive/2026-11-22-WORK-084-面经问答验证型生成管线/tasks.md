---
work_id: WORK-084
work: 面经问答验证型生成管线
artifact: tasks
status: approved
mode: strict
workflow: full
depends_on: [WORK-025, WORK-009]
related_to: [WORK-025, WORK-009]
updated: 2026-11-19
approved_by: 李子凡
approved_at: 2026-11-19 10:44:04 +0800
approver_role: 技术
---

# 面经问答验证型生成管线 实施任务

## 依据

已批准工件：[requirements.md](requirements.md)、[proposal.md](proposal.md)、[design.md](design.md)（变更清单见 design 的「变更清单」表，此处不重复）。

## 全局约束

- 只动 `backend/paideia-ai` 的 essay/web 包、`backend/paideia-app` 迁移、`frontend/apps/admin`；学习者端零改动。
- AgentScope Java + `OpenAIChatModel + DeepSeekFormatter + GuardedHttpTransport + nativeStructuredOutput(false)`，照抄 `EssayExtractor` 的构建方式；**不用模型工具调用**。
- 不改 `EssayExtractor` / `EssayQuestionParser` / `NowcoderEssaySource`。
- 数据库验证一律走 `scripts/test-db.sh`；后端命令在 `backend/` 目录下执行。
- 每完成一个可独立验证的切片就本地提交（push 已获常驻授权）；按切片分笔，只提交本次范围文件。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 校验调用抛异常时静默入库 | TASK-004 | `EssayVerificationServiceTest#verifyErrorGoesToPendingReview` |
| 关闭开关后行为偏离现状 | TASK-004 | `EssayCollectionServiceVerifyTest#verifyDisabledKeepsLegacyBodyAndNeverCallsVerifier` |
| 调用预算用尽后继续放行 | TASK-004 | `EssayVerificationServiceTest#budgetExhaustedGoesPending` |
| 非白名单域名被检索/抓取 | TASK-005 | `EssayReferenceRetrieverTest#nonWhitelistedHostDropped` |
| 抓取失败阻塞入库 | TASK-005 | `EssayReferenceRetrieverTest#fetchFailureReturnsEmpty` |
| 片段超长撑爆上下文 | TASK-005 | `EssayReferenceRetrieverTest#snippetTruncated` |
| 重复采纳产生两条条目 | TASK-006 | `EssayReviewServiceTest#approveTwiceReturnsSameEntryId` |
| 采纳时同指纹+标题已存在 | TASK-006 | `EssayReviewServiceTest#approveWhenEntryExistsReusesEntryId` |
| 非管理员访问复核接口 | TASK-006 | `AuthorizationIsolationTest`（自动矩阵覆盖新前缀） |
| 迁移命名/校验和/种子隔离 | TASK-003 | `MigrationVersionFormatTest`/`MigrationChecksumTest`/`MigrationSeedOverlapTest` |
| 修订把正确改成错误 | TASK-008 | `eval/compare.md` 的正向改坏率门（人工+脚本判定，非自动化） |

## 接口块（相邻任务字面一致）

```java
// EssayBodyRenderer（TASK-002 建，TASK-004/006 用）
public static String render(String title, String answer, String sourceUrl, String sourceTitle);

// EssayVerifier（TASK-004 建 checkFactual，TASK-005 建 revise）
record FactCheck(String verdict, double confidence, List<String> issues);   // verdict ∈ ok|issues|unsure
record Revision(String action, String answer, double confidence, List<String> usedRefs, String notes); // action ∈ accept|revise|reject
FactCheck checkFactual(String title, String answer, ModelAccess access);
Revision revise(String title, String draft, List<String> issues, List<ReferenceSnippet> snippets, ModelAccess access);

// EssayReferenceRetriever（TASK-005）
record ReferenceSnippet(String url, String title, String excerpt);
List<ReferenceSnippet> retrieve(String title, String answer);

// EssayVerificationService（TASK-004 建，TASK-005 接外部）
record VerificationOutcome(String status, String finalAnswer, double confidence,
                           List<String> issues, List<ReferenceSnippet> snippets,
                           String promptVersion, String model);
// status ∈ auto_passed|revised|pending_review
VerificationOutcome judge(String title, String answer, ModelAccess access, Long categoryId,
                          String fingerprint, String sourceUrl, String sourceTitle);

// EssayVerificationMapper（TASK-003）
int insert(EssayVerificationRow row);
Optional<EssayVerificationRow> findById(long id);
List<EssayVerificationRow> findPage(String status, int limit, int offset);
long count(String status);
int markReviewed(long id, String status, Long entryId, Long reviewedBy, String note);

// EssayReviewService（TASK-006）
long approve(long id, String title, String answer, boolean publish, long adminUserId);
void reject(long id, String note, long adminUserId);
```

## 任务

> **范围变更（2026-11-19）**：基线 1.72% 后缩范围，只做内部自检（TASK-002/003/004）；外部依据检索（TASK-005）转后续，见
> `.agent/notes/deferred-scope.md`「WORK-084 外部依据检索」。TASK-006/007（复核队列后端与页面）**已完成**——复核队列是本期内部自检的隔离出口，
> 不依赖外部检索；TASK-008 只对比内部自检效果（依赖 TASK-001、TASK-004）。

### TASK-001 | done | 建立现状基线标注集

- 对应：`AC-001`
- 依赖：无
- 新建：`.agent/changes/WORK-084-面经问答验证型生成管线/eval/samples.md`（样本+标签）、`eval/baseline.md`（统计）；不复用运行时目录，纯离线工件。
- 接口：无（离线）。
- 步骤：
  1. 只读导出候选：生产库 `SELECT id,title,body FROM knowledge_entries WHERE category_id IN (essay 分类)`，取 30~50 条，写入 `samples.md`。
  2. 逐条人工按「事实无误 / 事实错误 / 无法判定」标注（只判事实，不评覆盖度/可读性）。
  3. 统计并写入 `baseline.md`：样本数、错误数、错误率、标注口径一句话。
- 验证：`baseline.md` 含样本数≥30、三分类计数与错误率，且标注口径与 requirements「评测与标注」一致。
- 完成（2026-11-19）：[samples.md](eval/samples.md) 58 条（Java 22 / 前端 18 / 测试 8 / 数据 10）；[baseline.md](eval/baseline.md) **错误率 1.72%（1/58）**，唯一错误为 HashMap 树化概率数值差 10 倍。生成脚本：[make_samples.py](eval/make_samples.py)。

### TASK-002 | done | 抽取 EssayBodyRenderer（行为不变的重构）

- 对应：`REQ-003`
- 依赖：无
- 新建/修改：新建 `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayBodyRenderer.java`；修改 `EssayCollectionService.java:259-264` 删除私有 `render`，改调 `EssayBodyRenderer.render(question.title(), question.answer(), post.url(), safeTitle(post.title()))`。
- 接口：`EssayBodyRenderer.render(String title, String answer, String sourceUrl, String sourceTitle) : String`（输出与旧 `render` 逐字一致）。
- 测试：`backend/paideia-ai/src/test/java/io/github/xiaou61/ai/internal/essay/EssayBodyRendererTest#renderMatchesLegacy`
- 步骤：
  1. 写 `EssayBodyRendererTest`，用一条样例断言拼出的四段文本（`## 题` / 答案 / `---` / `> 题目来源：[…](url)`）与旧实现逐字一致；此时应红。
  2. 新建 `EssayBodyRenderer` 搬移实现，`EssayCollectionService` 改调；测试转绿。
- 验证：`backend$ scripts/test-db.sh mvn -B -pl paideia-ai -Dtest=EssayBodyRendererTest test` → 1 passed。
- 完成（2026-11-19）：`EssayBodyRenderer` 与 `EssayBodyRendererTest` 落盘，`EssayCollectionService:201` 改调并删除私有 `render`/`safeTitle`；实测 `Tests run: 3, Failures: 0, Errors: 0`，BUILD SUCCESS（命令加 `-am` 与引号后的 `-D` 参数）。

### TASK-003 | done | 建表 + mapper + verify 配置 + RunKind

- 对应：`REQ-003`、`REQ-004`、`REQ-005`
- 依赖：无
- 新建：`backend/paideia-app/src/main/resources/db/migration/V20261119103000__create_ai_essay_verifications.sql`（列定义见 design「接口与数据」）、`EssayVerificationMapper.java`（`@Mapper` + text block，风格同 `AiRunSpanMapper`）、`EssayVerificationRow.java`（record/POJO）。
  修改：`EssayProperties.java` 增 `private Verify verify = new Verify();` 与嵌套 `Verify`（字段/默认见 design 配置表）；`RunKind.java` 追加 `ESSAY_VERIFY`、`ESSAY_REVISE`。
- 接口：`EssayVerificationMapper` 五个方法（见接口块）；`EssayProperties.getVerify()` 返回 `Verify`。
- 测试：`MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest`（自动扫描新迁移）。
- 步骤：
  1. 写迁移 SQL（只建表、不插数据），索引 `idx_status_created(status,created_at)`、`idx_fingerprint(fingerprint)`。
  2. 写 mapper 与 row；`EssayProperties.Verify` 加 getter/setter；`RunKind` 加两常量。
  3. 跑三条迁移测试。
- 验证：`backend$ scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` → 全绿。
- 完成（2026-11-19）：迁移 `V20261119103000__create_ai_essay_verifications.sql`、`EssayVerificationRow`、`EssayVerificationMapper`（insert/findById/findPage/count/markReviewed）、`EssayProperties.Verify`+`External`、`RunKind.ESSAY_VERIFY/ESSAY_REVISE` 落盘；`migration-checksums.txt` 已登记新迁移；三条迁移测试 `Tests run: 8, Failures: 0, Errors: 0`，BUILD SUCCESS。

### TASK-004 | done | 内部自检 + 分流 + 落库（external 关闭）

- 对应：`REQ-001`、`REQ-002`、`REQ-003`、`REQ-004`、`REQ-005`、`AC-003`、`AC-004`
- 依赖：TASK-002、TASK-003
- 新建：`EssayVerificationPrompt.java`（`VERSION="essay-verify-v1"`、`"essay-revise-v1"`、system/user、JSON-as-text 解析）、`EssayVerifier.java`（`checkFactual`；`revise` 先留空实现仅内部修订分支）、`EssayVerificationService.java`（状态机、预算、异常兜底、写记录）。
  修改：`EssayCollectionService.java:181-221` 抽题循环改为 `judge(...)` 分流；未 `AUTO_PASS/REVISED` 不调 `createCollected`；`RunResult` 增 `pendingReview` 计数与日志。
- 接口：`EssayVerificationService.judge(...) : VerificationOutcome`（见接口块）；`EssayVerifier.checkFactual(...)`。
- 测试：`EssayVerificationServiceTest`（ok高置信→auto_passed、ok低置信→升级、issues→pending、异常→pending、预算用尽→pending）；`EssayCollectionServiceTest#disabledVerifyMatchesLegacy`。
- 步骤：
  1. 写上述测试（用 fake `KnowledgeApi` + 假 verifier），当前应红。
  2. 实现 prompt/verifier/service 与接线，测试转绿。
  3. `EssayCollectionServiceTest` 断言 `verify.enabled=false` 时调用序列与现状一致。
- 验证：`backend$ mvn -B -o -pl paideia-ai -am -Dtest='EssayVerificationServiceTest,EssayCollectionServiceVerifyTest,EssayCollectionRoutingTest,EssayCollectionFailureKindTest,EssaySourceFanOutTest,EssayBodyRendererTest' -Dsurefire.failIfNoSpecifiedTests=false test` → `Tests run: 29, Failures: 0, Errors: 0`，BUILD SUCCESS。
- 完成（2026-11-19）：`FactCheck`/`Revision`/`ReferenceSnippet`/`VerificationOutcome`、`EssayVerificationPrompt`（`essay-verify-v2`，只判事实错误）、`EssayVerifier`（`checkFactual`；16000 tokens/300s 以适配推理模型）、`EssayVerificationService`（`auto_passed`/`pending_review`，存疑只隔离不改写）、`EssayCollectionService` 接线（`RunResult.pendingReview`、verify 关闭时零改动）落盘；新增 `EssayVerificationServiceTest`（5 例）与 `EssayCollectionServiceVerifyTest`（3 例：关闭时正文逐字 legacy、pending 不入库、revised 用修订答案）。

> **TASK-005（外部依据检索 + 接入升级分支）已移出本期任务清单**（2026-11-19 范围裁决，2026-11-22 收尾时从 `tasks.md` 摘出，
> 原文与启动条件见 `.agent/notes/deferred-scope.md`「WORK-084 外部依据检索」）。它不是「没做完」，而是**本期决定不做**：
> 58 条抽样里内部自检已拦下全部真错（3/3，误报 0），外部检索的边际收益不足以支撑白名单、抓取、缓存与
> `UpstreamGuard` 那一整条链路的成本，转后续按需启动。摘出的原因：留着一条 `pending` 会让工作项永远停在「实现中」，
> 而它对应的验收项（AC-003 复核队列）已由 requirements 的范围变更明确「本期不验收」。

### TASK-006 | done | 复核队列后端（列表/采纳/驳回）

- 对应：`REQ-003`、`AC-003`
- 依赖：TASK-003、TASK-004
- 新建：`EssayReviewService.java`、`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/web/EssayReviewAdminController.java`（`@ApiAccess(ADMIN)`、`/api/v1/ai/admin/essay-verifications`）。
- 接口：`EssayReviewService.approve(long id, String title, String answer, boolean publish, long adminUserId) : long`；`reject(long id, String note, long adminUserId) : void`；`page(String status,int page,int size)`。
- 测试：`EssayReviewServiceTest`（`approveTwiceReturnsSameEntryId`、`approveWhenEntryExistsReusesEntryId`、`rejectDoesNotCreateEntry`）；`AuthorizationIsolationTest` 覆盖新前缀 401/403。
- 步骤：
  1. 写服务测试（fake `KnowledgeApi`），红。
  2. 实现采纳（`EssayBodyRenderer.render` + `createCollected`，`publish?"published":"draft"`）、驳回（写 `review_note`）、列表；绿。
  3. 控制器三端点 + ADMIN 注解。
- 验证：`backend$ mvn -B -o -pl paideia-ai -am '-Dtest=EssayReviewServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` → `Tests run: 7, Failures: 0, Errors: 0`，BUILD SUCCESS（2026-11-19 11:31）。守卫三件（`../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest`）→ `Tests run: 26, Failures: 0, Errors: 0`，BUILD SUCCESS，新前缀已纳入 401/403 矩阵。

### TASK-007 | done | 复核队列前端页面

- 对应：`REQ-003`
- 依赖：TASK-006
- 新建：`frontend/apps/admin/src/pages/EssayReviewPage.tsx`。
  修改：`frontend/apps/admin/src/router.tsx`（`:92` 区域加 `/knowledge/essay-reviews`，包 `<RequireAdmin>`）、`frontend/apps/admin/src/components/AdminShell.tsx`（`:47-52` 子菜单加「面经复核」、`:98-101` 面包屑加映射）、`frontend/apps/admin/src/knowledge-shared.tsx`（加三个客户端方法）。
- 接口：消费 `GET /api/v1/ai/admin/essay-verifications`、`POST /{id}/approve`、`POST /{id}/reject`。
- 测试：管理端当前没有独立 e2e 运行环境（`frontend` 下无 admin playwright 配置），本轮以 `tsc` 类型检查为准；页面行为留待有 e2e 基建时补。
- 步骤：
  1. 页面：状态筛选（默认 `pending_review`）+ 表格 + 详情（草稿/修订/问题/依据）+ 采纳（可编辑答案、勾选直接发布）/驳回。
  2. 接路由、菜单、面包屑、客户端方法；`pnpm --filter @paideia/admin typecheck` 通过。
  3. 写 e2e 并跑通。
- 验证：`frontend$ pnpm --filter @paideia/admin typecheck` → 0 error（2026-11-19 11:29）；`pnpm --filter @paideia/knowledge test` → 112 passed（含 admin-client 4 条）。

### TASK-008 | done | 效果评测（基线 vs 校验后）

- 对应：`AC-001`、`AC-002`
- 依赖：TASK-001、TASK-004
- 新建：`eval/compare.md`。
- 接口：无（离线脚本可复用 `ai_essay_verifications` 导出）。
- 步骤：
  1. 用同一批 `samples.md` 跑校验后管线（本地开启 verify，样本外无关数据不入库）。
  2. 人工复核校验后结果，统计「事实错误占比」与「正确→错误」正向改坏率。
  3. 写 `compare.md`：基线 vs 校验后两列数值 + 结论（改坏率 ≤5% 才算通过，否则回退阈值）。
- 验证：`compare.md` 含两侧错误率、正向改坏率与是否达标结论。
- 完成（2026-11-19）：`eval/run_verify.py` 对 58 条跑 `essay-verify-v2`，直接放行 55、拦下 3（10940/10912/10916），
  复核后 3 条全部是真错（其中 10912、10916 是初次标注漏掉的），漏报 0、误报 0，正向改坏 0。
  修正后基线错误率 ≈5.17%（3/58），校验后放行答案已知错误 0。结论见 `eval/compare.md`。

## 完成条件

- 全部 TASK 完成且验证命令有新鲜输出；`$lifecycle validate` 对 WORK-084 无错误。
- AC-001~004 均有归属任务与证据；`compare.md` 达标。
- 关闭 verify 时线上行为与现状一致（已由 TASK-004 单测守护）。
