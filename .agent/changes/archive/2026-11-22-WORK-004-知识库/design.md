---
artifact: design
work_id: WORK-004
work: 知识库
status: approved
created: 2026-10-10
updated: 2026-10-10
approved_by: xiaou61
approved_at: 2026-10-10 14:09:07 +0800
approver_role: CTO
---

# 知识库设计

## 系统上下文

- 后端：新增模块 `paideia-knowledge`，是 `io.github.xiaou61` 的直接子包，因此被 Spring Modulith 认作一个模块。依赖 `paideia-platform`（契约）、`paideia-persistence`（MyBatis 约定）、`paideia-security`（`CurrentUser`、过滤器链）。**不依赖 `paideia-account`**。
- 数据库：MySQL 8.0.46，迁移集中在 `backend/paideia-app/src/main/resources/db/migration/`，本项新增一个建表迁移（**编号取实施时目录内的下一个可用值，不要假定是 `V2`**，理由见「持久化与迁移」）。
- 前端：新增共享包 `frontend/packages/knowledge/`（类型、客户端、md 渲染器），学习者端 `apps/app` 新增两个页面，管理端 `apps/admin` 引入 Hash 路由并新增知识库管理区。
- 权限：读端点对"已登录用户"开放；写端点只对 `ROLE_ADMIN` 开放，由 `SecurityConfiguration` 的路径规则拒绝（见「安全与权限」）。
- 文件：附件（图片、PDF）存**服务器本地目录**，业务代码只依赖 `FileStorage` 端口，本版唯一实现是本地目录。**文件读取端点是本项目唯一免鉴权的接口**，这是 `SecurityConfiguration` 里唯一新增的放行项。

## 组件与职责

### 后端 `backend/paideia-knowledge/`

```
src/main/java/io/github/xiaou61/knowledge/
  package-info.java                 模块职责与边界说明
  KnowledgeApi.java                 包根对外契约（本项无消费者时保持最小实现，不预先造方法）
  internal/
    KnowledgeApiImplementation.java @Component，包私有
    category/  Category.java  CategoryMapper.java  CategoryService.java  CategoryTree.java
    entry/     Entry.java     EntryMapper.java     EntryService.java
    relation/  Relation.java  RelationMapper.java  RelationService.java
    file/      FileStorage.java(端口)  LocalFileStorage.java  StoredFile.java  StoredFileMapper.java  FileService.java
    assessment/ SelfAssessment.java  SelfAssessmentMapper.java  SelfAssessmentService.java
    search/    SearchQuery.java                      检索串归一化与短查询回退判定（纯函数，可单测）
    web/       KnowledgeReadController.java  KnowledgeAdminController.java  KnowledgeFileController.java  SelfAssessmentController.java  dto/…
```

- `CategoryTree`：把扁平行装成树并排序的纯函数，独立可单测。
- `SearchQuery`：判断是否走全文索引的纯函数（长度 ≥ `ngram_token_size` 走 `MATCH`，否则 `LIKE`），独立可单测。
- 两个控制器分开：`KnowledgeReadController` 映射 `/api/v1/knowledge/**`，`KnowledgeAdminController` 映射 `/api/v1/knowledge/admin/**`。分开是为了让"哪些是写接口"在文件层面一眼可见，与路径规则对齐。
- 实体是**可变类**（`useGeneratedKeys` 需要回写主键），mapper 是 `@Mapper` 接口 + 文本块 SQL，**不新建 XML**。
- 对外 DTO 用控制器内的嵌套 record，不直接回实体。

### 前端

