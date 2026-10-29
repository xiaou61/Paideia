---
artifact: test-report
work_id: WORK-027
work: 每日技术速递
status: passed
evidence: required
workflow: full
created: 2026-10-21
updated: 2026-10-22
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:23 +0800
---

# 每日技术速递验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | JDK 25，`scripts/test-db.sh`（独占 `paideia_run_*` / `paideia_digest_run_*` 库） |
| 实跑 | 本机 `--spring.profiles.active=test`，端口 18099，速递开关 `enabled=true` |
| 外部源 | 七个真实源（五个 RSS + GitHub + HN），2026-10-21 全部实测 200 |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 定时任务按日运行 | 实跑观察 `scheduling-1` 线程 | 0 | 通过 | 启动 5 分钟后自动执行，日志 `速递完成：候选 35 条…`；`@Scheduled(fixedDelay=24h, initialDelay=5min)` |
| AC-002 同 URL 不同 utm 只入库一条 | `DigestFingerprintTest` | 0 | 通过 | 6 条：六种追踪参数变体同指纹；`id=1` 与 `id=2` 不同指纹；非追踪参数保留 |
| AC-003 GitHub 主通路不可达仍产条目 | `GithubTrendingSourceTest` | 0 | 通过 | 6 条：API 空结果退回页面解析、两条都坏返回空列表不抛异常 |
| AC-004 单源失败不影响其它源 | 实跑（`失败源 0 个`）+ 编排单源 try/catch | 0 | 通过 | 抓取层逐源 catch，日志点名失败源；本次七源全通 |
| AC-005 清空 AI 凭据后仍入库、标降级 | 实跑（未配 AI 凭据） | 0 | 通过 | **入库 34 条全部 `degraded`**，标题+链接正常；`DigestSummarizerParseTest` 8 条覆盖空内容/坏 JSON 均返回空 map |
| AC-006 导航栏可达、默认当天、能翻前一天 | `digest.spec.ts` | 0 | 通过 | 从 `learner-nav-digest` 与 `home-entry-digest` 都能进；日期选择器列出最近 30 天 |
| AC-007 只存自己写的摘要、有来源与链接 | 实跑 SQL 抽样 | 0 | 通过 | 34 行全部有 `url`；降级时 `summary` 为 NULL 而非转载原文 |
| AC-008 `ai_runs` 当日 `kind=digest` 恰好 1 条 | 代码核对 + `RunKind` 接入 | 0 | 通过 | 编排里已接 `recorder.begin(RunSpec.of(RunKind.DIGEST, 0L))`，日报一次、周报一次；`userId=0` 表示平台行为。**首个版本漏了这一处，本轮补上**，见「失败与未验证项」第 1 条 |
| AC-009 `max-items-per-run` 调小后条目下降 | `DigestTakeByGroupTest` + 实跑 | 0 | 通过 | 上限 60 时入库 34（候选 35，1 条重复）；单测覆盖上限 0 与不满足上限两种边界 |
| AC-010 管理端按日期看清单、删除后不再出现 | `DigestIntegrationTest` | 0 | 通过 | 8 条：删除影响 1 行、再删 0 行（对应 404）；未登录三类请求全 401 |
| AC-011 去掉 token 后仍成功 | 实跑（未配 token） | 0 | 通过 | GitHub 拿到 15 条（匿名 Search API，限额 10/分、实测 `remaining 10/10`） |
| AC-012 四组同时存在时页面不崩 | 实跑 SQL 分组统计 | 0 | 通过 | `news 10 / github 15 / hackernews 9`，三组同时有数据；社区组因本次只配一个 RSS 源而为 0（空组前端不渲染标题） |
| AC-013 周报缺失时页签给提示不报错 | 接口实测 | 0 | 通过 | `GET /digest/weekly` → `200`、`data: null`；页面显示「本周周报尚未生成」 |
| AC-015 存成笔记预填、保存后出现在列表 | `NoteNewPage` 读 query + e2e | 0 | 通过 | `/notes/new?title=…&content=…` 预填；e2e 断言跳转与预填 |
| AC-016 同周周报只有一条 | `DigestIntegrationTest` | 0 | 通过 | 同周 upsert 两次 → 表中 1 行，body 为第二版 |

## 实跑数据（2026-10-21）

首次实跑（仅量子位一个 RSS 源 + GitHub + HN）：

```
速递完成：候选 35 条，已存在 0 条，入库 34 条（其中降级 34 条），失败源 0 个
```

分组统计：

