---
artifact: tasks
work_id: WORK-060
work: 数据集工坊与公开私有治理
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 11:10:58 +0800
approver_role: CTO
---

# 数据集工坊与公开私有治理实施任务

## 依据

- `requirements.md`（REQ-001…016 / AC-001…011）
- `proposal.md`（复用 source/status、只读工具、弹窗优先）
- `design.md`（接口契约、页面流程、安全判定）

## 全局约束

- 内部助手工具**只读**：`planner_build_dataset` 只拼 SQL，`sql_read_dataset` 只读 SQL；保存只能由用户点击。
- 写路径只有 `SqlDatasetUploadService` 一条：大小 → 白名单 → 沙箱干跑 + 抓结构 → insert；不新增旁路。
- 目录只返回用户名与规模，不返回邮箱、角色、SQL、数据行。
- 无迁移、无新表；`/sandbox/status` 的 `datasets` 形状保持不变。
- 新增前后端接口都要有失败文案；前端不做乐观更新。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 非作者切换别人的数据集可见性 | TASK-001 | `SqlDatasetControllerTest`（404 同一句） |
| 内置集/已下架集被作者改回公开 | TASK-001 | 同上（400 文案） |
| 公开数据集在目录里泄露 schema/数据行 | TASK-002 | `SqlDatasetCatalogServiceTest`（字段白名单） |
| 隐藏内置集出现在目录 | TASK-002 | 同上（不含 `orders-hidden-a`） |
| 上传者注销后目录 500 或显示 id | TASK-002 | 同上（「已注销用户」） |
| 助手拿到写工具或能直接落库 | TASK-003 | `AgentToolBridgeTest`、`AgentToolBridgeIntegrationTest` |
| AI 读整份 SQL 被普通提问触发 | TASK-003 / TASK-006 | 工具描述 + 页面上下文断言 |
| 「存为数据集」直接写库而不是只回填 | TASK-006 | e2e：点击后数据库无新行，保存按钮才写 |
| 保存时可见性默认成 public | TASK-005 | 单测/组件测试：默认 private |
| 预览表数把注释里的 CREATE TABLE 算进去 | TASK-005 | `dataset-schema.test.ts`（shortcut 注释 + 用例） |

## 任务

### TASK-001 | done | 后端：上传可见性与作者切换

- 对应：REQ-006、REQ-008、REQ-009、REQ-010、REQ-011、AC-003、AC-004、AC-005、AC-006
- 依赖：无
- 修改：
  - `backend/paideia-sandbox/.../dataset/SqlDatasetUploadService.java`：`upload(userId, name, sql, visibility)`；`private` → source=private/status=draft，`public` → community/published；非法值 400。
  - `backend/paideia-sandbox/.../dataset/SqlDatasetMapper.java`：`demoteToPrivate(key, now)`。
  - `backend/paideia-sandbox/.../web/SqlDatasetController.java`：`UploadRequest` 加 `visibility`；新增 `POST /{key}/visibility`；注入 `SqlDatasets` 用于内置判断。
  - `backend/paideia-sandbox/.../dataset/SqlAiService.java`：生成数据集固定传 `private`。
- 接口：见 design「上传」「切换可见性」。
- 测试：`SqlDatasetUploadServiceTest`（新增/扩展：private、public、非法值）、`SqlDatasetControllerTest`（新建：作者切换、非作者 404、内置 400、taken_down 400）、`SqlAiServiceTest`（4 参 upload）。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-sandbox -am test` → 0 failed。

### TASK-002 | done | 后端：上传者目录与数据集目录

- 对应：REQ-012、REQ-013、REQ-015、REQ-016、AC-007、AC-010
- 依赖：TASK-001
- 新建：
  - `backend/paideia-account/.../AccountDirectory.java`（`Map<Long,String> usernames(Collection<Long> ids)`）
  - `backend/paideia-account/.../internal/AccountDirectoryImplementation.java`
  - `backend/paideia-account/.../internal/user/UserMapper.java` 加 `findUsernames(ids)`（`<script>` + `foreach`；空集合直接返回空）
  - `backend/paideia-sandbox/.../DatasetOwnerNames.java`（端口 + `NONE`）
  - `backend/paideia-sandbox/.../dataset/SqlDatasetCatalogService.java`
  - `backend/paideia-app/.../DatasetOwnerNamesConfiguration.java`（`@Bean` 桥接）
- 修改：
  - `SqlDatasetController`：新增 `GET /catalog`，调用 catalog service。
  - `SqlDatasetAdminController`：`CommunityView` 加 `ownerName`；构造器注入 `DatasetOwnerNames`。
  - `SqlDatasetAdminControllerTest`：构造器补参数。
- 接口：见 design「目录」「管理端」。
- 测试：`SqlDatasetCatalogServiceTest`（内置+我的+公开、隐藏集不在、别人的私有集不在、注销作者文案、字段白名单）、`SqlDatasetAdminControllerTest`（ownerName）。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-sandbox -am test` → 0 failed；`scripts/test-db.sh mvn -B -pl paideia-account -am test` → 0 failed。

### TASK-003 | done | 后端：AI 只读工具与组接线

- 对应：REQ-001、REQ-002、AC-001、AC-009
- 依赖：无
- 修改：
  - `backend/paideia-ai/.../tools/AgentToolBridge.java`：`ASSISTANT` 的 toolsets 加 `"planner"`；更新注释（生成类工具只读、不落库）。
  - `backend/paideia-sandbox/.../dataset/SqlDatasetTools.java`：新增 `ReadDatasetSql`（`sql_read_dataset`，`ToolScope.READ`，截断 20,000 字符，隐藏集拒绝）。
  - `frontend/packages/assistant/src/page-context.ts`：SQL 工作台上下文加两句工具使用说明（新建用 `planner_build_dataset`；改现有先 `sql_read_dataset`；保存由用户点）。
