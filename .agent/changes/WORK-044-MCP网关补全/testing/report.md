---
artifact: test-report
work_id: WORK-044
work: MCP 网关补全：跨域能力、凭据治理与可观测
status: passed
evidence: required
verified_commit: 9313a8d
verified_at: 2026-10-27
created: 2026-10-27
updated: 2026-10-27
---

# MCP 网关补全验证报告

## 验证环境

- 时间：2026-10-27 14:47–15:56（+0800）。工作树 `.worktrees/mcp-completion`，分支 `work-044-mcp-completion`。
- 本机：Windows 10.0.22000，JDK 25（Temurin），Maven 3.9.0，Node 24、pnpm 12.10.1、Playwright 1.64。
- 数据库：经 `scripts/test-db.sh` 分配的独占库 `paideia_run_<ts>_<pid>`，**不碰共用的 `paideia_test`**。
  口令从被忽略的 `backend/config/remote-db.local.env` 读入，未回显。
- 前端依赖：worktree 内 `pnpm install --prefer-offline --ignore-scripts`（22 秒，940 包全复用缓存）。

## 交付内容

三个提交，按"能独立验证"切片：

| 提交 | 内容 |
| --- | --- |
| `324317f` | P1 凭据每日配额、用量统计、返回体截断、指标（Track B + D） |
| `54cd443` | P2 简历、面试、复习、记忆四域 8 个工具（Track A） |
| `ac45c13` | P3 凭据页用量展示与上限输入、e2e |

## 验证结果

- **`paideia-mcp` 单测 34 → 54 条，0 失败 0 跳过**（`logs/mcp-module.txt`）。
- **后端全量（除 app）1384 条，0 失败 28 跳过**（跳过的都是需要未配置外部环境的类）。
- **app 模块关键 7 类 23 条，0 失败**（`logs/app-critical.txt`）：
  `ModularityTest` 1 / `MigrationVersionFormatTest` 3 / `MigrationChecksumTest` 2 /
  `MigrationSeedOverlapTest` 3 / `McpToolSnapshotTest` 4 / `McpStartupSmokeTest` 5 /
  `McpAdminIntegrationTest` 5。
- **新写的配额真实链路 2 条，0 失败**（`logs/quota-integration.txt`）。
- **e2e 9 条全过**（`logs/e2e-mcp.txt`）：原 6 条 + 新增 3 条（上限显示、不限显示、四个新组可勾）。
- **前端 typecheck 通过**（`pnpm --filter @paideia/app typecheck`，退出码 0）。

关键数字（实测）：

