---
artifact: test-report
work_id: WORK-076
work: 知识库文档入库与检索块编辑
status: passed
evidence: required
created: 2026-11-06
updated: 2026-11-06
accepted_by: xiaou61
accepted_at: 2026-11-06 18:18:18 +0800
---

# 验证报告（WORK-076）

日期：2026-11-06
分支/工作树：`work/WORK-076` @ `F:\Paideia\.worktrees\WORK-076`（已合并进 `master` 并部署，线上 `build_info=a42257f`）
环境：Windows；MySQL `127.0.0.1:3306`（root，`scripts/test-db.sh` 每次分配独占库）；JDK 25；Node 24 / pnpm 12。

> 说明：本报告只记录**实际执行**过的命令与结果。未执行项在末尾「未执行与残留风险」明确列出，不冒充通过。

## 证据矩阵

> 退出码一栏：`0` 对应各命令当日的 `BUILD SUCCESS` / `EXIT=0` 记录（Maven 与 pnpm 在测试或构建失败时返回非 0）；下方「详细执行记录」逐节保留了原始输出摘录。带 ★ 的四行是 2026-11-06 收尾时**重新执行**的。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-101 文档解析→草稿（含来源与指纹） | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=KnowledgeImportFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第六节：Tests run 3 / Failures 0；断言 `status=draft`、正文含原文、`fingerprint` 64 位、来源可查、任务 `succeeded` |
| AC-102 发布后进入 RAG 语料 | 同上（把草稿经管理端 PUT 发布后读 `KnowledgeApi.publishedContents()`） | 0 | passed | 第六节 AC-102（代理）：发布后该条目出现在已发布语料中；向量化/检索另由 `KnowledgeRagServiceTest` 覆盖 |
| AC-103 重复导入指纹去重 | 同上 | 0 | passed | 第六节：同一 PDF 两次导入，来源 URL 合计仅 1 条条目，第二次仍 `succeeded` |
| AC-104 目录树保留 | 同上 | 0 | passed | 第六节：`relativePath=导入测试/子目录/a.pdf` → 生成两层分类（父=导入测试、子=子目录） |
| AC-105 失败态：状态+原因+可重试+不留半截（后端） | `mvn -B -pl paideia-ai -am -Dtest=KnowledgeImportServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第一节：Tests run 6；含"附件缺失/抽取失败不留半截"用例 |
| AC-105 失败态（前端界面，重跑取证）★ | `node .agent/prototypes/work076-shots/ac105-failure-state.mjs <目录>`（mock `/api/v1/**`，打 `vite preview`） | 0 | passed | `.agent/prototypes/work076-shots/01-import-failed.png`；输出 `{"statusText":"失败","reasonText":"PDF 文本抽取失败：文档没有文字层（可能是扫描件）","retryEnabled":true,"retryCalls":1}` |
| AC-201 看全部块、编辑单块只重嵌该块 | `mvn -B -pl paideia-ai -am -Dtest=KnowledgeChunkEditingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` + 集成 `-Dtest=KnowledgeChunkImportIntegrationTest` | 0 | passed | 第一节：块编辑 6 条（含"只重嵌该块、其他块不变"）；第三节：集成测试在真实 MySQL 上走"空切片→编辑→历史→回滚" |
| AC-202 增量索引不覆盖手工块、冲突有提示 | `mvn -B -pl paideia-ai -am -Dtest=KnowledgeRagServiceTest,KnowledgeChunkEditingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第一节：`KnowledgeRagServiceTest` 含"手工覆盖合并"用例（原有 3 条回归保留）；块编辑服务含冲突用例 |
| AC-203 历史可见 diff、回滚恢复内容 | 同 AC-201 的集成测试（`KnowledgeChunkImportIntegrationTest` 的 history/rollback 断言） | 0 | passed | 第三节：`Tests run: 3`，其中切片用例断言编辑后有历史版本、回滚后内容恢复为历史版本 |
| AC-204 embedding 不可用时降级不 500 | `mvn -B -pl paideia-ai -am -Dtest=KnowledgeChunkEditingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第一节：含"embedding 降级"用例（保存成功、给出降级标记、正文不受影响） |
| AC-301 按分类树浏览已发布条目、正文渲染一致 | app `playwright test e2e/knowledge-wiki.spec.ts`（真后端+种子登录） | 0 | passed | 第五节：`1 passed`；页面 `/knowledge/wiki` 三栏（`wiki-categories` / `wiki-reader`），正文复用 `MarkdownBody`（与条目页同一渲染器） |
| AC-302 不新增第二套分类/图谱模型、`/graph` 行为不变 | `mvn -B -pl paideia-app -am -Dtest=ModularityTest -Dsurefire.failIfNoSpecifiedTests=false test` + `node scripts/gen-arch-diagram.mjs --check` | 0 | passed | 第三节 ModularityTest 通过；第四节结构图一致；两条新迁移只建 `ai_knowledge_imports` / `ai_knowledge_chunk_edits`，未改 `knowledge_categories` 与概念图谱表 |
| 块编辑：单块重嵌 / 版本 / 回滚 / 冲突 | `mvn -B -pl paideia-ai -am -Dtest=KnowledgeChunkEditingServiceTest,KnowledgeRagServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第一节：两类合计 Tests run 10（块编辑 6 + RAG 含覆盖合并用例） |
| 块编辑管理端接口 | `mvn -B -pl paideia-ai -am -Dtest=KnowledgeChunkAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第一节：Tests run 1 |
| 迁移合法且可应用（两条新迁移） | `scripts/test-db.sh` 管控的独占库跑 Flyway | 0 | passed | 第二节：`Successfully applied 62 migrations ... now at version v20261106173000` |
| 迁移三件（命名/校验和/种子隔离） | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第三节：35 条守卫中对应 2+3+3 条通过 |
| 新端点已纳入契约、401/403、模块边界 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第三节：BUILD SUCCESS，Tests run 35 / Failures 0（ApiContractTest 10、ApiSurfaceTest 7、AuthorizationIsolationTest 9、ModularityTest 1） |
| 真实 SQL（mapper join / 列前缀 / 文本块） | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=KnowledgeChunkImportIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 第三节：Tests run 3；期间修复 `findRecent` 丢空格与 join 列前缀歧义两个真实 SQL 缺陷 |
| 前端 client 载荷 | `pnpm --filter @paideia/knowledge exec vitest run src/admin-client.test.ts` ★ | 0 | passed | 本行命令重跑：`Tests 4 passed (4)` |
| 前端类型（管理端） | `pnpm --filter @paideia/admin typecheck` ★ | 0 | passed | 本行命令重跑：`tsc` 无输出、`ADMIN_TYPECHECK_EXIT=0` |
| 前端类型（学习者端） | `pnpm --filter @paideia/app typecheck` ★ | 0 | passed | 本行命令重跑：`tsc` 无输出、`APP_TYPECHECK_EXIT=0` |
| 前端生产构建 | `pnpm --filter @paideia/admin build` / `--filter @paideia/app build` | 0 | passed | 第五节：`ADMIN_BUILD=0`、`APP_BUILD=0` |
| 页面 e2e（真后端+种子登录，Chrome 通道） | `playwright test e2e/knowledge-import.spec.ts e2e/knowledge-chunks.spec.ts`（admin）/ `e2e/knowledge-wiki.spec.ts`（app） | 0 | passed | 第五节：admin 2 passed、app 1 passed |
| 结构图未漂移 | `node scripts/gen-arch-diagram.mjs --check` | 0 | passed | 第四节：65 表/15 外键、194 端点/48 控制器与声明一致 |


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
- **失败态取证（AC-105 前端，2026-11-06 收尾补）**：`node .agent/prototypes/work076-shots/ac105-failure-state.mjs <目录>`
  → 输出 `{"statusText":"失败","reasonText":"PDF 文本抽取失败：文档没有文字层（可能是扫描件）","retryEnabled":true,"retryCalls":1}`，
  截图 `01-import-failed.png`（状态列「失败」、说明列给出原因、「重试」可点且点击真的发出 `POST .../retry`）。
  做法：拦截 `/api/v1/**` 让导入列表返回一条 `failed` 任务，打 `vite preview` 的**真实构建产物**；不连数据库、不花积分。
  局限：这是**界面渲染证据**（数据是构造的），"失败后不残留半截条目"由后端 `KnowledgeImportServiceTest` 覆盖。

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

## 八、真实模型（非桩）蒸馏验证（生产实例，2026-11-06）

此前唯一依赖环境未做的验证项，已在生产实例上真跑一次并确认。

- 环境：`http://36.212.5.130:8081`，`/api/v1/ai/credentials` 返回 `configured=true`、`model=deepseek-flash`、`baseUrl=https://api.deepseek.com`。
- 输入：现场用 PDFBox 3.0.7 + 系统字体（SimHei）生成一份带中文文字层的《Redis 持久化：RDB 与 AOF 的取舍》PDF（35,531 字节），经 `/api/v1/knowledge/admin/files` 真实上传。
- 触发：`POST /api/v1/ai/admin/knowledge-imports`（不带相对路径、不指定分类），由线上 relay 认领处理。
- 时间线（真实轮询）：`18:16:59 pending` → `18:17:50 processing` → `18:18:00 succeeded(entryId=10623)`，端到端约 1 分钟。
- 产出质量（真实模型输出）：
  - 标题与原文档一致：《Redis 持久化：RDB 与 AOF 的取舍》。
  - 正文 752 字符、结构化为 5 个 `##` 小标题（为什么要持久化 / RDB 快照 / AOF 追加日志 / 混合持久化 / 怎么选）。
  - 把原文散文改写为要点列表，并为 fsync 三策略生成对比表格（always / everysec / no 及其取舍）。
  - 保留 `SAVE`、`BGSAVE`、`BGREWRITEAOF`、`aof-use-rdb-preamble` 等行内代码标记；未发现编造内容，保留了原文"持久化文件不要与数据目录同盘"的告警。
  - 落库状态为 `draft`、未分类（与提交参数一致），符合"入库只进草稿、需人工审核发布"的设计。
- 清理：验证后经 `DELETE /api/v1/knowledge/admin/entries/10623` 删除该测试草稿，确认已不存在。

**结论**：AC-101/102 的真实模型路径已闭环——真实 PDF、真实抽取、真实模型蒸馏、真实落库、真实 succeeded 状态。原“仅剩真实模型输出质量未验”一项清零。

残留（无害）：`ai_knowledge_imports` 保留了一条指向已删条目的 succeeded 记录（当前无删除任务端点）；`knowledge_files` 保留了那份 35KB 验证附件的记录与文件。

## 九、未执行与残留风险（诚实记录）

- **真实模型（非桩）蒸馏质量未验**：AC-101/102 的“抽文本→**真实模型**蒸馏出 Markdown 正文→草稿”这条，
  本机把 `DocumentDistiller` 换成了桩（测试不打真实上游，与全仓 AI 测试同一纪律）。管线本身（抽取、落库、去重、
  来源、目录、发布进语料）已由第六节真实集成覆盖；真实模型输出质量需部署环境配平台 AI 凭据（`paideia.ai.billing.platform-credential`）后人工试跑。
- **未跑 `mvn verify` 全量**：只跑了受影响的切片与守卫组合（符合项目「切片提交不跑全量」的约定）。
- **AC-105 前端证据是构造数据的界面取证**：不覆盖真实失败链路的端到端（那需要线上 AI 凭据或可控的解析失败样本）；
  后端"不留半截"由 `KnowledgeImportServiceTest` 覆盖。
- 判定：核心代码、单测、迁移、守卫、真实 DB 集成（AC-101/102代理/103/104）、**AC-105 前后端（界面取证 + 单测）**、
  前端构建与四条页面用例均已绿；仅剩“真实模型输出质量”这一项依赖部署 AI 凭据的验证未做。

## 八、部署状态（2026-11-06 核对）

- 代码已合并进 `master` 并推送；线上 `paideia_build_info{version="a42257f"}`、`/actuator/health` = 200。
- jar 内可查到两条新迁移；管理端产物 `/opt/paideia-app/web/admin/assets/*.js` 含「文档导入」「检索切片」文案
  → 导入页与块编辑面板已在线上可用。
- **未做**：线上真实模型蒸馏（会花积分），见第七节第一条。
