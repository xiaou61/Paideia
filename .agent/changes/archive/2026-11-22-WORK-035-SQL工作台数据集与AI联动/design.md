---
artifact: design
work_id: WORK-035
work: SQL 工作台、数据集与 AI 联动
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-006, WORK-007, WORK-010, WORK-013, WORK-015, WORK-019, WORK-023, WORK-032, WORK-033]
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 10:02:57 +0800
approver_role: CTO
---

# 设计 · SQL 工作台、数据集与 AI 联动

## 系统上下文

```mermaid
flowchart LR
  subgraph 浏览器
    WB[SQL 工作台<br/>/practice/sql]
    ED[CodeMirror 6<br/>懒加载]
    ASS[悬浮助手]
  end
  subgraph 后端
    SB[paideia-sandbox<br/>执行 + 数据集解析]
    KN[paideia-knowledge<br/>关卡内容]
    AI[paideia-ai<br/>出题 / 造数据 / 助手]
    ST[FileStorage<br/>对象存储]
  end
  WB -->|run / explain| SB
  WB -->|载入关卡题| KN
  WB -->|出题 / 造数据| AI
  WB -->|填回编辑器| ASS
  ASS -->|页面上下文 + describe| SB
  SB -->|读内容| ST
  AI -->|干跑| SB
```

## 数据模型

### 新增表

```mermaid
erDiagram
  sql_datasets ||--o{ sql_dataset_reports : "被举报"
  sql_datasets {
    bigint id PK
    varchar dataset_key UK "对外的 key，不可枚举"
    varchar name
    varchar source "builtin | private | community"
    bigint owner_user_id "builtin 为 NULL"
    varchar storage_id "FileStorage 的 id；builtin 为 NULL"
    varchar schema_json "入库时抓的表结构快照"
    int table_count
    int row_count
    int size_bytes
    varchar status "draft | published | taken_down"
    datetime created_at
    datetime updated_at
  }
  sql_dataset_reports {
    bigint id PK
    varchar dataset_key
    bigint reporter_user_id
    varchar reason
    varchar status "pending | taken_down"
    datetime created_at
    datetime handled_at
  }
```

**为什么 `dataset_key` 与 `id` 分开**：`id` 是自增主键（项目约定），但对外标识不能是自增数——否则枚举 `/sandbox/run` 的 `dataset` 参数就能扫出所有私人数据集。`dataset_key` 用 UUID，形状与 `FileStorage` 的 id 一致。

**为什么 `schema_json` 存快照而不是每次现查**：现查要起容器（3 秒 + 一次配额），而 schema 在数据集内容不变时也不会变。写入时机是「入库成功之后、状态置为可用之前」。

**为什么不建 `builtin` 行**：内置数据集永远在 classpath，不依赖对象存储。把它们也塞进表会让「对象存储挂了」连累内置集。解析时先查表、查不到再查 classpath 注册表。

### 既有表

- `code_runs`：**不改结构**。`explain` 开关产生的运行仍写一条，`case_id` 沿用（可选），便于回答「这个人今天跑的都是 EXPLAIN 还是真查询」。
- 注销级联：`sql_datasets` 与 `sql_dataset_reports` 都要进 `AccountDeletionService` 的表清单——**私人数据集与举报记录都是用户数据**。社区数据集在被注销用户拥有时**不删**，改为 `owner_user_id = NULL` 并保留（否则别人引用中的社区数据集会消失）；这一条要在代码里显式写出，不能靠默认行为。

## 数据集解析

```
sql(userId, key):
  1. 查 classpath 注册表（内置）→ 命中即返回
  2. 查 sql_datasets by dataset_key
     - 不存在 → null（调用方 400）
     - source=private 且 owner_user_id != userId → null（调用方 400，**不区分"不存在"与"无权限"**）
     - status=taken_down → null
     - source=community 且 status=published → 允许
     - source=private 且 owner 是自己 → 允许
  3. FileStorage.read(storage_id) → 内容
```

