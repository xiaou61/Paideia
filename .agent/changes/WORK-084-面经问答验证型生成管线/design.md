---
work_id: WORK-084
work: 面经问答验证型生成管线
artifact: design
status: approved
mode: strict
workflow: full
related_to: [WORK-025, WORK-009]
updated: 2026-11-19
approved_by: 李子凡
approved_at: 2026-11-19 10:43:28 +0800
approver_role: 技术
---

# 面经问答验证型生成管线 设计

## 变更清单

| # | 位置 | 类型 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayCollectionService.java:181-221` | 修改 | 抽题后对每题直接 `knowledge.createCollected(...)` 入库 | 每题先交 `EssayVerificationService.judge(...)`；返回 `AUTO_PASS`/`REVISED` 才入库，`PENDING_REVIEW` 只写验证表 | REQ-001/003 |
| 2 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayCollectionService.java:259-264` | 删除 | `private static String render(question, post)` 私有实现 | 由新增 `EssayBodyRenderer.render(title, answer, sourceUrl, sourceTitle)` 取代（`EssayCollectionService` 与复核服务共用）；无其它取代者 | REQ-003 |
| 3 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayCollectionService.java:44-70` | 修改 | 构造注入 `properties/source/filter/extractor/knowledge/credentials/recorder` | 追加注入 `EssayVerificationService verifier` | REQ-001 |
| 4 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayProperties.java` | 新增 | 无 `verify` 配置 | 新增 `private Verify verify = new Verify();` 及嵌套类（字段见「接口与数据」） | REQ-005 |
| 5 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/RunKind.java` | 新增 | 无校验段枚举 | 追加 `ESSAY_VERIFY`、`ESSAY_REVISE` | REQ-004 |
| 6 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayVerifier.java` | 新增 | 无 | 内部自检 + 依据修订两次模型调用（同一 OpenAIChatModel 构建方式，照抄 `EssayExtractor`） | REQ-001/002 |
| 7 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayVerificationPrompt.java` | 新增 | 无 | 两套 system/user 提示 + `VERSION="essay-verify-v1"` / `"essay-revise-v1"` | REQ-002 |
| 8 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayReferenceRetriever.java` | 新增 | 无 | 白名单检索 + 抓取片段 + 进程内 LRU 缓存 | REQ-001 |
| 9 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayVerificationService.java` | 新增 | 无 | 编排（闸门→升级→分流）、判定映射、写记录 | REQ-001/003 |
| 10 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayVerificationMapper.java` | 新增 | 无 | 手写 MyBatis 注解 mapper（增/查/改状态） | REQ-003/004 |
| 11 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/essay/EssayReviewService.java` | 新增 | 无 | 复核列表、采纳（调 `KnowledgeApi.createCollected`）、驳回 | REQ-003 |
| 12 | `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/web/EssayReviewAdminController.java` | 新增 | 无 | `@ApiAccess(ADMIN)`、`/api/v1/ai/admin/essay-verifications` 三个端点 | REQ-003 |
| 13 | `backend/paideia-app/src/main/resources/db/migration/V20261119103000__create_ai_essay_verifications.sql` | 新增 | 无此表 | 建 `ai_essay_verifications`（只建结构） | REQ-003/004 |
| 14 | `frontend/apps/admin/src/router.tsx:92-116` 区域 | 新增 | 无 `/knowledge/essay-reviews` | 增一条 `<RequireAdmin><EssayReviewPage/></RequireAdmin>` | REQ-003 |
| 15 | `frontend/apps/admin/src/components/AdminShell.tsx:47-52` | 新增 | 知识库子菜单四项 | 追加 `{ to: '/knowledge/essay-reviews', label: '面经复核', icon: ShieldCheck }`；并在同文件 `:98-101` 的路径面包屑映射加一条 | REQ-003 |
| 16 | `frontend/apps/admin/src/pages/EssayReviewPage.tsx` | 新增 | 无 | 待复核列表 + 详情抽屉（草稿/修订/问题/依据）+ 采纳/驳回 | REQ-003 |
| 17 | `frontend/apps/admin/src/knowledge-shared.tsx` | 必改 | `admin` 客户端无复核方法 | 追加 `essayVerifications/approveEssayVerification/rejectEssayVerification` 三个方法 | REQ-003 |
| 18 | `.agent/changes/WORK-084-面经问答验证型生成管线/eval/` | 新增 | 无 | 标注表 + 基线/对比脚本（离线工具，不进运行时） | AC-001 |

## 系统上下文

