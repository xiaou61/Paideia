---
artifact: tasks
work_id: WORK-021
work: 个人知识图谱与掌握度
status: approved
workflow: full
depends_on: [requirements, proposal, design]
related_to: [WORK-004, WORK-006, WORK-010, WORK-020]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 14:12:00 +0800
approver_role: CTO
---

# 个人知识图谱与掌握度实施任务

## 依据

- `requirements.md`（REQ-001～REQ-014、AC-001～AC-016）
- `proposal.md`（概念独立成表、边不推导、掌握度不落库、浏览单表、2D 主入口）
- `design.md`（四张表结构、掌握度公式与档位规则、十个端点、组件划分）

## 全局约束

- 迁移编号取执行时的下一个（当前 V19），**不写死**；新增迁移后按 `MigrationChecksumTest` 的提示用 `-Dmigration.checksums.update=true` 登记校验和。
- 时间全部由应用传 `Instant`，不写 `DEFAULT CURRENT_TIMESTAMP`；SQL 只写 mapper。
- 学习者端点 `@ApiAccess(ApiAccess.Access.AUTHENTICATED)`；管理端点 `@ApiAccess(ApiAccess.Access.ADMIN)`。
- 前端新增依赖只允许 MIT；图库必须 `import()` 动态加载。
- 测试不依赖本地种子条目的具体标题。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 同一用户同一篇重复浏览产生两行 | TASK-003 | `repeatedViewIncrementsSameRow` |
| 对草稿条目上报浏览成功（泄露草稿存在） | TASK-003 | `viewingDraftIsNotFound` |
| 一个概念挂两个条目时只取一条的分数 | TASK-002 | `scoreAveragesAcrossCarrierEntries` |
| 自评"懂"但分数为 0 时被判成「掌握」 | TASK-002 | `conflictingEvidenceNeverReachesMastered` |
| 复习到期把掌握度直接归零 | TASK-002 | `reviewDueDoesNotZeroTheScore` |
| 空证据算出 NaN 或 0.5 | TASK-002 | `noEvidenceIsZeroAndUnknown` |
| 用户 A 的图谱里出现用户 B 的自评/分数 | TASK-004 | `otherUsersEvidenceIsInvisible` |
| 概念数超过 500 时无界返回 | TASK-004 | `truncationKeepsAllEvidencedConcepts` |
| 概念未挂任何条目时从图中消失 | TASK-004 | `conceptWithoutEntriesStillListed` |
| 注销后浏览记录残留 | TASK-005 | `deletionRemovesEntryViews` |
| 概念重名被写入两行 | TASK-006 | `duplicateConceptNameRejected` |
| 概念边自环被写入 | TASK-006 | `selfEdgeRejected` |
| 删除概念后关联与边成为悬空行 | TASK-006 | `deletingConceptCascadesLinksAndEdges` |
| 图谱节点位置每次进入都重算 | TASK-008 | `layoutReusesStoredPositions` |
| 图结构变化后仍复用旧坐标（节点跑到一起） | TASK-008 | `changedFingerprintRecomputesLayout` |
| 进入 2D 页面就把 3D 依赖打进首屏 | TASK-009 | 构建产物检查：`three` 不在 `/graph` 的静态 chunk |
| 助手返回他人薄弱概念 | TASK-010 | `weakConceptsAreScopedToCaller` |

## 任务

### TASK-001 | done | 四张表迁移与数据访问

- 对应：`REQ-001`、`REQ-002`、`REQ-003`
- 依赖：无
- 新建/修改：
  - `backend/paideia-app/src/main/resources/db/migration/V19__create_concept_and_view_tables.sql`（新建）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/concept/Concept.java`（新建）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/concept/ConceptMapper.java`（新建）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/concept/ConceptLinkMapper.java`（新建）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/entry/EntryViewMapper.java`（新建）