- 工具总数 **57 → 65**（新增 8 个）。
- 学习者默认视图 **11 → 11**（新增 8 个全部 `defaultGroup=false`，一个都没进）。
- `paideia-mcp` 测试 **34 → 54**（+20）。
- 新迁移 2 个，校验和已登记（`migration-checksums.txt` 只增 2 行）。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 简历可列可读、他人返回"没有找到" | `mvn -pl paideia-ai -Dtest=ResumeToolsTest` | 0 | 通过 | `logs/app-critical.txt` 同批；用例 `getTreatsMissingAsNotFound`、`getRendersSectionsWithHeadings` |
| AC-002 面试会话与评分可读、不含他人数据 | `mvn -pl paideia-ai -Dtest=InterviewToolsTest` | 0 | 通过 | 用例 `getTreatsOtherPeoplesEvaluationAsNotFound`、`getRendersDimensionsAndSuggestions` |
| AC-003 复习条目与计划可读 | `mvn -pl paideia-knowledge -Dtest=ReviewToolsTest` | 0 | 通过 | 用例 `dueRendersEachEntry`、`planSummarizesStageDistribution` |
| AC-004 记忆可列可写、写入在学习者端可见 | `mvn -pl paideia-ai -Dtest=MemoryToolsTest` | 0 | 通过 | 用例 `rememberWritesAndReturnsId`、`listRendersSourceAndText` |
| AC-005 默认视图工具数不变且不含新工具 | `mvn -pl paideia-app -Dtest=McpToolSnapshotTest` | 0 | 通过 | `defaultViewUnchanged`：11 个，逐名核对；**已反向验证** |
| AC-006 四个新组各自只返回本组工具 | 同上 | 0 | 通过 | `newDomainsAreGroupedAndNotDefault`：四组各 2 个，逐名核对 |
| AC-007 凭据页显示调用次数与最后使用 | `playwright test e2e/mcp-tokens.spec.ts` | 0 | 通过 | e2e 第 7、8 条（`今日 0 / 100 次`、`今日 0 次（不限）`） |
| AC-008 第 N+1 次被拒且上游未被调用 | `mvn -pl paideia-app -Dtest=McpQuotaIntegrationTest` | 0 | 通过 | `quotaStopsAfterLimit`：真实 HTTP，前 2 次成功、第 3 次返回"每日上限"；**已反向验证** |
| AC-009 第 21 个凭据被拒 | `mvn -pl paideia-mcp -Dtest=PatServiceTest` | 0 | 通过 | `rejectsTwentyFirstToken`、`revokedTokensDoNotCountTowardLimit` |
| AC-010 超长返回被截断并带标记 | 同上 | 0 | 通过 | `capTruncatesLongTextAndMarksIt`、`capKeepsStructuredIntact`；**已反向验证** |
| AC-011 配额与计数重启后不清零 | `mvn -pl paideia-app -Dtest=McpQuotaIntegrationTest` | 0 | 通过 | 计数落在 `mcp_token_usage` 表（`usage.totals(...)` 断言），非内存 |
| AC-012 指标可抓，被拒按原因分开计数 | `curl /actuator/prometheus`（真实应用） | 0 | 通过 | `logs/prometheus-scrape.txt`：三指标齐全，`denied{reason="quota"}=2`、`calls{result="denied"}=2` |
| AC-013 既有 57 个工具清单不变 | `mvn -pl paideia-app -Dtest=McpToolSnapshotTest` | 0 | 通过 | `allToolsShape`：65 个五元组 + 18 个组计数 |
| AC-014 `paideia-mcp` 既有测试全过 | `mvn -pl paideia-mcp test` | 0 | 通过 | `logs/mcp-module.txt`：54 条 0 失败 |

### 三条反向验证（证明测试真能抓到问题）

1. **默认视图**：把 `review_due` 的 `defaultGroup` 临时改成 `true` →
   `defaultViewUnchanged` 与 `newDomainsAreGroupedAndNotDefault` **双红**（实际清单多出
   `review_due`、`review_plan`）；改回后绿。
2. **配额拦截**：在 `McpToolInvoker` 的配额判定前加 `false &&` →
   `McpQuotaIntegrationTest.quotaStopsAfterLimit` **红**（第三次没被拦）；去掉后绿。
3. **截断保留 structured**：把 `cap` 的最后参数从 `result.structured()` 改成 `null` →
   `capKeepsStructuredIntact` **红**；改回后绿。

## 过程中发现并修掉的问题

1. **我自己写的截断计数 bug**：`if (shown++ >= MAX_LISTED)` 在判断时已经自增，
   导致"还有 N 条未列出"少数一条。`ReviewToolsTest.dueTruncatesAndSaysHowManyLeft` 抓到，
   改成先判后加。
2. **两条测试断言在我接手前就已经过期**（不是本项引入）：
   - `McpAdminIntegrationTest.allAdminToolsAreRegistered` 写死 38 个管理工具，
     但 WORK-042 加了 `admin_ops` 3 个，实际 41 —— **该测试在本次改动之前就是红的**，
     只是没人在这之后跑过这个类。
   - 同文件里"学习者默认视图 12 个"，实际 19（`assistant_ask` 等进了默认组）；
     只读管理工具 19，实际 22。
   这三处已按实测值修正，并在提交信息里注明"不是本项引入"。
3. **`Map.of` 不接受 null**：把 `dailyCallLimit` 放进 transport context 时用 `Map.of` 会 NPE
   （该字段本来就可空）。改成 `HashMap`。
4. **`McpUsageRecorder` 跨包可见性**：`McpTokenController` 在 `internal.web`、recorder 在
   `internal.usage`，同模块不同子包，方法必须 `public`。编译期即暴露。

## 未验证项

1. **并发超额**：两个请求同时通过配额判定时，实际可能多放一次（判定与计数非原子）。
   design 第六节已记为已知限制；未做并发测试。
