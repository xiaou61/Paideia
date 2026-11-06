---
artifact: tasks
work_id: WORK-076
work: 知识库文档入库与检索块编辑
status: approved
created: 2026-11-06
updated: 2026-11-06
approved_by: xiaou61
approved_at: 2026-11-06 16:53:45 +0800
approver_role: CTO
---

# 知识库文档入库与检索块编辑实施任务

## 依据

- 已批准需求：`requirements.md`（xiaou61 / CTO，2026-11-06）。
- 提案与设计：`proposal.md`、`design.md`（本计划批准时一并批准）。

## 全局约束

- 迁移文件名 14 位时间戳（`V20261106HHMMSS__描述.sql`），生产迁移只建结构、不插业务数据；时间列存 UTC。
- 数据访问手写 MyBatis；跨模块只走 `KnowledgeApi` 包根类型，不碰 `internal`。
- 新后端端点全部在 `/api/v1/ai/admin/**`（沿用 `hasRole('ADMIN')` 前缀规则）。
- 只提交本工作项归属文件；用户已有改动保持原样。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 扫描件/加密 PDF 抽不到文本却生成空条目 | TASK-003、TASK-005 | `PdfTextExtractorTest#extractBlankOrEncryptedThrows`、`KnowledgeImportServiceTest#blankTextMarksFailed` |
| 同一 PDF 重复上传产生重复条目 | TASK-005 | `KnowledgeImportServiceTest#sameFingerprintCreatesOnce` |
| 蒸馏返回结构非法（空 title/body） | TASK-004、TASK-005 | `DocumentDistillerValidationTest#rejectsEmptyFields` |
| 手工编辑的块被下一轮增量索引覆盖 | TASK-009 | `KnowledgeChunkEditingServiceTest#editSurvivesReindex` |
| 正文变更导致编辑块错位/冲突被静默覆盖 | TASK-009 | `KnowledgeChunkEditingServiceTest#conflictTrueWhenBaseHashDiffers` |
| 编辑块时 embedding 服务不可用导致 500 | TASK-009 | `KnowledgeChunkEditingServiceTest#editWithEmbeddingDownIsPending` |
| 回滚后检索结果未更新 | TASK-009 | `KnowledgeChunkEditingServiceTest#rollbackAppendsAndChangesRetrieval` |
| 导入任务被两个 worker 并发处理 | TASK-006 | `KnowledgeImportJobTest#processPendingSingleFlight` |
| 非管理员访问导入/块编辑接口 | TASK-007、TASK-010 | `AuthorizationIsolationTest#adminEndpointsRequireAdmin` |
| `relativePath` 含 `../` 造成目录穿越或超长 slug | TASK-002、TASK-005 | `KnowledgeApiAttachmentPathTest#ensureCategoryPathSanitizes` |

## 任务

### TASK-001 | done | PDFBox 抽取能力探针

- 对应：`REQ-101`
- 依赖：无
- 新建/修改：`.agent/notes/pdf-extraction.md`（探针结论）
- 接口：无（交付物是决策记录）
- 测试：无（探针，结论写入笔记）；验证编译：`mvn -B -pl paideia-ai -am -DskipTests compile`
- 步骤：
  1. 在 `paideia-ai/pom.xml` 临时引入 PDFBox，试编译成功，记录可用版本与许可证。
  2. 用一份中文 PDF 与一份扫描件各跑一次抽取，记录“有文本/无文本”的实际输出特征。
  3. 把版本、许可证、中文支持、扫描件行为写入 `.agent/notes/pdf-extraction.md` 并作为 TASK-003 依据。
- 验证：`mvn -B -pl paideia-ai -am -DskipTests compile` 成功；笔记文件存在且给出结论。

### TASK-002 | done | KnowledgeApi 扩展：findAttachment + ensureCategoryPath