- `frontend/packages/knowledge/src/`：`types.ts`（DTO）、`client.ts`（`createKnowledgeApi(apiClient)`）、`markdown-body.tsx`（`MarkdownBody`）、`heading-slug.ts`（标题 → 锚点 id，纯函数，可单测；**重复标题追加序号**）、`entry-toc.tsx`（正文目录）、`image-lightbox.tsx`（图注 + 点击放大，复用 `@paideia/ui` 的 `Dialog`）、`self-assessment.tsx`（懂了／还不懂按钮）、`index.ts`。
- `apps/app/src/pages/KnowledgeListPage.tsx`（列表 + 分类树 + 搜索 + 「我的标记」入口）、`KnowledgeEntryPage.tsx`（详情 + md 渲染 + 目录 + 上一篇／下一篇 + 反向链接 + 自评 + 邻域）；`apps/app/src/components/CategoryTree.tsx`（展开／折叠与筛选）；`apps/app/src/router.tsx` 加两条 Hash 路由，另加一条「我的标记」。
- `apps/admin/src/router.tsx`（新建）、`apps/admin/src/pages/KnowledgeAdminPage.tsx`（分类管理、条目列表与编辑、关系编辑，**外加草稿预览**——预览复用 `MarkdownBody` 与学习者的关系区块，而不是另做一套渲染）。
  - **后续（2026-10-12，用户验收后）**：该单页已拆成 `pages/KnowledgeCategoriesPage.tsx` 与 `pages/KnowledgeEntriesPage.tsx`，路由相应变成 `/knowledge/categories` 与 `/knowledge/entries`（旧的 `/knowledge` 重定向到条目管理），共用件抽到 `apps/admin/src/knowledge-shared.tsx`，侧栏改成可展开的父菜单。验收结论不变，见 `testing/report.md` 的「验收后的界面重建」。
- 复用的既有件：`AppShell`/`PageHeader`/`Section`、`Card`/`Table`/`Dialog`/`Input`/`Label`/`Button`/`Select`/`Empty`/`Alert`/`Spinner`/`Badge`。

## 请求或事件流程

**学习者读一条条目**

1. 学习者端路由 `/knowledge/entries/:id` → `createKnowledgeApi(api).getEntry(id)`。
2. `createAuthorizedFetch` 附加 access 令牌；401 时刷新一次并重放（沿用 WORK-003）。
3. 后端 `KnowledgeReadController.getEntry` → `EntryService.findPublished(id)`；**只查 `status = 'published'`**，草稿一律按不存在处理（404）。
4. 返回 `ApiResponse<EntryDetail>`；前端用 `MarkdownBody` 渲染 `body`，并请求 `/relations` 渲染邻域。

**管理员录入并发布**

1. 管理端知识库管理区 → `POST /api/v1/knowledge/admin/entries`（携带 `status: draft`）。
2. 过滤器链先匹配 `/api/v1/knowledge/admin/**` → `hasRole('ADMIN')`：非管理员在此被拒（403），匿名 401。
3. 通过则进入 `KnowledgeAdminController` → `EntryService.create`，插入并回写自增 id。
4. 发布 = 同一个 `PUT .../admin/entries/{id}`，把 `status` 置为 `published`；服务端在状态由非发布变为发布时写 `published_at`（UTC）。
5. 发布后，学习者端列表与检索即可见（因为只读查询过滤 `published`）。

## 接口与数据

### 只读端点（已登录即可）

| 方法与路径 | 说明 | 返回 |
| --- | --- | --- |
| `GET /api/v1/knowledge/categories` | 分类树（只含至少有一条已发布条目的分支？否——返回全部非空分类树，条目数为 0 的分类也返回，避免导航跳空） | `List<CategoryNode>` |
| `GET /api/v1/knowledge/entries?categoryId&q&page&size` | 已发布条目分页列表；`categoryId` 为树节点时**包含其后代分类**；`q` 非空时走检索 | `PageResult<EntrySummary>` |
| `GET /api/v1/knowledge/entries/{id}` | 条目详情（含 md 正文） | `EntryDetail` |
| `GET /api/v1/knowledge/entries/{id}/relations` | 该条目的前置与相关条目 | `EntryRelations` |

`EntrySummary`：`{ id, title, categoryId, categoryName, updatedAt }`（**不含正文**，列表不传大字段）。
`EntryDetail`：`{ id, title, body, categoryId, categoryName, publishedAt, relationsCount }`。
`EntryRelations`：`{ prerequisites: List<EntryRef>, related: List<EntryRef>, dependents: List<EntryRef> }`。
`EntryRef`：`{ id, title }`。

