---
artifact: tasks
work_id: WORK-027
work: 每日技术速递
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-004, WORK-006, WORK-009, WORK-010, WORK-022, WORK-025]
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-21 13:46:49 +0800
approver_role: CTO
---

# 任务 · 每日技术速递

**排序原则**：先让**数据能进库**（T-01..T-05，纯后端、可单测、不碰界面），再让**一次 AI 调用可验证**（T-06），最后才做界面（T-07..T-11）。前面任何一环跑不通，后面的界面全是空壳。

**每个切片都可独立验证**：每完成一个任务就能跑一条命令看到结果，不需要等全部做完。

**全局约束**：不新增 Maven 依赖（RSS 用 JDK `javax.xml`，JSON 用已有 Jackson）；默认关闭（`enabled: false`）；不改 WORK-025 的任何代码；不动 `paideia` 服务以外的部署配置；**token 与任何凭据只进被忽略的本地文件**。

## 前置

**T-00 ✅ 已满足**（2026-10-21 核对）：WORK-025「八股文专区」已于 2026-10-20 完成沉淀、四件工件全签；其前端入口（首页「八股文专区」卡 + 知识库分类路径）在 `a512ee1`/`5a6f322` 补齐并有余证 e2e。硬依赖解除，本项 13 个任务可开工。

## 任务

### TASK-001 | done | 迁移与数据模型

新建迁移 `V<14位时间戳>__create_digest_tables.sql`（例如 `V20261021103045__create_digest_tables.sql`）：
`digest_items` 与 `digest_weeklies` 两张表，字段与索引见 design.md。`created_at` 由应用写入 UTC，**不用 `DEFAULT CURRENT_TIMESTAMP`**。

**不要用 `V25__` 这种递增号**（2026-10-21 起的新规矩）：多会话并行时递增号是被争抢的共享命名空间，
实测两个分支都写 `V25` 会让应用整体起不来。`MigrationVersionFormatTest` 会挡住纯数字号。
只建结构、不插业务数据（`MigrationSeedOverlapTest` 守着）。

**验收**：`scripts/test-db.sh` 跑一次，`MigrationChecksumTest` 与 `MigrationVersionFormatTest` 通过；`SHOW CREATE TABLE digest_items` 里能看到 `uk_digest_items_fingerprint` 与 `idx_digest_items_edition`。

### TASK-002 | done | 指纹与 URL 规范化

`DigestFingerprint`：剥掉 `utm_*`、`ref`、`spm`、`from`、`fbclid`、`gclid` 查询参数，去掉末尾 `/`，小写化 host，取 SHA-256。

**验收**：单测覆盖——同一文章带 6 种不同追踪参数的 URL 得到同一个指纹；不同文章得到不同指纹；无查询参数的 URL 原样通过。

### TASK-003 | done | RSS 源（五个源共用一个实现）

`RssDigestSource`：用 JDK `javax.xml.parsers` 解析 RSS 2.0 / Atom，取 `title`/`link`/`pubDate`/`description`（截断 200 字作 `summaryHint`）。**单个条目的 `pubDate` 坏掉只跳过该条，不整批失败。**

**XXE 防护是必做项，不是加固可选项**：RSS 是外部站点给的 XML，`DOCTYPE` 实体能让解析器去读
`file:///...`。`DocumentBuilderFactory` 必须设这四条（照抄 `ResumeTextExtractor` 的先例）：
`disallow-doctype-decl=true`、`external-general-entities=false`、`external-parameter-entities=false`、
`FEATURE_SECURE_PROCESSING=true`，外加 `setXIncludeAware(false)`、`setExpandEntityReferences(false)`。

**访问源地址的 HTTP 也要有边界**：只允许 `http`/`https`、不跟随跨主机重定向、连接与读取都要超时
（`DigestHttp` 薄封装统一做）。

**验收**：单测喂四份夹具——① 正常 RSS ② 含一条坏 `pubDate` 的 RSS（断言好条目仍在）③ Atom 格式
④ **带 `<!DOCTYPE ... <!ENTITY xxe SYSTEM "file:///etc/passwd">]>` 的恶意 RSS（断言解析拒绝、
该源记为失败、不向调用方抛异常、不读到任何文件内容）**。真实联调一次：`qbitai` 与 `ithome` 各抓到非零条。（research.md 已实测两个源可访问）

### TASK-004 | done | GitHub 与 HN 两个源

`GithubTrendingSource`：**有 token 走 Search API**（`q=created:>=<7天前> stars:>100&sort=stars`），**无 token 走页面解析**（先定位 `<h2>` 块再取 href —— 直接匹配 `href="/([^"]+)"` 会撞到赞助商链接），解析失败退回 Search API。
`HackerNewsSource`：Algolia `search?tags=front_page&hitsPerPage=10`。

**验收**：单测喂一份改坏的 HTML，断言返回空列表且**触发兜底**（用桩记录调用顺序）；真实联调两个源各抓到非零条。断言 GitHub 条目的 `heat` 来自当日新增 star。

### TASK-005 | done | 编排服务与入库

`DigestCollectionService.runOnce()`：抓（单源 try/catch）→ 批内去重 → 查库剔除已存在（**只查 `edition_date` 在 [今天-1, 今天+1] 的窗口**）→ 截断 → 调 AI → 入库。

**验收**：集成测试用一个假 `DigestSource` 喂三条（其中一条与库里已有相同指纹）——断言只新增两条。**反向验证：把窗口查询改成全表，测试仍应通过（证明行为不变）；把唯一约束去掉后重复插入应被测试抓到。**

### TASK-006 | done | 一次 AI 调用与降级

