---
artifact: test-report
work_id: WORK-060
work: 数据集工坊与公开私有治理
status: passed
evidence: required
workflow: full
created: 2026-11-02
updated: 2026-11-02
---

# WORK-060 验证报告

**结论**：数据集工坊、公开/私有与目录已实现，验收标准逐条有命令级证据（AC-008 除外，见「未覆盖」）。
后端切片与既有门禁通过；前端 `@paideia/assistant` 78/78、`@paideia/knowledge` 93/93、
`@paideia/sql-workbench` 47/47、五个包 typecheck 通过；`sql-practice.spec.ts` **7 passed / 1 skipped / 0 failed**。
结构图两处已重新生成（同时吸收了本工作区里其它会话的在飞迁移与端点）。

**未提交、未部署**：本次改动仍在本机工作区；按项目规则，本地提交与部署需用户单独授权。

## 验证环境

| 项 | 值 |
| --- | --- |
| 本机 | Windows / Git Bash；JDK 25；Node + pnpm workspace；Playwright 走系统 Chrome（`E2E_CHANNEL=chrome`） |
| 数据库 | `scripts/test-db.sh` 每次分配的独占库（如 `paideia_run_1102134917_37776`，跑完自动删） |
| 沙箱 | 本机没有 Docker，沙箱不可用；e2e 里 `/sandbox/status` 用 mock（ready=true），后端单测不依赖容器 |
| 并发会话 | 工作区里有另一会话的在飞迁移（`V20261102120000__create_learning_plan_items.sql` 等），见「外部阻塞」 |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 助手能调 `planner_build_dataset` 与 `sql_read_dataset`，两者只读 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AgentToolBridgeIntegrationTest,McpToolSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 13/13；`toolsCannotWrite` 清单新增两个工具仍只含只读工具；`McpToolSnapshotTest` sql 组 1→2、总数 66→67；`AgentToolBridgeTest` 11/11 断言 ASSISTANT 含 planner 组 |
| AC-002 「存为数据集」把回答里的 SQL 送进面板，并显示预计表数 | `pnpm --filter @paideia/assistant test` + `E2E_CHANNEL=chrome PAIDEIA_BACKEND_PORT=8085 PAIDEIA_APP_PORT=5185 ../../../scripts/test-db.sh npx playwright test e2e/sql-practice.spec.ts` | 0 | passed | `conversation-panel.test.tsx`「存为数据集」78/78 中的一条：点击后 `pendingDatasetSql` 收到完整 SQL；e2e「保存数据集：预览表数…」断言「预计 2 张表：t、u」 |
| AC-003 存为私有：只有作者能看到 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=SqlDatasetCatalogIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 10/10；「私有集只对作者可见」：作者目录含 key，陌生人目录不含（真实 HTTP + DB） |
| AC-004 存为公开：陌生人可见且显示上传者用户名 | 同上 | 0 | passed | 「公开集对所有人可见并显示上传者」：陌生人目录含 key，`ownerName` 等于插入的用户名；e2e「浏览数据集」断言弹窗显示 `alice` |
| AC-005 作者切回私有后陌生人立刻看不到，作者自己仍能看到 | 同上 | 0 | passed | 「作者可以收回私有且陌生人不再可见」：`POST /visibility {private}` 200 后陌生人目录不含、作者目录仍含 |
| AC-006 非作者 / 内置 / 已下架不能切换 | 同上 | 0 | passed | 「非作者不能切换可见性」404；「内置集不能切换」400；「已下架集不能由作者切换」两个方向都 400 |
| AC-007 目录不含隐藏内置集、别人的私有集、已下架社区集 | 同上 + `../scripts/test-db.sh mvn -B -pl paideia-sandbox,paideia-account -am test` | 0 | passed | 「已下架的社区集不出现在目录」；`SqlDatasetCatalogServiceTest`「目录包含内置我的与公开且不泄露隐藏集」断言不含 `orders-hidden-a`；私有不可见见 AC-003 |
| AC-008（工具与保存路径）`sql_read_dataset` 返回完整 SQL；保存永远新增一行、不改动既有数据集 | `../scripts/test-db.sh mvn -B -pl paideia-sandbox -am test` | 0 | passed | `SqlDatasetToolsTest` 9/9：返回完整 DDL+INSERT（含数据行、隐藏集拒绝、别人的私人集拒绝）；`SqlDatasetUploadServiceTest`「公开写 community_published、私有写 private_draft，且不改动既有数据集」断言两次保存 key 不同、`insert` 是唯一写操作（`updateSnapshot`/`promoteToCommunity` 从未被调用）。**模型端到端行为未跑**，见「未覆盖」 |
| AC-009 保存前不写库，只有点「确认保存」才写 | `pnpm --filter @paideia/assistant test` + e2e `sql-practice.spec.ts` | 0 | passed | `conversation-panel.test.tsx` 的「存为数据集」只写待消费状态，面板包没有任何数据集写接口；e2e 断言上传请求只在点击确认后出现，请求体含 `"visibility":"private"` |
| AC-010 管理端列表显示上传者用户名；注销作者显示「已注销用户」 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=SqlDatasetCatalogIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 「管理端列表显示上传者用户名」`ownerName`=插入的用户名；「注销作者显示已注销用户」`owner_name` 为 NULL 时返回「已注销用户」 |
| AC-011 既有 SQL 练习 e2e 不回归 | `E2E_CHANNEL=chrome PAIDEIA_BACKEND_PORT=8085 PAIDEIA_APP_PORT=5185 ../../../scripts/test-db.sh npx playwright test e2e/sql-practice.spec.ts` | 0 | passed | 7 passed / 1 skipped / 0 failed；跳过的是既有「连续输入」用例（本机无 Docker，编辑器不渲染，用例自带显式 skip） |

