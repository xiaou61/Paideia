---
artifact: tasks
work_id: WORK-004
work: 知识库
status: approved
created: 2026-10-10
updated: 2026-10-10
approved_by: xiaou61
approved_at: 2026-10-10 14:09:08 +0800
approver_role: CTO
---

# 知识库实施任务

## 依据

- `.agent/changes/WORK-004-知识库/requirements.md`（REQ-001…012、AC-001…013）
- `.agent/changes/WORK-004-知识库/proposal.md`（推荐方案 1—6）
- `.agent/changes/WORK-004-知识库/design.md`（模块结构、DDL、端点和错误映射、安全规则、测试策略）

## 全局约束

- 迁移只在 `backend/paideia-app/src/main/resources/db/migration/`，**编号取实施时目录内的下一个可用值，不要写死 `V2`**（工作区已有另一会话的 `V2__pin_account_table_collation.sql`，同号会让 Flyway 失败）；e2e 种子在 `db/devdata/`，**不得进入生产迁移目录**。
- 不新建 MyBatis XML；mapper 是 `@Mapper` 接口 + 文本块 SQL；实体是可变类（`useGeneratedKeys` 回写主键）。
- 时间存 UTC；标识符小写 snake_case；SQL 只在 mapper 中。
- 每个任务用真实的窄检查验证，不靠"看代码没问题"。
- 前端新增含 `.tsx` 的包必须同步在 `packages/ui/src/styles/globals.css` 补 `@source`。
- 上传目录来自配置、位于仓库之外、不被 Git 跟踪；**上传的二进制不得进入仓库**。
- 每个任务结束时 `cd backend && mvn -B -DskipTests package` 与 `cd frontend && pnpm -r typecheck` 必须通过（涉及前端的任务）。

## 任务

### TASK-001 | done | 后端模块骨架与五张表迁移

- 对应：`REQ-001`、`REQ-002`、`AC-001`
- 依赖：无
- 修改：`backend/pom.xml`（`<modules>` 与 `<dependencyManagement>` **两处**）、`backend/paideia-app/pom.xml`（加依赖）
- 新建：`paideia-knowledge/pom.xml`、`paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/{package-info.java,KnowledgeApi.java}`、`paideia-app/src/main/resources/db/migration/V3__create_knowledge_tables.sql`、`paideia-app/src/main/resources/db/devdata/V951__seed_knowledge.sql`
- 步骤：
  1. 按 `paideia-account/pom.xml` 建模块 pom：依赖 `paideia-platform`、`paideia-persistence`、`paideia-security`。
  2. 根 pom 的 `<modules>` 与 `<dependencyManagement>` 各加一行；`paideia-app/pom.xml` 加 `<dependencies>` 项。
  3. **先列 `db/migration/` 取下一个可用编号**，再写建表迁移：**五张表**的 DDL 照 `design.md` 的「持久化与迁移」逐字落地（含 `FULLTEXT … WITH PARSER ngram`、关系表两端 `ON DELETE CASCADE`、`CHECK` 自指约束、`knowledge_files` 的 `CHAR(36)` UUID 主键、`knowledge_self_assessments` 的 `UNIQUE(user_id, entry_id)`）。**实际取到 `V3`**——`V2` 已被账号表的字符集迁移占用。
  4. 写 `V951__seed_knowledge.sql`：两级分类、三条已发布条目、一条草稿条目、两条关系（前置 + 关联）。
- 验证：**构建与迁移都已真跑**——`mvn -B verify -Dspring-boot.repackage.skip=true` → **BUILD SUCCESS**，8 个模块全 SUCCESS（含新建的 `paideia-knowledge`），**76 条测试 0 失败**（`ModularityTest` 通过说明新模块已纳入边界校验）。迁移在 MySQL 8.0 上真实执行：日志 `Migrating schema 'paideia_test' to version "3 - create knowledge tables"` → `Successfully applied 1 migration … now at version v3`，即五张表的 DDL 有效（含 ngram 全文索引、`CHECK` 约束与两处外键）。
- **本任务尚未收口的三项**：① 未以 `local` profile 启动核对 `/actuator/health`（本机 8080 被另一会话的进程占用，且启动会向 dev 库应用迁移，未擅自执行）；② e2e 种子 `V951` **尚未被执行**——它只在 e2e 的 Flyway locations 下加载，将由 TASK-009 的 e2e 覆盖；③ 设计里列的 `internal/KnowledgeApiImplementation.java` **未创建**——`KnowledgeApi` 当前没有任何方法，为它写实现类纯属仪式，等第一个真实方法出现时再建。

