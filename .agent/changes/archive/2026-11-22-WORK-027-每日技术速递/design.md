---
artifact: design
work_id: WORK-027
work: 每日技术速递
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-004, WORK-006, WORK-009, WORK-010, WORK-022, WORK-025]
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-21 13:46:49 +0800
approver_role: CTO
---

# 设计 · 每日技术速递

## 数据模型

两张表。**不建第三张**——日报按 `edition_date` 分组从 `digest_items` 直接取，成刊状态由「当天有没有行」推出（I-03）。

### `digest_items`

```sql
CREATE TABLE digest_items (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  -- 归属哪天：按 UTC 日切，与 edition 查询的索引一致
  edition_date  DATE            NOT NULL,
  -- news | github | community | hackernews；决定前端分组
  group_key     VARCHAR(16)     NOT NULL,
  -- 源标识，如 qbitai / ithome / infoq / juejin / oschina / github-trending / hackernews
  source_key    VARCHAR(32)     NOT NULL,
  source_name   VARCHAR(64)     NOT NULL,
  title         VARCHAR(512)    NOT NULL,
  -- 我们自己写的一句话摘要；AI 失败时为 NULL（降级态）
  summary       VARCHAR(1024)   NULL,
  url           VARCHAR(1024)   NOT NULL,
  -- 规范化 URL 的 SHA-256，去重靠它
  fingerprint   CHAR(64)        NOT NULL,
  -- 源自身的信号：HN 点数、GitHub 当日新增 star；其它源为 NULL
  heat          INT             NULL,
  -- 一条副信息：语言、作者、评论数等，直接给人看
  meta          VARCHAR(255)    NULL,
  -- 原文发布时间（UTC 毫秒）；源不提供时为抓取时间
  published_at  BIGINT          NULL,
  -- ready | degraded（AI 没写成摘要）
  summary_state VARCHAR(16)     NOT NULL DEFAULT 'ready',
  created_at    DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_digest_items_fingerprint (fingerprint),
  KEY idx_digest_items_edition (edition_date, group_key, heat)
) ENGINE = InnoDB;
```

**为什么 `fingerprint` 上是全局唯一**（与 WORK-025 的「同分类内判重」不同）：新闻的重复是**跨源**的（同一条被两个门户转），不是分类内的。全局唯一直接挡住。

**为什么 `published_at` 是 BIGINT 而不是 DATETIME**：源给的是 epoch 秒/毫秒，转来转去容易错时区。存原始 epoch，展示时由前端按用户本地时区格式化。

### `digest_weeklies`

```sql
CREATE TABLE digest_weeklies (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  -- 该周周一的 UTC 日期
  week_start   DATE         NOT NULL,
  body         MEDIUMTEXT   NOT NULL,
  -- 生成时用了多少条日报素材，便于回看质量
  item_count   INT          NOT NULL,
  created_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_digest_weeklies_week (week_start)
) ENGINE = InnoDB;
```

`week_start` 唯一：重复跑不产生第二条（AC-016）。重跑时用 `ON DUPLICATE KEY UPDATE` 覆盖。

**不建外键**：两张表与账号无关（内容是公共的），删除用户不涉及。

### 迁移文件名

按 2026-10-21 新规矩，**必须用 14 位时间戳**，不要写 `V25__`：

```
db/migration/V20261021103045__create_digest_tables.sql
```

原因见 `AGENTS.md`：多个会话并行时递增号是被争抢的共享命名空间，实测两个分支都写 `V25`
会让应用整体起不来（`Found more than one migration with version 25`）。
`MigrationVersionFormatTest` 会挡住纯数字号。

建表 SQL 只建结构，**不插业务数据**（`MigrationSeedOverlapTest` 守着）；本项没有种子需求。

## 端口与类

