---
artifact: tasks
work_id: WORK-050
work: 记忆的少而准：召回排序、时间淘汰、半自动提案与离线评测
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-006, WORK-013, WORK-010, WORK-033, WORK-036]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 14:14:28 +0800
approver_role: CTO
---

# 任务 · 记忆的少而准

**排序原则**：先让"评测"能跑（S1），否则后面的每次改动都不可证伪；再改安全与召回（S2），
它们直接影响用户看到的东西；淘汰（S3）是独立的一刀；半自动（S4）最后，因为它要动提示词与面板。
S1 的基线必须用**现状代码**跑出来，不能等改完再补。

## 依据

- `requirements.md`：REQ-001..012、AC-001..013、用户决定 D-01..D-06。
- `proposal.md`：五个切片的交付拆分、验收映射、风险表。
- `design.md`：排序公式、候选表结构、失败处理、测试策略、需求追踪。

## 全局约束

- 新迁移用 14 位时间戳命名；生产迁移只建结构。
- 任何碰数据库的验证走 `scripts/test-db.sh`，不直接连 `paideia_test`。
- 内环按项目节奏：`-pl '!paideia-app'` 单测 + 四个边界用例；全量只在交接与发版时跑。
- 评测需要真上游，默认跳过；**跳过不等于通过**，验收看 `Skipped` 列与报告文件是否存在。
- 提示词改了必须升版本号（`memory-extract-v1` → `v2`），否则"A/B 差异来自哪里"说不清。
- 金集里不许出现任何真实的个人信息、口令或连接串——用例本身不能成为泄露源。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 过滤器把正常文本误伤（"密码学"、"身份证校验算法"）或漏判 `password=` 连接串 | TASK-004 | `SensitiveContentTest`：`doesNotFlagTechnicalTerms`、`flagsConnectionStringWithPassword` |
| 被过滤的原文进了候选表（等于换个地方泄露） | TASK-009 | `MemoryCandidateStoreTest.filteredCandidateStoresNoContent` |
| 关键词为空时排序退化成"最近 8 条" | TASK-005 | `MemoryRecallOrderTest.blankKeywordFallsBackToKindWeight` |
| 结构化那一路没有全文索引导致排序恒等于时间序 | TASK-005 | `MemoryRecallOrderTest.structuredOrdersByRelevance` |
| `touch` 把 `last_used_at` 写成比已有值更早的时间 | TASK-006 | `MemoryStoreTest.touchNeverMovesBackwards` |
| 淘汰删掉了仍在被召回的行 | TASK-007 | `MemoryPurgeTest.recentlyUsedSurvives` |
| 淘汰重复执行导致计数与删除翻倍 | TASK-007 | `MemoryPurgeTest.isIdempotent` |
| `PROPOSE` 与 `ADD` 混用：既写正式表又写候选 | TASK-009 | `MemoryExtractionServiceTest.proposeWritesOnlyCandidates` |
| 接受候选并发两次产生两条正式记忆 | TASK-009 | `MemoryCandidateAcceptTest.concurrentAcceptCreatesOne` |
| 总开关关闭后外部 MCP 工具仍能写候选/记忆 | TASK-010 | `MemorySwitchTest.rememberToolRespectsSwitch` |
| 注销后候选表留残余 | TASK-010 | `MemoryDeletionTest.candidatesArePurged` |
| 无上游凭据时评测以"0 条通过"的形式呈现为成功 | TASK-003 | `MemoryEvalTest.reportsNotEvaluatedWithoutKey` |
| 金集里混入真实个人信息 | TASK-001 / TASK-002 | 自查：逐条读一遍用例正文 |

## 任务

### TASK-001 | done | 提取侧金集（120 段对话）

- 对应：`REQ-009`、`AC-010`
- 依赖：无
- 新建/修改：`backend/paideia-ai/src/test/resources/golden/memory/extraction-cases.json`（新建）
- 接口：无代码接口；文件被 TASK-003 读取，schema 见 design 的评测一节
- 步骤：
  1. 写 120 段用例，每段含 `existing`（已有记忆，可空）、`turns`（1–3 轮）、`expect`。
  2. 覆盖分布：ADD 36 / UPDATE 24 / DELETE 12 / PROPOSE 18 / 无操作 30。**120 条是 AC-010 推出来的**：
     分母 14–17 时一条翻转就是 6–7 个百分点，5 pp 容差不可能达到；扩到 120 后每个分母 ≥ 24。
  3. `expect` 用"内容包含某片段"而不是逐字相等，避免被措辞变化判错。
  4. 逐条自查：不含真实手机号、真实邮箱、真实口令。
