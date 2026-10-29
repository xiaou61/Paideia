---
artifact: test-report
work_id: WORK-004
work: 知识库
status: passed
evidence: required
created: 2026-10-10
updated: 2026-10-10
accepted_by: xiaou61
accepted_at: 2026-10-29 13:16:47 +0800
---

# 知识库验证报告

## 验证环境

- 后端：JDK 25 / Spring Boot 4.1.1；MyBatis 手写 mapper；MySQL 8.0.46（在自有服务器上，经本机 `127.0.0.1:3307` 的 SSH 隧道访问），验证库 `paideia_test`。
- 测试库口令从被忽略的 `backend/config/application-local.yml` 读入环境变量，**未回显、未进入任何被跟踪的文件**。
- 前端：pnpm 12.10.1 workspace；Playwright chromium；e2e 用一个手工启动的后端（各应用的 `reuseExistingServer: true` 复用它），后端启动实测 8.4 秒。
- 验证基准：`f0edc79`（测试节奏与上下文合并）。本轮新增的 `V952` 与两个 e2e spec 在验证时尚未提交。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 迁移与启动 | `cd backend && mvn -B verify` | 0 | passed | `testing/logs/backend-verify.txt`（含 `Successfully applied … version "3 - create knowledge tables"`） |
| AC-002 只读浏览与 md 渲染 | `pnpm --filter @paideia/app exec playwright test` | 0 | passed | `testing/logs/e2e-app.txt`（11 passed） |
| AC-003 中文检索、不返回草稿 | 同上 | 0 | passed | `testing/logs/e2e-app.txt` |
| AC-004 关系与邻域 | `mvn -B verify` 的关系用例 + 学习者端 e2e | 0 | passed | `testing/logs/backend-verify.txt`、`e2e-app.txt` |
| AC-005 管理端录入并发布 | `pnpm --filter @paideia/admin exec playwright test` | 0 | passed | `testing/logs/e2e-admin.txt`（7 passed） |
| AC-006 三态鉴权 | `mvn -B verify` 的 `KnowledgeAdminIntegrationTest` | 0 | passed | `testing/logs/backend-verify.txt` |
| AC-007 构建、边界检查、桌面产物不含管理端 | `pnpm -r typecheck`、`pnpm -r test`、`pnpm -r --filter '!@paideia/desktop' build`、`pnpm -w build:desktop`、安装包内文案检索 | 0 | passed | `testing/logs/build-apps.txt`、`testing/logs/build-desktop.txt`、`testing/logs/artifact-exclusion.txt` |
| AC-008 上传与免鉴权读取 | `mvn -B verify` 的 `KnowledgeFileIntegrationTest` | 0 | passed | `testing/logs/backend-verify.txt` |
| AC-009 正文导航（目录／上下篇／反向链接） | 学习者端 e2e | 0 | passed | `testing/logs/e2e-app.txt` |
| AC-010 分类树展开与筛选 | 学习者端 e2e | 0 | passed | `testing/logs/e2e-app.txt` |
| AC-011 草稿预览 | 管理端 e2e + `mvn -B verify`（对学习者 403） | 0 | passed | `testing/logs/e2e-admin.txt`、`backend-verify.txt` |
| AC-012 自评可用 | 学习者端 e2e（刷新后仍在）+ `mvn -B verify` | 0 | passed | `testing/logs/e2e-app.txt`、`backend-verify.txt` |
| AC-013 自评隔离 | `mvn -B verify` 的 `KnowledgeSelfAssessmentIntegrationTest` | 0 | passed | `testing/logs/backend-verify.txt` |

（反向验证不列进这张矩阵：矩阵的语义是"每条 AC 对应一条通过的检查"，而反向验证刻意的期望结果是**失败**。它们逐条记在下一节，证据路径同在那里给出。）

数量：**后端 124 条测试 0 失败、0 错误、0 跳过**（knowledge 22、app 58、account 13、security 11、persistence 4、platform 7、web 9），实测 91 秒；**前端单测 63 条**（core 14、ui 17、knowledge 15、auth 13、platform-desktop 4），`pnpm -r typecheck` 9 个包全过；**e2e 23 条**（学习者端 11、管理端 7、展览页 5）。

## 反向验证的逐条结论