```
paideia-ai/internal/digest/
  DigestSource.java          端口：fetch(窗口) -> List<FetchedItem>
  FetchedItem.java           record(title, url, summaryHint, heat, meta, publishedAt, sourceKey, sourceName)
  DigestHttp.java            JDK HttpClient 的薄封装：超时、不跟随跨主机重定向、只允许 http(s)
  RssDigestSource.java       三个门户 RSS + 两个社区 RSS 共用一个实现，按配置区分
  FeedXml.java               RSS/Atom 解析（**含四条 XXE 防护**，见「RSS 解析必须自己防 XXE」）
  GithubTrendingSource.java  页面解析 + Search API 两条路
  HackerNewsSource.java      Algolia
  DigestProperties.java      配置
  DigestConfiguration.java   条件装配（两个调度）
  DigestCollectionService.java  编排：抓 → 去重 → 一次 AI 压缩 → 入库
  DigestSummarizer.java      那一次 AI 调用
  DigestPrompt.java          提示词与解析
  DigestFingerprint.java     URL 规范化 + SHA-256
  DigestWeeklyService.java   周报聚合与一次 AI 调用
  DigestJob.java             日报调度
  DigestWeeklyJob.java       周报调度
  DigestMapper.java          两张表的 SQL
  web/DigestController.java  学习者端读接口
  web/DigestAdminController.java  管理端删除
```

**新增两个 `RunKind`**：`DIGEST = "digest"`、`DIGEST_WEEKLY = "digest_weekly"`
（现有 7 个：chat / memory_extract / interview_review / mcp_ask / resume_import /
resume_optimize / entry_summary）。加常量即可，`ai_runs.kind` 是字符串列，统计页自动多两个筛选值。

**一个 `RssDigestSource` 服务五个源**（而不是五个类）：它们的行为完全一样（解析 RSS、取 title/link/pubDate），差别只在 URL 与显示名。五个类会有四份重复代码——这与 WORK-025「一源一类」不同，因为牛客是**结构化 API**、语义与 RSS 完全不同。

GitHub 与 HN 各一个类，因为它们的响应形态不同（HTML / JSON）。

## 编排流程

```
runOnce():
  1. 未启用 / 无可用的源配置 → 跳过并返回原因（**没有「分类缺失」这一步**：
     速递不写 `knowledge_entries`，见 I-02，它没有知识库分类的概念——
     "分类缺失就跳过"是 WORK-025 的逻辑，本项不适用）
  2. 对每个启用的源：抓取（包 try/catch，单源失败只记日志）
  3. 合并 → 规范化 URL 指纹 → 批内去重
  4. 查库：已存在的指纹剔除（只查当天及前后一天，不查全表）
  5. 按上限截断（maxItemsPerRun）
  6. 一次 AI 调用：输入全部候选，输出 [{fingerprint, title, summary}]
  7. 逐条入库；AI 没返回的条目 summary_state='degraded'，summary=NULL
  8. 记 RunKind.DIGEST 的用量
```

**第 4 步的窗口**：不查全表，只看 `edition_date` 在 [今天-1, 今天+1] 的行。新闻不会隔一个月又重新出现；查全表会让每次运行扫一张不断增长的表。

**第 6 步的失败语义**：AI 调用抛异常 → **整个 run 不中断**，所有条目按 `degraded` 入库（AC-005）。这比"全部不入库"合理：标题 + 链接本身就有价值。

## AI 调用设计

一次调用、两件事（挑 + 写），所以输出必须是结构化 JSON：

```json
{"items": [
  {"fingerprint": "sha256...", "title": "中文标题", "summary": "一句话摘要"},
  ...
]}
```

**输入给模型的字段**：`fingerprint`（用于回填）、原始标题、源名、`heat`、`summaryHint`（RSS 的 description 或 HN 的标题上下文，**截断到 200 字**）、语言标记。