### 管理端点（仅 `ROLE_ADMIN`）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/v1/knowledge/admin/categories` | 分类树（含空分类），带每个分类的条目数 |
| `POST /api/v1/knowledge/admin/categories` | 建分类 `{ parentId?, name, slug, sortOrder? }` |
| `PUT /api/v1/knowledge/admin/categories/{id}` | 改名／换父／改排序 |
| `DELETE /api/v1/knowledge/admin/categories/{id}` | 删分类；**有子分类或有条目时拒绝**（409） |
| `GET /api/v1/knowledge/admin/entries?status&categoryId&q&page&size` | 条目列表，**含草稿** |
| `POST /api/v1/knowledge/admin/entries` | 建条目 `{ title, body, categoryId?, status }` |
| `PUT /api/v1/knowledge/admin/entries/{id}` | 更新条目，含 `status`（发布＝把 status 置 published）；发布时写 `published_at` |
| `DELETE /api/v1/knowledge/admin/entries/{id}` | 删条目；关联关系随之级联删除 |
| `POST /api/v1/knowledge/admin/relations` | 建关系 `{ fromEntryId, toEntryId, relationType }` |
| `DELETE /api/v1/knowledge/admin/relations/{id}` | 删关系 |
| `POST /api/v1/knowledge/admin/files` | 上传附件（multipart，字段 `file`），返回 `{ id, url, contentType, size, originalName }`；`url` 是稳定标识 `/api/v1/knowledge/files/{id}` |
| `GET /api/v1/knowledge/admin/entries/{id}` | 条目详情，**含草稿**；草稿预览用它（返回与只读详情同构的数据，只是不过滤 `status`） |

### 免鉴权端点（全项目唯一）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/v1/knowledge/files/{id}` | 读取附件字节。**不要求登录**（用户裁决）。按 id 查表定位磁盘路径——路径不是从请求拼出来的，因此不存在穿越；响应的 `Content-Type` 用**入库时嗅探到的类型**并带 `X-Content-Type-Options: nosniff`；PDF 以 `inline` 呈现，图片直接内联 |

### 学习者写入端点（全项目唯一一处学习者可写的数据）

| 方法与路径 | 说明 |
| --- | --- |
| `PUT /api/v1/knowledge/entries/{id}/self-assessment` | 标记「懂了／还不懂」`{ level }`；按 `(user_id, entry_id)` upsert |
| `DELETE /api/v1/knowledge/entries/{id}/self-assessment` | 取消标记（幂等：没有标记也返回成功） |
| `GET /api/v1/knowledge/self-assessments?level=` | 列出**自己**标记过的条目，可按状态过滤 |

- 三个端点都**只操作当前令牌对应的人**，路径与请求体里都没有 `user_id`。
- 只允许标记**已发布**条目（草稿返回 404），与只读浏览的口径一致。
- 条目详情响应增加 `selfAssessment: 'understood' | 'unsure' | null` 字段，**只可能是自己的**。

**不单独设 publish/unpublish 端点**：发布是通过 `PUT` 携带 `status` 完成的，管理界面上的「发布」按钮就是一次 `PUT`。少一个端点、少一处状态分支。

### 错误映射（沿用既有 `ErrorCode`，不新增错误码）

| 场景 | 错误码 | HTTP |
| --- | --- | --- |
| 条目／分类不存在，或草稿被非管理员读取 | `NOT_FOUND` | 404 |
| 分类 `slug` 冲突、同级分类重名 | `CONFLICT` | 409 |
| 删除仍有子分类或仍被条目引用的分类 | `CONFLICT` | 409 |
| 重复建立同一条关系（同 from/to/type） | `CONFLICT` | 409 |
| 关系两端相同（自指） | `INVALID_ARGUMENT` | 400 |
| 标题为空、slug 格式非法、`status` 取值非法 | `INVALID_ARGUMENT` | 400 |
| 无令牌访问任一端点 | `UNAUTHENTICATED` | 401 |
| 非管理员访问 `/api/v1/knowledge/admin/**` | `FORBIDDEN` | 403 |
| 上传类型不在白名单、声明类型与嗅探结果不符、或扩展名不在白名单 | `INVALID_ARGUMENT` | 400 |
| 上传文件超过 10 MB | `INVALID_ARGUMENT` | 400 |
| 文件 id 格式非法、表中无记录、或磁盘上文件缺失 | `NOT_FOUND` | 404 |
| 上传目录不可写 | `INTERNAL` | 500（日志给出目录路径与原因） |
| 自评的 `level` 取值不在 `understood`／`unsure` 内 | `INVALID_ARGUMENT` | 400 |
| 对未发布条目做自评 | `NOT_FOUND` | 404（与只读浏览口径一致，不暴露草稿存在） |