```
EssayCollectionJob(@Scheduled)
  └─ EssayCollectionService.runOnce()
       抓 NowcoderEssaySource → 过滤 TechEssayFilter → 抽题 EssayExtractor
       └─ 每题 EssayVerificationService.judge(title, answer, post)
            ├─ EssayVerifier.checkFactual()        → ok|issues|unsure, confidence   [1 次调用]
            ├─ (可疑且 verify.external.enabled) EssayReferenceRetriever.retrieve()  [0~3 次 HTTP]
            ├─ EssayVerifier.revise()              → accept|revise|reject + answer   [0~1 次调用]
            ├─ AUTO_PASS / REVISED → KnowledgeApi.createCollected(...)  （经 EssayBodyRenderer.render）
            └─ PENDING_REVIEW      → EssayVerificationMapper.insert(... status=pending_review)

管理端 EssayReviewPage
  └─ EssayReviewAdminController → EssayReviewService
       ├─ list(pending_review|all)
       ├─ approve(id, title?, answer?, publish) → KnowledgeApi.createCollected + 状态 approved
       └─ reject(id, note)                     → 状态 rejected
```

外部依赖：DeepSeek（沿用 `CredentialService.access(0)` + `GuardedHttpTransport`）、白名单官方文档站点（只读 GET）、`UpstreamGuard`（SSRF 闸）。

## 组件与职责

- **EssayVerificationService（新增，编排）**：唯一决策点。输入一条题目/答案/来源，输出 `VerificationOutcome`（含最终状态、最终答案、置信、问题列表、依据、runId）。负责成本闸（`maxCallsPerRun`）、异常兜底（→ `pending_review`）、写记录。**不构建模型客户端**，模型调用委托 `EssayVerifier`。
- **EssayVerifier（新增）**：两个方法 `checkFactual(question, answer, access)`、`revise(question, draft, issues, snippets, access)`。内部用与 `EssayExtractor` 相同的 `OpenAIChatModel + DeepSeekFormatter + GuardedHttpTransport + nativeStructuredOutput(false)`；温度 0；输出按 JSON-as-text 解析（照 `EssayQuestionParser.stripFence` 的思路，不依赖原生结构化输出）。
- **EssayVerificationPrompt（新增）**：集中提示词与版本号。
- **EssayReferenceRetriever（新增）**：`List<Snippet> retrieve(String question)`。步骤：拼 query → 检索 → 只保留白名单域 → 取前 3 → 逐个 `UpstreamGuard.assertAllowed` → `HttpClient` GET（https、禁重定向、8s、≤200KB）→ 去标签取正文 → 截 1200 字。缓存键 `sha1(query)`，进程内 LRU（上限 500、TTL 7 天）。**任何一步失败返回空列表，不抛**。
- **EssayVerificationMapper（新增）**：`insert`、`findById`、`findPage(status, offset, size)`、`countByStatus`、`markReviewed(id, status, entryId, reviewedBy, note)`。
- **EssayReviewService（新增）**：管理端用例。采纳时用 `EssayBodyRenderer.render` 组装正文，`CollectedEntry.status` 取 `publish ? "published" : "draft"`，`categoryId`/`fingerprint`/`sourceUrl` 来自验证行。幂等：已 `approved` 的行再次采纳直接返回原 `entryId`。
- **EssayBodyRenderer（新增，工具）**：把现 `EssayCollectionService.render` 原样搬出为 `public static String render(String title, String answer, String sourceUrl, String sourceTitle)`，行为逐字不变。
- **EssayCollectionService（改）**：`runOnce` 中抽题循环改为调用验证服务；`created/duplicated` 计数口径不变，新增 `pendingReview` 计数放进 `RunResult` 与日志。

## 请求或事件流程

判定状态机（`VerificationOutcome.status`）：

```
checkFactual →
  ok     且 confidence ≥ accept-confidence(0.80)            → AUTO_PASS
  ok     且 confidence <  accept-confidence                 → 视为 unsure，进入升级
  issues / unsure →
      external.enabled ? retrieve() : []
      snippets 非空 → revise() →
             action=accept 且 confidence ≥ revise-accept(0.75) → REVISED（用原答案）
             action=revise 且 confidence ≥ revise-accept       → REVISED（用修订答案）
             其它                                             → PENDING_REVIEW
      snippets 为空（含抓取失败）→ 内部修订（同一 revise 调用，snippets=[]）→ 同上规则
  任一步模型/网络异常                                        → PENDING_REVIEW(reason=verify_error)
```

阈值与档位集中在一个 `switch`，便于 P0 后调参。`verify.maxCallsPerRun` 用尽后，剩余题**不校验直接 `PENDING_REVIEW`**（宁可人工，不静默放行）。

## 接口与数据