**「不区分不存在与无权限」是刻意的**：区分了就等于给出一个枚举 oracle，攻击者能靠 400 的文案差异确认某个 key 存在。

**签名改造的检查点**：`sql(String key)` 直接删掉而不是重载。留一个旧签名，将来一定会有人调到它，而它没有用户参数——那是越权。

## 上传与干跑

```
POST /api/v1/sql-datasets（multipart）
  → 大小检查（≤ 512 KB）
  → 语句白名单解析（只许 CREATE TABLE/INDEX/VIEW、INSERT）
  → 沙箱干跑：用一个**一次性**的验证 key 灌进去
  → 成功 → FileStorage.put → 抓 schema → 落表
  → 失败 → 返回可读原因，不落任何东西
```

**为什么干跑用一次性 key 而不是先落表再试**：落表后失败要回滚两处（表 + 文件），而回滚失败会留下一个「存在但不可用」的数据集。先试后存只有一处写入。

**语句白名单必须自己解析而不是靠 MySQL 拒**：`LOAD DATA`、`CREATE TRIGGER`、`CREATE PROCEDURE` 在 MySQL 里都是合法的，但它们是「在沙箱里执行任意逻辑」的入口。沙箱本身有隔离，但数据集内容会被**别人的运行**重复执行——一旦里面藏了 `TRIGGER`，它在每次别人跑 SQL 时都执行一次。

## 执行计划

`/sandbox/run` 加 `explain: boolean`。为 true 时容器脚本在同一次 `mysql` 调用里先跑 `EXPLAIN FORMAT=TREE <user sql>` 再跑用户 SQL，或者只跑前者（见下）。

**取舍**：一次运行只跑一种，避免结果集混在一起难以区分。

- `explain=true` → 只跑 `EXPLAIN FORMAT=TREE`，返回一份结果集
- `explain=false` → 现在的行为

前端点「看执行计划」时发一次 `explain=true` 的运行。这仍是一次配额与一次容器启动，但**不会**比跑一次 SQL 更贵。用户看到的是「这句走了索引还是全表扫」。

**降级**：`EXPLAIN FORMAT=TREE` 在 MySQL 8.0 才有（5.7 要 `EXPLAIN FORMAT=JSON` 或默认表格）。镜像钉的是 8.0，所以直接可用；若将来降版本，退回默认 `EXPLAIN` 表格形态并标注。

### 实测结论（2026-10-23，在 codenest-online 的真实镜像上跑过）

在 `paideia-sandbox-mysql@sha256:e2eafb6e…` 上，用与生产完全相同的加固参数（`--read-only`、`--tmpfs /tmp:size=768m`、`--user 999:999`、`--memory=512m`）实跑了三条 `EXPLAIN FORMAT=TREE`，走 `--xml`（应用真实走的路径）：

```
走二级索引：-> Index lookup on customers using idx_customers_city (city='北京')  (cost=0.7 rows=2)
全表扫描：  -> Filter: (customers.`name` like '%a%')  (cost=0.55 rows=1)
              -> Table scan on customers  (cost=0.55 rows=3)
```

**结论一：现有 `SqlResultCodec` 能正确解析，不用改解析器。** XML 里 `EXPLAIN` 是**单列单行**（`<field name="EXPLAIN">`），整棵树被塞进**一个字段值**，多行之间是真实换行（XML 里是 `&#10;`，解析后变 `\n`）。所以拿到的是「1 列 × 1 行」，前端按 `\n` 与缩进自己还原成树。

**结论二（必须写进实现）：`EXPLAIN` 的树在字段值里，不在结果集结构里。** 因此：

- 前端不能用「结果表」组件渲染它，必须用专门的 `ExplainTree`（按 `->` 与缩进切节点）。
- **判读「走索引 vs 全表扫」是字符串匹配，不是结构化字段**：含 `Table scan` 即全表扫，含 `Index lookup` / `Index range scan` / `Index scan` 即走索引。这个匹配逻辑要单独成一个纯函数并单测（AC-004 靠它），不能散在组件里。
- 一个查询可能有**多层节点混合**（如上面全表扫描那条：Filter 在上、Table scan 在下）。所以节点级标注，不是整条查询级标注。

