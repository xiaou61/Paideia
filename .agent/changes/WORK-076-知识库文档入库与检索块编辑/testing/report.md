# 验证报告（WORK-076）

日期：2026-11-06
分支/工作树：`work/WORK-076` @ `F:\Paideia\.worktrees\WORK-076`
环境：Windows；MySQL `127.0.0.1:3306`（root，`scripts/test-db.sh` 每次分配独占库）；JDK 25；Node 24 / pnpm 12。

> 说明：本报告只记录**实际执行**过的命令与结果。未执行项在末尾「未执行与残留风险」明确列出，不冒充通过。

## 一、后端单元测试（模块内，纯 Mockito，无 DB）

命令（在 `backend/`）：

- `mvn -B -pl paideia-knowledge -am -Dtest=KnowledgeApiAttachmentPathTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=ResumeTextExtractorTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=DistilledEntryValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=KnowledgeImportServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=KnowledgeImportJobTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=KnowledgeImportControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=KnowledgeChunkEditingServiceTest,KnowledgeRagServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -B -pl paideia-ai -am -Dtest=KnowledgeChunkAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

结果（均 `Tests run: N, Failures: 0, Errors: 0`）：

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| KnowledgeApiAttachmentPathTest | 6 | findAttachment / ensureCategoryPath（含路径清洗防穿越） |
| ResumeTextExtractorTest（回归） | 10 | 复用抽取器无回归 |
| DistilledEntryValidatorTest | 8 | 蒸馏结构化校验（空/非法/缺字段/超长） |
| KnowledgeImportServiceTest | 6 | 导入编排：草稿落库、指纹去重、附件缺失/抽取失败不留半截 |
| KnowledgeImportJobTest | 3 | relay 委托、异常吞掉、单飞 |
| KnowledgeImportControllerTest | 1 | 导入列表 |
| KnowledgeChunkEditingServiceTest | 6 | 单块编辑只重嵌、版本、回滚、冲突/孤儿、embedding 降级 |
| KnowledgeRagServiceTest | 4 | 检索排序 + **手工覆盖合并**（含原有 3 条回归） |
| KnowledgeChunkAdminControllerTest | 1 | 切片列表 |

## 二、迁移（全新库应用）

`scripts/test-db.sh` 分配的独占库上，Flyway 输出：

- `Successfully validated 62 migrations`
- 逐条 `Migrating schema ... to version "20261106172000 - create ai knowledge imports"`、`"20261106173000 - create ai knowledge chunk edits"`
- `Successfully applied 62 migrations ... now at version v20261106173000`

→ 两条新迁移合法、可应用；`ai_knowledge_imports`、`ai_knowledge_chunk_edits` 均建表成功。

新增迁移登记校验和：`-Dmigration.checksums.update=true` 登记后
`migration-checksums.txt` 新增两行：
`V20261106172000__create_ai_knowledge_imports.sql 278575980`
`V20261106173000__create_ai_knowledge_chunk_edits.sql -2023697050`

## 三、后端守卫与集成（真实 MySQL，`scripts/test-db.sh`）

命令（Git Bash）：

```
../scripts/test-db.sh mvn -B -pl paideia-app -am \
  -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**BUILD SUCCESS — Tests run: 35, Failures: 0, Errors: 0**，各计数：

- ApiContractTest 10 / ApiSurfaceTest 7 / AuthorizationIsolationTest 9 / MigrationChecksumTest 2 / MigrationSeedOverlapTest 3 / MigrationVersionFormatTest 3 / ModularityTest 1

→ 新端点已纳入接口契约、授权隔离（401/403）通过、模块边界无越界、迁移三件通过。

新增集成测试 `KnowledgeChunkImportIntegrationTest`（真实 MySQL，经 HTTP 走 controller→service→mapper→DB）：