### TASK-002 | done | 只读浏览：分类树、条目列表与详情（含 md 渲染与阅读辅助）

- 对应：`REQ-002`、`REQ-004`、`REQ-008`、`REQ-009`、`REQ-010`、`AC-002`、`AC-009`、`AC-010`
- 依赖：TASK-001
- 新建：`knowledge/internal/{category,entry}/`（实体、mapper、service、`CategoryTree`）、`knowledge/internal/web/KnowledgeReadController.java` 及 DTO；`frontend/packages/knowledge/src/{types.ts,client.ts,markdown-body.tsx,heading-slug.ts,entry-toc.tsx,image-lightbox.tsx,index.ts}`、`packages/knowledge/src/heading-slug.test.ts`、`apps/app/src/pages/{KnowledgeListPage.tsx,KnowledgeEntryPage.tsx}`、`apps/app/src/components/CategoryTree.tsx`、`apps/app/e2e/knowledge.spec.ts`
- 修改：`apps/app/src/router.tsx`（两条 Hash 路由）、`packages/ui/src/styles/globals.css`（`@source` 补 `packages/knowledge/src`）、`apps/app/package.json`（加 `@paideia/knowledge` 依赖）、`packages/knowledge/package.json`（加 `react-markdown`、`remark-gfm`）
- 步骤：
  1. 实体与 mapper：`knowledge_categories` 全量查询 + 内存装树；`knowledge_entries` **只查 `status='published'`** 的分页列表与详情（列表不查 `body`）；详情查询同时取**同分类内的相邻条目**（上一篇／下一篇）。
  2. `GET /categories`、`GET /entries`、`GET /entries/{id}` 三个只读端点；草稿按 404 处理。详情响应含 `prev`／`next` 与 `dependents`（反向链接）。
  3. `packages/knowledge`：`createKnowledgeApi(client)` 包装 `requestEnvelope`；`MarkdownBody` 用 `react-markdown` + `remark-gfm`，`h1..h3` 走自定义组件用 `heading-slug` 生成 id（**重复标题追加序号**），`img` 包成 `figure` + 替代文本作图注、点击开 `Dialog` 放大。
  4. `apps/app`：列表页（可展开／折叠并按名称筛选的分类树 + 条目列表 + 空态）、详情页（标题 + 目录锚点 + `MarkdownBody` + 上一篇／下一篇 + 反向链接列表）。
  5. 单测 `heading-slug.test.ts`：中文标题、标点、**重复标题**都要产出稳定且不冲突的 id。
  6. e2e：登录种子学习者 → 打开知识库 → 展开分类并筛选 → 打开一条 → 点目录跳到某章节 → 断言上一篇／下一篇可达、反向链接非空。
- 验证：`cd frontend && pnpm -r typecheck && pnpm -r test` 通过；`cd backend && mvn -B verify` 通过；`pnpm test:e2e` 中 `apps/app/e2e/knowledge.spec.ts` 通过。

### TASK-003 | done | 中文关键词检索

- 对应：`REQ-005`、`AC-003`
- 依赖：TASK-002
- 新建：`knowledge/internal/search/SearchQuery.java`、`knowledge/src/test/java/…/search/SearchQueryTest.java`
- 修改：`EntryMapper` 加 `MATCH … AGAINST` 与 `LIKE` 两条查询、`GET /entries` 接 `q`、`KnowledgeListPage.tsx` 加搜索框
- 步骤：
  1. `SearchQuery` 判定：归一化后长度 ≥ 分词长度走全文索引，否则回退 `LIKE`；纯函数，先写单测。
  2. mapper 两条查询都**带 `status='published'` 条件**。
  3. 列表页加搜索输入（受控 + 防抖）与"无结果"空态。
  4. 集成测试：用中文关键词（≥2 字）断言命中；用单字断言走回退路径仍有结果；断言草稿不出现在结果里。