- 对应：`REQ-101`、`REQ-104`
- 依赖：无
- 新建/修改：
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/KnowledgeApi.java`（新增方法与 record）
  - `backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/KnowledgeApiImplementation.java`（实现）
- 接口：产出
  - `Optional<AttachmentContent> findAttachment(String fileId)`
  - `long ensureCategoryPath(List<String> segments)`
  - `record AttachmentContent(String id, String originalName, String contentType, long sizeBytes, byte[] bytes)`
- 测试：`backend/paideia-knowledge/src/test/java/io/github/xiaou61/knowledge/internal/KnowledgeApiAttachmentPathTest.java`
- 步骤：
  1. 写失败测试：`findAttachment` 对存在文件返回字节、对未知 id 返回空；`ensureCategoryPath(["章1","节1"])` 首次创建、再次调用复用不重复建。
  2. 实现 `findAttachment`（`StoredFileMapper` + `FileStorage.read`）。
  3. 实现 `ensureCategoryPath`（按段 upsert 到 `knowledge_categories`，slug 由段名规范化；清洗空段/`.`/`..`/超长）。
  4. 跑测试转绿。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-knowledge -am test -Dtest=KnowledgeApiAttachmentPathTest` → 期望全部 passed。

### TASK-003 | done | 复用 ResumeTextExtractor（原计划新建 PdfTextExtractor）

- 对应：`REQ-101`
- 依赖：TASK-001
- 说明（实施修订）：TASK-001 探针确认 `ResumeTextExtractor` 已实现 PDF/DOCX 文本抽取（PDFBox 3.0.7、20 页封顶、无文字层判定、XXE/zip 炸弹防线），无需新建。改为把 `extract(byte[],String)` 与 `ExtractedText` 提升为 public 并加注释，供 `internal.importing` 复用。属内部可见性调整，不改变范围与验收。
- 修改：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/resume/ResumeTextExtractor.java`（可见性 + 注释）
- 测试：既有 `ResumeTextExtractorTest`（回归）
- 验证：`-Dtest=ResumeTextExtractorTest` → Tests run: 10, Failures: 0（2026-11-06 已执行）

### TASK-004 | done | 蒸馏端口 DocumentDistiller + AgentScope 实现

- 对应：`REQ-102`
- 依赖：无
- 新建：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/DocumentDistiller.java`（端口 + record）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/provider/AgentScopeDocumentDistiller.java`（实现）
- 接口：
  - `DistilledEntry distill(String fileName, String text, ModelAccess access)`
  - `record DistilledEntry(String title, String bodyMarkdown, List<String> suggestedConcepts)`
- 测试：`DocumentDistillerValidationTest`
- 步骤：
  1. 写失败测试：对“缺 title / 缺 body”的结构化输出应视为失败（抛 BizException）。
  2. 定义端口与 record（签名不含框架类型，遵 D-09）。
  3. 实现 AgentScope 结构化输出 + 校验（title 非空≤200、body 非空 Markdown、suggestedConcepts 可空）。
  4. 测试转绿（用桩 provider，不真调上游）。
- 验证：`-Dtest=DocumentDistillerValidationTest` → passed。

### TASK-005 | done | 导入任务表 + 编排服务

- 对应：`REQ-101`、`REQ-102`、`REQ-103`、`REQ-105`、`REQ-107`
- 依赖：TASK-002、TASK-003、TASK-004
- 新建/修改：
  - 迁移 `backend/paideia-app/src/main/resources/db/migration/V<14位时间戳>__create_ai_knowledge_imports.sql`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/importing/KnowledgeImportMapper.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/importing/KnowledgeImport.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/importing/KnowledgeImportService.java`
- 接口：
  - `long enqueue(long operatorId, Long targetCategoryId, String fileId, String relativePath)`
  - `void processOne(KnowledgeImport job)`
  - 消费：`KnowledgeApi.findAttachment`、`PdfTextExtractor.extract`、`DocumentDistiller.distill`、`KnowledgeApi.createCollected`
