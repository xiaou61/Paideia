---
artifact: test-plan
work_id: WORK-004
work: 知识库
status: draft
created: 2026-10-10
updated: 2026-10-10
---

# 知识库测试计划

## 测试范围

覆盖 `REQ-001`…`REQ-012`：迁移可用性、只读浏览与 md 渲染、正文导航（目录／上下篇／反向链接）、附件呈现、分类树交互、中文检索、条目关系与邻域视图、管理端录入、草稿预览、管理端写入路径的角色拒绝、文件上传与免鉴权读取、以及**学习者自评及其隔离**。**不覆盖**内容质量、性能压测、浏览器兼容矩阵、AI 相关（不在本项范围）。

## 环境与前置条件

- 后端集成测试需要到服务器的 MySQL 隧道与 `PAIDEIA_TEST_DB_PASSWORD`（未配置时相关用例跳过并在 stderr 提示，构建不因此失败——沿用既有行为）。测试库为 `paideia_test`，**不是** `paideia`。
- e2e 需要后端 jar（`cd backend && mvn -B -DskipTests package`）、可用隧道，并由各 app 的 `playwright.config.ts` 拉起 `pnpm dev`；e2e 通过环境变量显式加载 `db/devdata`（种子分类、条目、关系）与测试库连接。
- 上传根目录在集成测试与 e2e 中指向**临时目录**（测试不得往真实上传目录写文件）；"目录不可写"的用例通过注入一个只读目录实现。
- 前置门槛：需求、提案、设计、任务四件均已批准（本计划执行前确认，不是对测试计划的单独审批）。

## 验收矩阵

| 验收 | 检查方式 | 位置／命令 | 通过判据 |
| --- | --- | --- | --- |
| AC-001 | 迁移与启动 | `mvn -B verify`；以 `local` 启动后 `GET /actuator/health` | 迁移 `V2` 执行成功；健康检查 UP；三张表存在且含全文索引 |
| AC-002 | e2e | `apps/app/e2e/knowledge.spec.ts` | 列表可见、分类导航可达目标分类、详情标题与 md 渲染结果符合预期 |
| AC-003 | 集成 + e2e | `KnowledgeIntegrationTest` 的检索用例；e2e 搜索断言 | 中文关键词（≥2 字）命中；单字查询走回退仍有结果；结果不含草稿 |
| AC-004 | 集成 + e2e | `KnowledgeIntegrationTest` 的关系用例；`KnowledgeEntryPage` 邻域断言 | 前置／后继／相关三组内容正确；邻域节点数与关系数一致 |
| AC-005 | e2e | `apps/admin/e2e/knowledge.spec.ts` | 管理员可建分类与条目、发布成功、列表状态变为已发布 |
| AC-006 | 集成 | `KnowledgeIntegrationTest` 的鉴权用例（匿名 / 学习者 / 管理员三态） | 匿名 401、学习者 403、管理员放行；学习者读接口看不到草稿 |
| AC-007 | 构建与回归 | 见「回归范围」 | 全部命令退出码 0 |
| AC-008 | 集成 + e2e | `KnowledgeIntegrationTest` 的上传用例；管理端 e2e 上传与学习者端显示断言 | 非法类型／SVG／超限被拒；**匿名 GET 能取到文件字节**且返回嗅探类型；id 为 UUID 形状；上传后正文引用在学习者端显示出来 |
| AC-009 | 单测 + e2e | `heading-slug.test.ts`；`apps/app/e2e/knowledge.spec.ts` | 标题 id 稳定且重复标题不冲突；目录点击可跳到章节；上一篇／下一篇可达；反向链接列出把该条目作为前置的条目 |
| AC-010 | e2e | `apps/app/e2e/knowledge.spec.ts` | 分类树能展开／折叠；分类内按名称筛选能缩小显示范围 |
| AC-011 | 集成 + e2e | `KnowledgeIntegrationTest`；管理端 e2e | 管理员能预览未发布条目并看到与学习者端一致的渲染；`/admin/entries/{id}` 对学习者 403、匿名 401 |
| AC-012 | 集成 + e2e | `KnowledgeIntegrationTest`；`apps/app/e2e/knowledge.spec.ts` | 标记与取消标记持久化；详情显示自己的标记；能列出自己标记过的条目 |
| AC-013 | 集成（独立测试类） | `KnowledgeSelfAssessmentIsolationTest` | 用户 A 读不到用户 B 的标记；**详情接口不返回他人的标记**；未发布条目标记 404；重复标记是 upsert |

## 自动化检查

