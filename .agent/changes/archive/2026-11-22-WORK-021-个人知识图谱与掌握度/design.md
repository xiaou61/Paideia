---
artifact: design
work_id: WORK-021
work: 个人知识图谱与掌握度
status: approved
workflow: full
depends_on: [requirements, proposal]
related_to: [WORK-004, WORK-006, WORK-010, WORK-020]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 14:12:00 +0800
approver_role: CTO
---

# 个人知识图谱与掌握度设计

## 系统上下文

```text
条目页 /knowledge/entries/:id
   └─ 挂载时上报一次 ──→ POST /api/v1/knowledge/entries/{id}/view
                                │
                          knowledge_entry_views（新）

小测 / 动手题（已有渲染器）
   └─ progress.update() ──→ PUT /api/v1/learning/entries/{id}  （已存在）

自评（已有）
   └─ → knowledge_self_assessments（已存在）

           三路证据 ──┐
                      ├──→ ConceptMastery（纯函数）──→ 档位 + 分数
概念/条目关联（新）──┘                                       │
                                                             ▼
                            GET /api/v1/learning/graph ──→ /graph 页面（2D 默认 / 3D 可选）
                                                             │
管理端 /knowledge/concepts ──→ /api/v1/knowledge/admin/concepts** ──┘
                                                             │
助手工具 knowledge_weak_concepts ─────────────────────────────┘
```

## 组件与职责

| 组件 | 位置（planned） | 职责 |
| --- | --- | --- |
| `Concept` | `internal/concept/Concept.java` | 概念实体：id、name、summary、categoryId |
| `ConceptMapper` | `internal/concept/ConceptMapper.java` | 概念 CRUD、名称查重、按分类列 |
| `ConceptLinkMapper` | `internal/concept/ConceptLinkMapper.java` | 条目↔概念、概念↔概念两类关联的读写 |
| `ConceptMastery` | `internal/concept/ConceptMastery.java` | **纯函数**：证据 → 分数与档位。不接受 IO |
| `ConceptGraphService` | `internal/concept/ConceptGraphService.java` | 聚合三路证据，产出图谱视图；复用既有两个服务 |
| `EntryViewMapper` | `internal/entry/EntryViewMapper.java` | 浏览记录的 upsert 与按用户查询 |
| `ConceptAdminService` | `internal/admin/ConceptAdminService.java` | 管理端校验与写入（名称唯一、条目存在、边不自环） |
| `KnowledgeGraphPage` | `frontend/apps/app/src/pages/KnowledgeGraphPage.tsx` | 页面壳、数据加载、视角切换、点选面板 |
| `GraphCanvas2D` | `frontend/apps/app/src/components/graph/GraphCanvas2D.tsx` | sigma 渲染，动态 import |
| `GraphCanvas3D` | `frontend/apps/app/src/components/graph/GraphCanvas3D.tsx` | 3d-force-graph 渲染，动态 import |
| `graph-layout.ts` | `frontend/apps/app/src/components/graph/graph-layout.ts` | 坐标计算与本地持久化 |
| `KnowledgeConceptsPage` | `frontend/apps/admin/src/pages/KnowledgeConceptsPage.tsx` | 管理端概念维护 |

## 请求或事件流程

### 记录一次浏览

1. `KnowledgeEntryPage` 挂载并拿到 `entryId` 后调用一次 `POST /api/v1/knowledge/entries/{id}/view`。
2. 控制器取 `CurrentUserId.require()`；服务层用 `EntryMapper.findPublishedById` 校验，未发布 → `NOT_FOUND`（不落行）。
3. Mapper 执行 upsert：`INSERT ... ON DUPLICATE KEY UPDATE view_count = view_count + 1, last_viewed_at = #{now}`。
4. 同一页面重复渲染不重复调用：前端用 `useRef` 记录已上报的 `entryId`，刷新页面才计一次新浏览。

### 计算掌握度并出图