- 测试：`KnowledgeImportServiceTest`
- 步骤：
  1. 建表迁移（结构见 design 的 `ai_knowledge_imports`）。
  2. Mapper CRUD（insert/findById/findByStatus/updateStatus）。
  3. `processOne`：读附件→抽文本（空→failed + error）→蒸馏→`createCollected`（fingerprint=sha256(规范化文本)、status=draft、sourceUrl=`/api/v1/knowledge/files/{fileId}`、categoryId 由 relativePath 走 `ensureCategoryPath` 或 targetCategoryId）→ 更新任务 succeeded + entryId。
  4. 复用现有取凭据/telemetry（RunSpec kind=`knowledge_import`）。
  5. 测试转绿：成功生成草稿、同指纹只建一次、空文本 failed。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am test -Dtest=KnowledgeImportServiceTest` → passed。

### TASK-006 | done | 导入定时 relay + 重试

- 对应：`REQ-106`
- 依赖：TASK-005
- 新建：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/importing/KnowledgeImportJob.java`
- 接口：`KnowledgeImportService.processPending()`；`KnowledgeImportService.retry(long id)`（failed→pending 清零 error）
- 测试：`KnowledgeImportJobTest`
- 步骤：
  1. 写失败测试：并发两次 `processPending` 只跑一份（`AtomicBoolean` 单飞）；`retry` 把 failed 置回 pending。
  2. 实现 `KnowledgeImportJob`（`@Scheduled(fixedDelay=…)`，构造期注册，异常自吞，同 `EssayCollectionJob`）。
  3. 测试转绿。
- 验证：`-Dtest=KnowledgeImportJobTest` → passed。

### TASK-007 | done | 导入管理端接口

