---
artifact: proposal
work_id: WORK-069
work: AI 失败的处置语义
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 14:14:25 +0800
approver_role: owner
---

# AI 失败的处置语义提案

## 摘要

把「这次 AI 失败接下来该怎么办」从各调用点各自的 `catch` 与日志，提升为 `ai_runs` 上一等、可查询的字段 `failure_kind`：

- `retryable`：临时失败，可自动重来；
- `bad_output`：模型输出不合规，同配置重来没有意义；
- `needs_human`：要人改配置或拍板。

三条链路按它分流：记忆提取根据它决定是否继续退避，营地出题与面经抓取先只做记录、不改变现有重试行为；管理端 AI 运行查询可按它筛选与计数。

## 推荐方案

### 核心机制

1. `ai_runs` 新增 `failure_kind VARCHAR(16) NULL`，不加索引、不加 CHECK。`NULL` 只用于 `OK` / `CANCELLED` 这类非失败终态；所有失败终态（`FAILED` / `TIMEOUT` / `INTERRUPTED`）至少落一个处置值（见下）。
2. 在端口包 `io.github.xiaou61.ai` 新增 `FailureKind` 枚举，提供 `key()` 与 `isRetryable()` 两个方法。`isRetryable()` 是「可重试」判定的唯一口径。
3. `RunRecorder.RunScope` 增加带处置语义的默认方法：
   `default void finish(RunOutcome outcome, String errorType, FailureKind failureKind)`，默认转发到现有两参方法，避免一次性改所有实现。
4. `DefaultRunRecorder` 覆盖三参 `finish`：三参优先；两参按 `RunOutcome` 给兜底处置——`FAILED`/`TIMEOUT`/`INTERRUPTED` 默认 `retryable`，`OK`/`CANCELLED` 保持 `NULL`。这样「失败的 run 不允许没有处置」成为采集层的不变式，而不是每个调用方各自记得。
5. 持久化链路同步扩展：`RunRecord`、`RunPayload.RunFinish`、`MysqlRunSink.toRow`、`AiRunMapper.finish`、`RunSummary` 与 `AiRunAdminService` 全部带上 `failureKind`。

### 三条链路的分流口径

- **记忆提取**（唯一改变行为的链路）：新增 `MemoryFailureClassifier`：
  - 模型输出/解析失败（`MemoryOutputException`）→ `bad_output`；
  - `BizException(ErrorCode.INVALID_ARGUMENT)`（上游拒绝凭据等需要人处理）→ `needs_human`；
  - 其余异常（上游不可用、超时、线程池拒绝、数据库临时失败）→ `retryable`。
  失败时写 `ai_runs.failure_kind`；`retryable` 继续走现有 `MemoryExtractionJobs.fail` 的退避，`bad_output` / `needs_human` 走新增的 `failPermanently`：任务行直接置 `failed`，`attempts` 与 `next_attempt_at` 不再推进。
- **营地出题**：在 `CampGenerationException` 上提供 `failureKind()`，把既有三档映射为 `EMPTY_RESPONSE → needs_human`、`BAD_SHAPE → bad_output`、`NO_VALID_ITEM → bad_output`；`CampService` 的 `create` / `retest` 失败分支按它 `finish`。生成器抛出的其它运行异常按采集层的 `retryable` 兜底。
- **面经抓取**：`EssayCollectionService` 目前**没有 `RunRecorder`**，这是本项必须补的采集点，否则 REQ-004 无法落地。抓取来源失败记一条 `essay_collect` / `retryable`；每篇帖子抽取失败记一条 `essay_extract` / `bad_output`。这是新增的遥测，不新增重试、不改变现有「抓取失败整次跳过、单篇失败继续其它帖子」的行为。

### 管理端

- 明细列表的查询参数增加 `failureKind`；`RunSummary` 带出 `failureKind` 并显示对应标签。
- 概览新增 `byFailureKind`（与其它排行同一组筛选），管理端概览页增加「按失败处置」排行卡。
- 筛选下拉直接列三个枚举值（`retryable` / `bad_output` / `needs_human`），不从自由文本推断。

## 范围

- `ai_runs` 表结构与采集管道：`RunRecorder`、`RunRecord`、`RunPayload`、`MysqlRunSink`、`AiRunMapper`。
- 记忆提取：`MemoryExtractionService`、`MemoryExtractionJobs`、`MemoryExtractionJobMapper`、`MemoryCandidateParser`。
- 营地出题：`CampGenerationException`、`CampService`。
- 面经抓取：`EssayCollectionService`（新增采集）与 `EssayExtractor` 的失败边界。
- 管理端：`AiRunAdminService`、`AiRunAdminController`、`AiRunsListPage`、`AiRunsOverviewPage`。
- 迁移：新增一条 14 位时间戳迁移；不修改 `V21`。

不在本项范围：

- 不建「待人工队列」表与处置页面；
- 不给面经增加当轮重试；
- 不给 `failure_kind` 加索引或 CHECK（行数还小，全表扫可接受；索引等有成规模数据再加）；
- 不把 `failure_kind` 接进 AIOps 诊断输入；
- 不回填历史 `ai_runs` 的 `failure_kind`（历史行保持 `NULL`，管理端显示「未记录」）。

