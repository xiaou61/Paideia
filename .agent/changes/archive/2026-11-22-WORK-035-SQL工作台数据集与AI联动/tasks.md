---
artifact: tasks
work_id: WORK-035
work: SQL 工作台、数据集与 AI 联动
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-006, WORK-007, WORK-010, WORK-013, WORK-015, WORK-019, WORK-023, WORK-032, WORK-033]
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 10:02:57 +0800
approver_role: CTO
---

# 任务 · SQL 工作台、数据集与 AI 联动

**排序原则**：S1 先把工作台立起来（不碰数据表），S2 才动数据集存储与权限，S3 才接 AI，S4 最后收口配额。**S2 是唯一有真实安全风险的阶段**，它开始前 S1 必须已经合并。

**全局约束**：

- 内置数据集永远留在 classpath，不依赖对象存储。
- `SqlDatasets.sql(key)` 旧签名**直接删除**，不重载。
- 上传与 AI 生成的数据集**必须干跑通过**才允许保存。
- 编辑器与图表库**只在 SQL 页懒加载**，不得进主 chunk。
- 测试库一律走 `scripts/test-db.sh`。

## 任务状态

| 任务 | 名称 | 阶段 | 状态 |
| --- | --- | --- | --- |
| T-01 | 编辑器实测与懒加载守卫 | S1 | done |
| T-02 | 结果表增强 | S1 | done |
| T-03 | schema 接口与补全数据源 | S1 | done |
| T-04 | 表结构侧栏与 ER 图 | S1 | done |
| T-05 | 执行计划可视化 | S1 | done |
| T-06 | 结果图表 | S1 | done |
| T-07 | 数据集迁移与 Mapper | S2 | done |
| T-08 | 数据集解析改造（安全边界） | S2 | done |
| T-09 | 上传与干跑 | S2 | done |
| T-10 | 社区发布、举报与下架 | S2 | done |
| T-11 | 管理端两页 | S2 | done |
| T-12 | AI 现场出题 | S3 | done |
| T-13 | AI 生成数据集 | S3 | done |
| T-14 | 载入关卡题 | S3 | done |
| T-15 | 助手页面上下文与填回 | S3 | done |
| T-16 | `sql_describe_dataset` 只读工具 | S3 | done |
| T-17 | 每人并发 1 与日上限调整 | S4 | done |
| T-18 | 验收报告 | — | done |

## S1 · 工作台本体

### T-01 | 编辑器实测与懒加载守卫

- 文件：`frontend/packages/sql-workbench/`（新建包）、`SqlPracticePage.tsx`。
- 内容：引入 CodeMirror 6 + `@codemirror/lang-sql`，封装成受控组件；页面用 `lazy()` 引入。
- **先做这一步的理由**：体积与 Vite 8 兼容性是两个未验证的假设，它们一旦不成立整个编辑器方案要重选。先量再写。
- **实测已完成（2026-10-23）**：精简版 417.1 kB min / **137.2 kB gzip**；`basicSetup` 全套 436.5 / 143.6；语言包单独占 291.2 kB min。门槛因此定为 **gzip ≤ 160 kB**（原写的「400 kB gzip 前」是没量就定的数字，按 min 口径卡会逼着砍用户感觉不到的功能）。
- 验收：
  - 构建产物里 CodeMirror 在独立 chunk；写一个脚本读 `dist/assets/*.js` 的主 chunk，断言它**不** import 编辑器 chunk（AC-001）。
  - 编辑器 chunk **gzip ≤ 160 kB**。
  - typecheck 通过；编辑器可输入、行号与括号匹配生效；配色用项目令牌（不引 CodeMirror 自带主题）。

### T-02 | 结果表增强

- 文件：`sql-workbench` 包内的 `ResultTables.tsx`。
- 内容：按 `statementIndex` 分卡；列排序（点表头）；数值列右对齐；`\u0000NULL` 渲染为弱化文字；超过 200 行分页；复制为 CSV。
- 验收：用 `orders-public` 跑一条多语句 SQL，得到多张卡；`bob` 的空串与 `cid` 的 NULL 在界面上可区分（AC-002）。

### T-03 | schema 接口与补全数据源

- 文件：`SqlDatasets` 增加 `schema(String key)`；`GET /api/v1/sql-datasets/{key}/schema`；`sandbox/datasets/*.schema.json`（内置集快照，脚本生成一次提交）。
- 内容：内置数据集的 schema 静态文件 + 一个生成脚本；接口返回表/列/类型/主键/可空。
- 验收：接口返回 `orders-public` 的四张表与列定义；编辑器在 `WHERE ` 后能补出列名。