- 验证：`SearchQueryTest` 通过；`KnowledgeIntegrationTest` 的检索用例通过；e2e 中搜索断言通过。

### TASK-004 | done | 条目关系与邻域视图

- 对应：`REQ-003`、`AC-004`
- 依赖：TASK-002
- 新建：`knowledge/internal/relation/`（实体、mapper、service）、`GET /entries/{id}/relations`、`apps/app/src/components/KnowledgeNeighborhood.tsx`（手写 SVG）
- 修改：`KnowledgeEntryPage.tsx` 挂邻域视图与前置／相关列表
- 步骤：
  1. 关系读取返回三组：前置（入边 `prerequisite`）、后继（出边 `prerequisite`，即"这条是谁的前置"）、相关（`related`，按无向查双向）。
  2. 邻域 SVG：当前条目居中，三组分组围绕，连线按类型区分线型，节点可点跳转。
  3. e2e：打开含关系的种子条目，断言前置列表与 SVG 节点数量。
- 验证：集成测试断言三组关系内容正确；e2e 通过。

### TASK-005 | done | 角色路径规则与管理端写端点

- 对应：`REQ-006`、`AC-006`
- 依赖：TASK-001
- 修改：`backend/paideia-security/src/main/java/io/github/xiaou61/security/SecurityConfiguration.java`（在 `anyRequest()` 前插入 `.requestMatchers("/api/v1/knowledge/admin/**").hasRole("ADMIN")`）
- 新建：`knowledge/internal/{category,entry,relation}` 的写方法、`knowledge/internal/web/KnowledgeAdminController.java`、`KnowledgeAdminIntegrationTest.java`
- 步骤：
  1. **先改安全规则再写接口**：顺序必须在 `/api/v1/knowledge/**` 与 `anyRequest()` 之前。
  2. 分类：建／改／删；删前判子分类与条目引用，命中返回 409 且消息区分两种原因；换父时校验不成环。
  3. 条目：建／改（含 `status`）／删；状态由非发布变发布时写 `published_at`（UTC）；`status` 与 `title` 做参数校验。
  4. 关系：建／删；自指返回 400，重复返回 409（唯一索引兜底并转成业务错误）。
  5. 集成测试造数据**只能走 HTTP**（不得引用 `knowledge.internal.*`，否则 `ModularityTest` 会失败）：管理员令牌建分类与条目，学习者令牌断言 403，无令牌断言 401。
- 验证：`KnowledgeIntegrationTest` 三态鉴权用例通过；`ModularityTest` 通过；`mvn -B verify` 全绿。

### TASK-006 | done | 管理端路由、知识库管理区与草稿预览

- 对应：`REQ-006`、`REQ-011`、`AC-005`、`AC-011`
- 依赖：TASK-005
- 修改：`apps/admin/package.json`（加 `react-router` 与 `@paideia/knowledge`）、`apps/admin/src/main.tsx`（改用 `RouterProvider`）、`apps/admin/src/pages/AdminHomePage.tsx`（保留为首页，管理区入口指向新页）
- 新建：`apps/admin/src/router.tsx`、`apps/admin/src/pages/KnowledgeAdminPage.tsx`、`apps/admin/e2e/knowledge.spec.ts`
  - **该页面已于 2026-10-12（用户验收后）拆成 `pages/KnowledgeCategoriesPage.tsx` 与 `pages/KnowledgeEntriesPage.tsx`**，共用件在 `apps/admin/src/knowledge-shared.tsx`，侧栏改为可展开父菜单；本任务当时建的文件名保留在历史里，当前形态见 `design.md` 同段的后续说明与 `testing/report.md` 的「验收后的界面重建」。