## 持久化与迁移

新增建表迁移 `V<n>__create_knowledge_tables.sql`，与既有迁移同目录（生产迁移集中在 `paideia-app`），风格一致：`BIGINT UNSIGNED AUTO_INCREMENT` 主键、`DATETIME(3)` 审计列、唯一/普通索引显式命名、`ENGINE=InnoDB`。

**编号取实施时目录内的下一个可用值，不要写死 `V2`。** 设计定稿时目录里只有 `V1`，但工作区随后出现了另一个会话未提交的 `V2__pin_account_table_collation.sql`——**两个同号迁移会让 Flyway 直接失败**。实施时先列目录取最大值再顺延，并确认没有并发会话正准备用同一个号。

```sql
CREATE TABLE knowledge_categories (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  parent_id   BIGINT UNSIGNED NULL,
  name        VARCHAR(64)  NOT NULL,
  slug        VARCHAR(64)  NOT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_categories_slug (slug),
  KEY idx_knowledge_categories_parent (parent_id),
  CONSTRAINT fk_knowledge_categories_parent FOREIGN KEY (parent_id)
    REFERENCES knowledge_categories (id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_entries (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  category_id  BIGINT UNSIGNED NULL,
  title        VARCHAR(200) NOT NULL,
  body         MEDIUMTEXT   NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'draft',
  published_at DATETIME(3)  NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_knowledge_entries_category (category_id),
  KEY idx_knowledge_entries_status (status),
  CONSTRAINT fk_knowledge_entries_category FOREIGN KEY (category_id)
    REFERENCES knowledge_categories (id),
  FULLTEXT KEY ft_knowledge_entries_title_body (title, body) WITH PARSER ngram
) ENGINE=InnoDB;

CREATE TABLE knowledge_entry_relations (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  from_entry_id BIGINT UNSIGNED NOT NULL,
  to_entry_id   BIGINT UNSIGNED NOT NULL,
  relation_type VARCHAR(32)     NOT NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_relations_edge (from_entry_id, to_entry_id, relation_type),
  KEY idx_knowledge_relations_to (to_entry_id),
  CONSTRAINT fk_knowledge_relations_from FOREIGN KEY (from_entry_id)
    REFERENCES knowledge_entries (id) ON DELETE CASCADE,
  CONSTRAINT fk_knowledge_relations_to FOREIGN KEY (to_entry_id)
    REFERENCES knowledge_entries (id) ON DELETE CASCADE,
  CONSTRAINT ck_knowledge_relations_not_self CHECK (from_entry_id <> to_entry_id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_files (
  id            CHAR(36)      NOT NULL,            -- UUID，也是磁盘文件名，不可枚举
  original_name VARCHAR(255)  NOT NULL,
  content_type  VARCHAR(64)   NOT NULL,            -- 入库时嗅探所得，不是客户端声明
  size_bytes    BIGINT UNSIGNED NOT NULL,
  uploader_id   BIGINT UNSIGNED NOT NULL,          -- 管理员账号 id（来自令牌，不接受客户端传入）
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_knowledge_files_uploader (uploader_id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_self_assessments (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,          -- 来自令牌，不接受客户端传入
  entry_id   BIGINT UNSIGNED NOT NULL,
  level      VARCHAR(16)     NOT NULL,          -- understood | unsure
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_self_assessments (user_id, entry_id),
  KEY idx_knowledge_self_assessments_entry (entry_id),
  KEY idx_knowledge_self_assessments_user_level (user_id, level),
  CONSTRAINT fk_knowledge_self_assessments_entry FOREIGN KEY (entry_id)
    REFERENCES knowledge_entries (id) ON DELETE CASCADE
) ENGINE=InnoDB;
```