- 验证：`python -c "import json;json.load(open('...'))"` 通过；用脚本统计各动作条数与上面的分布一致

### TASK-002 | done | 召回侧金集（30 条查询）

- 对应：`REQ-009`、`AC-002`、`AC-010`
- 依赖：无
- 新建/修改：`backend/paideia-ai/src/test/resources/golden/memory/recall-cases.json`（新建）
- 接口：被 TASK-003 读取
- 步骤：
  1. 写 30 条用例：每条给一组记忆（含 `kind`、`ageDays`）+ 一句查询 + 期望首位命中的那条 + 期望进入前 8 的集合。
  2. 覆盖分布：`weak_topic` 相关 12 条、`preference` 6 条、`goal` 4 条、`entry_understanding` 4 条、关键词为空的 4 条。
  3. 至少 6 条必须是**旧但相关**的记忆排在新但无关的前面——这是本项要证明的核心行为。
- 验证：JSON 可解析；统计各 kind 条数符合分布；"旧但相关"用例数 ≥ 6

### TASK-003 | done | 评测执行器与基线

- 对应：`REQ-009`、`REQ-010`、`AC-010`、`AC-011`
- 依赖：TASK-001、TASK-002
- 新建/修改：`paideia-ai/src/test/java/.../memory/MemoryEvalTest.java`（新建，提取侧，需模型）、
  `paideia-app/src/test/java/.../MemoryRecallEvalTest.java`（新建，召回侧，需库）、
  两份结果汇到 `target/memory-eval/report.md`
- 接口：读 TASK-001/002 的金集；产出 Markdown 表（指标名、数值、用例数、离散度）
- 步骤：
  1. 提取侧：按金集逐条调真模型，算 precision / recall / 重复率 / 错 UPDATE 率。
  2. 召回侧：把金集灌进库，调 `MemoryStore.recall`，算 Recall@8 / MRR / 噪声条数。
  3. 成本：从 `ai_runs` 或提取调用的 usage 累计 token，算摊到每轮的增量。
  4. 无 `DEEPSEEK_API_KEY` 时 `assumeTrue` 跳过，并在报告里写"未评测"而不是 0。
  5. **用现状代码跑一遍，把数字写进 `.agent/changes/WORK-050-.../testing/baseline.md`**。
- 验证：设 key 跑（数字进工件）；不设 key 再跑一次，断言 `Skipped` 与"未评测"输出

### TASK-004 | done | 敏感信息过滤

- 对应：`REQ-001`、`AC-001`
- 依赖：无（与 S1 并行）
- 新建/修改：`ai/internal/memory/SensitiveContent.java`（新建，纯函数）、`MemoryStore`（两个写入入口调用）、
  `MemoryTools.Remember`（调用）、`SensitiveContentTest`（新建）
- 接口：`SensitiveContent.find(String text): Optional<Category>`；`Category` 枚举
  `PHONE / ID_CARD / BANK_CARD / SECRET / CONNECTION_STRING`
- 步骤：
  1. 五类各写一组正则，先写"不该命中"的反例（密码学、身份证校验、手机号格式的教学示例）。
  2. 接入 `MemoryStore.rememberStructured` / `rememberText` / `overwrite`：命中即抛业务异常并**不写库**。
  3. `MemoryTools.Remember` 把异常转成 `ToolResult.fail`，不吞。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai test -Dtest=SensitiveContentTest` 全绿；
  其中"反例不误伤"用例数与"正例命中"用例数各 ≥ 8

### TASK-005 | done | 召回相关性排序（两路）

- 对应：`REQ-002`、`REQ-003`、`AC-002`
- 依赖：TASK-007 的迁移（结构化那一路需要 ngram 索引；两个任务要约定同一个迁移文件，谁先落地谁合并）
- 新建/修改：`MemoryMapper`（新增 `selectStructuredRelevant`、改 `searchText` 的排序）、
  `MemoryStore.recall`、`MemoryRecallOrderTest`（新建，在 paideia-app）
- 接口：`selectStructuredRelevant(long userId, String query, boolean fulltext, String like, int limit)`
- 步骤：
  1. 按 design 的公式写两条 SQL；关键词为空时去掉 `MATCH` 项。
  2. 单字/过短关键词沿用 `SearchQuery` 判定退回 `LIKE`（不重写这套判定）。
  3. 排序结果写进 `MemoryStore.recall`，签名不变。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MemoryRecallOrderTest` 全绿，
  且"旧但相关排在前"的用例通过