**提示词约束**（三段，都写死在代码里，与 `EssayExtractionPrompt` 同形态）：
1. 只根据给定信息写，信息不足就说「标题说了什么」，**不得编造事实**；
2. 标题要中文；原文是中文的保持原标题，不改写；
3. 输出条数**不得超过输入条数**，可以少（挑掉不重要的），但不能多。

**为什么用 `fingerprint` 回填而不是顺序对齐**：模型可能少返回条目，顺序也会变。用指纹匹配是唯一不会错位的做法。

**DeepSeek 限制**：`nativeStructuredOutput(false)` + 不用工具（与 `EssayExtractor` 同配置）；`maxTokens` 给足 4096，因为思考 token 会占额度。

## 周报

```
runWeekly():
  1. 取上周一至今的 digest_items，按 group_key 分组，每条只取 title
  2. 总量超过阈值（默认 150 条）时按天分段摘要两轮，否则一次
  3. 一次调用生成 markdown「本周技术主线」，300~500 字
  4. upsert 到 digest_weeklies（week_start 唯一）
```

**为什么允许两轮**：一周 6 天 × 约 50 条 = 300 条，输入可能到 3 万 token。仍在上下文内，但输出质量会下降。阈值可配，先用一次；超阈值才拆。

**周报失败不影响日报**：独立调度、独立 try/catch（AC-013）。

## 接口

学习者端（都要登录，与知识库同门禁）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/digest?date=YYYY-MM-DD` | 某日全部条目，按 `group_key` 分组返回；不传 date 取今天 |
| GET | `/api/v1/digest/dates?limit=30` | 有内容的日期列表（给日期选择器） |
| GET | `/api/v1/digest/weekly?week=YYYY-MM-DD` | 某周周报；不传取本周；无则返回 null |

管理端（`hasRole('ADMIN')`，**规则必须写在路径规则里且顺序在前**——WORK-004 的教训）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/digest/admin/items?date=` | 当日条目（含 `source_key`、`fingerprint`，便于排查） |
| DELETE | `/api/v1/digest/admin/items/{id}` | 删除单条 |

**路径必须是 `<模块>/admin`，不是 `/api/v1/admin/<模块>`**（2026-10-21 核对后订正）。
项目现有四条管理端规则全部是前者：

```
/api/v1/knowledge/admin/**   /api/v1/notes/admin/**   /api/v1/ai/admin/**   /api/v1/ops/admin/**
```

写成 `/api/v1/admin/digest` 会**没有任何规则匹配**，直接落到 `anyRequest().authenticated()`
——**任何登录用户都能删速递条目**，而且不报错。这正是本节开头引用的 WORK-004 教训。

`SecurityConfiguration` 新增一条，**排在 `/api/v1/digest/**` 之前**：

```java
.requestMatchers("/api/v1/digest/admin/**").hasRole("ADMIN")
.requestMatchers("/api/v1/digest/**").authenticated()
```

控制器标 `@ApiAccess(ApiAccess.Access.ADMIN)`；`ApiSurfaceTest` 会校验声明与实际行为一致。

**删除是硬删**（不是软删）：这是从外面抓来的公开新闻，没有审计价值，也没有"恢复"需求。删掉之后同一天重跑**不会**再抓回来（指纹已不在库里 → 会重新入库），这是已知行为——**若需要永久屏蔽某个 URL，那要另做黑名单，一期不做**。

## 前端

`frontend/apps/app/src/pages/DigestPage.tsx`（新），路由 `/digest`。

结构：
- 顶部：日期选择（今天 / 前一天 / 日期列表）
- 两个页签：「日报」「周报」
- 日报：四组卡片，组内有条数徽标；每组内按 `heat` 降序（无 heat 的按发布时间）
- 每条卡片：中文标题、一句摘要、来源徽标 + heat 数字（有的话）、「存成笔记」按钮
- `summary_state='degraded'` 时摘要位置显示原文标题（灰字），不显示空