| 检查 | 命令 | 期望 |
| --- | --- | --- |
| 后端全量 | `cd backend && PAIDEIA_TEST_DB_PASSWORD=<本地读入> mvn -B verify` | BUILD SUCCESS，全部用例通过 |
| 纯单元 | `cd backend && mvn -B -pl paideia-knowledge test` | `CategoryTreeTest`、`SearchQueryTest` 通过 |
| 前端类型与单测 | `cd frontend && pnpm -r typecheck && pnpm -r test` | 全部工程通过 |
| 前端 e2e | `cd frontend && pnpm test:e2e` | 学习者端、管理端、展览页全部通过 |
| 上传与读取 | 随 `mvn -B verify` 的 `KnowledgeIntegrationTest` | 白名单与超限被拒；匿名 GET 文件成功；`/admin/files` 上传对学习者仍 403 |
| 自评隔离 | `mvn -B verify` 的 `KnowledgeSelfAssessmentIsolationTest` | A 标记对 B 不可见（列表与详情两条路径都查）；未发布条目 404；重复标记 upsert |
| 标题锚点 | `cd frontend && pnpm --filter @paideia/knowledge test` | 中文、标点、重复标题都产出稳定且不冲突的 id |
| 模块边界 | 随 `mvn -B verify` 的 `ModularityTest` | 新模块纳入校验且无非法跨模块访问 |
| 前端边界 | `pnpm -r test` 内的四个检查 | `workspace-boundaries`、`tokens`、`source-coverage`、`contrast` 全过 |
| 桌面产物 | `cd frontend && pnpm -w build:desktop` | 产物不含管理端与知识库管理区界面 |

## 人工检查

- **反向验证角色规则真的生效**：临时把 `/api/v1/knowledge/admin/**` 的 `hasRole('ADMIN')` 规则注释掉，确认集成测试的三态用例**失败**，再恢复。只断言"管理员能写"是不够的——那样规则写反、甚至完全没生效，测试也会绿。
- **反向验证中文检索真的走索引**：临时去掉 `WITH PARSER ngram` 重建索引，确认中文检索用例**失败**，再恢复。
- **反向验证 `@source` 覆盖检查有效**：临时删掉 `globals.css` 里 `packages/knowledge/src` 那行，确认 `source-coverage.test.ts` 失败，再恢复。
- **反向验证免鉴权放行没有放过头**：确认匿名 `GET /api/v1/knowledge/files/{id}` 成功，同时确认匿名 `POST /api/v1/knowledge/admin/files` 被拒。放行规则若漏写 `HttpMethod.GET`，写入会一起被放开——这一条就是查它。
- **反向验证自评隔离真的生效**：临时去掉自评查询里的 `user_id` 过滤，确认 `KnowledgeSelfAssessmentIsolationTest` **失败**，再恢复。这条必要性最高——自评是本项目第一条用户私有数据，隔离写错会串号，而"能标记、能看到自己的"这类断言在串号时**照样通过**。
- 在真实浏览器里打开一条含代码块与表格的 md 条目，确认可读且不被裁切；键盘走一遍分类树；打开一张图片确认图注与放大可用、Esc 能关。

## 回归范围

- 四个应用构建：`apps/app`、`apps/admin`、`apps/public`、`apps/ui-kit`。
- 既有 e2e：`apps/app/e2e/home.spec.ts`、`apps/admin/e2e/admin.spec.ts`、`apps/ui-kit/e2e/ui-kit.spec.ts`——管理端引入路由后必须继续通过（**修断言而不是删用例**）。
- WORK-003 的登录门与令牌处理不受影响：知识库页面必须登录后才可达。
- 桌面产物仍不含管理端（构造性事实，随构建检查）。
- `paideia-account` 不受影响：本项不引入对它的依赖，`AccountApi` 仍无消费者。

## 已知缺口

- **文件读取端点免鉴权 → 未发布条目引用的图片，URL 一旦泄露即可被读**。这是用户 2026-10-10 明确裁决接受的取舍（换取 `<img>` 直接可用与浏览器缓存），不是缺陷；缓解是文件名用 UUID、不提供目录列举。触发重评：出现他人的真实内容、或对外网开放时。
- **孤儿文件不清理**：删条目不删附件（同一文件可能被多处引用），本版不做定时清理，也不做容量告警。文件量级在数百个以内时可接受。
- **本地磁盘是容量与备份的单点**：上传目录不在数据库里，备份需要单独覆盖该目录。
- **图片与附件以外的媒体不支持**：不做音视频、不做 PDF 之外的文档预览。
- **自评只有两个状态，且没有作答支撑**：它只反映学习者自己说的"懂了／还不懂"，**不是掌握度推断**，也不参与任何排程。信号比作答弱得多，这是刻意的——真正的掌握度要等练习与作答那一层。
- **「我的标记」列表不分页**：按当前用户过滤后直接返回；条目量级小时够用，量大了要补分页。
- **草稿预览只覆盖单条**：没有"整棵分类树的预览"或"发布前批量检查"。
- **`ngram` 分词长度以下的查询走 `LIKE` 全表扫描**：条目量在数百量级可接受；条目量上去后需要重新评估（换更大 `ngram_token_size`、或引入专门的检索组件）。
- **无相关性排序调优**：全文检索按默认相关性，不做权重、不同义词、不纠错。
- **不做全局关系图**：只有邻域视图；跨条目的整体关系总览推迟。
- **无内容版本历史**：修改覆盖原内容，没有回滚。
- **未做并发写冲突的显式处理**：两个管理员同时编辑同一条目时后写覆盖先写（无乐观锁）。当前管理员人数极少，暂不处理；若成为问题需要加版本列。