**配置（`paideia.ai.essay.verify`，默认关闭以保持现状）**

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关；false 时 `runOnce` 路径与现状逐条一致 |
| `external.enabled` | `true` | 是否允许联网抓文档（enabled 才生效） |
| `accept-confidence` | `0.80` | 自检直接通过阈值 |
| `revise-accept-confidence` | `0.75` | 修订稿可入库阈值 |
| `max-calls-per-run` | `120` | 单次运行的模型调用硬上限（含自检+修订） |
| `max-reference-urls` | `3` | 单题最多抓几个文档 |
| `snippet-chars` | `1200` | 单段喂给模型的字符上限 |
| `fetch-timeout-seconds` | `8` | 单次抓取超时 |
| `reference-domains` | 见提案待决定 #3 | 白名单；为空则 external 自动失效（只做内部校验） |
| `prompt-version` | 由常量读取 | 记录用，不单独配 |

**表 `ai_essay_verifications`**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK | |
| `status` | VARCHAR(24) NOT NULL | `auto_passed` / `revised` / `pending_review` / `approved` / `rejected` |
| `question_title` | VARCHAR(255) NOT NULL | 题目 |
| `draft_answer` | MEDIUMTEXT NOT NULL | 抽取原答案 |
| `final_answer` | MEDIUMTEXT NULL | 修订后答案（未修订为 NULL） |
| `confidence` | DECIMAL(4,3) NULL | 0~1 |
| `issues` | JSON NULL | 自检问题清单 |
| `snippets` | JSON NULL | 依据片段摘要 `[{url,title,excerpt}]`（审计用） |
| `category_id` | BIGINT UNSIGNED NULL | 目标分类 |
| `fingerprint` | VARCHAR(64) NULL | 帖子指纹（同 WORK-025） |
| `source_url` | VARCHAR(1024) NULL | 原帖地址 |
| `source_title` | VARCHAR(255) NULL | 原帖标题 |
| `entry_id` | BIGINT UNSIGNED NULL | 采纳后生成的条目 id |
| `run_id` | VARCHAR(64) NULL | `ai_runs` 的 trace 关联（可空） |
| `prompt_version` | VARCHAR(32) NULL | |
| `model` | VARCHAR(64) NULL | |
| `review_note` | VARCHAR(1000) NULL | 复核备注/驳回原因 |
| `reviewed_by` | BIGINT UNSIGNED NULL | 管理员 userId |
| `reviewed_at` | DATETIME(3) NULL | |
| `created_at` / `updated_at` | DATETIME(3) | 迁移样式同 `V20261106172000__create_ai_knowledge_imports.sql` |

索引：`KEY idx_status_created (status, created_at)`、`KEY idx_fingerprint (fingerprint)`。

**管理端 API（`/api/v1/ai/admin/essay-verifications`，`@ApiAccess(ADMIN)`）**

- `GET ?status=pending_review&page=1&size=20` → `PageResult<VerificationRow>`（含 title/draft/final/confidence/issues/snippets/sourceUrl/createdAt）。
- `POST /{id}/approve` body `{title?, answer?, publish:boolean}` → `{entryId}`；状态 `approved`。
- `POST /{id}/reject` body `{note?}` → 状态 `rejected`。

## 持久化与迁移

- 仅新增一张表，不改 `knowledge_entries`。迁移文件名 14 位时间戳，`BIGINT UNSIGNED AUTO_INCREMENT`、utf8mb4、`DATETIME(3)` 默认当前时间，均照现有样式。
- **不插业务数据**（AGENTS：生产迁移只建结构；如需配置行须显式 id≥900，本设计无）。
- 回滚：删表即可，因为功能由开关控制且不触碰既有表。

## 失败处理与恢复

| 场景 | 处理 |
| --- | --- |
| 自检调用失败/超时/输出不可解析 | 该题 `PENDING_REVIEW`，`review_note=verify_error:<code>`；不抛给 `runOnce` |
| 检索无结果 / 抓取失败 / 非白名单 | 跳过外部，走内部修订；`snippets=null` |
| 修订输出不可解析 | 该题 `PENDING_REVIEW` |
| `max-calls-per-run` 用尽 | 剩余题 `PENDING_REVIEW`，本轮日志记 `budget_exhausted` |
| 采纳时条目已存在（同指纹+标题） | `createCollected` 返回 `created=false`，用返回的 `entryId` 回填，状态仍 `approved` |
| 管理员重复采纳 | 幂等返回原 `entryId` |
| verify 关闭 | 完全走老路径，不写验证表 |

## 安全与权限

- 外发：每个 URL 必过 `UpstreamGuard.assertAllowed`（https + 公网 IP），`followRedirects=NEVER`，只带固定 `User-Agent`，不带任何凭据/Cookie；域名必须命中白名单（检索阶段与抓取阶段各校验一次）。
- 内容：外部片段只进模型上下文，**不落条目正文**；入库答案必须是模型重写的文本。
- 复核 API 全部 `@ApiAccess(ADMIN)`；`reviewed_by` 取当前管理员。
- 不改变学习者端可见性：未采纳的 `pending_review` 不产生条目。

## 可观测性