### TASK-006 | done | 召回后刷新使用时间

- 对应：`REQ-005`、`AC-004`
- 依赖：TASK-005
- 新建/修改：`MemoryMapper`（两条批量 UPDATE，`<foreach>`）、`MemoryStore.touch`、`ConversationService` 的召回调用点
- 接口：`void touch(long userId, List<Long> structuredIds, List<Long> textIds)`
- 步骤：
  1. 两条 `UPDATE ... SET last_used_at = #{now} WHERE user_id = #{userId} AND id IN (...)`。
  2. 空集合直接返回，不发 SQL。
  3. 调用点包 try/catch：失败只记 WARN，不影响回答。
  4. **不写比已有值更早的时间**：`SET last_used_at = GREATEST(COALESCE(last_used_at, #{now}), #{now})`。
- 验证：`-Dtest=MemoryStoreTest` 全绿，含 `touchNeverMovesBackwards`、`touchEmptyListIssuesNoSql`

### TASK-007 | done | 迁移、淘汰任务与级联

- 对应：`REQ-004`、`REQ-011`、`AC-003`、`AC-004`、`AC-005`
- 依赖：无（与 S2 并行；与 TASK-005 共用迁移文件）
- 新建/修改：`paideia-app/src/main/resources/db/migration/V<ts>__memory_relevance_candidates_and_ttl.sql`（新建）、
  `MemoryMapper.purgeStructured` / `purgeText`、`MemoryStore.purgeExpired`、
  `MemoryRetentionPurger`（新建，对照 `TelemetryRetentionPurger`）、`MemoryPurgeTest`（新建）
- 接口：`int purgeExpired(Instant now)`；TTL 分档在 `MemoryProperties`
- 步骤：
  1. 迁移三项：结构化 ngram 索引、两表 `last_used_at` + 索引、候选表（design 的 DDL）。
  2. `purgeExpired`：按 kind 循环 `DELETE ... WHERE kind=? AND COALESCE(last_used_at, created_at) < ? LIMIT 500`，
     返回总行数；两次执行第二次为 0。
  3. `@Scheduled(fixedDelay = 3600_000)`，进 `ScheduledTaskRecorder`；关闭时（`ai.telemetry.enabled=false` 无关，
     用 `MemoryProperties` 自己的开关）不跑。
  4. 注销清单加 `ai_memory_candidates`。
- 验证：`-Dtest=MemoryPurgeTest` 全绿；迁移在零数据新库上能跑（`scripts/test-db.sh` 自动给独占库）

### TASK-008 | done | PROPOSE 动作与提示词 v2

- 对应：`REQ-006`、`AC-006`
- 依赖：TASK-001（金集用来判断提议得对不对）
- 新建/修改：`MemoryExtractionPrompt`（`VERSION` → `memory-extract-v2`，加 `PROPOSE` 与"不确定就问"规则）、
  `MemoryCandidateParser`、`MemoryCandidate`（加 `propose` 取值）、`MemoryCandidateParserTest`
- 接口：`MemoryCandidate.Action` 增 `PROPOSE`
- 步骤：
  1. 提示词加第五种动作与三条规则：什么算"不确定"、提议时 `kind` 可以留空、提议也要给 `reason`。
  2. 解析器认新动作；未知动作仍按"整次放弃"处理（不静默跳过）。
  3. 用 TASK-001 的 6 条"应 PROPOSE"用例跑一次，检查提议率不是 0 也不是 100%。
- 验证：`-Dtest=MemoryCandidateParserTest` 全绿；真连跑一次记录提议率进工件

### TASK-009 | done | 候选表与整理分流