「存成笔记」= `<Link to={'/notes/new?title=…&content=…'}>`（I-16）。**需要给 `/notes/new` 加读取 query 参数预填**——这是本项对笔记页唯一的改动。

**按身份分组已撤销**（2026-10-21，随 requirements 的 D-14 / REQ-013 / AC-014 一起）。
后端 `Identity.selectable()` **只对 `job_seeker` 返回 true**，界面上另外三个是 `disabled` 的留位，
"切换身份"这个动作不存在。所以：

- **不加 `digestGroups`**，`DigestPage` 按固定顺序渲染四组：news → github → community → hackernews。
- 不做"按身份隐藏分组"。
- 恢复条件写进 requirements 的非目标：等第二个身份可选时再接 `identity` 配置。

**仍然要改 `preferredNav`**（这是导航顺序，与速递分组无关）。它的类型与取值分散在五处，
漏一处就会 TypeScript 报错或行为不一致：

| 位置 | 现状 | 要做的 |
| --- | --- | --- |
| `IdentityConfig.preferredNav` 类型 | `('learn' \| 'knowledge' \| 'notes' \| 'my-assessments' \| 'interview')[]` | 加 `'digest'` |
| `CONFIGS.job_seeker` | 5 项 | 加 `'digest'`（排在第 2 位：求职者每天看速递比泛读知识库更贴合场景） |
| `CONFIGS.postgrad` | 4 项 | 加 `'digest'` |
| `CONFIGS.civil_service` | 4 项 | 加 `'digest'` |
| `CONFIGS.daily_learning` | 4 项 | 加 `'digest'` |
| `config.test.ts` | 断言 `preferredNav.length > 0`、`job_seeker[0] === 'interview'` | 补一条：每个身份的 `preferredNav` 都含 `'digest'` |

**`HomePage` 的 `QUICK_LINKS` 也要加 `'digest'`**：那张表的 `key` 与 `preferredNav` 的类型同源
（`QuickLinkKey`），只改一边会让排序拿不到对应卡片——`HomePage` 的注释里记着这个坑（"用 includes 判断而不是按 key 去重"）。

导航：`LearnerHeader.tsx` 的 `NAV` 加一项 `{ key: 'digest', to: '/digest', label: '每日速递' }`。

首页：`HomePage.tsx` 的 `QUICK_LINKS` 加一张卡。

## 配置

```yaml
paideia:
  ai:
    digest:
      enabled: false              # 默认关闭，与 essay / decision 同纪律
      # GitHub token；未配置时退回匿名（限额 60/时）
      github-token: ${DIGEST_GITHUB_TOKEN:}
      # 单次运行最多入库条数（成本闸）
      max-items-per-run: 60
      # 每周最多取多少条日报素材做周报
      weekly-max-items: 150
      # 日期窗口：抓多久以内的内容（小时）
      lookback-hours: 30
      timeout-seconds: 20
      publish-directly: true      # D-07 内容直出
      rss:
        - key: qbitai
          name: 量子位
          url: https://www.qbitai.com/feed
          group: news
        - key: ithome
          name: IT之家
          url: https://www.ithome.com/rss/
          group: news
        - key: infoq
          name: InfoQ
          url: https://www.infoq.cn/feed
          group: news
        - key: juejin
          name: 掘金
          url: https://juejin.cn/rss
          group: community
        - key: oschina
          name: 开源中国
          url: https://www.oschina.net/news/rss
          group: community
```

**源清单在配置里而不是代码里**：加一个 RSS 源不该改代码。这是与 WORK-025 不同的一处——牛客是一个特殊 API，速递的 RSS 源是同质的。

## 与既有模块的关系

- **不新增 Maven 依赖**：RSS 解析用 JDK 的 `javax.xml` DOM/SAX（项目里没有 RSS 库，为五个源引一个依赖不值得）。JSON 用已有的 Jackson。
- **不依赖 `paideia-knowledge`**：速递不进知识库（I-02）。
- **不依赖 `paideia-note`**：转笔记是纯前端跳转（I-16）。
- **复用**：`CredentialService`（取平台凭据）、`RunRecorder`（用量）、`BizException`。

