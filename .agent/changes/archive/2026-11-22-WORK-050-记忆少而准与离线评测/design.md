---
artifact: design
work_id: WORK-050
work: 记忆的少而准：召回排序、时间淘汰、半自动提案与离线评测
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-006, WORK-013, WORK-010, WORK-033, WORK-036]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 14:14:28 +0800
approver_role: CTO
---

# 设计 · 记忆的少而准

## 系统上下文

```
学习者端面板 ──┐
               ├─→ AiMemoryController ─┬→ MemoryStore ──────────→ ai_memories_structured / ai_memories_text
外部 MCP 工具 ─┘                        └→ MemoryCandidateStore ─→ ai_memory_candidates
                                          ↑
对话一轮结束 → MemoryExtractionService ───┘（自动整理，PROPOSE 分流）
知识库自评事件 → SelfAssessmentMemoryBridge ┘（直写结构化，不过候选）
TelemetryRetentionPurger 同款节奏 → MemoryRetentionPurger（TTL 淘汰 + 候选过期）
```

不变的三条边界：严格按 `user_id` 隔离；不建跨模块外键（注销靠应用层清单）；时间列由应用按 UTC 写。

## 组件与职责

| 组件 | 位置 | 职责 | 不做什么 |
| --- | --- | --- | --- |
| `SensitiveContent` | `ai/internal/memory`（新建，纯函数） | 判定一段文本是否含敏感形态，返回命中的类别 | 不做脱敏改写、不抛异常 |
| `MemoryStore` | 既有 | 正式记忆的读写；**新增**相关性召回、`touch`、`purgeExpired` | 不认识候选、不认识模型 |
| `MemoryCandidateStore` | 新建 | 候选的写入、列出、接受、拒绝、过期清理 | 不判断该不该提案（那是模型） |
| `MemoryExtractionService` | 既有 | 分流 `ADD/UPDATE/DELETE`（直写）与 `PROPOSE`（写候选） | 不做内容过滤的判定（调 `SensitiveContent`） |
| `MemoryRetentionPurger` | 新建 | 每小时：TTL 淘汰 + 候选过期清理，各分批 | 不删"被频繁召回"的行 |
| `MemoryEvalTest` / `MemoryRecallEvalTest` | 测试 | 读金集、跑评测、写 `target/memory-eval/report.md` | 不进常规构建（无凭据时跳过） |

## 请求或事件流程

**一轮对话的召回（改动点在第 2、4 步）**

1. `ConversationService.prepare` 按两个开关决定要不要召回（不变）。
2. `MemoryStore.recall(userId, keyword)` 分别查两路，**各自按相关性取前 8**（现在分别按 `created_at DESC`）。
3. 拼成 `[学习者事实] …` / `[他说过] …` 两行前缀（不变）。
4. 召回成功后 `touch(userId, structuredIds, textIds)` 批量刷新 `last_used_at`（新增，失败只记日志）。

**自动整理（改动点在 3、4 步）**

1. `scheduleIfDue` 用条件 UPDATE 抢游标拿到增量（不变）。
2. `MemoryExtractor` 发一次调用，模型回 `candidates[]`（不变，提示词升 v2）。
3. 逐条分流：
   - `ADD/UPDATE/DELETE` → 先过 `SensitiveContent`；命中则**整条丢弃**，写一条 `status='filtered'` 的候选记录（**只记长度与类别，不记正文**）。
   - `PROPOSE` → 过同一个过滤器后写候选表（`status='pending'`）。
   - `SKIP` → 什么都不做。
4. 一次整理里任何一步抛异常 → 整次放弃（现有语义：不写半条），但**已过滤的丢弃不因为后面的失败而回滚**（它不写正式表，没有半条之说）。

**半自动确认（新增）**

1. 面板 `GET /api/v1/ai/memories/candidates?status=pending`。
2. 接受 → 事务内：写正式表（`source='auto'`）+ 把候选置 `accepted`；写正式表失败则整体回滚。
3. 拒绝 → 置 `rejected`（保留 7 天后由清理删除，便于"我刚才手滑了"回看）。

## 接口与数据

**`MemoryStore` 新增/改动的方法**

```java
List<MemoryItem> recall(long userId, String keyword);          // 签名不变，行为改为相关性排序
void touch(long userId, List<Long> structuredIds, List<Long> textIds);  // 批量，幂等
int purgeExpired(Instant now);                                  // 返回删除行数；按 kind 分档循环
```

**`MemoryCandidateStore`**