- 步骤：
  1. 引入 Hash 路由，`/` 保持现有单屏行为（现有 `admin.spec.ts` 的 `admin-console` 断言必须继续通过）。
  2. 分类管理：树 + 新建／改名／删除对话框。
  3. 条目管理：列表（含草稿筛选）+ 编辑对话框（标题、分类、纯文本域正文与实时预览、保存/发布）。
  4. 关系管理：在条目编辑里选择目标条目与类型。
  5. **草稿预览**：用 `GET /admin/entries/{id}`（含草稿）驱动一个预览视图，**复用学习者端的 `MarkdownBody` 与关系区块**——预览之所以可信就在于它走同一段渲染代码，不要另做一套。
  6. e2e：用种子管理员登录 → 新建分类与条目 → **先预览草稿确认渲染结果** → 发布 → 断言列表中状态变为已发布。
- 验证：`pnpm -r typecheck` 通过；`pnpm test:e2e` 中 `apps/admin/e2e/admin.spec.ts` 与 `knowledge.spec.ts` 均通过；`KnowledgeIntegrationTest` 中断言 `/admin/entries/{id}` 对学习者 403、匿名 401。

### TASK-007 | done | 本地文件存储、上传与正文引用（含图注与放大）

- 对应：`REQ-007`、`REQ-009`、`AC-008`
- 依赖：TASK-005（需要管理员路径规则）、TASK-006（管理端界面挂上传入口）
- 新建：`knowledge/internal/file/{FileStorage.java,LocalFileStorage.java,StoredFile.java,StoredFileMapper.java,FileService.java}`、`knowledge/internal/web/KnowledgeFileController.java`、`V2` 追加 `knowledge_files` 建表语句（若 TASK-001 已含则只核对）
- 修改：`SecurityConfiguration.java`（加 `GET /api/v1/knowledge/files/**` 的 `permitAll`，**限定 GET**）、配置文件加上传根目录项、`packages/knowledge/src/markdown-body.tsx`（把 `/api/v1/knowledge/files/…` 重写为带 baseUrl 的绝对地址）、`KnowledgeAdminPage.tsx` 的条目编辑器加上传与"复制引用"按钮
- 步骤：
  1. 先写 `FileStorage` 端口与 `LocalFileStorage`（根目录来自配置，缺失或不可写时在启动或首次使用时明确报错）。
  2. 上传端点：**先校验后落盘**——扩展名白名单 + MIME 嗅探双重校验、拒 SVG、10 MB 上限；落盘成功再插表；插表失败则删除刚写的文件。
  3. 读取端点：按 id 查表拼路径（不接受请求里的路径片段），用嗅探到的内容类型 + `nosniff`，PDF 用 `inline`。
  4. 前端：编辑器上传后把 `/api/v1/knowledge/files/<uuid>` 写进正文；`MarkdownBody` 渲染时补 baseUrl。
  5. 集成测试：管理员上传成功并匿名 GET 到字节；非法类型、SVG、超限分别被拒；id 为 UUID 形状；只读目录下上传返回 500 且日志含路径。
- 验证：`mvn -B verify` 通过（含上传用例）；`pnpm -r test` 通过；e2e 中"管理员插入图片 → 学习者端可见"通过。

### TASK-008 | done | 学习者自评掌握度