`DigestSummarizer` + `DigestPrompt`：一次调用，输出 `{"items":[{fingerprint,title,summary}]}`，**按指纹回填，不按顺序**。只接受输入集合内的指纹。

**验收**：单测三种响应——① 正常全返回 ② 少返回两条（断言其余正常、缺的标 `degraded`）③ 返回一个不在输入的指纹（断言被丢弃、不写垃圾行）。**AI 抛异常时整批按 `degraded` 入库，不中断**（AC-005 的单元级证据）。

### TASK-007 | done | 调度、配置与遥测

`DigestProperties` + `DigestConfiguration`（`@ConditionalOnProperty`）+ `DigestJob`（`fixedDelay=24h`, `initialDelay=5min`，自己接住异常）+ 新增 `RunKind.DIGEST`。`application.yml` 加 `paideia.ai.digest` 段，**默认 `enabled: false`**。

**验收**：`enabled=false` 时容器里没有 `DigestJob` Bean（启动断言）；`enabled=true` 时有一条；`RunKind.DIGEST` 出现在 `ai_runs` 的统计筛选里。

### TASK-008 | done | 学习者端读接口

`DigestController`：`GET /api/v1/digest?date=`、`/digest/dates`、`/digest/weekly`。**要登录**（与知识库同门禁）。

**验收**：集成测试——未登录 401；已登录返回按 `group_key` 分组的结构；不传 date 返回今天；`date` 格式非法返回 400。

### TASK-009 | done | 学习者端页面

`DigestPage.tsx` + 路由 `/digest` + `LearnerHeader` 的 `NAV` 加一项 + `HomePage` 的 `QUICK_LINKS` 加一张卡。

**验收**：e2e 从首页与导航都能进；默认显示当天；能切到前一天；`degraded` 条目显示原文标题而不是空摘要。

### TASK-010 | done | 周报

`DigestWeeklyService`（聚合上周 → 一次 AI 调用 → upsert 到 `digest_weeklies`）+ `DigestWeeklyJob`（`fixedDelay=7d`）+ `RunKind.DIGEST_WEEKLY` + 页面「周报」页签。

**验收**：集成测试——同周跑两次只有一行（AC-016）；周报生成抛异常时日报数据不受影响、接口返回 null 而不是 500（AC-013）。

### TASK-011 | done | 管理端删除

`DigestAdminController`：`GET /api/v1/digest/admin/items?date=`、`DELETE /api/v1/digest/admin/items/{id}`。**路径规则里必须写 `hasRole('ADMIN')` 且顺序在 `/api/v1/digest/**` 之前**（WORK-004 的教训）。

**路径必须是 `<模块>/admin`，不是 `/api/v1/admin/<模块>`**（2026-10-21 订正，见 design「接口」节）。
项目现有四条管理端规则全是前者。写错时没有任何规则匹配，会落到
`anyRequest().authenticated()` —— **任何登录用户都能删条目**，而且不报错。

**验收**：集成测试——非管理员 403、管理员可删、删后学习者端读不到（AC-010）。

### TASK-012 | done | 导航入口与转笔记

**按身份分组已撤销**（2026-10-21，随 D-14 / REQ-013 / AC-014 一起）：后端 `Identity.selectable()`
只对 `job_seeker` 返回 true，另外三个身份是 `disabled` 留位，"切换身份"这个动作不存在。
所以**不加 `digestGroups`**，`DigestPage` 固定顺序渲染四组（news → github → community → hackernews）。

仍要做三件：
1. `LearnerHeader.tsx` 的 `NAV` 加 `{ key: 'digest', to: '/digest', label: '每日速递' }`；
2. `NoteNewPage` 读 query 参数预填标题与正文；卡片加「存成笔记」按钮；
3. `preferredNav` 的**六处**改动（类型联合、四个身份配置、`HomePage` 的 `QUICK_LINKS`）——
   漏一处就 TypeScript 报错或首页排序取不到卡片。

**验收**：单测——`config.test.ts` 断言每个身份的 `preferredNav` 都含 `'digest'`。
**不断言"身份间 digestGroups 不同"**——该功能已撤销，不要为不存在的东西写测试。
e2e——点「存成笔记」跳到 `/notes/new` 且标题与正文已预填，保存后出现在笔记列表（AC-015）。

### TASK-013 | done | 实跑与收口

`enabled: true` 实跑一次完整日报 + 一次周报，核对真实抓取条数、AI 用量与页面呈现。把实测数字写进 `testing/report.md`。

**验收**：`ai_runs` 里当日 `kind=digest` **恰好 1 条**（AC-008）；页面四组都有内容；抽查 5 条确认摘要是我们自己写的、有来源链接（AC-007）；`project-lifecycle.ps1 validate` 无错误。

## 依赖关系

- T-01 → T-02..T-06 全部
- T-02、T-03、T-04 → T-05
- T-05、T-06 → T-07
- T-07 → T-08 → T-09
- T-09 → T-12
- T-07 → T-10（周报复用编排与 AI 端口）
- T-08 → T-11（管理端读同一批表）
- 全部 → T-13

## 评审焦点

规格暗示但任务未直接覆盖的失败模式，逐条列在 design.md 的「评审焦点」表里。三条最容易被漏的：

1. **AI 少返回条目**（T-06 的第二个夹具）——用顺序对齐的实现会静默错位，界面看起来"摘要串了"。
2. **空候选池**（所有源都失败）——必须不调 AI、不写行、不报错（T-05 的边界）。
3. **管理端删除后重跑又抓回来**（T-11）——这是已知行为，**要写进文档而不是"修"它**；修它等于引入黑名单，那是另一个功能。