1. `GET /api/v1/learning/graph` 取 `userId`。
2. 一次读取概念全表（数百行）、条目↔概念关联、概念边。
3. 复用地读取本人证据：
   - `LearningProgressService.listMine(userId, 200)` → `entryId → View`（含两类块分数）；
   - `SelfAssessmentService.listMine(userId, null)` → `entryId → level`；
   - `EntryViewMapper.findByUser(userId)` → `entryId → (count, lastViewedAt)`。
4. 对每个概念，把挂在它下面的条目作为证据来源，交给 `ConceptMastery` 算分数与档位。
5. 按「有证据优先、分数降序、id 升序」排序；超过 500 个时截断并置 `truncated = true`。
6. 组装节点、边、统计返回。

## 接口与数据

### 掌握度纯函数

```java
public final class ConceptMastery {

    /** 一路证据：某个载体条目上已知的东西。缺失即 null。 */
    public record Evidence(Double scoreRatio, String selfLevel, boolean viewed, Instant lastActivity) {}

    public record Result(double score, String tier, boolean reviewDue) {}

    public static Result compute(List<Evidence> evidences, boolean reviewDue) { ... }
}
```

**分数**（0–1，用于着色与排序）：

```text
每个载体条目：
  可用信号与权重  scoreRatio 0.5 / selfLevel 0.3 / viewed 0.2
  该条目的值 = 可用信号按权重重新归一后的加权平均
    （例：只有分数 → 就是分数本身；分数 + 自评 → 0.625·分 + 0.375·自评）
概念分数 = 有任一信号的条目的值的平均
再乘衰减：0.5 ^ (距最近活动天数 / 30)，下限 0.5

短评：衰减下限 0.5 是为了不让"两个月没碰"直接把掌握标签清零，
      它是取舍不是精确模型；引入 BKT 时替换整个方法。
```

**档位**（决策规则，与分数解耦，便于单测）：

```text
无任何证据                         → unknown   （未接触）
有分数且 scoreRatio >= 0.8 且 自评懂 → mastered （掌握）
自评懂 或 (有分数且 scoreRatio >= 0.8) → familiar （熟悉）
其余有证据                          → learning  （了解）
```

`reviewDue` 由调用方判定（任一载体条目的 `review_due_at <= now`），不由纯函数推导。

这样 AC-005 的四条断言各自对应一条规则，不需要构造分数区间去凑。

### 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/knowledge/entries/{id}/view` | 记录一次浏览，幂等累加；未发布 404 |
| GET | `/api/v1/learning/graph` | 本人的图谱：节点 + 边 + 统计 |
| GET | `/api/v1/knowledge/admin/concepts` | 概念列表（含关联条目数、关系数） |
| POST | `/api/v1/knowledge/admin/concepts` | 新建 |
| PUT | `/api/v1/knowledge/admin/concepts/{id}` | 修改 |
| DELETE | `/api/v1/knowledge/admin/concepts/{id}` | 删除（级联关联与边） |
| POST | `/api/v1/knowledge/admin/concepts/{id}/entries` | 关联条目 `{"entryId":1}` |
| DELETE | `/api/v1/knowledge/admin/concepts/{id}/entries/{entryId}` | 解除关联 |
| POST | `/api/v1/knowledge/admin/concept-edges` | 建关系 `{"fromConceptId":1,"toConceptId":2,"relationType":"prerequisite"}` |
| DELETE | `/api/v1/knowledge/admin/concept-edges/{id}` | 删除关系 |

图谱响应：

```json
{
  "concepts": [
    {
      "id": 3, "name": "哈希表", "summary": "用散列把键映射到桶……",
      "categoryId": 2, "categoryName": "数据结构",
      "score": 0.72, "tier": "familiar", "reviewDue": true,
      "evidence": { "viewed": 2, "assessed": "understood", "scoredEntries": 1 },
      "entries": [ { "entryId": 12, "title": "字典与集合", "scoreRatio": 0.66 } ]
    }
  ],
  "edges": [ { "from": 3, "to": 7, "type": "prerequisite" } ],
  "stats": { "concepts": 12, "touched": 5, "mastered": 2, "dueForReview": 1, "truncated": false }
}
```