- 测试：`AgentToolBridgeTest`（ASSISTANT 含 planner）、`AgentToolBridgeIntegrationTest.toolsCannotWrite`（清单加两个只读工具）、`McpToolSnapshotTest`（sql 组 1→2）、`SqlDatasetToolsTest`（读 SQL、隐藏集、无权、只读标记）、`page-context.test.ts`（说明出现）。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-ai -am test` → 0 failed；`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed。

### TASK-004 | done | 前端：client + 目录弹窗 + 选择器入口

- 对应：REQ-012、REQ-014、AC-004、AC-007
- 依赖：TASK-002
- 修改：
  - `frontend/packages/knowledge/src/client.ts`：`uploadSqlDataset(name, sql, visibility)`；`datasetCatalog()`；`setDatasetVisibility(key, isPublic)`；`DatasetCatalogItem` 类型。
  - `frontend/apps/app/src/components/DatasetCatalogDialog.tsx`：新建弹窗；分组「公开 / 我的」；每行显示可见性徽章、表数/行数、上传者；「使用」与作者切换按钮；切私有二次确认。
  - `frontend/apps/app/src/pages/SqlPracticePage.tsx`：选择器旁「浏览数据集」；弹窗状态；选中后关闭。
- 测试：`frontend/packages/knowledge` 的类型/客户端测试（若已有 mock 约定）；`DatasetCatalogDialog` 组件测试（mock query：公开集显示上传者、我的集显示切换、空态）。
- 验证：`pnpm --filter @paideia/knowledge test`、`pnpm --filter @paideia/app typecheck` → 0 failed。

### TASK-005 | done | 前端：数据集面板两模式与预览

- 对应：REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、AC-002、AC-003、AC-008、AC-011
- 依赖：TASK-001、TASK-004
- 修改：
  - `frontend/packages/sql-workbench/src/dataset-schema.ts`：`parseCreateTableNames(sql)`（只认 `CREATE TABLE` 字面量；shortcut 注释）。
  - `frontend/packages/sql-workbench/src/dataset-schema.test.ts`：标准生成 SQL、注释/无建表、大小写。
  - `frontend/apps/app/src/pages/SqlPracticePage.tsx`：
    - 面板加模式切换「新建 / 基于当前数据集改」；
    - 新建：主题 + 「让 AI 设计」+ 「直接生成并保存」；
    - 改现有：当前数据集表清单 + 「每张表加 N 行」+ 每表按钮 → `ask(...)`；
    - 名字输入、公开/私有选择（默认私有）、预览「预计 N 张表：…」；
    - `saveDataset` 传 visibility；成功后刷新目录 + status，选中新 key；
    - 「直接生成并保存」也传 visibility。
- 接口：`POST /sql-datasets` 的 `visibility`；`generate` 也加 `visibility`（TASK-001 一并改）。
- 测试：`dataset-schema.test.ts`；`SqlPracticePage` 现有 e2e 仍绿；新增 e2e：mock status + schema，打开面板，断言默认私有、预览表数、保存请求体带 `visibility:'private'`。
- 验证：`pnpm --filter @paideia/sql-workbench test`、`pnpm --filter @paideia/app typecheck`、`playwright test e2e/sql-practice.spec.ts`。

### TASK-006 | done | 前端：助手「存为数据集」通道

- 对应：REQ-002、AC-002、AC-009
- 依赖：TASK-003、TASK-005
- 修改：
  - `frontend/packages/assistant/src/context.tsx`：`pendingDatasetSql` + `applyDatasetSql` + `clearPendingDatasetSql`（与 `pendingSql` 同形）。
  - `frontend/packages/assistant/src/conversation-panel.tsx`：SQL 工作台场景下，在「填入编辑器」旁加「存为数据集」；取第一个 SQL 代码块，没有则提示。
  - `frontend/apps/app/src/pages/SqlPracticePage.tsx`：消费 `pendingDatasetSql` → 填 SQL、开面板、切新建、滚动到面板。
- 测试：`conversation-panel.test.tsx`（SQL 工作台显示按钮；点击调用 `applyDatasetSql`；无代码块提示）；`SqlPracticePage` e2e：点击后面板出现且 SQL 填入，**不发保存请求**。
- 验证：`pnpm --filter @paideia/assistant test`、`pnpm --filter @paideia/app typecheck`。

### TASK-007 | done | 验证与文档

- 对应：AC-001…AC-011
- 依赖：TASK-001…006
- 内容：
  - 后端切片：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`（约 88 秒 / 410 条）+ app 门禁 4 条。
  - 前端：`pnpm --filter @paideia/knowledge test`、`@paideia/assistant`、`@paideia/sql-workbench`、`pnpm --filter @paideia/app typecheck`。
  - e2e：`playwright test e2e/sql-practice.spec.ts`（含新增目录/面板用例）。
  - 手工：两个账号验证 AC-003/004/005（私有不可见、公开可见、切回私有不可见）。
  - 更新 `.agent/history/updates.md`；按需更新 `docs/architecture`（本项不改模块边界/表结构，预计不需要）。
- 产出：`testing/verification.md`（命令、退出码、结果、证据位置）；`validate` 通过（WORK-053 的历史证据缺口单独记录，不混入本项）。