- 对应：`REQ-012`、`AC-012`、`AC-013`
- 依赖：TASK-005
- 新建：`knowledge/internal/assessment/{SelfAssessment.java,SelfAssessmentMapper.java,SelfAssessmentService.java}`、`knowledge/internal/web/SelfAssessmentController.java`、`paideia-app/src/test/java/io/github/xiaou61/KnowledgeSelfAssessmentIsolationTest.java`
- 修改：建表迁移**追加** `knowledge_self_assessments`（与 TASK-001 是同一个迁移文件，不新开迁移）、`KnowledgeReadController` 的详情响应加 `selfAssessment`、`packages/knowledge/src/{types.ts,client.ts,self-assessment.tsx,index.ts}`、`KnowledgeEntryPage.tsx` 挂按钮、`apps/app` 新增「我的标记」页与路由
- 步骤：
  1. 建表：`knowledge_self_assessments`，`UNIQUE(user_id, entry_id)`、`entry_id` 外键 `ON DELETE CASCADE`。
  2. 三个端点：`PUT`／`DELETE /entries/{id}/self-assessment`、`GET /self-assessments?level=`。`user_id` **只取自令牌**，路径与请求体里没有用户标识；只允许标记**已发布**条目（草稿返回 404）。
  3. 详情响应加 `selfAssessment` 字段，**只可能是自己的**。
  4. 前端：详情页「懂了／还不懂」按钮（再点一次取消）、列表页的「我的标记」入口与状态筛选。
  5. 隔离测试：用户 A 标记后，用户 B 的列表与**详情响应**都读不到；对未发布条目标记返回 404；同一用户重复标记是 upsert 而不是插两行。
- 验证：`mvn -B verify` 通过（含隔离用例）；`pnpm -r test` 通过；e2e 中标记后刷新仍在。

### TASK-009 | done | 学习者端可见性打通与全量回归

- 对应：`AC-002`、`AC-005`、`AC-007`、`AC-008`、`AC-009`、`AC-012`
- 依赖：TASK-003、TASK-004、TASK-006、TASK-007、TASK-008
- 修改：`apps/app/e2e/knowledge.spec.ts`（补"管理员发布后学习者可见"的跨端断言）
- 步骤：
  1. e2e 顺序执行：管理员发布一条新条目 → 学习者端检出该条目；同时断言草稿不出现。
  2. 跑全量回归：四个应用构建、`pnpm -r typecheck`、`pnpm -r test`、`pnpm test:e2e`、`mvn -B verify`。
  3. 跑既有边界检查：`packages/core/src/workspace-boundaries.test.ts`、`packages/ui/src/tokens.test.ts`、`packages/ui/src/styles/source-coverage.test.ts`、`packages/ui/src/theme/contrast.test.ts`。
  4. 桌面产物确认不含管理端与知识库管理区界面。
  5. **四项反向验证**（只断言"功能可用"是不够的，这些检查必须能咬）：注释掉管理员角色规则 → 三态用例变红；去掉 `WITH PARSER ngram` 重建索引 → 中文检索用例变红；删掉 `globals.css` 里 `packages/knowledge/src` 那行 `@source` → `source-coverage` 变红；**去掉自评查询里的 `user_id` 过滤 → 隔离用例变红**。每次验证后恢复。
- 验证：上述命令全部通过；结果写入 `testing/report.md`。

## 实施进展（2026-10-10，TASK-001—009 全部完成）

九片都有可复查证据。命令与结果如下（测试库口令从被忽略的 `backend/config/application-local.yml` 读入环境变量，未回显）：