- 对应：`REQ-104`、`REQ-106`
- 依赖：TASK-006
- 新建：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/importing/KnowledgeImportController.java`
- 接口：`POST /api/v1/ai/admin/knowledge-imports`、`GET …?status=`、`POST …/{id}/retry`（DTO 见 design）
- 测试：`KnowledgeImportControllerTest`、`ApiContractTest`、`AuthorizationIsolationTest`
- 步骤：
  1. 写失败测试：创建返回 importIds；列表按状态过滤；retry 生效。
  2. 实现 controller（`@ApiAccess(ApiAccess.Access.ADMIN)` 或依赖前缀规则，与现有 ai admin 一致）。
  3. 补契约与授权：非 ADMIN 403。
  4. 测试转绿。
- 验证：`-Dtest=KnowledgeImportControllerTest`、`-Dtest=AuthorizationIsolationTest` → passed。

### TASK-008 | done | 块编辑表 + mapper

- 对应：`REQ-201`、`REQ-204`
- 依赖：无
- 新建/修改：
  - 迁移 `V<14位时间戳>__create_ai_knowledge_chunk_edits.sql`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/rag/KnowledgeChunkEditMapper.java`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/rag/KnowledgeChunkEdit.java`
- 接口：
  - `int insert(KnowledgeChunkEdit edit)`
  - `List<KnowledgeChunkEdit> findCurrentByEntry(long entryId)`
  - `int findMaxVersion(long entryId, int chunkIndex)`
  - `List<KnowledgeChunkEdit> findRevisions(long entryId, int chunkIndex)`
  - `List<KnowledgeChunkEdit> findAllCurrent()`
  - `List<KnowledgeChunkEdit> findPendingEmbedding()`
- 测试：`KnowledgeChunkEditMapperTest`
- 步骤：
  1. 建表迁移（结构见 design 的 `ai_knowledge_chunk_edits`）。
  2. Mapper 实现；`findCurrentByEntry` 取每个 `(entry,chunkIndex)` 最大 version。
  3. 测试转绿（插入多版本，当前取最新）。
- 验证：`-Dtest=KnowledgeChunkEditMapperTest` → passed。

### TASK-009 | done | 块编辑服务 + RAG 合并 + 降级补嵌

- 对应：`REQ-202`、`REQ-203`、`REQ-204`、`REQ-205`、`QR-002`
- 依赖：TASK-008、现有 `KnowledgeRagService`/`EmbeddingClient`
- 新建/修改：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/rag/KnowledgeChunkEditingService.java`（新）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/rag/KnowledgeRagService.java`（改 `retrieveContext` 合并；`reindexChanged` 补嵌 pending）
- 接口：
  - `List<ChunkView> list(long entryId)`
  - `EditResult edit(long entryId, int chunkIndex, String content, long editorId)`
  - `List<Revision> revisions(long entryId, int chunkIndex)`
  - `int rollback(long entryId, int chunkIndex, int version, long editorId)`
  - `record ChunkView(int chunkIndex, String content, String origin, Integer version, boolean conflict, boolean orphaned)`
  - `record EditResult(int version, boolean embeddingPending)`
- 测试：`KnowledgeChunkEditingServiceTest`
- 步骤：
  1. 写失败测试：编辑只对单块调用一次 embed；`editSurvivesReindex`（编辑后跑 reindex，检索仍用编辑内容）；`conflictTrueWhenBaseHashDiffers`；`editWithEmbeddingDownIsPending`（embed 抛错→embeddingPending=true，embedding 存 NULL）；`rollbackAppendsAndChangesRetrieval`（回滚追加新版本且检索内容变回）。
  2. 实现 `edit`：算 `base_content_hash`=当前自动块 hash，插新版本，尝试 embed（失败置 NULL/ pending）。
  3. `rollback`：读目标版本内容，追加 version=max+1。
  4. 改 `retrieveContext`：加载 `findAllCurrent()`，按 `(entry,chunkIndex)` 覆盖自动块；`embedding IS NULL` 时回退自动块。
  5. 改 `reindexChanged`：对 `findPendingEmbedding()` 补嵌。
  6. 测试转绿。
- 验证：`-Dtest=KnowledgeChunkEditingServiceTest` → passed。

### TASK-010 | done | 块编辑管理端接口

- 对应：`REQ-201`
- 依赖：TASK-009
- 新建：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/rag/KnowledgeChunkAdminController.java`
- 接口：`GET/PUT /api/v1/ai/admin/knowledge-entries/{entryId}/chunks`、`…/{chunkIndex}`、`…/{chunkIndex}/revisions`、`…/{chunkIndex}/rollback`（见 design）
- 测试：`KnowledgeChunkAdminControllerTest`、`AuthorizationIsolationTest`
- 步骤：
  1. 写失败测试：列表/编辑/历史/回滚四端点。
  2. 实现 controller。
  3. 授权：非 ADMIN 403。
  4. 测试转绿。
- 验证：`-Dtest=KnowledgeChunkAdminControllerTest`、`-Dtest=AuthorizationIsolationTest` → passed。

### TASK-011 | done | 前端 admin-client 扩展

- 对应：`REQ-201`、`REQ-106`
- 依赖：TASK-007、TASK-010
- 新建/修改：`frontend/packages/knowledge/src/admin-client.ts`、`frontend/packages/knowledge/src/index.ts`（导出类型）
- 接口：`knowledgeImports.{create,list,retry}`；`chunkEdits.{list,edit,revisions,rollback}`
- 测试：`frontend/packages/knowledge/src/admin-client.test.ts`
- 步骤：
  1. 写失败测试：请求路径/方法/载荷正确。
  2. 实现 client 方法与类型。
  3. 测试转绿。
- 验证：`cd frontend && pnpm --filter @paideia/knowledge test` → passed。

### TASK-012 | done | 管理端导入页（文件夹保留目录）

- 对应：`REQ-104`、`REQ-106`
- 依赖：TASK-011
- 新建/修改：
  - `frontend/apps/admin/src/pages/KnowledgeImportPage.tsx`
  - `frontend/apps/admin/src/router.tsx`（route `/knowledge/imports`）
  - `frontend/apps/admin/src/components/AdminShell.tsx`（侧栏入口）