## 备选方案

### 方案 A：复用 `error_type` 前缀（不选）

在 `error_type` 里塞 `retryable:xxx` 之类前缀，成本最低。不选原因：`error_type` 是低基数归因列，前缀会污染既有按错误类型分组的统计，且「按处置筛选」要变成 `LIKE`，无法建立稳定口径。它满足「能查」但满足不了「处置是一等语义」。

### 方案 B：独立失败处置表（不选）

新建 `ai_failure_actions(run_id, kind, ...)`。最有力的理由是未来可以承载人工处置状态与审计；但本项已经明确不建待人工队列，所有失败天然属于一次 `ai_runs`，独立表只会增加 join、清理与一致性负担。等真的需要「待办 + 处置 + 权限 + 页面」时再拆，不是现在。

### 方案 C：只加字段、不改采集管道（不选）

让各业务链路自己写一条 SQL。改动最少，但会把「哪些路径写了、哪些没写」变成永久的不一致来源，也无法让新链路默认继承失败语义；`RunRecorder` 是既有的唯一采集入口，本项应把它作为唯一写入点。

## 仓库影响

- 数据：`ai_runs` 表加一列；历史行保持 `NULL`。
- 行为：只有记忆提取的重试行为改变（不可重试失败直接失败，不再烧退避）；营地与面经行为不变。
- 观测：新增 `failure_kind` 维度；管理端新增筛选与排行。
- 采集边界：面经抓取从「没有 ai_runs」变成「有 ai_runs 记录」，属于新增遥测，不改变业务结果。
- 迁移：新增 14 位时间戳文件；按 `MigrationChecksumTest` 提示用 `-Dmigration.checksums.update=true` 登记新文件，必须 review `migration-checksums.txt` 的 diff。

## 交付拆分

1. **数据与采集管道**：迁移 + `FailureKind` + `RunScope` 默认方法 + `RunRecord`/`MysqlRunSink`/`AiRunMapper`；先让所有失败终态有兜底处置。
2. **记忆提取分流**：分类器、`MemoryOutputException`、`failPermanently`、`MemoryExtractionService` 接线。
3. **营地出题映射**：`CampGenerationException.failureKind()` 与两条 `CampService` 失败分支。
4. **面经抓取采集**：`EssayCollectionService` 增加 `RunRecorder`，抓取失败与单篇抽取失败分别记录。
5. **管理端查询与界面**：`RunSummary.failureKind`、`failureKind` 筛选、`byFailureKind` 概览排行、前端两页。
6. **回归与收口**：结构化测试报告、迁移三守卫、必要的前端类型检查。

## 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 其它 AI 链路不写 `failure_kind`，被误当成“没失败” | 统计口径不完整 | 采集层对 `FAILED`/`TIMEOUT`/`INTERRUPTED` 兜底 `retryable`；管理端对 `NULL` 明确显示「未记录」，不把它当成功 |
| 记忆分类把上游临时故障误判 `needs_human` | 不再重试、任务提前失败 | 分类器只把 `ErrorCode.INVALID_ARGUMENT` 视为需要人；上游不可用/timeout/线程池等一律 `retryable`，并有分类器单测 |
| 面经新增遥测影响每日抓取性能 | 抓取多一次轻量记录，理论上有队列开销 | `RunRecorder` 本身零 IO、队列满会丢弃；`EssayCollectionService` 只在每篇抽取前后记录，不串行阻塞上游 |
| 迁移时间戳与其它会话撞号 | Flyway 启动失败 | 实施时用实施当刻的 14 位时间戳，先 `git log --all -- <迁移目录>` 查重；`MigrationVersionFormatTest` 挡纯数字号 |
| 新迁移未登记校验和 | `MigrationChecksumTest` 红 | 用 `-Dmigration.checksums.update=true` 登记并 review diff |

## 验收映射

| 需求/验收 | 落点 |
| --- | --- |
| REQ-001 / AC-001 | 新迁移；`FailureKind`；`RunRecord`/`MysqlRunSink`/`AiRunMapper`；迁移三守卫 |
| REQ-002 / AC-002 | `MemoryFailureClassifier`；`MemoryExtractionJobs.failPermanently`；`MemoryExtractionService` 分流；反向验证去掉分类后测试变红 |
| REQ-003 / AC-003 | `CampGenerationException.failureKind()`；`CampService` 两处 catch |
| REQ-004 / AC-004 | `EssayCollectionService` 的抓取失败与单篇抽取失败记录；不重试行为断言 |
| REQ-005 / AC-005 | `RunSummary.failureKind`；`AiRunMapper.page/countPage` 的 `failureKind` 筛选；`Overview.byFailureKind`；管理端类型检查 |

## 待决定事项

- 面经是否要当轮重试：**本项不做**（先攒一周数据再定）。
- `failure_kind` 是否进 AIOps 诊断输入：**本项不做**（只做记录与查询）。

以上两项按 `requirements.md` 的现有口径处理；实施前仍需要用户明确确认需求，并按 `project-lifecycle` 记录 requirements 的批准。