| 片 | 证据 | 结果 |
| --- | --- | --- |
| TASK-001 | `mvn -B verify` | BUILD SUCCESS，8 模块全过；日志 `Successfully applied 1 migration … now at version v3`——五张表 DDL 在 MySQL 8.0 上有效。**e2e 种子 `V951` 也真实执行过**，原先"种子未验证"的缺口已闭合 |
| TASK-002 | `KnowledgeReadIntegrationTest`（12 条）+ `CategoryTreeTest`（7）+ `heading-slug.test.ts`（9）+ `pnpm -r typecheck` | 分类树嵌套、已发布过滤、子分类浏览、草稿 404、md 渲染与锚点 id 全部通过；前端 9 个包类型检查通过 |
| TASK-003 | `SearchQueryTest`（4 条）+ 集成测试检索用例 | 中文长查询走 `FULLTEXT + ngram` 命中、单字查询走 `LIKE` 回落仍命中、检索不返回草稿、`%` 被当字面量转义 |
| TASK-004 | 集成测试的关系用例（两个方向）+ `KnowledgeNeighborhood` | 前置与反向链接**两个方向都断言**（互为镜像的入边/出边极易写反） |
| TASK-005 | `KnowledgeAdminIntegrationTest`（9 条） | **匿名 401 / 学习者 403 / 管理员放行**三态；录入草稿→学习者不可见→发布→可见；删分类（有子或有条目）409、换父成环 400、自环 400、重复关系 409、非法状态 400 |
| TASK-006 | 管理端 e2e（7 条，含既有 `admin.spec.ts` 的 5 条） | 管理端引入 Hash 路由并把"门"集中到 `RequireAdmin`（五个既有断言点原样保留）；管理区可建分类、建/改/删条目、发布、建/删关系；**草稿预览复用 `MarkdownBody`** |
| TASK-007 | `KnowledgeFileIntegrationTest`（7 条）+ `LocalFileStorageTest`（6）+ `MediaTypeSnifferTest`（5）+ `resolveMediaUrl` 单测（6） | 匿名读成功且返回嗅探类型与 `nosniff`、学习者上传 403、SVG 拒绝、内容与扩展名不符拒绝、超限拒绝、`..` 路径被 4xx 拦住 |
| TASK-008 | `KnowledgeSelfAssessmentIntegrationTest`（7 条） | 标记只对自己可见（**列表与详情两条读路径都断言**）、草稿不可标记 404、非法值 400、取消幂等、重复标记是 upsert |
| TASK-009 | 全量回归 + 两端 e2e + 反向验证 | 后端 **124 条 0 失败**（91 秒）、前端单测 63 条、e2e **23 条**（学习者端 11／管理端 7／展览页 5）。三项反向验证**咬住**，`ngram` 那项**未咬住**——见 `testing/report.md` 的「反向验证的逐条结论」与「剩余风险」 |

**实施期间的四处偏离**（都是简化、不改变范围，已记入更新历史）：

1. **关系数据并入详情响应**，没有另开 `/entries/{id}/relations` 端点——邻域视图本来就要先取详情，一个请求能拿到的就不该拆成两个。
2. **管理端分类树不带"每分类条目数"**（设计里写了）。与 AC-005 无关；要用时把 group-by 加上即可。
3. **`KnowledgeApi` 当前为空接口**且未建实现类（设计里列了 `KnowledgeApiImplementation`）：没有跨模块调用者，为它写实现类是纯仪式。
4. **管理端详情的草稿预览响应多带 `relations`（含 id）**：学习者侧那三个关系视角是给人读的、不带 id，而管理端要删关系必须有 id；两者用途不同，各用一条查询而不是硬凑成同一个形状。

**报告状态**：`testing/report.md` 为 **`passed`**。13 条 AC 与三项反向验证都有命令 + 退出码 + 证据位置；桌面安装包也真实构建并核对过（`pnpm -w build:desktop`，退出码 0、59 秒——Electron 已在本机缓存，远快于首次的约 20 分钟），安装包内 `app.asar` 里「知识库管理」出现 **0** 次、「我标记过的内容」**2** 次（反向对照）。唯一遗留是 `ngram` 那项反向验证**未咬住**，已作为**剩余风险**列出——它不构成立即风险（索引功能正常），但意味着设计里那条理由目前缺少证据。

## 完成条件

- 十二个 `REQ` 全部有对应实现，十三条 `AC` 全部有可复查证据（命令 + 退出码 + 证据位置）。
- 管理端写接口的三态鉴权有自动化检查，且**顺序错误会导致该检查失败**（即测试真的能抓住路径规则写反）。
- 新增模块通过 `ModularityTest`；新增前端包被 `source-coverage` 覆盖。
- 迁移与种子遵循目录约定：生产迁移目录中不含 e2e 种子；上传目录不进仓库。
- **文件读取端点是全项目唯一的免鉴权接口**，测试必须同时证明"匿名能读"与"写仍需管理员"。
- **自评端点全项目唯一一处学习者可写的数据**：测试必须证明它写的是自己的、读的也只是自己的；详情接口不得泄露他人的标记。
- 全部任务 `done`，`testing/report.md` 为 `passed` 且无未验证项（若存在环境性缺口，如实标注）。
