---
artifact: design
work_id: WORK-033
status: approved
created: 2026-10-22
updated: 2026-10-22
approved_by: xiaou61
approved_at: 2026-10-22 16:30:26 +0800
approver_role: CTO
---

# 题目知识点关联与掌握诊断设计

## 数据模型

迁移文件：`V20261022170000__create_question_attempts_and_mastery.sql`（14 位时间戳，只建结构）。

```sql
question_attempts(
  id bigint unsigned auto_increment primary key,
  user_id bigint unsigned not null,
  entry_id bigint unsigned not null,
  block_key varchar(64) not null,
  question_key varchar(128) not null,
  fingerprint char(64) not null,
  choice_index int null,
  correct tinyint(1) not null,
  latency_ms int null,
  judged_by varchar(16) not null,          -- server | client
  created_at datetime(3) not null,
  key idx_attempt_user_time (user_id, created_at),
  key idx_attempt_fingerprint (fingerprint)
)

question_concepts(
  id bigint unsigned auto_increment primary key,
  entry_id bigint unsigned not null,
  block_key varchar(64) not null,
  question_key varchar(128) not null,
  concept_id bigint unsigned not null,
  weight decimal(3,2) not null default 1.00,
  source varchar(16) not null,             -- author | ai | inferred
  confidence decimal(3,2) null,
  created_at datetime(3) not null,
  unique key uk_question_concept (entry_id, block_key, question_key, concept_id),
  key idx_question_concept_concept (concept_id)
)

concept_mastery_state(
  user_id bigint unsigned not null,
  concept_id bigint unsigned not null,
  mastery decimal(5,4) not null,
  attempts int not null,
  correct int not null,
  updated_at datetime(3) not null,
  primary key (user_id, concept_id)
)

question_difficulty(
  fingerprint char(64) primary key,
  entry_id bigint unsigned not null,
  block_key varchar(64) not null,
  question_key varchar(128) not null,
  rating decimal(6,2) not null,
  attempts int not null,
  correct int not null,
  updated_at datetime(3) not null
)
```

**不建题目表**：题干、选项、答案仍是条目正文（唯一权威）。四张表只存「身份 + 证据 + 状态」。

## 题目身份与判定

- `blockKey`：围栏块 JSON 的 `id`；缺 id 的块不参与（现有 schema 允许缺省，不允许编造）。
- `questionKey = blockKey + "#" + questionIndex`。
- `fingerprint = sha256(entryId + "|" + blockKey + "|" + index + "|" + stem + "|" + options.join("|") + "|" + answerIndex)`，十六进制小写。
- 服务端从条目正文重新解析题目并判定，**不信任客户端传来的 correct**；`judged_by=server`。找不到题目或题型不支持时返回 `400 INVALID_ARGUMENT`，不落库。
- 内容变更后指纹变化：旧难度的 attempts 不参与新指纹，旧作答记录保留。

## 算法

### BKT（纯函数 `MasteryModel`）

```text
update(prior, correct, guess, learn, slip):
  afterLearn = prior + (1 - prior) * learn
  if correct: posterior = afterLearn*(1-slip) / (afterLearn*(1-slip) + (1-afterLearn)*guess)
  else:       posterior = afterLearn*slip / (afterLearn*slip + (1-afterLearn)*(1-guess))
  夹到 [0.001, 0.999]
```

参数：`prior=0.3, learn=0.15, slip=0.10, guess=1/options.size()`（选项数 2–6 时 0.5–0.1667；越界按 0.25）。
一题多概念时，按关联权重 `w` 把一次作答折算成 `w` 份证据：更新与计数均按权重乘；一期只支持权重 1。0，多概念时按顺序各更新一次（记录在 design 的已知简化里）。
档位：`attempts=0 → unknown`；`mastery>=0.80 → mastered`；`>=0.60 → familiar`；其余 `learning`。

### Elo（纯函数 `ItemRating`）

```text
ability = 1200 + (mastery - 0.5) * 400
expected = 1 / (1 + 10^((rating - ability)/400))
rating' = rating - K * (actual - expected)      // K=24
```