**结论三：容器内存下限比配置里写的高。** 第一次用 `--memory=512m` 跑时 mysqld 被 OOM 杀掉（`Killed`），改用 1024m 才起来。但线上 `sql-memory` 就是 `512m` 且线上能正常跑——差异在于线上是**冷启动后的稳定态**，而我的探针在同一次运行里既 `cp -a` 整个 datadir 又立刻启动 mysqld，峰值更高。**这条不改配置**，但要记进 T-05 的验收：如果加 `explain` 分支后发现偶发 OOM，第一个怀疑对象是这个峰值而不是 SQL 本身。

## 前端结构

### 包划分

| 位置 | 内容 |
| --- | --- |
| `frontend/packages/sql-workbench/`（新增） | 工作台全部组件：编辑器封装、结果表、schema 侧栏、ER 图、执行计划树、图表。**独立包**，不进 `@paideia/ui` |
| `frontend/apps/app/src/pages/SqlPracticePage.tsx` | 组合根：拿数据、接路由、接助手上下文 |

**为什么独立包**：CodeMirror 与图表库是本项目唯一的重前端依赖，放 `@paideia/ui` 会让管理端与桌面端也带上它们（`ui` 是所有应用的共享包）。独立包只在 `@paideia/app` 里被引用，且用 `lazy()` 引入。

### 体积实测（2026-10-23）

在隔离目录用 esbuild 按生产同等的 minify 设置量了三种组合：

| 组合 | min | gzip |
| --- | --- | --- |
| `basicSetup` 全套（含 vim/emacs 键位与默认主题） | 436.5 kB | 143.6 kB |
| 精简版（逐件挑选扩展，去掉 vim/emacs 与主题） | 417.1 kB | 137.2 kB |
| 仅编辑器核心（不含 SQL 语言包） | 318.3 kB | 103.7 kB |
| 仅 `@codemirror/lang-sql` | 291.2 kB | 96.9 kB |

**结论：门槛按 gzip 口径定，取 160 kB。** 原先写的「400 kB gzip 前」是个没量就定的数字，量完发现精简版 417 kB 已经超了——但 gzip 后只有 137 kB，而网络传的是 gzip 后的。按 min 口径卡门槛会逼着人去砍功能换一个用户根本感觉不到的数字。

**因此 T-01 的验收改成**：编辑器 chunk **gzip ≤ 160 kB**，且**不进主 chunk**。用精简版（`basicSetup` 里 `vim()`/`emacs()` 与主题都不需要——我们用项目自己的令牌配色）。

**这两个数也说明 SQL 语言包占了 2/3**（291 kB of 417 kB）。如果将来还要再瘦，第一步是看能不能只保留补全与高亮、砍掉 Lezer 的完整语法树——但那会牺牲括号匹配精度，不值得，记在这里备查。

### 懒加载

```tsx
const SqlWorkbench = lazy(() => import('@paideia/sql-workbench'))
```

必须验证：未进入 `/practice/sql` 时，构建产物里编辑器代码在独立 chunk 中且未被主 chunk 引用。这一条做成构建期检查（读 `dist/assets/*.js` 找主 chunk 的 import 图），而不是靠人眼看。

### 补全的 schema 来源

`sql_describe_dataset` 与前端补全共用同一份数据：`/api/v1/sql-datasets/{key}/schema`。它返回 `schema_json` 的内容（或内置数据集的静态快照）。

**内置数据集的 schema 快照怎么来**：加数据集时由脚本生成一次并提交进仓库（`sandbox/datasets/*.schema.json`），与 `.sql` 同目录。不在运行时抓——内置集不该在首次访问时多等 3 秒。

## 助手联动

### 页面上下文

`AssistantContextValue` 增加一个**有界的**场景对象：