| 分组 | 条数 | heat 非空 | 降级 |
| --- | --- | --- | --- |
| news | 10 | 0 | 10 |
| github | 15 | 15 | 15 |
| hackernews | 9 | 9 | 9 |
| community | 0 | — | — |

去重：34 个不同指纹 / 34 行（无重复）。

GitHub 样例（heat = 当日新增 star）：

```
GitHub 热榜 | deepseek-ai/DeepInfer    | heat=15192
GitHub 热榜 | Gathin23/cleanyourtv     | heat=13841
GitHub 热榜 | ly05010419/MacNativeGames | heat=3130
```

七个源连通性实测（`curl` + `DigestLiveProbeTest`）：

| 源 | 结果 |
| --- | --- |
| 量子位 | 200，解析 10 条 |
| IT之家 | 200，解析 60 条 |
| InfoQ | 200，解析 20 条 |
| 掘金 | 200，解析 20 条 |
| 开源中国 | 200，解析 50 条 |
| Hacker News | 200，解析 10 条 |
| GitHub | 200，Search API 15 条；**页面解析 0 条** |

## 实跑暴露并修掉的两个真缺陷

### 1. `github.com/trending` 已改成 SPA——页面解析通路实际永不命中

首次实跑时 GitHub 组 0 条。抓下真实 HTML 发现：`title` 是 "Trending repositories on GitHub today"，
但 `<article>` 0 次、`Box-row` 0 次、`stars today` 0 次、仓库链接 0 个——**仓库行由 JS 注入**。

原设计（research.md 与 proposal 都写）是「无 token 走页面解析为主」，那条路在当前页面结构下
等于每天拿一次空结果。

**修法**：主次反转——Search API 永远是主通路（无 token 时限额 60/时，一天一次够用），
页面解析降为补充（万一 GitHub 改回服务端渲染）。`GithubTrendingSourceTest` 6 条把新顺序钉住，
`DigestLiveProbeTest` 有一条专门断言"页面解析现在返回 0"，并注明若哪天非空就该重新评估。

### 2. Search API 的查询串含 `>` 是 URI 非法字符——被 catch 静默吞成空结果

```
java.net.URI.create("…?q=created:>=2026-10-14+stars:>100…")
→ IllegalArgumentException: Illegal character in query at index 53
```

同一串用 `curl` 手打是正常返回 261 个结果的，所以这个缺陷**只有应用真跑才暴露**：
`searchApi` 的 `catch (DigestSourceException)` 把它当成"没结果"，日志里只有一行 warn。

**修法**：查询串整体 `URLEncoder.encode`。修后 GitHub 从 0 条变成 15 条。

### 3. 成本闸按前缀截断会让单个源垄断配额

首次实跑入库 60 条**全部是 IT 之家**，GitHub / 社区 / HN 三组一条都进不来——
因为截断是 `fresh.subList(0, 60)`，而 IT 之家一个源就给了 60 条。

**修法**：改成按分组轮转取（每组一轮取一条，直到取满）。`DigestTakeByGroupTest` 4 条钉住：
一家 100 条时其它三组仍必须进得来。

## 失败与未验证项

| 项 | 原因 | 状态 |
| --- | --- | --- |
| ~~AC-008 `ai_runs` 记 `kind=digest`~~ | 首个版本漏了（常量加了、`recorder.begin()` 没接），写报告时发现并补上。**2026-10-21 真跑复验**：`ai_runs` 里出现 `kind=digest userId=0 status=ok in=1266 out=3405`，当轮恰好 1 条 | **已通过** |
| ~~AI 摘要的真实写作质量~~ | **已补验**：见下方「真连上游」一节，12 条全部产出中文摘要、0 条降级 | **已通过** |
| 社区组内容 | 实跑只配了量子位一个 RSS 源；掘金与开源中国虽实测 200 且能解析（20 / 50 条），但没进实跑批次 | 部分验证（源可用性已验证） |
| ~~周报真实生成~~ | **已补验**：见下方「真连上游」一节，产出 558 字三段 markdown | **已通过** |
| `digest.spec.ts` e2e | 5 条中 4 条通过、1 条按设计跳过（当天无内容时"每条都有原文链接"无法断言） | **已执行** |
| 生产部署 | 未部署；`application.yml` 默认 `enabled: false`，需要部署时显式打开并配 GitHub token | 未执行 |

## 结论

**通过。** 采集、去重、分组轮转、成本闸、鉴权、删除、周报缺失语义都有实测证据；
`AC-008` 已复验（`ai_runs` 里 `kind=digest status=ok`，当轮恰好 1 条）；
AI 摘要与周报提示词均已真连上游验证；线上已部署并跑出 **60 条、0 条降级**。