实际答对 `actual=1`：题目比能力低则 rating 下调。新题 rating 初始 1200，`attempts<5` 标 `uncalibrated=true`。

### 前置诊断（纯函数 `PrerequisiteDiagnosis`）

输入：失败概念 id、`Map<conceptId, mastery>`、边列表（`from` 是前置、`to` 是后继）、最大深度 3。
从失败概念沿**反向边**（`to == current`）走，权重每层 ×0.5；跳过 mastery ≥ 0.7；收集「自身 < 0.7 且所有直接前置 ≥ 0.7」的节点；按权重降序，最多 3 条，每条带路径 id 列表。

### 概念候选（`QuestionConceptSuggester`）

1. 文本：题干 + 选项做中文 bigram 集合；
2. 概念文本：`name + summary` 同样切 bigram；
3. 分数 = `2*|交集| / (|题目| + |概念|)`（Dice 系数）；
4. 继承加分：题目所在条目的概念 +0.2；概念图一层邻居 +0.1；
5. 取 top-5，分数 < 0.05 丢弃；输出命中词（最多 5 个）作为理由。

**只返回候选，不写库**；写库必须经管理端确认接口。

## 接口

```java
// 记录一次作答（学习者）
POST /api/v1/learning/attempts
{ "entryId": 123, "blockKey": "py-09-quiz", "questionKey": "py-09-quiz#0",
  "choice": 1, "latencyMs": 4200 }
→ { "correct": true, "fingerprint": "...", "concepts": [{ "conceptId": 3, "mastery": 0.52, "tier": "learning" }],
    "suggestions": [{ "conceptId": 8, "name": "B+ 树", "weight": 0.5, "path": [3, 8] }] }

// 我的掌握状态（读，给诊断卡）
GET /api/v1/learning/mastery → [{ "conceptId": 3, "mastery": 0.52, "tier": "learning", "attempts": 4 }]

// 管理端：题目清单与标注
GET /api/v1/learning/admin/questions?entryId=123
PUT /api/v1/learning/admin/question-concepts
{ "entryId": 123, "blockKey": "py-09-quiz", "questionKey": "py-09-quiz#0",
  "concepts": [{ "conceptId": 3, "weight": 1.0 }] }
GET /api/v1/learning/admin/question-concepts/suggestions?entryId=123&blockKey=...&questionKey=...
```

安全：两个学习者端点 `AUTHENTICATED`；三个管理端点 `ADMIN` 且路径按项目约定放在「模块/admin」下（`/api/v1/learning/admin/**` 规则必须排在 `/api/v1/learning/**` 之前）。

## 与既有模块的接线

- `ConceptGraphService.build(userId)`：若 `concept_mastery_state` 有该用户该概念的行，用 BKT 的 mastery/tier；否则回退现有 `ConceptMastery`。接口 `Node.score/tier` 不变，前端零改动。
- `learning_progress` 继续按块记分（关卡通过口径不变）；逐题记录是**追加**，不替代块分。
- `AccountDeletionService` 的删除表清单追加 `question_attempts`、`concept_mastery_state`（按 user_id）。
- 复习调度不动：诊断只返回建议，复习仍按条目。

## 失败与边界

| 情况 | 行为 |
| --- | --- |
| 块 id 缺失 / 题号越界 / 选项越界 | 400，不落库 |
| 同一题重复作答 | 全部保留，各自更新 BKT 与 Elo |
| 题目无概念关联 | 作答照常记录，返回空 concepts 与空 suggestions |
| 概念图为空 | 诊断返回空列表，不报错 |
| 内容已改、指纹不再匹配 | 旧难度行保留；新指纹从 1200 起步 |
| 并发作答 | 掌握状态用单条 upsert（`ON DUPLICATE KEY UPDATE`），以最后一次写入为准；BKT 非严格串行，文档记录该简化 |

## 已知简化（写在代码注释里）

- `shortcut: BKT 状态按最后写入覆盖，不做逐作答重放，出现跨标签页并发作答时以最后一条为准；需要严格序列时升级为按 attempts 重放。`
- `shortcut: 一题多概念时按权重顺序各更新一次，不做联合概率；概念数增长到每题 4 个以上时改为按证据分摊。`