### T-04 | 表结构侧栏与 ER 图

- 文件：`sql-workbench` 包的 `SchemaSidebar.tsx`、`ErDiagram.tsx`。
- 内容：侧栏列出表与列；ER 图用纯 SVG 或既有 `sigma`/`graphology`（已在 app 依赖里）绘制。无外键时只画表不画连线，并提示原因。
- 验收：侧栏的表/列数与 `orders-public` 实际一致（AC-003）；无外键时给出说明而不是空图。

### T-05 | 执行计划可视化

- 文件：`SandboxController`（`explain` 字段）、`DockerCommand`（EXPLAIN 分支）、`sql-workbench` 的 `ExplainTree.tsx`。
- 内容：`/sandbox/run` 加 `explain: boolean`；为 true 时容器只跑 `EXPLAIN FORMAT=TREE`；前端渲染树，节点标「走索引」/「全表扫描」。
- **解析验证已完成（2026-10-23，真实镜像 + 生产加固参数）**：`SqlResultCodec` **能正确解析**，不用改解析器。但输出形态与预期不同——见下面两条，它们改变实现方式。
- **必须按这两条实现**：
  1. `EXPLAIN` 的树在**一个字段值里**（1 列 × 1 行，多行用 `\n` 连接），**不在结果集结构里**。所以不能用结果表组件渲染，必须专门的 `ExplainTree` 按 `->` 与缩进切节点。
  2. 「走索引 vs 全表扫」是**字符串匹配**（含 `Table scan` 即全表扫；含 `Index lookup`/`Index range scan`/`Index scan` 即走索引），且**按节点标注**而不是整条查询——一条查询可能 Filter 在上、Table scan 在下。这个匹配逻辑单独成纯函数并单测，不散在组件里。
- 验收：一句走索引的 SQL 与一句全表扫描的 SQL，视图分别标出两类（AC-004）；匹配函数有单测覆盖两种正例与「多层混合」一种。
- **已知风险**：探针里用 `--memory=512m` 时 mysqld 被 OOM 杀掉，1024m 才起来；线上同为 512m 却能跑，差异是探针在同一次运行里既 `cp -a` 整个 datadir 又立刻启动。若加 `explain` 分支后偶发 OOM，先怀疑这个峰值而不是 SQL 本身。

### T-06 | 结果图表

- 文件：`sql-workbench` 包的 `ResultChart.tsx`；新增图表依赖（选型在实施时定，优先体积小的）。
- 内容：手动选 X/Y 轴；柱状与折线两种；非数值 Y 轴给明确提示不渲染。
- 验收：选两列画柱状图成功；选非数值列作 Y 轴给出提示（AC-005）。

## S2 · 数据集三源

### T-07 | 数据集迁移与 Mapper

- 文件：`V20261023XXXXXX__create_sql_dataset_tables.sql`（**14 位时间戳，动 schema 前先 `git log --all --oneline -- <迁移目录>` 查是否有人改同一张表**）、`SqlDatasetMapper.java`。
- 内容：`sql_datasets` 与 `sql_dataset_reports` 两张表，字段见 design。
- 验收：独占测试库上迁移成功；`describe` 字段与 design 一致；`MigrationSeedOverlapTest` 通过（迁移不往种子表插行）。

### T-08 | 数据集解析改造（安全边界）

- 文件：`SqlDatasets.java`（**删除 `sql(String key)`**，改为 `sql(long userId, String key)`）、`SandboxController`。
- 内容：按 design 的解析顺序；「不存在」与「无权限」都返回同一文案。
- 验收（**硬门槛**）：
  - AC-007 反向测试：用户 B 用用户 A 的私人 key 运行被拒，且**响应与 key 不存在时逐字相同**。
  - 全仓 grep 确认没有旧签名残留。
  - 内置数据集行为不变（回归）。

### T-09 | 上传与干跑

- 文件：`SqlDatasetUploadService.java`、`POST /api/v1/sql-datasets`（multipart）。
- 内容：大小检查 → 语句白名单解析 → 沙箱干跑（一次性 key）→ `FileStorage.put` → 抓 schema → 落表。
- 验收：合法文件可创建并可运行；含 `TRIGGER` 被拒并说明原因；语法错误被拒（干跑失败）（AC-006）。三件事各一条测试。