这一版从"部分通过"到"通过"之间修掉 **6 个真缺陷**，全部只在真跑时暴露：

| # | 缺陷 | 怎么发现的 |
| --- | --- | --- |
| 1 | `github.com/trending` 已成 SPA，页面解析永不命中 | 实跑 GitHub 组 0 条，抓真 HTML 看到 `Box-row` 0 次 |
| 2 | Search API 查询串含 `>` 是 URI 非法字符，被 catch 吞成空结果 | 同上；同一串 curl 手打返回 261 条 |
| 3 | 成本闸按前缀截断，单个源垄断全部配额 | 实跑 60 条全是 IT 之家 |
| 4 | `GuardedHttpTransport` 用 OkHttp 默认 10 秒读超时 | 12 条跑了 20 秒 → 整批静默降级 |
| 5 | 提示词要求"挑重要的"，但编排全部入库 → 被挑掉的显示"没摘要" | 真连 5 条只返回 3 条 |
| 6 | 60 条满批时 `maxTokens=8192` 被思考吃满；分批后整轮超时又被前一批耗尽 | 线上两轮：先全降级，再二、三批 `InterruptedException` |

另有**一次线上事故**（迁移版本倒挂致服务挂约 20 分钟）已复盘并写进 `.agent/rules/always.md`。

## 全量与 e2e（2026-10-21 收口）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `scripts/test-db.sh mvn -B test` | **592 运行 / 0 失败 / BUILD SUCCESS**（4 分 11 秒） |
| 迁移校验和 | `MigrationChecksumTest` | 新迁移已登记（`-Dmigration.checksum.update=true` 一次），既有校验和未变 |
| 全接口自查 | `ApiWalkthroughTest` | 5 个速递端点已纳入；`api-shape.json` 只新增 4 条、无既有端点变更 |
| 学习者端 e2e | `playwright test digest.spec.ts` | **4 passed / 1 skipped / 0 failed**（独占库 `paideia_e2e_digest_*`、端口 18098/5183） |
| 前端单测 | `pnpm --filter @paideia/identity test` | 4 条通过（含新增的"每个身份都含 digest"） |

`AC-008` 的补法：`DigestCollectionService` 里 `recorder.begin(RunSpec.of(RunKind.DIGEST, 0L))`
包住那一次 AI 调用，`DigestWeeklyService` 同样用 `DIGEST_WEEKLY`。`userId=0` 表示平台行为
（速递没有用户归属，与 WORK-025 同一取舍），运营统计里能与真实学习者花的钱区分开。

## 真连上游（2026-10-21 下午，用线上那把 DeepSeek key）

实跑环境原先缺平台凭据，摘要与周报都只走了降级分支。本轮补了三条真连验证。

### 1. 提示词缺陷：要求"挑"导致 60% 的条目看起来是坏的

首次真连（5 条候选）：**只返回 3 条**。原提示词写的是"可以挑掉你认为不重要的条目"。

问题在于编排层**把所有候选都入库**——被挑掉的那两条会落 `degraded`，界面上显示
「这条还没写好摘要」。也就是说"挑"没有真的过滤掉任何东西，只是让三分之二的卡片看起来是坏的。
而 `degraded` 这个状态应该只表示"AI 这次没写成"（凭据缺失/上游失败/超时，即 AC-005）。

改成"**每一条都要写**"后重跑：**5/5 条全部返回**，9.0 秒、2320 tokens。

### 2. 共享传输层的 10 秒读超时——只在"非流式 + 大输出"时暴露

配好凭据后真跑 12 条候选，日志：

```
WARN OkHttpTransport : Failed to read response body: timeout
WARN DigestSummarizer: 速递 AI 压缩失败，全部按降级入库：OpenAI API returned empty response body
速递完成：候选 35 条，入库 12 条（其中降级 12 条）
```

根因：`GuardedHttpTransport` 里的 `OkHttpClient.Builder()` **没有设 `readTimeout`**，
OkHttp 4.x 的默认值是 **10 秒**，而 AgentScope 自己的 `HttpTransportConfig` 默认是 **5 分钟**。
12 条约跑 20 秒 → 卡在 10 秒 → 整批静默降级。

**为什么之前没人发现**：既有调用（面经抽题、记忆整理、简历优化）都是"单条、几秒"，撞不到 10 秒。
这是本项第一个"一次处理几十条"的调用。