再补三点：

- **`knowledge_self_assessments` 是本项目第一张「用户私有业务数据」表。** `user_id` 只从令牌取；条目被删时随 `ON DELETE CASCADE` 一起清掉（标记脱离条目就没有意义）；`UNIQUE(user_id, entry_id)` 给 upsert 一个唯一落点，也让"取消标记"就是一次 delete。
- **升级路径**：当作答与基于作答的掌握度成为独立子系统时，这张表很可能被取代或迁移——届时要走数据库迁移，而不是就地改名。

- **`id` 用 `CHAR(36)` 存 UUID 而不是自增**：自增会被枚举（`/files/1`、`/files/2`…），而这个端点免鉴权，枚举就等于把全部附件列出来。用 UUID 是这条免鉴权路径的必要配套。
- **磁盘目录不由迁移管理**：上传根目录来自配置（本地 profile 指向服务器本地路径），不在仓库内、不被 Git 跟踪；迁移只建表，不建目录。

要点与理由：

- **`status` 用 `VARCHAR(16)` 而不是 `TINYINT`**：这是管理端手工录入的数据，可读性优先于一个字节；取值由应用层枚举约束（`draft` / `published`）。
- **全文索引带 `WITH PARSER ngram`**：默认解析器不切中文，不写这一句中文检索基本不召回。
- **关系表两端都是 `ON DELETE CASCADE`**：删条目不该留下悬空边；`CHECK` 阻止自指（MySQL 8.0.16+ 生效）。
- **分类删除用应用层判定而不是数据库级联**：删一个有子分类的分类会级联删掉整棵子树，那是数据丢失；应用层遇到子分类或条目引用就返回 409。
- 分类树的读取用**一次全量查询 + 内存装树**（分类数量极小，避免递归 CTE 的复杂度）。
- 不建"分类路径"冗余列：改名时要维护它，而按树遍历已足够。
- e2e 种子 `db/devdata/V951__seed_knowledge.sql`（**仅 e2e 显式加载**，production 迁移目录里没有它），提供一棵两级分类与三条已发布、一条草稿条目，以及一条前置关系。

## 失败处理与恢复

- 迁移失败：Flyway 在启动时失败即启动失败，不进入半可用状态（既有行为）。
- 分类树的父指针若因并发写形成环：`PUT categories/{id}` 换父时校验新父不是自己的后代，违规返回 `INVALID_ARGUMENT`；这是唯一会产生环的入口，必须拦在写路径上。
- 删除分类被拒时错误消息要说明是"还有子分类"还是"还有条目"，让管理员知道先处理什么。
- 检索在 ngram 回退路径上不返回 5xx：短查询走 `LIKE`，结果为空就是空列表，不报错。
- md 渲染：`react-markdown` 默认不执行内联 HTML；渲染异常退化为纯文本展示，不留白屏。
- 上传中断或校验失败时**先校验后落盘**：类型、大小、嗅探都在写文件之前完成，避免留下半截文件；落盘成功后才插表，插表失败则删除刚写的文件（尽力回滚，失败只记日志）。
- 文件表中存在记录但磁盘文件缺失：返回 404 并记 `WARN`，不做自动修复，也不把异常当 500 抛出。

## 安全与权限

- **修改 `paideia-security/.../SecurityConfiguration.java`**：在现有放行规则之后、`anyRequest().authenticated()` 之前插入

  ```java
  .requestMatchers(HttpMethod.GET, "/api/v1/knowledge/files/**").permitAll()
  .requestMatchers("/api/v1/knowledge/admin/**").hasRole("ADMIN")
  ```

  **两条规则的顺序都在 `anyRequest().authenticated()` 之前**，且免鉴权那条**限定为 `GET`**——上传是 `/api/v1/knowledge/admin/files`，仍走管理员规则，不能因为放行读取而把写入也放开。