- 接口：
  - `ConceptMapper.findAll(): List<Concept>`
  - `ConceptMapper.findById(Long id): Concept`
  - `ConceptMapper.insert(Concept concept): int`（`useGeneratedKeys` 回写 id）
  - `ConceptMapper.update(Concept concept): int`
  - `ConceptMapper.delete(Long id): int`
  - `ConceptMapper.countByName(String name, Long excludeId): long`
  - `ConceptLinkMapper.findAllEntryLinks(): List<EntryConceptLink>`（含 `entryId`、`conceptId`）
  - `ConceptLinkMapper.findAllEdges(): List<ConceptEdge>`（含 `id`、`fromConceptId`、`toConceptId`、`relationType`）
  - `ConceptLinkMapper.linkEntry(Long entryId, Long conceptId, Instant now): int`
  - `ConceptLinkMapper.unlinkEntry(Long entryId, Long conceptId): int`
  - `ConceptLinkMapper.insertEdge(Long from, Long to, String type, Instant now): int`
  - `ConceptLinkMapper.deleteEdge(Long id): int`
  - `EntryViewMapper.upsert(Long userId, Long entryId, Instant now): int`
  - `EntryViewMapper.findByUser(Long userId): List<EntryViewRow>`（含 `entryId`、`viewCount`、`lastViewedAt`）
- 测试：`MigrationChecksumTest` 通过（新迁移已登记）
- 步骤：
  1. 写迁移文件，四张表与外键/唯一键与 `design.md` 逐字一致；
  2. 起一次测试库让 Flyway 跑到 V19，`SHOW CREATE TABLE` 核对外键与 CHECK；
  3. 写四个实体与三个 mapper，方法签名与上面接口块一致；
  4. 跑 `mvn -pl backend/paideia-app -am test -Dtest=MigrationChecksumTest` 并按提示登记校验和。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=MigrationChecksumTest` → 1 passed

### TASK-002 | done | 掌握度纯函数

- 对应：`REQ-004`、`REQ-005`、`REQ-006`、`AC-005`、`AC-006`、`AC-007`
- 依赖：无
- 新建：`backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/concept/ConceptMastery.java`
- 测试：`backend/paideia-knowledge/src/test/java/io/github/xiaou61/knowledge/internal/concept/ConceptMasteryTest.java`
- 接口：
  - `ConceptMastery.Evidence(Double scoreRatio, String selfLevel, boolean viewed, Instant lastActivity)`
  - `ConceptMastery.Result(double score, String tier, boolean reviewDue)`
  - `ConceptMastery.compute(List<Evidence> evidences, boolean reviewDue): Result`
  - 档位常量：`"unknown" | "learning" | "familiar" | "mastered"`
- 步骤：
  1. 按设计写出四档规则与加权平均，先写测试跑红；
  2. 实现 `compute`，跑绿；
  3. 补衰减测试：同一份证据，`lastActivity` 距今 0 天与 60 天的分数不同，且 60 天不低于 0.5；
  4. 补 `noEvidenceIsZeroAndUnknown`：空列表返回 `0.0 / unknown / false`；
  5. 补 `conflictingEvidenceNeverReachesMastered`：自评 `understood` + `scoreRatio = 0.0` → `familiar`。
- 验证：`mvn -pl backend/paideia-knowledge test -Dtest=ConceptMasteryTest` → 8 passed

### TASK-003 | done | 浏览上报端点与条目页接线

- 对应：`REQ-003`、`AC-003`、`AC-004`、`AC-013`（后半段在 TASK-005）
- 依赖：TASK-001
- 新建/修改：
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/entry/EntryViewService.java`（新建）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/web/KnowledgeController.java`（修改：新增端点）
  - `frontend/apps/app/src/pages/KnowledgeEntryPage.tsx`（修改：挂载上报一次）
- 接口：
  - `EntryViewService.record(long userId, long entryId): void`
  - `POST /api/v1/knowledge/entries/{id}/view` → `ApiResponse<Void>`
- 步骤：
  1. 写 `EntryViewService.record`：`findPublishedById` 不存在则抛 `NOT_FOUND`，否则 `EntryViewMapper.upsert(userId, entryId, Instant.now())`；
  2. 在 `KnowledgeController` 加端点，`@ApiAccess` 保持与既有学习者端点一致；
  3. 条目页用 `useRef<number | null>` 记录已上报的 id，`useEffect` 依赖 `entryId`，重复渲染不再上报；
  4. 写集成测试：一次上报一行；两次上报一行且 `view_count = 2`；草稿 404 且零行。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=EntryViewIntegrationTest` → 3 passed

### TASK-004 | done | 图谱聚合服务与端点