- `RunKind.ESSAY_VERIFY`（自检）与 `ESSAY_REVISE`（修订）各自 `recorder.begin(...).withModel(...).withPromptVersion(...)`，成功/失败 `finish(RunOutcome, code, FailureKind)`；FailureKind 沿用 `BAD_OUTPUT`/`RETRYABLE`。
- `runOnce` 日志追加：`校验 通过N/修订M/复核K/降级D/预算用尽`。
- `ai_essay_verifications` 支撑管理端列表与后续统计（按 status 计数）。

## 实施顺序

1. P0 标注集与基线脚本（离线，先量现状）。
2. P1：`EssayBodyRenderer` 抽取 + `EssayVerifier`/`EssayVerificationPrompt` + `EssayVerificationService`（external 关闭）+ `EssayVerificationMapper` + 迁移 + `EssayProperties.verify` + `RunKind` + `EssayCollectionService` 接线。
3. P3：`EssayReviewService` + `EssayReviewAdminController` + 管理端页面/路由/菜单/客户端。
4. P2：`EssayReferenceRetriever` + 接入 `EssayVerificationService` 升级分支。
5. P4：用同一批样本跑对比，出报告。

## 测试策略

- 单测（`backend/paideia-ai/src/test/.../essay/`，沿用 `EssaySourceFanOutTest` 的 fake `KnowledgeApi` 与假模型）：
  - 闸门 ok/高置信 → AUTO_PASS 且入库一次；ok/低置信 → 进升级。
  - issues + 有依据 + revise → REVISED 且入库用修订稿。
  - 无依据 → 内部修订；修订拒绝 → PENDING_REVIEW 且**未入库**。
  - 模型抛异常 → PENDING_REVIEW(reason=verify_error)。
  - `verify.enabled=false` → 与现状完全相同（调用序列断言）。
  - `EssayReferenceRetriever`：非白名单域被丢弃、`UpstreamGuard` 拒绝时返回空、超长截断。
  - `EssayBodyRenderer` 与旧 `render` 输出逐字一致（回归断言）。
- 迁移：`MigrationVersionFormatTest`/`MigrationChecksumTest`/`MigrationSeedOverlapTest` 自动覆盖；本地 `scripts/test-db.sh` 跑。
- 管理端：`EssayReviewPage` 的 e2e 走 `frontend/apps/admin` 的既有 playwright（新增 spec 或并入 knowledge 相关）。后端 401/403 由 `AuthorizationIsolationTest` 覆盖（新端点须声明 ADMIN）。
- 效果：P4 脚本对同一样本跑基线 vs 校验后，输出「事实错误占比、正向改坏率」。

## 需求追踪

| 需求 | 设计落点 | 测试 |
| --- | --- | --- |
| REQ-001 | 变更 #6/#9/#8 与状态机 | 状态机单测 |
| REQ-002 | `EssayVerificationPrompt` + 解析 | 判定解析单测 |
| REQ-003 | `pending_review` + `EssayReviewService/Controller` + #14-17 | 复核 e2e + 服务单测 |
| REQ-004 | `RunKind` #5 + 表 #13 + 日志 | 记账断言 |
| REQ-005 | `EssayProperties.verify` #4 | verify=false 回归 |
| AC-001/002 | P0/P4 | eval 报告 |
| AC-003 | 表字段 | 行存在性断言 |
| AC-004 | #4/#9 | 同上 |

## 待决定事项

同提案「待决定事项」1~5（阈值、检索实现、白名单、采纳默认状态、`always.md` 例外记录）；本设计按建议默认值推进，批准时可一并确认。

## 范围变更（2026-11-19，基线后缩范围）

- 本期实现：`EssayBodyRenderer`、`EssayVerificationPrompt`、`EssayVerifier`（仅 `checkFactual`）、`EssayVerificationService`、`EssayVerificationMapper`、迁移、`EssayProperties.verify.*`（`external` 固化为 false）、`RunKind.ESSAY_VERIFY`。
- 推迟（对应 TASK-005/006/007）：`EssayReferenceRetriever`、`EssayVerifier.revise` 的外部分支、`EssayReviewService` 与 `EssayReviewAdminController`、管理端 `EssayReviewPage`。
- 状态机简化：`ok && confidence>=0.80` → `auto_passed`；其余（issues/unsure/低置信/异常/预算耗尽）→ `pending_review`（记录落库，本期无复核页，仅 DB 可查）。**取消 `revised` 分支**。
- 表 `ai_essay_verifications` 保留 `revised_answer`/`used_refs` 等列，后续启用外部检索时无需改表。
- 校验提示词 `essay-verify-v2` 按「只判事实错误」校准；`deepseek-flash` 是推理模型，单次判定 completion 需 16k、超时 300s（实测 30–45s/条），故 `verify.enabled` 默认关闭。评测见 `eval/compare.md`。