- **自评端点不提权、不提路径规则**：它落在 `/api/v1/knowledge/**` 之下，因此只需"已登录"即可访问，由 `anyRequest().authenticated()` 兜住；管理员规则不会误伤它（它在 `/admin/` 路径之外）。**权限靠数据过滤而不是靠路径**：写入时 `user_id` 取自令牌，读取时 `WHERE user_id = :currentUser`，请求里根本没有用户标识可传。
- **自评的隔离必须有独立检查**，且要覆盖"详情接口不泄露他人标记"这一路径——只测"列表按用户过滤"不够，详情是另一条查询。
- **草稿预览走 `/admin/` 路径**，因此天然继承管理员规则；它返回草稿全文，绝不能落在只读路径下。

  **顺序是关键**：Spring Security 按声明顺序取第一个匹配的规则。若把 `/api/v1/knowledge/**` 的规则写在前面，管理端规则永不生效，且不会有任何报错——只会在某天表现为"学习者能改知识库"。AC-006 的三态测试专门覆盖这一点。
- 不引入 `@EnableMethodSecurity` 与 `@PreAuthorize`：路径规则只有一处、默认覆盖后续新增的管理端接口；详见提案的备选方案对比。
- **不接受客户端传入的用户身份**：本项所有端点都不需要用户 id，写操作只在日志里记录 `CurrentUser.subjectId()` 作为操作者。
- 学习者读路径**在 SQL 层就过滤 `status = 'published'`**，不依赖控制器判断；草稿对非管理员按 404 处理（不是 403，避免暴露"这里有个未发布的条目"）。
- md 渲染不产生 HTML 字符串，因此不存在 `dangerouslySetInnerHTML` 注入面；仍以测试断言原始 `<script>` 不被解释。
- 本项不引入新的凭据、密钥或连接串。

## 可观测性

- 写操作（建／改／删分类、条目、关系）在 `INFO` 记录：操作者 `subjectId`、目标类型与 id、动作；**不记录条目正文**（体积大且有用户内容）。
- 上传在 `INFO` 记录：附件 id、嗅探到的类型、字节数、上传者 `subjectId`；**不记录文件内容**。被拒的上传记 `WARN`，带上拒绝原因（类型不在白名单／超限／嗅探不符），便于排查"为什么传不上去"。
- 检索在 `DEBUG` 记录归一化后的查询串与命中数，便于排查"中文搜不到"。
- 不新增指标端点或埋点（可观测性栈属 D-15 暂缓项，本项不做）。
- 沿用既有 `traceId`：管理端报错时用户可凭界面上的 traceId 对齐日志。

## 实施顺序

与 `tasks.md` 的纵向切片一致：模块骨架与迁移 → 只读浏览（含 md 渲染）→ 检索 → 关系与邻域 → 角色规则与写端点 → 管理端界面 → 回归。每个切片结束时学习者端或管理端都能看到一个可演示的变化。

## 测试策略

| 层 | 位置 | 覆盖 |
| --- | --- | --- |
| 后端单元 | `paideia-knowledge/src/test/java/…/category/CategoryTreeTest.java`、`search/SearchQueryTest.java` | 装树与排序；短查询回退判定（纯函数，无需数据库） |
| 后端集成 | `paideia-app/src/test/java/io/github/xiaou61/KnowledgeIntegrationTest.java`（真实 MySQL，`@ActiveProfiles("test")`，与 `AccountAuthIntegrationTest` 同注解组合） | 迁移可用；只读端点；草稿不可见；中文检索命中与短查询回退；关系级联删除；分类删除被拒；**管理端三态鉴权（匿名 401 / 学习者 403 / 管理员放行）**；上传白名单与超限被拒；**匿名 GET 文件能取到字节**且返回嗅探类型；id 为 UUID 形状 |
| 后端文件目录 | 集成测试使用临时目录作为上传根 | 上传目录不可写时失败可见（构建期注入一个只读目录断言返回 500 且日志有路径） |
| 后端自评隔离 | `paideia-app/src/test/java/io/github/xiaou61/KnowledgeSelfAssessmentIsolationTest.java` | 用户 A 标记后用户 B 读不到；**详情接口只返回自己的标记**；对未发布条目标记返回 404；同一用户重复标记是 upsert 而不是插两行 |
| 前端单测 | `packages/knowledge/src/heading-slug.test.ts` | 标题 → id；**重复标题追加序号**；中文标题与标点不产生空 id |
| 模块边界 | `paideia-app/.../ModularityTest.java`（既有） | 新模块自动纳入校验，`internal` 不可被外部引用 |
| 前端单测 | `packages/knowledge/src/client.test.ts`、`markdown-body.test.tsx` | 客户端拼路径与错误传播；md 渲染不出 HTML 字符串、脚本不执行 |
| 前端 e2e | `apps/app/e2e/knowledge.spec.ts`、`apps/admin/e2e/knowledge.spec.ts` | 学习者浏览／分类导航／检索；管理员建条目 → 发布 → 学习者在学习者端看到 |
| 回归 | 既有命令 | 四个应用构建、`pnpm -r typecheck`、`pnpm -r test`、`workspace-boundaries`/`tokens`/`source-coverage`/`contrast`、桌面产物不含管理端 |