```java
long propose(long userId, Store store, String kind, Long refId, String content,
             long conversationId, long refMessageId, String reason, Instant expiresAt);
List<Candidate> pending(long userId, int limit, int offset);
Optional<Candidate> find(long userId, long id);              // 归属条件在 SQL 里
boolean markAccepted(long userId, long id, Instant now);
boolean markRejected(long userId, long id, Instant now);
int dropExpired(Instant now, int batch);
int deleteByUser(long userId);                                // 注销级联
```

**面板端点**（都挂 `/api/v1/ai/memories` 下，学习者本人鉴权，与既有一致）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/candidates` | `status` 默认 `pending`，分页 |
| POST | `/candidates/{id}/accept` | 写正式表并置 `accepted` |
| POST | `/candidates/{id}/reject` | 置 `rejected` |

**排序公式**（两路一致，只在 SQL 里算，不把候选集拉进内存）

```
结构化：score = kind_weight(kind) * POW(0.5, ageDays / 180) * (1 + MATCH(content) AGAINST (query))
全文  ：score = MATCH(content) AGAINST (query) * POW(0.5, ageDays / 180)
kind_weight：weak_topic 1.5 / entry_understanding 1.3 / goal 1.2 / preference 1.0
```

- 结构化那一路要 `(1 + MATCH)` 而不是纯 `MATCH`：没有关键词命中时（例如用户只说了"继续"）也要能按
  `kind_weight × 衰减` 排序，而不是退回"最近 8 条"。
- `query` 为空时去掉 `MATCH` 项，只用 `kind_weight × 衰减`。
- 单字或过短关键词在 ngram 上召回为零，沿用 `SearchQuery` 的判定退回 `LIKE`（既有行为，不重写）。

## 持久化与迁移

一个迁移文件 `V<14 位时间戳>__memory_relevance_candidates_and_ttl.sql`：

```sql
-- ① 召回排序要能算相关度，结构化那一路也要 ngram 全文索引（现在只有 text 那一路有）
ALTER TABLE ai_memories_structured
  ADD FULLTEXT KEY ft_ai_memory_structured (content) WITH PARSER ngram;

-- ② "最近一次被召回"——淘汰以它为准，只按 created_at 会误删长期有效的事实
ALTER TABLE ai_memories_structured
  ADD COLUMN last_used_at DATETIME(3) NULL,
  ADD KEY idx_ai_memory_structured_used (user_id, last_used_at);
ALTER TABLE ai_memories_text
  ADD COLUMN last_used_at DATETIME(3) NULL,
  ADD KEY idx_ai_memory_text_used (user_id, last_used_at);