- 对应：`REQ-006`、`REQ-007`、`AC-006`、`AC-007`、`AC-008`
- 依赖：TASK-007（候选表）、TASK-008（新动作）
- 新建/修改：`MemoryCandidateStore`（新建）、`MemoryCandidateMapper`（新建）、
  `MemoryExtractionService`（分流）、`MemoryCandidateStoreTest`、`MemoryExtractionServiceTest`、`MemoryCandidateAcceptTest`
- 接口：design 里的 `MemoryCandidateStore` 六个方法
- 步骤：
  1. 写候选表读写，归属条件全在 SQL 里。
  2. 分流：`ADD/UPDATE/DELETE` 过过滤器后直写；`PROPOSE` 写候选；被过滤的写 `status='filtered'` 且 `content` 为 NULL。
  3. 接受：事务内写正式表 + 置 `accepted`；用 `UPDATE ... WHERE status='pending'` 的影响行数做并发闸，
     影响 0 行说明别人已经处理过，返回冲突而不是再写一条。
  4. `dropExpired`：`DELETE ... WHERE status IN ('pending','rejected','filtered') AND expires_at < ? LIMIT 500`。
- 验证：`-Dtest='MemoryCandidate*Test,MemoryExtractionServiceTest'` 全绿，含 `filteredCandidateStoresNoContent`、
  `concurrentAcceptCreatesOne`、`proposeWritesOnlyCandidates`

### TASK-010 | done | 候选端点、注销与总开关

- 对应：`REQ-008`、`REQ-011`、`REQ-012`、`AC-009`、`AC-012`、`AC-013`
- 依赖：TASK-009
- 新建/修改：`AiMemoryController`（三个端点）、`MemoryTools`（总开关闸）、`AiUserDeletionListener`、
  `MemorySwitchTest`、`MemoryDeletionTest`
- 接口：`GET /api/v1/ai/memories/candidates`、`POST .../{id}/accept`、`POST .../{id}/reject`
- 步骤：
  1. 三个端点，`ApiResponse` 包装，与现有记忆端点同风格；`filters` 与 `security` 规则不需要新增（同前缀）。
  2. 总开关关闭时：清单返回空、接受/拒绝返回业务错误、`memory_remember` 返回失败。
  3. 注销清单确认包含候选表；集成测试断言四张表（两记忆 + 候选）都清空。
- 验证：`-Dtest='MemorySwitchTest,MemoryDeletionTest'` 全绿

### TASK-011 | done | 面板「待确认」与 e2e

- 对应：`REQ-008`、`AC-007`、`AC-008`
- 依赖：TASK-010
- 新建/修改：学习者端记忆面板组件、`@paideia/assistant`（或现有客户端）加三个方法、`e2e/assistant.spec.ts`
- 接口：消费 TASK-010 的三个端点
- 步骤：
  1. 面板顶部加"待确认（N）"区，每条显示内容、来源入口、模型给的理由、接受/拒绝按钮。
  2. 被过滤的显示成一行计数与原因，**不显示原文**。
  3. e2e：插入一条候选 → 面板可见 → 接受 → 正式列表出现、候选区消失。
- 验证：`playwright test e2e/assistant.spec.ts --grep "记忆"` 通过

### TASK-012 | done | 复测与对比报告

- 对应：`AC-010`、`AC-011`
- 依赖：TASK-003 到 TASK-011 全部
- 新建/修改：`.agent/changes/WORK-050-.../testing/report.md`（新建）
- 接口：无
- 步骤：
  1. 用同一套金集重跑，数字与 `testing/baseline.md` 并排。
  2. 端到端：有记忆 vs 无记忆各跑同一批问题，记录判分差异（人工抽查 + LLM 判分）。
  3. 成本：记录本轮整理与召回的 token 增量。
  4. **不达标的指标照实写**，并写清原因；不许把"当前不达标"写成断言当前是错的绿测试。
- 验证：报告含四组数字、基线与复测并排、每项达标/不达标都有结论

## 完成条件

- 12 个任务全部 `done`，`tasks.md` 状态与实际执行结果一致。
- `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` 全绿（看 `Skipped` 列）。
- 四个边界用例与 `Memory*Test` 集成切片全绿。
- 评测报告存在且含四组数字；无凭据时明确写"未评测"。
- 学习者端记忆 e2e 通过。
- `$lifecycle validate . --strict` 通过。
- `.agent/history/updates.md` 有本轮记录；本地提交按切片分笔；**push 需单独授权**。