## 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001 | 「持久化与迁移」的 `knowledge_entries` |
| REQ-002 | `knowledge_categories` + `CategoryTree` + 分类导航页 |
| REQ-003 | `knowledge_entry_relations` + `/entries/{id}/relations` + 邻域视图 |
| REQ-004 | 只读端点表 + `MarkdownBody`；草稿过滤写在 SQL 层 |
| REQ-005 | `SearchQuery` + `FULLTEXT … WITH PARSER ngram` + 短查询回退 |
| REQ-006 | 管理端点表 + `SecurityConfiguration` 的 `hasRole('ADMIN')` 路径规则 |
| REQ-007 | `knowledge_files` + `FileStorage` 端口与 `LocalFileStorage` + 上传端点 + 免鉴权读取规则 + `MarkdownBody` 的图片重写 |
| REQ-008 | `heading-slug.ts` + `entry-toc.tsx` + 详情响应里的相邻条目 + 关系读取的 `dependents` 列表化 |
| REQ-009 | `MarkdownBody` 的 `img` 组件（图注）+ `image-lightbox.tsx` |
| REQ-010 | `apps/app/src/components/CategoryTree.tsx` |
| REQ-011 | `GET /admin/entries/{id}` + 管理端复用 `MarkdownBody` 的预览视图 |
| REQ-012 | `knowledge_self_assessments` + `SelfAssessmentController` + 详情响应的 `selfAssessment` 字段 + 「我的标记」页 |

| 验收 | 设计落点 |
| --- | --- |
| AC-001 | 迁移 DDL + `local` 启动健康检查 |
| AC-002 | 只读端点 + `apps/app` 两页 + e2e |
| AC-003 | 检索路径 + 草稿过滤 + 中文断言 |
| AC-004 | 关系端点 + 邻域 SVG 组件 |
| AC-005 | 管理端点 + 管理端路由与管理区 |
| AC-006 | 路径规则顺序 + 三态集成测试 |
| AC-007 | 回归清单 |
| AC-008 | `knowledge_files` DDL + 上传校验（白名单／嗅探／超限）+ 免鉴权 GET 规则 + 前端图片渲染 |
| AC-009 | `heading-slug` + `entry-toc` + 相邻条目查询 + `dependents` 列表 |
| AC-010 | `CategoryTree.tsx` 的展开状态与筛选 |
| AC-011 | `GET /admin/entries/{id}` + 管理员规则 + 共用渲染组件 |
| AC-012 | `knowledge_self_assessments` 的 upsert／delete + 详情 `selfAssessment` + 「我的标记」列表 |
| AC-013 | `KnowledgeSelfAssessmentIsolationTest` |

## 待决定事项

无。需求第 8—12 条（本地文件存储与免鉴权读取、单分类、两种关系、管理端引路由）已在需求与本设计中落定；若用户改判，受影响的是文件存储实现与放行规则、分类表基数、关系枚举、管理端路由四处的具体写法。