- 测试：`frontend/apps/admin/e2e/knowledge-import.spec.ts`
- 步骤：
  1. 页面：`<input webkitdirectory>` 选文件夹，逐文件走 `upload` 拿 fileId，带 `webkitRelativePath` 调 `knowledgeImports.create`；列表轮询状态；失败可 retry。
  2. 路由与侧栏入口。
  3. e2e：上传含子目录 → 断言任务列表出现且成功后分类树生成子节点。
- 验证：`bash ../scripts/test-db.sh pnpm --filter @paideia/admin test:e2e e2e/knowledge-import.spec.ts` → passed。

### TASK-013 | done | 管理端块编辑面板

- 对应：`REQ-201`、`REQ-202`、`REQ-203`、`REQ-204`
- 依赖：TASK-011
- 新建/修改：
  - `frontend/apps/admin/src/pages/KnowledgeEntryChunksPage.tsx`
  - `frontend/apps/admin/src/router.tsx`（`/knowledge/entries/:entryId/chunks`）
  - `frontend/apps/admin/src/components/AdminShell.tsx`、`KnowledgeEntriesPage.tsx`（入口按钮）
- 测试：`frontend/apps/admin/e2e/knowledge-chunks.spec.ts`
- 步骤：
  1. 列表展示块（auto/manual、冲突/孤儿标记）；编辑保存；历史 diff；回滚。
  2. 路由与入口。
  3. e2e：编辑一块 → 检索（用 API 或页面）内容变化；回滚 → 内容恢复。
- 验证：`e2e/knowledge-chunks.spec.ts` → passed。

### TASK-014 | done | 学习者目录阅读视图

- 对应：`REQ-301`、`REQ-302`
- 依赖：TASK-002
- 新建/修改：
  - `frontend/apps/app/src/pages/KnowledgeWikiPage.tsx`
  - `frontend/apps/app/src/router.tsx`、`frontend/packages/identity`（NAV / `preferredNav`）
- 测试：`frontend/apps/app/e2e/knowledge-wiki.spec.ts`
- 步骤：
  1. 左侧分类树 + 右侧按序渲染已发布条目正文（复用 `MarkdownBody`）。
  2. 加入导航入口与 `preferredNav` key。
  3. e2e：进入页面 → 展开分类 → 正文渲染。
- 验证：`e2e/knowledge-wiki.spec.ts` → passed。

### TASK-015 | done | 收尾：结构图 + 迁移守卫 + 验收报告

- 对应：全部 `AC-*`
- 依赖：TASK-001…TASK-014
- 新建/修改：`docs/architecture/05-database-tables.md`（重生成）；`testing/report.md`
- 步骤：
  1. `node scripts/gen-arch-diagram.mjs` 重生成结构图，`--check` 通过。
  2. 跑 `MigrationVersionFormatTest`、`MigrationSeedOverlapTest`、`ApiContractTest`、`AuthorizationIsolationTest`。
  3. 跑受影响后端切片测试与三条 e2e；把命令与输出记入 `testing/report.md`。
- 验证：`node scripts/gen-arch-diagram.mjs --check` 通过；守卫与切片测试 passed；report 记录新鲜证据。

## 完成条件

- 每个 `AC-*` 至少被一个任务覆盖且对应测试通过。
- `testing/report.md` 记录本轮实际执行的命令与输出；未执行的验证写"未运行"，不得写成通过。
- 结构图与数据库迁移一致（`--check` 通过）。

## 执行记录