2. **UTC 日切**：配额按 UTC 日切，北京时间用户早上 8 点"跨天"。
   e2e 与集成测试都在同一天内跑，**没有测跨天的行为**。
3. **Grafana 面板的渲染**：两个 MCP 面板已写进 `paideia-ops.json`（JSON 合法、面板 id 不重复），
   但**没有在真实 Grafana 里看过它们画出线来**——本机 Prometheus 不可达，
   面板要有数据才验证得了。
4. **未部署**：全部提交都在 `work-044-mcp-completion` 分支，**未合并 master、未部署、未推送**。

## 端到端补充验证（2026-10-27 16:08，应用真实启动）

前面的自动化测试都用 `SimpleMeterRegistry` 或测试上下文。这一轮把**真实 jar 跑起来**，
用真实 HTTP 走完整链路，证据在 `logs/prometheus-scrape.txt`。

过程：`java -jar paideia-app-…jar --spring.profiles.active=local --server.port=8097`，
数据源指向独占库 `paideia_run_1027160520_1998`（`paideia_test` 已被并发会话污染：
残留迁移 `900` 导致 Flyway 校验失败——正是项目规则说的情况，故改用派生库）。

- **`/actuator/prometheus` 里三个指标都真实出现**：
  `paideia_mcp_tool_calls_total{result,toolset}`、
  `paideia_mcp_tool_duration_seconds{tool}`、`paideia_mcp_denied_total{reason}`。
  `deploy/ops/prometheus.yml` 抓的就是 `/actuator/prometheus`，所以**无需改配置即被采集**。
- **配额真实拦截**：建 `dailyCallLimit=1` 的 PAT，第 1 次 `tools/call` 返回 `isError:false`，
  第 2 次返回 `isError:true` 且正文是「今天已经调用 1 次，达到这个凭据的每日上限；明天再来，
  或让账号主人调高上限。」
- **指标按原因分类**：`paideia_mcp_denied_total{reason="quota"} 2.0`、
  `paideia_mcp_tool_calls_total{result="denied",toolset="note"} 2.0` —— 被拒不计入工具失败率。

REQ-015 的**两句都已满足**：指标进 Prometheus 抓取（实测）+ 有可见位置（`admin_ops_summary` 的 mcp 分组实测返回、Grafana 两个面板已写入）。

## 与需求的偏差

| REQ | 状态 |
| --- | --- |
| REQ-007 新增工具上限 12 个 | 实际 8 个（每域 2 个），在限内 |
| REQ-015 指标在 `admin_ops_summary` 或 Grafana 有位置 | **已做**：`admin_ops_summary` 新增 `mcp` 分组（实测返回 8 组，第 7 组是 mcp）；`paideia-ops.json` 加两个 MCP 面板 |
| REQ-016 被拒按原因分类 | 6 类全部可产生指标。`VIEW_NOT_FOUND` 原先漏了（在 servlet 层返回、到不了 invoker），**已改成在分派 servlet 里记**，实测 `reason="view_not_found"` 产生 1 |
| REQ-020 前端不新增概念 | 只加了一个输入框与一行文字，未新增页面或术语 |

## 结论

Track A（四个域全覆盖）、Track B（配额/计数/截断/数量上限）、Track C（不做，用户裁决）、
Track D 的**指标注册部分**已完成。AC-001..AC-014 全部有实测证据，其中 AC-005 / AC-008 / AC-010
做了反向验证。

Track D 的两处补齐（2026-10-27 16:44）：
- **REQ-015**：`admin_ops_summary` 新增 `mcp` 分组（实测 8 组，含 mcp；Prometheus 不可达时显示"未知"而不是 0，
  因为 0 会被误读成"没人用"）；`paideia-ops.json` 加两个 MCP 面板。
- **REQ-016**：`VIEW_NOT_FOUND` 原先在分派 servlet 就返回、到不了 `McpToolInvoker`，所以那一类指标永远不产生。
  已改成在 servlet 的 catch 里记，新增 `McpDispatcherServletTest` 两条钉住，**反向验证过**（去掉那行两条都红），
  并实测 `paideia_mcp_denied_total{reason="view_not_found"} 1.0`。

至此 AC-001..AC-014 全部有实测证据，无遗留缺口。