### T-10 | 社区发布、举报与下架

- 文件：`SqlDatasetPublishService`、举报端点、管理端下架端点。
- 内容：私人 → 社区（`status=published`）；举报落 `sql_dataset_reports`（同一人对同一数据集只留一条，照搬 `uk_note_report_once`）；下架置 `status=taken_down`。
- 验收：发布后他人选择器可见；下架后运行与浏览都被拒（AC-008）。

### T-11 | 管理端两页

- 文件：`frontend/apps/admin/src/pages/SqlDatasetsPage.tsx`、`SqlDatasetReportsPage.tsx`。
- 内容：照搬 `KnowledgeEntriesPage` 与 `NoteReportsPage` 的形状（共享 `Table`、分页、删除二次确认）。
- 验收：两页可列出与操作；管理端 e2e 各一条。

## S3 · AI 与助手联动

### T-12 | AI 现场出题

- 文件：`QuestionGeneratorPort`（knowledge 定义、ai 实现）或复用既有 `CampItemGenerator` 的形状；`RunKind.QUESTION_GENERATE`。
- 内容：给定数据集 schema，生成题面 + 参考 SQL + 判定口径；参考 SQL 在沙箱真跑取得期望结果集；题不落库。
- 验收：审计里能看到那次参考 SQL 的运行（AC-009）；与口径不符的结果显示「不一致」。
- **已知限制写进代码注释**：题目唯一性无法自动保证，只能靠显式口径。

### T-13 | AI 生成数据集

- 文件：同上端口 + `RunKind.DATASET_GENERATE`。
- 内容：生成 DDL + INSERT；**必须干跑通过**才允许保存为私人数据集。
- 验收：导入失败时不允许保存且错误可读（AC-010）。

### T-14 | 载入关卡题

- 文件：`SqlPracticePage` 的查询参数处理；`/practice/sql?entryId=&blockKey=`。
- 内容：从知识库读该块的 `starter` 与题面填入工作台；明确提示「这一题在关卡里是要计分的」。
- 验收：从某一关的题块点进来，编辑器有起始 SQL 与题面；在工作台跑完**不写** `learning_progress`（数据库断言）。

### T-15 | 助手页面上下文与填回

- 文件：`frontend/packages/assistant/src/context.tsx`（`PageContext`、`applySql`）、`SqlPracticePage`、`conversation-panel` 的上下文展示、markdown 渲染器的「填入编辑器」按钮注入。
- 内容：按 design 的有界场景对象；只放错误原文或行数摘要，不放数据行。
- 验收：助手能引用当前草稿回答「刚才为什么报错」；界面上没有「让助手直接运行」的入口（AC-011）。

### T-16 | `sql_describe_dataset` 只读工具

- 文件：`SqlDatasetTools.java`（toolset `sql`）、`AgentToolBridge.toolsetsOf` 给 `ASSISTANT` 与 `TUTOR` 加 `sql` 组。
- 内容：给 key 返回表结构；**内部自己走一次权限判定**，不信任模型传来的 key。
- 验收：助手能查到自己的数据集；用别人的 key 时返回「没有找到」；返回内容**不含数据行**。

## S4 · 配额与并发收口

### T-17 | 每人并发 1 与日上限调整

- 文件：`RunSlot.java`（每人一层）、`SandboxProperties`（`sqlDailyLimit` 20→500）、`application.yml`、`SqlPracticePage` 的提示口径。
- 内容：两层闸；释放顺序先全局后用户；页面不常驻显示剩余次数，仅 `remaining <= 20` 时提示。
- 验收：同一用户并发两次，第二次被拒（AC-012）；`/sandbox/status` 的 `datasets` 不含隐藏集（AC-013，既有回归）。
- **回退**：这一阶段只改两个共用组件，出问题可独立回退而不影响 S1–S3。

### T-18 | 验收报告

- 文件：`testing/report.md`。
- 内容：AC-001..013 逐条证据；S1–S4 的实际验证数字。
- 验收：每条 AC 有实测证据或明确写「结构保证，未实测」及原因。

## 未覆盖（明确不做）

- SQL 逐行单步（设计 I-06）
- 题库表（REQ-012）
- 助手执行 SQL（I-09）
- 自动猜图表轴（I-11）
- 数据集版本管理
- 社区数据集的市场排序与搜索