三项咬住，一项**没有咬住**——后者比咬住更重要，照实记。证据：`testing/logs/reverse-verification-backend.txt`（角色规则 + 自评过滤，一次运行同时报出，退出码 1）与 `testing/logs/reverse-verification-source.txt`（`@source`，退出码 1）；临时改动均已用 `git checkout --` 精确还原，工作区无残留。

1. **角色路径规则**：注释掉 `hasRole('ADMIN')` 后，`learnerIsForbiddenOnAdminEndpoints` 与 `uploadRequiresAdmin` 都由"期望 403"变成"实际 200"。顺序或存在性写错都会被这条抓住。
2. **自评隔离**：去掉查询里的 `user_id` 条件后，`markingIsVisibleOnlyToTheMarkerOnBothReadPaths` 失败，证据里直接打出**用户 B 读到了用户 A 的标记**（`[{"entryId":1,"title":"什么是依赖注入","level":"understood",…}]`）。这正是"能标记、能看到自己的"这类断言在串号时照样通过的反面。
3. **Tailwind `@source`**：删掉那行后 `source-coverage` 失败并点名缺登记的目录。
4. **`WITH PARSER ngram`：没有咬住。** 摘掉解析器后，`chineseKeywordSearchHitsThroughFulltextIndex` **仍然通过**——说明"中文能搜到"这条断言**对解析器不敏感**，而设计里"不加 ngram 中文基本不召回"的说法**没有得到本次实验支持**。索引已还原、探针迁移已删除。这不是"没做"，是"做了但结论与预期相反"，因此列入下面的剩余风险而不是当作已验证。

## 失败与未验证项

**无失败项。** 13 条验收标准与三项反向验证全部有命令、退出码与证据位置（见上）。

未做（均不在本项范围）：性能与容量测试、并发写冲突验证。

另有一项**反向验证没有咬住**——它不是"没做"，是"做了但结论与预期相反"：`WITH PARSER ngram`。摘掉解析器后中文检索用例仍然通过，说明设计里"不加 ngram 中文基本不召回"缺乏证据。索引已还原、探针迁移已删。详见上一节第 4 条与下面的「剩余风险」。

## 剩余风险

- **`WITH PARSER ngram` 的必要性待重新评估**：反向验证未咬住（见上）。当前索引带 ngram，功能正常，因此**不构成立即风险**；但它意味着设计里的这条理由没有证据，后续若要简化 schema 或换检索方案，需要先弄清楚"什么查询在什么分词下会失效"。
- **附件读取端点免鉴权**（用户在 2026-10-10 明确裁决接受）：未发布条目引用的图片，URL 一旦泄露即可被读。缓解是不可枚举的 UUID 与不提供目录列举。触发重评：出现他人的真实内容或对外网开放时。
- **附件目录默认落系统临时目录**并告警（与设计里"未配置即启动失败"不同，理由是本地与测试环境都没有该变量）。生产必须显式配置 `paideia.knowledge.upload-dir`。
- **孤儿附件不清理**：删条目不删文件，本版不做定时清理，也不做容量告警。
- **自评只有两个状态、不参与任何排程**：它是学习者模型的第一块砖，不是掌握度推断。
- **证据文件在本机**：`testing/logs/` 被 `.gitignore` 忽略（与 WORK-001/002/003 一致），换一台机器看不到这些日志。

## 验收后的界面重建（2026-10-10，用户反馈）

用户看过线上实例后否掉了管理端的观感（原话「这个管理端做的太丑了吧。我要类似于若依那种的」），按**路线 A**（用现有组件重建、不引新依赖）重做了 `frontend/apps/admin`：新增侧栏 + 面包屑外壳（`AdminShell`）、列表从卡片堆改成 `<Table>`、**5 个原生 `<select>` 全部换成设计系统的 `Select`**、新建与删除改成弹窗（删除带二次确认）、列表加工具栏与分页。

**对本报告结论的影响：无。** TASK-006 的五个断言点（`admin-sign-in`／`admin-console`／`admin-denied`／`admin-current-user`／`admin-health-status`）与 AC-005／AC-011 的 e2e 断言**原样保留并通过**；`testing/logs/e2e-admin.txt` 已追加重建后的复跑结果（**7 passed，26.2 秒**，退出码 0）。e2e 自身改了三处选择器（`<li>` → `<tr>`、原生下拉 → 点开再选、删除多一步确认）——那是跟随界面形态，不是放宽断言。