- 2026-11-06 TASK-001：探针结论见 `.agent/notes/document-text-extraction.md`（不新增依赖，复用 `ResumeTextExtractor`）。
- 2026-11-06 TASK-002：`mvn -B -pl paideia-knowledge -am -Dtest=KnowledgeApiAttachmentPathTest -Dsurefire.failIfNoSpecifiedTests=false test` → Tests run 6, Failures 0, Errors 0。
- 2026-11-06 TASK-003：`mvn -B -pl paideia-ai -am -Dtest=ResumeTextExtractorTest -Dsurefire.failIfNoSpecifiedTests=false test` → Tests run 10, Failures 0, Errors 0（回归通过）。
- 2026-11-06 TASK-002/003 合并编译：`mvn -B -pl paideia-knowledge,paideia-ai -am -DskipTests compile` → EXIT=0。
- 2026-11-06 TASK-004：`-Dtest=DistilledEntryValidatorTest` → Tests run 8, Failures 0。
- 2026-11-06 TASK-005：`-Dtest=KnowledgeImportServiceTest` → Tests run 6, Failures 0。
- 2026-11-06 TASK-006：`-Dtest=KnowledgeImportJobTest` → Tests run 3, Failures 0。
- 2026-11-06 TASK-007：`-Dtest=KnowledgeImportControllerTest` → Tests run 1, Failures 0。
- 2026-11-06 TASK-008：迁移 `V20261106173000__create_ai_knowledge_chunk_edits.sql` + 实体 + mapper 已写，参与 `paideia-ai` 编译通过；mapper SQL 依赖 MySQL，待集成测试（环境就绪）验证后转 done。
- 2026-11-06 TASK-009：`-Dtest=KnowledgeChunkEditingServiceTest,KnowledgeRagServiceTest` → Tests run 10, Failures 0（含合并覆盖用例）。
- 2026-11-06 TASK-010：`-Dtest=KnowledgeChunkAdminControllerTest` → Tests run 1, Failures 0。
- 2026-11-06 TASK-011：`pnpm --filter @paideia/knowledge exec vitest run src/admin-client.test.ts` → 4 passed；`pnpm --filter @paideia/knowledge typecheck` EXIT=0。
- 2026-11-06 TASK-012/013/014：页面+路由+入口实现；`pnpm --filter @paideia/admin typecheck` EXIT=0、`pnpm --filter @paideia/app typecheck` EXIT=0。e2e 规格待补并在 TASK-015 运行。
- 2026-11-06 守卫：test-db.sh + `-Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest` → BUILD SUCCESS，Tests run 35，Failures 0。Flyway 成功应用 62 迁移（含两条新迁移，now at v20261106173000）。
- 2026-11-06 集成：`-Dtest=KnowledgeChunkImportIntegrationTest` → Tests run 3, Failures 0（真实 MySQL；期间修复 2 个 SQL 缺陷：IMPORT findRecent 丢空格、CHUNK_EDIT 列前缀歧义）。
- 2026-11-06 结构图：`gen-arch-diagram.mjs` 生成 65 表/15 外键、194 端点/48 控制器，`--check` 通过；README 计数已更新。
- 2026-11-06 TASK-008：mapper SQL 已由集成测试在真实库上验证 → done。
- 2026-11-06 TASK-015：验证报告 `testing/report.md` 已产出；Playwright e2e 已编写但未运行，真实模型蒸馏端到端（AC-101/102）待环境补验。
- 2026-11-06 全链路集成：`-Dtest=KnowledgeImportFlowIntegrationTest` → Tests run 3, Failures 0（真实 PDF→抽取→桩蒸馏→draft+来源+指纹+目录树；重复导入指纹去重=1 条；发布后进入 publishedContents 语料）。覆盖 AC-101/103/104 与 AC-102 代理。
- 2026-11-06 前端生产构建：`pnpm --filter @paideia/admin build`=0、`@paideia/app build`=0。
- 2026-11-06 e2e 实跑（Chrome）：admin `knowledge-import`+`knowledge-chunks` → 2 passed；app `knowledge-wiki` → 1 passed。期间修复 admin playwright 配置不识别 E2E_CHANNEL 的问题（与 app 对齐）。