`entries` 每个节点最多 8 条；`scoreRatio` 为 null 表示该条目没有分数记录。

### 助手工具

`knowledge_weak_concepts`（READ 作用域，`readOnly = true`）：返回本人掌握度最低的 5 个概念与全部待复习概念，每条形如「哈希表（了解，载体：字典与集合）」。直接复用 `ConceptGraphService`，不新增查询。

## 持久化与迁移

迁移文件名取执行时的下一个编号（当前下一个是 **V19**，不要写死）。

```sql
CREATE TABLE knowledge_concepts (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name        VARCHAR(64)     NOT NULL,
  summary     VARCHAR(500)    NULL,
  category_id BIGINT UNSIGNED NULL,
  created_at  DATETIME(3)     NOT NULL,
  updated_at  DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_concepts_name (name),
  KEY idx_knowledge_concepts_category (category_id)
) ENGINE = InnoDB;

CREATE TABLE knowledge_entry_concepts (
  entry_id   BIGINT UNSIGNED NOT NULL,
  concept_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3)     NOT NULL,
  PRIMARY KEY (entry_id, concept_id),
  KEY idx_knowledge_entry_concepts_concept (concept_id),
  CONSTRAINT fk_kec_entry FOREIGN KEY (entry_id)
    REFERENCES knowledge_entries (id) ON DELETE CASCADE,
  CONSTRAINT fk_kec_concept FOREIGN KEY (concept_id)
    REFERENCES knowledge_concepts (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE knowledge_concept_edges (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  from_concept_id BIGINT UNSIGNED NOT NULL,
  to_concept_id   BIGINT UNSIGNED NOT NULL,
  relation_type   VARCHAR(32)     NOT NULL,
  created_at      DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_concept_edges (from_concept_id, to_concept_id, relation_type),
  KEY idx_knowledge_concept_edges_to (to_concept_id),
  CONSTRAINT fk_kce_from FOREIGN KEY (from_concept_id)
    REFERENCES knowledge_concepts (id) ON DELETE CASCADE,
  CONSTRAINT fk_kce_to FOREIGN KEY (to_concept_id)
    REFERENCES knowledge_concepts (id) ON DELETE CASCADE,
  CONSTRAINT ck_kce_not_self CHECK (from_concept_id <> to_concept_id),
  CONSTRAINT ck_kce_type CHECK (relation_type IN ('prerequisite', 'related'))
) ENGINE = InnoDB;

CREATE TABLE knowledge_entry_views (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL,
  entry_id        BIGINT UNSIGNED NOT NULL,
  view_count      INT             NOT NULL DEFAULT 1,
  first_viewed_at DATETIME(3)     NOT NULL,
  last_viewed_at  DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_entry_views (user_id, entry_id),
  KEY idx_knowledge_entry_views_entry (entry_id),
  KEY idx_knowledge_entry_views_user_recent (user_id, last_viewed_at),
  CONSTRAINT fk_kev_entry FOREIGN KEY (entry_id)
    REFERENCES knowledge_entries (id) ON DELETE CASCADE
) ENGINE = InnoDB;
```

要点：

- `knowledge_concepts.category_id` **不建外键**：概念可以不属于任何分类，且分类删除不应带走概念（与"概念是公共内容"一致）。
- 三张关联/边表都带 **ON DELETE CASCADE**，删概念或删条目不留悬空行。
- `knowledge_entry_views` 与 `knowledge_self_assessments` 一致：内只需 `on duplicate key update`，外键只指条目，**用户删除靠注销监听器**。
- 所有时间列由应用传值，不用 `DEFAULT CURRENT_TIMESTAMP`（项目纪律，避免会话时区问题）。

## 失败处理与恢复

