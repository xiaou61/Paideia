---
artifact: requirements
work_id: WORK-069
work: AI 失败的处置语义
status: approved
created: 2026-11-04
updated: 2026-11-04
mode: strict
mode_reason: 动 ai_runs 表结构（迁移）且改变三条链路的重试行为，属于数据与公共行为变化。
approved_by: xiaou61
approved_at: 2026-11-04 14:14:25 +0800
approver_role: owner
---

# 需求

## 目标

让「这次 AI 失败接下来该怎么办」成为**落库、可查询**的一等语义，而不是调用方各自 `catch` 后打个日志。
三条链路（营地出题、记忆提取、面经抓取）按它分流，其中记忆提取是唯一今天真的会因此改变行为的链路。

- **REQ-001**：`ai_runs` 增加 `failure_kind`，取值只能是 `retryable`（临时失败，可自动重来）、`bad_output`（模型输出不合规，同配置再来一次没用）、`needs_human`（要人改配置或拍板）；`NULL` 表示不是失败。**这是处置语义，不是错误归因**——归因仍由已有的 `error_type` 承担。
- **REQ-002**：记忆提取的失败按处置分流——`bad_output` / `needs_human` 直接置 `failed`，**不再烧退避与 attempts**；`retryable` 保持现有退避（WORK-061 的 `retryBackoffSeconds × 2^(attempts-1)`，封顶 10 分钟）。
- **REQ-003**：营地出题把已有的 `CampGenerationException.Kind` 映射到 `failure_kind`，其中 `EMPTY_RESPONSE` 标 `needs_human`（它意味着 max_tokens 被思考吃满，重来同样被吃满）。
- **REQ-004**：面经抓取的失败标出处置：抓取失败 `retryable`、单篇抽题失败 `bad_output`。**行为不变**（不重试），本项只让它可被统计。
- **REQ-005**：管理端的 AI 运行查询带出 `failure_kind`，可按它筛选与计数。

## 验收标准

- **AC-001**：迁移在零数据新库与已有数据的库上都能跑通；`MigrationVersionFormatTest` / `MigrationChecksumTest` / `MigrationSeedOverlapTest` 全绿。
- **AC-002**：记忆提取遇到不可重试失败时，任务行 `status=failed`、`attempts` 不再增长、`next_attempt_at` 不被推后，且对应 `ai_runs.failure_kind` 有值；遇到 `retryable` 时仍按退避重排。**反向验证**：去掉分流映射后，该测试必须变红。
- **AC-003**：营地三种 `Kind` 各自映射正确，`EMPTY_RESPONSE → needs_human`、`BAD_SHAPE → bad_output`、`NO_VALID_ITEM → bad_output`。
- **AC-004**：面经两条失败路径写入对应的 `failure_kind`，且「不重试」这一现有行为在测试里被断言（防止后续顺手改成静默重试）。
- **AC-005**：管理端查询按 `failure_kind` 过滤时只返回对应行；不传时行为与现在一致。

## 决策分层

### 已确认事实（读代码得到）

- `RunOutcome` 是 `ok / failed / timeout / cancelled / interrupted`，说的是**调用技术上怎么了**，不含处置意图。
- `ai_runs.error_type` 已经是低基数归因列，纪律是"绝不写异常消息"。
- `CampGenerationException.Kind` 三档已经存在，注释明写调用方据此决定是否值得修复重试——但它是 camp 包私有的，不落库、不跨链路。
- 记忆提取已有退避与 `maxExtractionAttempts` 上限（WORK-061），**所有失败一律进重试**。
- 面经抓取失败只写日志，用户不可见、事后不可统计。
- `EssayCollectionService` **当前没有 `RunRecorder` 记录**（全仓没有 `essay_*` 的 `RunKind`）；REQ-004 的“可被统计”需要新增这个采集点，这属于本项实现的一部分，不改变抓取/抽题的业务行为。

### 用户决定（2026-11-04）

- 语义落在 `ai_runs` 加一列；不选"复用 error 前缀"（不可查询），也不选"独立待人工队列表"（那是新功能面）。
- 三条链路全做。

### 实现选择（待方案与设计阶段确认）

- 枚举放 `io.github.xiaou61.ai` 端口包；`RunScope` 用**默认方法**扩展，避免改所有实现。
- 迁移只加列、不加索引、不加 CHECK。
- 「可重试」的判定口径收敛成一处常量方法，避免三条链路各猜一套。

### 待确认问题

- 面经是否需要**当轮重试**（不是本项，先攒一周数据再定）。
- `failure_kind` 要不要进 AIOps 诊断的输入（本项只做记录与查询）。

## 不做

- 不建「待人工队列」表。那要带处置入口（列表 + 处置 + 权限 + 页面），是另一个功能面。
- 不给面经加当轮重试。要定调度语义（任务表还是线程内退避）与幂等。
- 不加索引。行数还小，全表扫可接受。`shortcut: 行数上百万后补 (failure_kind, started_at) 索引`。