- 对应：`REQ-004`、`REQ-005`、`REQ-007`、`AC-001`、`AC-002`、`AC-005`、`AC-006`、`AC-007`、`AC-008`、`AC-009`
- 依赖：TASK-001、TASK-002
- 新建/修改：
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/concept/ConceptGraphService.java`（新建）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/web/LearningController.java`（修改：新增 `/graph`）
- 接口：
  - `ConceptGraphService.build(long userId): ConceptGraphService.Graph`
  - `Graph(List<Node> concepts, List<Edge> edges, Stats stats)`
  - `Node(long id, String name, String summary, Long categoryId, String categoryName, double score, String tier, boolean reviewDue, Evidence evidence, List<Carrier> entries)`
  - `Evidence(int viewed, String assessed, int scoredEntries)`
  - `Carrier(long entryId, String title, Double scoreRatio)`
  - `Edge(long from, long to, String type)`
  - `Stats(int concepts, int touched, int mastered, int dueForReview, boolean truncated)`
  - 常量：`MAX_NODES = 500`、`MAX_CARRIERS = 8`
- 步骤：
  1. 一次取概念、条目关联、边；再复用 `LearningProgressService.listMine(userId, 200)` 与 `SelfAssessmentService.listMine(userId, null)`、`EntryViewMapper.findByUser`；
  2. 按概念聚合载体条目，为每个条目构造 `Evidence`，调 `ConceptMastery.compute`；
  3. 排序：有证据优先 → 分数降序 → id 升序；截断到 500 并置标志；统计按截断后集合算；
  4. 端点挂在 `LearningController` 的 `/graph`，归属取自令牌；
  5. 写集成测试覆盖：两用户隔离、截断保留有证据者、无载体的概念仍出现、无概念返回空。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=LearningGraphIntegrationTest` → 6 passed

### TASK-005 | done | 注销级联

- 对应：`REQ-013`、`AC-013`
- 依赖：TASK-001
- 修改：`backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/user/KnowledgeUserDeletionListener.java`
- 步骤：
  1. `USER_TABLES` 增加 `{"knowledge_entry_views", "user_id"}`；
  2. 在 `AccountDeletionCascadeIntegrationTest` 增加断言：注销后该用户浏览行为 0 行；
  3. 反向验证：注释掉新增那一行，测试必须变红。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=AccountDeletionCascadeIntegrationTest` → 全绿

### TASK-006 | done | 管理端概念维护端点

- 对应：`REQ-001`、`REQ-002`、`REQ-011`、`AC-001`、`AC-012`
- 依赖：TASK-001
- 新建/修改：
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/admin/ConceptAdminService.java`（新建）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/web/KnowledgeAdminController.java`（修改：新增五个端点组）
- 接口（与 `design.md` 端点表逐字一致）：
  - `ConceptAdminService.create(Long categoryId, String name, String summary): Long`
  - `ConceptAdminService.update(Long id, Long categoryId, String name, String summary): void`
  - `ConceptAdminService.delete(Long id): void`
  - `ConceptAdminService.linkEntry(Long conceptId, Long entryId): void`
  - `ConceptAdminService.unlinkEntry(Long conceptId, Long entryId): void`
  - `ConceptAdminService.createEdge(Long fromConceptId, Long toConceptId, String relationType): Long`
  - `ConceptAdminService.deleteEdge(Long id): void`
  - `ConceptAdminService.list(): List<ConceptAdminService.ConceptSummary>`