-- ③ 候选：与正式记忆分表（保留期、查询、删除语义都不同）
CREATE TABLE ai_memory_candidates (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id          BIGINT UNSIGNED NOT NULL,
  store            VARCHAR(16)     NOT NULL,  -- structured | text
  kind             VARCHAR(32)     NULL,
  ref_id           BIGINT UNSIGNED NULL,
  content          VARCHAR(1024)   NULL,      -- 被敏感过滤的行**必须为 NULL**
  content_bytes    INT             NOT NULL DEFAULT 0,
  source           VARCHAR(16)     NOT NULL,  -- auto | manual
  conversation_id  BIGINT UNSIGNED NULL,
  ref_message_id   BIGINT UNSIGNED NULL,
  reason           VARCHAR(255)    NULL,
  status           VARCHAR(16)     NOT NULL,  -- pending | accepted | rejected | filtered | expired
  decision_reason  VARCHAR(64)     NULL,      -- sensitive | user | expired
  created_at       DATETIME(3)     NOT NULL,
  expires_at       DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_memory_candidates_pending (user_id, status, created_at),
  KEY idx_ai_memory_candidates_expire (status, expires_at)
) ENGINE = InnoDB;
```

`last_used_at` 可空且 `NULL` 表示"从未被召回"——存量行不需要回填，淘汰按
`COALESCE(last_used_at, created_at)` 判定。

## 失败处理与恢复

| 场景 | 行为 |
| --- | --- |
| `touch` 失败 | 只记日志，不影响已经发出的回答。下一次召回会再写一遍，不需要补偿 |
| 淘汰任务失败 | 抛给 `ScheduledTaskRecorder` 记录（与 `TelemetryRetentionPurger` 一致），下一小时重试 |
| 候选接受时正式表写入失败 | 事务回滚，候选仍是 `pending`；用户重试即可 |
| 模型返回非法 JSON | 整次整理放弃（既有语义），游标不推进，下一轮再来 |
| 模型返回空内容 | 视为故障抛异常，**不能**当成"没什么可记的"——这是已知的"思考 token 吃满 max_tokens 会静默空响应"坑 |
| 候选表写入失败 | 与整理失败同等处理：整次放弃，游标不推进 |
| 过滤器本身抛异常 | 按"命中"处理（宁可丢一条，不可写一条带密钥的记忆），并记 WARN |

## 安全与权限

- **被过滤的内容绝不落库**。候选表对被过滤的行只存 `content_bytes` 与 `decision_reason`；
  面板上显示的是"有 N 条内容包含手机号/口令/密钥，未记录"，**不是原文**。把原文存进候选表等于换个地方泄露。
- 候选的读写一律在 SQL 里带 `user_id`（模型给的 `id` 是不可信输入，与现有的删除/覆盖同一纪律）。
- 管理端**不提供**候选的全局浏览：管理员没有理由读别人的待确认记忆。
- `memory_remember`（外部 MCP 工具）走同一个过滤器与同一个总开关闸。
- 注销：`AiUserDeletionListener` 的 `USER_TABLES` 增一行 `{"ai_memory_candidates", "user_id"}`。

## 可观测性

- 日志（沿用 `ai.memory.*` 前缀）：`filtered`（类别 + 字节数，不含正文）、`proposed`、
  `candidate_accepted`、`candidate_rejected`、`purged`（按 kind 分行）、`candidates_dropped`。
- 计数指标：`paideia.ai.memory.purged`、`paideia.ai.memory.filtered`、`paideia.ai.memory.proposed`
  （Micrometer，与 `paideia.ai.runs` 同一命名风格）。
- 评测报告写 `backend/paideia-ai/target/memory-eval/report.md`（每次运行覆盖，不提交）。

## 实施顺序

1. **S1 金集与执行器**（先做，无代码改动）：三份 JSON + 两个测试类 + 用现状跑出基线。
2. **S2 过滤与排序**：`SensitiveContent` → 写入路径接入 → 结构化全文索引与两个召回 SQL → `touch`。
3. **S3 淘汰**：迁移 → `purgeExpired` → `MemoryRetentionPurger` → 级联清单。
4. **S4 半自动**：提示词 v2 → 解析新动作 → 候选表与 `MemoryCandidateStore` → 服务分流 → 端点 → 面板。
5. **S5 复测**：同金集重跑，出对比与不达标项清单。

S2 与 S3 可并行；S4 依赖 S1（要用金集判断"提议得对不对"）。

## 测试策略

| 层 | 覆盖 | 命令 |
| --- | --- | --- |
| 纯函数单测 | `SensitiveContent`（手机号/证件/密钥/连接串/正常文本不误伤）、`MemoryCandidateParser` 认 `PROPOSE` | `scripts/test-db.sh mvn -B -pl paideia-ai test -Dtest=SensitiveContentTest,MemoryCandidateParserTest` |
| 切片/集成 | 召回排序 SQL、`touch` 幂等、`purgeExpired` 分档与幂等、候选三个端点、注销级联、总开关四入口 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='Memory*Test'` |
| 真上游评测 | 提取金集 + 端到端问题（`@EnabledIfEnvironmentVariable(DEEPSEEK_API_KEY)`） | `scripts/test-db.sh mvn -B -pl paideia-ai,paideia-app -Dtest='MemoryEvalTest,MemoryRecallEvalTest' test` |
| 前端 | 面板"待确认"区接受/拒绝 | `playwright test e2e/assistant.spec.ts --grep "记忆"` |

**评测必须能被证实跑过**：无凭据时 surefire 的 `Skipped` 列会显示跳过数，报告文件也不会生成——
验收时看这两处，不看 `BUILD SUCCESS`。

## 需求追踪

| REQ | 落点 | 任务 |
| --- | --- | --- |
| REQ-001 | `SensitiveContent` + 两个写入入口 + `MemoryTools` | TASK-004 |
| REQ-002 / REQ-003 | `MemoryMapper.searchText` / 新增 `selectStructuredRelevant` | TASK-005 |
| REQ-004 | `MemoryStore.purgeExpired` + TTL 分档 | TASK-007 |
| REQ-005 | `MemoryStore.touch` + 召回调用点 | TASK-006 |
| REQ-006 | `PROPOSE` 动作 + 提示词 v2 + 分流 | TASK-008 / TASK-009 |
| REQ-007 | `ai_memory_candidates` + 三个终态 + 过期清理 | TASK-009 / TASK-010 |
| REQ-008 | 三个端点 + 面板 | TASK-010 / TASK-011 |
| REQ-009 / REQ-010 | 金集 + 两个评测类 | TASK-001..003 |
| REQ-011 | `AiUserDeletionListener` 增表 | TASK-010 |
| REQ-012 | 总开关的四处判定 | TASK-009 / TASK-010 |

## 待决定事项

无。Q-001（权重数值）在 S1 跑出基线后由 S2 定；Q-002..Q-004 已在需求里定案。