## 测试与构建汇总

- 后端：
  - `mvn -B -pl paideia-sandbox,paideia-account -am test` → 136/0/0（含新增 `SqlDatasetControllerTest` 7、`SqlDatasetCatalogServiceTest` 5、`SqlDatasetUploadServiceTest` 4、`SqlDatasetToolsTest` 9）。
  - `mvn -B -T 1C -pl '!paideia-app' test` → BUILD SUCCESS（含 `AgentToolBridgeTest` 11/11）。
  - `mvn -B -pl paideia-app -am -Dtest=SqlDatasetCatalogIntegrationTest` → 10/10；`-Dtest=AgentToolBridgeIntegrationTest,McpToolSnapshotTest` → 13/13。
  - `-DskipTests package` 产出 jar → BUILD SUCCESS（e2e 前置）。
- 前端：
  - `pnpm --filter @paideia/sql-workbench test` → 47/47（`parseCreateTableNames` 新增 3 条）。
  - `pnpm --filter @paideia/assistant test` → 78/78（新增「存为数据集」1 条；前两次运行因机器负载令既有反馈用例 5s 超时，静置复跑全绿）。
  - `pnpm --filter @paideia/knowledge test` → 93/93。
  - `pnpm --filter @paideia/app --filter @paideia/admin --filter @paideia/knowledge --filter @paideia/assistant --filter @paideia/sql-workbench typecheck` → 全部通过。
- 结构图：`node scripts/check-diagrams.mjs` → 12 个 mermaid 图块全部可解析；`node scripts/gen-arch-diagram.mjs --check` → 四张图全部一致（数据库 61 表 / API 190 端点）。**注意**：这两处生成物同时吸收了本工作区里其它会话的在飞迁移（`learning_plan_items`、`ai_memory_candidates`）与端点，不是本项引入。

## 未覆盖与已知限制

- **AC-008 的模型端到端未覆盖**：需要真实上游让它读 SQL、加行、产出完整 SQL。本机不做真实 AI 调用；工具本身与保存路径都有测试。
- e2e 的目录/保存/改现有三条用的是 mock 状态，不是线上真实数据集；线上是否已有社区数据不影响这三条要验的界面行为。
- 目录一期最多 200 条、不做分页；上传者只显示用户名（不显示邮箱/角色）。
- 切换可见性是即时生效；把公开集收回私有时作者端有二次确认，但**正在用它做题的人会立刻失败**，这是产品已知取舍（见需求 D-04 与 design「已知边界」）。
- 预览表数只数 `CREATE TABLE` 字面量（`parseCreateTableNames` 的 shortcut 注释），注释里的同名语句会被算进去；真实结构以保存时的沙箱干跑为准。

## 外部阻塞（非本项缺陷）

- `MigrationChecksumTest.newMigrationsAreRecorded` 在**本工作区**为红：另一会话的在飞迁移文件
  `db/devdata/V958__seed_sketch_block.sql`、`V959__seed_review_due.sql`、
  `db/migration/V20261102120000__create_learning_plan_items.sql` 还没登记进
  `src/test/resources/migration-checksums.txt`。本项**没有新增迁移**，不代这个会话登记；
  `ModularityTest`（1/1）、`MigrationVersionFormatTest`（3/3）、`MigrationSeedOverlapTest`（3/3）均通过。

## 反向验证

- 可见性：`SqlDatasetControllerTest` 在**去掉所有者判定就会红**的方向上断言——非作者切到 404、`taken_down` 到 400、内置到 400；`SqlDatasetCatalogIntegrationTest` 用真实 SQL 再判一次（私有不给陌生人、下架不进目录）。
- 默认私有：`parseVisibility` 的空值/`private`/非法值三条断言；`upload` 只在显式 `public` 时才写 `community/published`。
- 回填不写库：面板包不认识数据集接口，`applyDatasetSql` 只改本地 state；e2e 用请求体断言上传只在点击确认后发生。
- 预览函数：标准生成 SQL 得 3 张表、无建表语句得空数组、`IF NOT EXISTS` 与反引号大小写归一。