另**发现一处与本项无关的跨模块缺陷**：所有时间戳比真实时刻快 8 小时（线上 JDBC 缺 `forceConnectionTimeZoneToSession`，而 MySQL 会话时区是 `SYSTEM`，实测 `now()` 与 `utc_timestamp()` 差 8 小时），与「时间存 UTC」的规则冲突。

**该缺陷当日已修（用户裁决走"补连接串参数"这条路）**，修复与验证如下，**对本项结论仍无影响**：

- 改动：四处 JDBC 连接串补上 `forceConnectionTimeZoneToSession=true`（测试 `application-test.yml`、`paideia-persistence` 的 `TestDatabase`、管理端与学习者端两个 `playwright.config.ts`；另有被忽略的本地配置与服务器上的 `application.yml`）。
- 存量数据：只回拨**由数据库默认值写入**的列（`created_at`／`updated_at`／`refresh_tokens.created_at` 等），**应用绑定 Instant 的列不动**（`published_at`／`expires_at`／`revoked_at`／`email_verified_at`）——后者本来就是 UTC，一起回拨反而会错。列清单取自 `information_schema`，不靠猜。
- 验证：回拨后条目 8 的 `created_at` = `09:44:11.546`，与本来就正确的 `published_at` = `09:44:11.545` **只差 1 毫秒**，两条独立写入路径对上了；新写入一条后接口返回 `2026-10-10T10:04:21.270Z`，而那一刻真实 UTC 是 `10:04:30`（本机本地时间 18:04）——不再快 8 小时。
- 回归：后端 `mvn -B verify` → **124 条 0 失败**；管理端 e2e **7 passed（23.3 秒）**；学习者端 e2e **11 passed（32.2 秒）**。

## 验收后的界面重组（2026-10-12，用户继续反馈）

用户接着提了两点：分类与条目"不能分成两个页面吗"，以及侧栏"应该父菜单、下拉选这俩子菜单"。都已实现并部署：

- `KnowledgeAdminPage` 拆成 `pages/KnowledgeCategoriesPage.tsx`（分类表 + 新建分类弹窗）与 `pages/KnowledgeEntriesPage.tsx`（条目表 + 工具栏 + 分页 + 条目编辑器）；路由 `/knowledge/categories` 与 `/knowledge/entries`，旧地址 `/knowledge` 重定向到条目管理。共用件抽到 `apps/admin/src/knowledge-shared.tsx`（分类树展开、下拉选项、删除确认弹窗、加载行、api 客户端）。
- 侧栏改为一级 + 子菜单：`知识库` 展开为 `分类管理`／`条目管理`，未手动点过时跟着当前路由展开；面包屑相应变成两级。
- 顺手修掉一处真缺陷：条目编辑器的「目标条目」下拉原先吃的是表格当前筛选结果（筛成"仅草稿"时只能关联到草稿），改为不随筛选走的全量列表。

**对本报告结论仍无影响**，且这次的证据比上一次更贴近线上：**e2e 直接在已部署实例上跑，7 passed / 14.7 秒**（新增 `E2E_BASE_URL`／`E2E_ADMIN_PATH`／账号环境变量，设了就不再启本地后端与 dev server；用法见 `.agent/notes/dev-workflow.md`）。同一批断言、同一个链路（分类页建分类 → 条目页录入发布 → 回分类页删除）没有放宽；e2e 只多了一处"跨页导航"。

**关于验证锚点**：本报告正文的验证基准是 `f0edc79`，此后代码又有上述界面改动与一次连接串改动（时区）。当前状态的验证覆盖是：后端 **124 条在时区修复之后重跑过 0 失败**，之后只改过 `frontend/apps/admin`（学习者端与后端未动）；管理端 **7 条在线上跑过**（含最新的拆页与父菜单）；学习者端 **11 条**跑于拆页之前，而拆页未触及 `apps/app` 与 `packages/knowledge`。若要把锚点刷新到当前提交，重跑一次 `mvn -B verify` 即可（结论预期不变）。
