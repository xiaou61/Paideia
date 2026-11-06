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
- 说明：`pnpm --filter @paideia/knowledge test` 全量时 `recommend.test.ts` 有 2 条失败，与本工作项无关（未改 `recommend`），属既有问题。

## 六、未执行与残留风险（诚实记录）

- **未执行真实模型蒸馏的端到端**：AC-101/AC-102 的“上传 PDF → 模型蒸馏 → 草稿 → 发布 → RAG 命中”需要部署环境配置平台 AI 凭据（`paideia.ai.billing.platform-credential`）。本机测试 profile 关闭 embedding/RAG，且测试从不打真实上游，所以这条**未端到端验证**。导入编排的成功/失败分支由 `KnowledgeImportServiceTest`（桩 distiller）与集成测试的 plumbing 覆盖。
- **未执行 Playwright e2e**：`knowledge-import.spec.ts` / `knowledge-chunks.spec.ts` / `knowledge-wiki.spec.ts` 已编写（页面级 smoke，不触发模型），但需要后端 jar + Redis + 前端服务 + Chrome 通道，本轮未起整套 e2e 服务，**未运行**。
- **未跑 `mvn verify` 全量**：只跑了受影响的切片与守卫组合（符合项目「切片提交不跑全量」的约定）。
- 判定：核心代码与全部自动化可覆盖的分支已绿；受“平台模型凭据/整套 e2e 服务”约束的端到端路径待环境就绪补验。