| 情形 | 行为 |
| --- | --- |
| 浏览未发布/不存在条目 | 404 `NOT_FOUND`，不写行（不泄露草稿存在） |
| 概念重名 | 400 `INVALID_ARGUMENT`（名称全局唯一） |
| 关联不存在的条目/概念 | 404 |
| 边自环（from == to） | 400 |
| 边重复 | 幂等：唯一键冲突按成功处理 |
| 图谱查询时某概念无载体条目 | 该节点仍然返回，档位 `unknown`，`entries` 为空——概念是管理员建立的，不该因为没挂内容就消失 |
| `learning_progress` 的 JSON 解析失败 | 复用既有 `parse` 的"坏数据当空"策略，不影响整页 |
| 超过 500 个概念 | 截断并标 `truncated`，统计按截断后的集合算 |

## 安全与权限

- 学习者端点：`@ApiAccess(ApiAccess.Access.AUTHENTICATED)`，`userId` 只来自 `CurrentUserId.require()`；请求体与路径不出现用户标识。
- 管理端点：沿用既有 `KnowledgeAdminController` 的 `@ApiAccess(ApiAccess.Access.ADMIN)`（项目纪律：后端无全局角色规则，写接口必须自己声明）。
- 图谱响应**只含本人证据**：他人的分数、自评、浏览不会进入任何字段。
- 概念与关系是公共内容，任何已登录用户都能读；只有管理员能写。
- 管理端写入的文本长度受限：名称 ≤ 64、说明 ≤ 500，与列宽一致。

## 可观测性

- 图谱查询打一条 `concept.graph.built userId=… nodes=… edges=… truncated=…`（INFO，不含概念内容）。
- 截断时打 WARN，便于发现"有人建了太多概念"。
- 浏览写入失败不重试、不报错给用户：这是旁路数据，不该让阅读页失败。

## 实施顺序

1. 迁移 + 实体 + mapper（概念、关联、边、浏览）；
2. `ConceptMastery` 纯函数 + 单测（先红后绿）；
3. 聚合服务 + 图谱端点 + 浏览端点 + 集成测试；
4. 注销级联 + 级联测试；
5. 管理端概念端点 + 管理端页面；
6. 2D 图谱页 + 坐标持久化 + e2e；
7. 3D 切换 + 按需加载验证；
8. 助手工具 + 提示词补充。

## 测试策略

| 层 | 覆盖 |
| --- | --- |
| 单测 | `ConceptMasteryTest`：四档规则、衰减、多载体平均、空证据、分数与自评冲突 |
| 集成 | `LearningGraphIntegrationTest`：图谱端点、证据聚合、用户隔离、截断、浏览 upsert、草稿 404、注销级联 |
| 管理端集成 | 概念 CRUD、重名、自环、级联删除 |
| 前端单测 | `graph-layout.test.ts`：指纹变化重算、坐标复用 |
| e2e | `graph.spec.ts`：空状态、节点渲染、点选面板、跳转条目；3D 不进入默认路径 |

测试数据不得依赖本地种子的具体条目内容（项目既有约定），一律用自建条目 id。

## 需求追踪

| 设计元素 | 需求 |
| --- | --- |
| `knowledge_concepts` / `knowledge_entry_concepts` | REQ-001 |
| `knowledge_concept_edges` | REQ-002 |
| `knowledge_entry_views` + 浏览端点 | REQ-003 |
| `ConceptMastery` | REQ-004、REQ-005、REQ-006 |
| `GET /learning/graph` | REQ-007 |
| `KnowledgeGraphPage` + `GraphCanvas2D` | REQ-008、REQ-010 |
| `GraphCanvas3D`（动态 import） | REQ-009 |
| 管理端概念页 | REQ-011 |
| `knowledge_weak_concepts` | REQ-012 |
| 注销监听器表清单 | REQ-013 |
| 空状态分支 | REQ-014 |

## 待决定事项

无。前端依赖版本在实现时取当时最新的 MIT 版本并在 task 里记录。