```ts
interface PageContext {
  kind: 'sql-workbench'
  datasetKey: string
  draftSql: string        // 截断到 2000 字符
  lastResult: string      // 截断到 800 字符；只放错误或行数摘要，不放数据行
}
```

**为什么不放数据行**：结果集可能上千行，塞进提示词既贵又可能把用户没想分享的数据送进模型。只放「报错原文」或「返回 3 列 12 行」，足够模型判断问题在哪。

**为什么单独开一个字段而不是塞进 `entryTitle`**：`entryId` 那套是「正在读哪条知识」，语义完全不同；混用会让条目页的清理逻辑（`setEntry(null)`）把 SQL 上下文一起清掉。

### 只读工具 `sql_describe_dataset`

```java
new ToolDescriptor(
    "sql_describe_dataset", "sql", "查看数据集表结构",
    "给数据集 key 返回它的表、列、类型与主键。不返回数据行。",
    schema, null, /*readOnly*/ true, false, true, ToolScope.READ, true);
```

**它属于 `sql` 组**，而 `sql` 组只在 `ASSISTANT` 与 `TUTOR` 的 `toolsetsOf` 里出现。`INTERVIEW` 与 `REVIEW` 不加——面试官不需要看你的 SQL 练习库。

**工具内部必须自己再校验一次权限**：工具拿到的是 `context.userId`，`sql_describe_dataset` 内部走同一个 `sql(userId, key)` 权限判定。不能假设「模型只会传自己有权的 key」。

### 填回编辑器

`AssistantContextValue` 增加 `applySql(text: string)`，与既有的 `ask()` 同一形状（写一个待消费对象，页面消费一次就清）。**不是工具**：助手在回答里给一段 SQL 代码块，前端渲染时带一个「填入编辑器」按钮（复用 markdown 渲染器的按钮注入机制，与 WORK-014 的「问 AI」同源）。

这样助手不需要知道「编辑器」这个概念，它只是正常回答；按钮是前端的渲染能力。

## 配额与并发

```
requireQuota(userId, language)   // 日上限：SQL 500，其它 50
RunSlot.acquire(userId)          // 全局 2 + 每人 1
```

**每人并发 1 怎么实现**：`RunSlot` 内部维护 `Map<Long, Boolean>`（或 `ConcurrentHashMap.newKeySet()`），acquire 时先 tryAdd(userId)，失败抛「你有一个查询还在跑」。全局信号量仍然存在，两层都要过。

**释放顺序**：`close()` 里先还全局槽再移除 userId 标记；反过来的话，用户在标记移除后、全局槽释放前重试，会拿到「忙」而实际上没人在跑——不致命但会让人困惑。

**日上限 500 的定位**：它不是预算，是熔断。页面上不常驻显示，只在 `remaining <= 20` 时提示。`/sandbox/status` 仍返回数字（前端要判断阈值），但界面不展示大数字。

## 管理端

新增两页，进既有侧栏：

- **数据集管理**：列出社区数据集（按状态筛选），可下架/恢复；显示作者、举报数。
- **举报处理**：照搬 `NoteReportsPage` 的四状态形状（pending / taken_down），可一键下架。

## 已知限制

- **ER 图里的关联关系不全来自外键**：课程第 08 课专门讲「很多团队不用外键」，所以数据集里可能一张外键都没有。此时 ER 图只画表与列，不画连线；作者可在数据集元信息里手工标注关联（`schema_json` 里留一个 `relations` 数组）。
- **结果图表只支持柱状与折线**：饼图与散点图不在本期。
- **社区数据集没有搜索与排序**：只按名称分组，靠数量增长后再看。
- **AI 出题不能保证题目唯一性**：判定口径显式化只能减少歧义，不能消除。题面里写清「要哪几列、要不要排序、NULL 怎么算」，这是能做到的上限。
- **`explain=true` 仍占一次配额**：它确实起了一个容器。若将来觉得不合理，可以单独给它一个不计入的豁免，但那会让「配额 = 容器启动次数」这条口径变模糊。