**`GuardedHttpTransport` 用不上**（2026-10-21 核对后订正）：它是 AgentScope 的
`HttpTransport` 实现，专门拦**出站模型请求**的 SSRF（模型客户端可能自己拼地址、跟随重定向）。
抓 RSS 用的是 JDK `HttpClient`，与它不在一层——WORK-025 的 `NowcoderEssaySource` 就是这么做的，
本项照抄那条路即可。

**但 SSRF 的意图要自己带上**：速递的源地址来自配置（不是用户输入），风险低于模型 baseUrl，
不过仍要**禁止跟随重定向到非预期主机**、只允许 `http`/`https`、并设连接与读取超时。
这三点写进 `DigestSource` 的契约里，由 `DigestHttp` 一个薄封装统一做（单测覆盖）。

### RSS 解析必须自己防 XXE（design 初稿漏了）

外部站点给的 XML 和用户上传的 docx 属于同一类风险面：**DOCTYPE 实体可以让解析器去读
`file:///...` 或发起内网请求**。项目里已有正确做法，照它写：

```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

先例：`backend/paideia-ai/.../resume/ResumeTextExtractor.java`。**这四条不是加固可选项**——
不设的话一个恶意/被篡改的 RSS 源就能读到进程能读的任何文件。

单测：喂一份带 `<!DOCTYPE ... [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>` 的 RSS，
断言解析**拒绝**且不抛到上层（该源记为失败，其余源照常）。

## 评审焦点（规格没覆盖、实现时容易错的地方）

| 失败模式 | 指到 |
| --- | --- |
| **管理端路径写错 → 任何登录用户能删** | 路径必须是 `/api/v1/digest/admin/**`；`ApiSurfaceTest` + `AuthorizationIsolationTest` 断言学习者 403 |
| **RSS 带 DOCTYPE 实体读本地文件** | `RssDigestSource` 单测：喂 XXE 载荷，断言拒绝且不抛到上层 |
| **抓取跟随重定向到内网** | `DigestHttp` 单测：重定向到非白名单主机时不跟随 |
| 同一 URL 带不同 `utm_*` 参数 → 两条 | `DigestFingerprint` 单测 + AC-002 |
| RSS `pubDate` 缺失或格式异常 → 整批解析失败 | `RssDigestSource` 单测：坏条目跳过、好条目保留 |
| GitHub 页面结构变化 → 解析返回空列表而非抛异常 | `GithubTrendingSource` 单测：喂一份改坏的 HTML，断言空列表 + 走兜底 |
| AI 少返回条目 → 顺序对齐错位 | 指纹回填（设计已定）+ 单测：喂少两条的 JSON |
| AI 返回不在输入里的 fingerprint → 写入垃圾行 | 只接受输入集合内的指纹，其余丢弃 |
| 并发两个实例同时跑 → 重复入库 | `uk_digest_items_fingerprint` 唯一约束挡住，捕获重复键异常按"已存在"处理 |
| 管理端删除后同一天重跑又抓回来 | 已知行为，文档说明；不做黑名单 |
| 时区：`edition_date` 用 UTC 日切，用户在中国看到"今天"可能少几条 | 前端用 `published_at` 显示本地时间；`edition_date` 只用于分组。**这个偏差是设计选择，写进测试注释** |
| 周报素材超上下文 | `weekly-max-items` 截断 + 两轮分段（可配） |
| 空候选池（所有源都失败） | 不调 AI、不写行、返回统计；页面显示当天无内容而不是报错 |
| **只改了 `preferredNav` 类型、没改 4 个身份的配置数组 → 首页排序取不到卡片** | `config.test.ts` 补断言：每个身份的 `preferredNav` 都含 `'digest'` |