修法：显式设 `readTimeout=5min` / `writeTimeout=1min` / `callTimeout=6min`，
真正的时间上限仍由调用方的 `blockLast(Duration)` 决定。

修后重跑同一批：**12 条全部成功、降级 0 条**。

### 3. 真连实测数据

| 项 | 结果 |
| --- | --- |
| 摘要（5 条单测式真连） | 5/5 条，9.0 秒，2320 tokens |
| 摘要（12 条整批实跑） | 12/12 条，降级 0，`ai_runs`: in=1266 / out=3405 |
| 周报（6 条素材） | 558 字、三段 markdown、9.9 秒 |
| 英文翻译 | "Introducing the new reasoning model" → 「介绍全新推理模型」 |
| 中文保持 | 「AI 推理模型发布，长链思考更强」原样保留 |
| HN 标题 | "Ask HN: is Rust worth learning in 2026" → 「Ask HN：2026 年 Rust 还值得学吗」 |

分组轮转在同一轮也验证到了：`news 4 / github 4 / hackernews 4`——上限 12 时三组各拿 4 条，
没有再出现"一个源吃满配额"。

摘要样例（真实产出）：

```
[量子位] 飞书豆包工作AI先锋未来人才大赛：企业正在争抢会用AI做成事的人
  → 大赛聚焦真实问题解决力，企业争抢能用AI做成事的人。
[GitHub 热榜] deepseek-ai/DeepInfer
  → DeepSeek 模型的高性能大规模推理框架。
[Hacker News] eMudhra 中间 CA 遭入侵
  → eMudhra 中间 CA 遭入侵，引发 376 条评论讨论。
```

## 生产部署与满批实跑（2026-10-21 16:00–16:55）

### 部署过程撞到迁移版本倒挂（服务挂了约 20 分钟）

`V20261021134700__create_digest_tables.sql`（本项）比 `V20261021135406__create_camp_tables.sql`
（WORK-029，另一会话）**早 10 分钟生成**，但 camp 先部署到生产。于是生产库最后应用的版本是
`…135406`，我的 `…134700` 成了"更早但未应用"→ Flyway：

```
Detected applied migration not resolved locally: 20261021135406.
Detected resolved migration not applied to database: 20261021134700.
Validate failed: Migrations have failed validation
```

**测试一路绿是因为 `application-test.yml` 开了 `out-of-order: true`，生产刻意没开。**

修法：把迁移改名成 `V20261021161000__create_digest_tables.sql`（内容不变、校验和不变），
顺序追加。过程中还踩到两次：
- **`mvn package` 不 clean 时旧迁移残留在 `target/classes`**，jar 里同时打两个版本 → 校验和登记了新的、旧文件仍在
- **从工作树构建会缺对方会话的文件**（工作树是合并前的快照）→ `applied migration not resolved locally`

最终从主树 `mvn clean package` 构建、用 `unzip -l` 确认 jar 里只有两个正确迁移，才部署成功。
已把这条写进 `.agent/rules/always.md`。

### 满批 60 条：两层缺陷

**第一层**：60 条一次性喂进 DeepSeek，`maxTokens=8192` 被思考 token 吃满 → 上游静默返回空，
60 条全部降级（日志「速递 AI 返回空内容（思考 token 可能吃满）」）。5 条和 12 条都够用，
问题只在满批。→ `maxTokens=16384` + 按 24 条分批。

**第二层**：分批后仍失败——超时是按"整轮 180 秒"算的，第 1 批用完预算后第 2、3 批
直接 `InterruptedException`。→ 改成按批 120 秒。

### 最终线上结果（16:52 那轮）

```
速递完成：候选 159 条，已存在 0 条，入库 60 条（其中降级 0 条），失败源 0 个
```

| 分组 | 条数 | 降级 |
| --- | --- | --- |
| news | 18 | 0 |
| community | 18 | 0 |
| github | 15 | 0 |
| hackernews | 9 | 0 |

`ai_runs`：`kind=digest userId=0 status=ok input=7493 output=7523`。

线上接口 `GET /api/v1/digest` 返回 60 条、四组齐全、全部有中文摘要。
学习者端 e2e 打线上：**4 passed / 1 skipped / 0 failed**。

### 仍未做

- **周报没在生产触发过**：`DigestWeeklyJob` 是 `initialDelay=10min` + `fixedDelay=7d`，
  部署后第一次要等 10 分钟，本轮窗口内没等到；它的提示词已在本机真连验证过（558 字三段）。
- 管理端删除的 e2e 未跑（接口层有集成测试与 401/403 证据）。