```
../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=KnowledgeChunkImportIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**Tests run: 3, Failures: 0, Errors: 0**。覆盖：
- 切片：初始空 → 编辑单块 → 历史 → 回滚（真实 mapper SQL）；
- 导入：列表可读、登记空批次返回空 id 列表；
- 非管理员访问切片/导入接口 403。

期间**发现并修复两个真实 SQL 缺陷**（集成测试的价值所在）：
1. `KnowledgeImportMapper.findRecent` 文本块把 `SELECT` 与列清单间的空格吃掉（`SELECTid,...`）→ 改为内联列名；
2. `KnowledgeChunkEditMapper` 只给首列加了 `c.` 前缀，`entry_id` 在多表 join 下歧义 → 改为 `SELECT c.*`。
修复后集成测试由 500 转全绿。

## 四、架构图

- `node scripts/gen-arch-diagram.mjs` → 已写出 12 模块 / 16 前端包 / **65 张表（15 外键）** / **194 端点（48 控制器）**
- `node scripts/gen-arch-diagram.mjs --check` → 全部一致（含 README 计数已更新为 65/15、194/48）

## 五、前端

- 依赖安装：工作树 `pnpm install --prefer-offline --ignore-scripts`（18s，复用 store）。
- `pnpm --filter @paideia/knowledge exec vitest run src/admin-client.test.ts` → **4 passed**（导入/切片 client 路径、方法与载荷）。
- `pnpm --filter @paideia/admin typecheck` → EXIT=0
- `pnpm --filter @paideia/app typecheck` → EXIT=0
- **生产构建**：`pnpm --filter @paideia/admin build` → ADMIN_BUILD=0；`pnpm --filter @paideia/app build` → APP_BUILD=0（新增页面可正常打包）
- **页面级 e2e（实跑，系统 Chrome 通道，真实后端+种子登录）**：
  - admin `e2e/knowledge-import.spec.ts` + `e2e/knowledge-chunks.spec.ts` → **2 passed**
  - app `e2e/knowledge-wiki.spec.ts` → **1 passed**
  - 期间把 admin 的 `playwright.config.ts` 补上与 app 一致的 `E2E_CHANNEL` 通道支持（此前 admin 忽略该变量、去找未安装的 bundled Chromium），属测试基础设施修复。
- 说明：`pnpm --filter @paideia/knowledge test` 全量时 `recommend.test.ts` 有 2 条失败，与本工作项无关（未改 `recommend`），属既有问题。

## 六、文档入库全链路集成（模型打桩，真实上传/抽取/落库）

命令：

```
../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=KnowledgeImportFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**Tests run: 3, Failures: 0, Errors: 0**。用真实 PDFBox 造带文字层的 PDF，经真实
`/api/v1/knowledge/admin/files` 上传 → 登记导入 → 触发 relay 的 `processPending` → 真实
`ResumeTextExtractor` 抽文本 → **仅把 `DocumentDistiller` 换成桩**（不打真实上游）→ 真实落库。断言：

- **AC-101**：按 `source_url` 查到条目，`status=draft`、正文含原文、`fingerprint` 为 64 位、有来源记录；
  导入任务 `status=succeeded`。
- **AC-103**：同一 PDF 上传两次导入 → 两个来源 URL 合计只有 **1 条**条目，第二次导入仍 `succeeded`（命中指纹去重）。
- **AC-104**：`relativePath=导入测试/子目录/a.pdf` → `knowledge_categories` 生成两层（父=导入测试、子=子目录）。
- **AC-102（代理）**：把该草稿经管理端 PUT 发布为 published 后，`KnowledgeApi.publishedContents()`
  包含该条目——这正是 RAG 索引的语料来源（RAG 抽取/向量化逻辑另由 `KnowledgeRagService` 单测覆盖）。

> 期间另修一个测试自身缺陷（去重断言误用原始文本而非抽取文本），改按来源 URL 计数后为真实去重。

## 七、未执行与残留风险（诚实记录）

- **真实模型（非桩）蒸馏质量未验**：AC-101/102 的“抽文本→**真实模型**蒸馏出 Markdown 正文→草稿”这条，
  本机把 `DocumentDistiller` 换成了桩（测试不打真实上游，与全仓 AI 测试同一纪律）。管线本身（抽取、落库、去重、
  来源、目录、发布进语料）已由第六节真实集成覆盖；真实模型输出质量需部署环境配平台 AI 凭据（`paideia.ai.billing.platform-credential`）后人工试跑。
- **未跑 `mvn verify` 全量**：只跑了受影响的切片与守卫组合（符合项目「切片提交不跑全量」的约定）。
- 判定：核心代码、单测、迁移、守卫、真实 DB 集成（含 AC-101/102代理/103/104）、前端构建与三条页面 e2e 均已绿；
  仅剩“真实模型输出质量”这一项依赖部署 AI 凭据的验证未做。