- 步骤：
  1. 校验名称唯一（`countByName` 带 `excludeId`）、长度 ≤ 64、说明 ≤ 500；
  2. 校验边：from ≠ to、type ∈ {prerequisite, related}、两端概念存在；
  3. 写集成测试：重名 400、自环 400、删概念后关联与边归零、解除关联幂等；
  4. 端点全部 `@ApiAccess(ApiAccess.Access.ADMIN)`。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=ConceptAdminIntegrationTest` → 6 passed

### TASK-007 | done | 管理端概念页

- 对应：`REQ-011`、`AC-001`、`AC-012`
- 依赖：TASK-006
- 新建/修改：
  - `frontend/apps/admin/src/pages/KnowledgeConceptsPage.tsx`（新建）
  - `frontend/apps/admin/src/router.tsx`（修改：`/knowledge/concepts`）
  - 侧栏菜单（修改：概念入口）
- 步骤：
  1. 列表页：名称、分类、载体条目数、关系数、操作；沿用既有 `Table` 与分页；
  2. 新建/编辑用弹窗表单（名称、说明、分类下拉，分类选项来自既有分类接口）；
  3. 行内「关联条目」打开弹窗，可选择多个条目并保存；
  4. 行内删除走既有二次确认模式；
  5. 关系维护：概念详情里的一个小子表（前置/关联），可增删。
- 验证：`pnpm --filter @paideia/admin typecheck` 通过；手工在本地管理端建一条概念并关联两个条目后，`GET /api/v1/learning/graph` 能看到该节点

### TASK-008 | done | 2D 图谱页与坐标持久化

- 对应：`REQ-008`、`REQ-010`、`AC-010`、`AC-015`、`AC-016`
- 依赖：TASK-004
- 新建/修改：
  - `frontend/apps/app/src/pages/KnowledgeGraphPage.tsx`（新建）
  - `frontend/apps/app/src/components/graph/GraphCanvas2D.tsx`（新建）
  - `frontend/apps/app/src/components/graph/graph-layout.ts`（新建）
  - `frontend/apps/app/src/components/graph/ConceptDetailPanel.tsx`（新建）
  - `frontend/apps/app/src/router.tsx`（修改：`/graph`）
  - `frontend/apps/app/package.json`（修改：新增 `sigma`、`graphology`、`graphology-layout-forceatlas2`，均为 MIT）
- 接口：
  - `graph-layout.ts`：`layoutKey(concepts: {id:number}[], edges: {from:number;to:number}[]): string`、`loadPositions(key: string): Record<number, {x:number;y:number}> | null`、`savePositions(key: string, positions: Record<number, {x:number;y:number}>): void`、`resetPositions(key: string): void`
  - `GraphCanvas2D` props：`{ nodes: GraphNode[]; edges: GraphEdge[]; onSelect(id: number): void; selectedId: number | null }`
- 步骤：
  1. 写 `graph-layout.ts` 与单测（指纹变则重算、指纹同则复用）；
  2. `GraphCanvas2D` 用 `await import('sigma')` 与 `await import('graphology')`，按档位着色；无 WebGL 时返回提示组件（`AC-016`）；
  3. 页面加载 `/api/v1/learning/graph`；空数据走空状态文案（`AC-015`）；
  4. 点选节点 → 右侧面板显示说明与载体条目，条目可点击跳 `/knowledge/entries/:id`；
  5. 「重新布局」按钮清本地坐标后重算；
  6. 写 e2e：空状态、有节点时渲染 canvas、点选后面板出现条目链接。
- 验证：`pnpm --filter @paideia/app test:e2e -- graph.spec.ts` → 3 passed

### TASK-009 | done | 3D 切换与按需加载

- 对应：`REQ-009`、`AC-011`
- 依赖：TASK-008
- 新建/修改：
  - `frontend/apps/app/src/components/graph/GraphCanvas3D.tsx`（新建）
  - `frontend/apps/app/src/pages/KnowledgeGraphPage.tsx`（修改：视角切换）
  - `frontend/apps/app/package.json`（修改：新增 `3d-force-graph`，MIT）
- 步骤：
  1. 视角切换默认 2D，切到 3D 时才 `await import('3d-force-graph')`；
  2. 3D 复用同一份坐标（x/y 直接用，z 取 0，或首次给随机 z 后持久化）；
  3. 构建后检查产物：`three` 只出现在独立的动态 chunk，不在 `/graph` 的静态依赖里；
  4. 切回 2D 时卸载 3D 画布，不保留第二个 canvas。
- 验证：`pnpm --filter @paideia/app build` 后检查 `dist/assets` 中 `three` 所在 chunk 未被 `index` 静态引用；e2e 走一次切换不报错

### TASK-010 | done | 助手工具与提示词

- 对应：`REQ-012`、`AC-014`
- 依赖：TASK-004
- 新建/修改：
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/tools/ConceptTools.java`（新建）
  - `backend/paideia-ai/.../HarnessPrompts.java`（修改：导师模式加一句"可调用 knowledge_weak_concepts"）
- 接口：
  - 工具名 `knowledge_weak_concepts`，`ToolScope.READ`，`readOnly = true`
  - 返回最多 5 个最低分概念 + 全部 `reviewDue` 概念
- 步骤：
  1. 实现工具，内部只调 `ConceptGraphService.build(context.userId())`；
  2. 输出格式：「哈希表（了解，待复习，载体：字典与集合）」；
  3. 测试：两个用户各自调用只看到自己的概念；
  4. 提示词只加一句，不改既有段落。
- 验证：`mvn -pl backend/paideia-app -am test -Dtest=ConceptToolsTest` → 2 passed

## 完成条件

- AC-001～AC-016 每条都能在 `test-report` 里指到一次实际执行（命令 + 结果）。
- 两个图库都是动态加载，并有产物或网络证据。
- 注销级联有反向验证记录（去掉那一行会红）。
- 概念表为空时图谱页的空状态文案经人工确认。
