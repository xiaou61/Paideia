---
artifact: test-report
work_id: WORK-062
work: 重复代码收敛与共享工具提取
status: passed
evidence: required
verified_commit: 9aceb1fb
created: 2026-11-03
updated: 2026-11-03
---

# 重复代码收敛与共享工具提取验证报告

15 个切片全部落地在分支 `feat/work-062-utils`（worktree `.worktrees/work-062`，叠放于 WORK-061 分支之上）。
`status: passed` 的含义是：本项**实际实施的每一片**都有实测证据并全部通过；
**它不掩盖三处按记录缓做/不做**（F4、F7 的完整合并、EmptyHint）与**一项验证口径替换**（分支栈本地 e2e → typecheck+单测+构建），
逐条列在「未覆盖与已知限制」。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 后端 B1–B5 替换完整性 | rg 五组"消失判据" | 0 | 通过 | B1 文案只剩 `CurrentUser.java:42`；B2 `MessageDigest.getInstance("SHA-256")` 只剩 `Hashes.java`；B3 三种正典只剩 `ToolArgs`；B4 `lastIndexOf("```")` 只剩两变体+ModelJson；B5 `HttpClient.newBuilder()` 只剩 OpsHttp+转发代理 |
| AC-001 单元切片 | scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test | 0 | 通过 | 全模块 0 失败（B1 轮实测 805 条 / 59 秒；此后各片复跑均绿） |
| AC-001 守卫切片 | scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test | 0 | 通过 | 35 条 / 0 红（每片复跑） |
| AC-001 ops 面 | scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=Ops*,Analytics*,Tempo*,Loki*,ScheduledTaskMetrics* -Dsurefire.failIfNoSpecifiedTests=false test | 0 | 通过 | 117 条 / 0 失败 / 0 跳过（与基线一致） |
| AC-002 测试五件（T1/T2/T3/T4/T5） | 逐片替换 + app 全量：scripts/test-db.sh mvn -B -pl paideia-app -am test | 1 | 通过 | 替换 14+52+11+4+5 处；app 全量 **478 条 / 4 红 / 4 跳过**——4 红全部为 WORK-061 报告已登记的外部项，**零新增失败**；T2/T4 判据 grep 全过 |
| AC-002 受影响类实跑 | -Dtest=…（Ops 对 / AssistantConversation / MemoryAuto / MemoryJobs / Camp 家族 / Mcp / AdminUser / Resume 等） | 0 | 通过 | 47 条（T3）、38 条中含 1 已知外部红（T5）、25 条（T4 家族）等，逐片记录在 updates |
| AC-003 前端共享件（已实施的六件） | 九包 typecheck + 七包单测 + 两端 Vite build | 0 | 通过 | F1 53 处/34 文件、F2 29 处/16 文件、F3 5 处、F5 12 处、F6 33 处/14 文件、F7 encode 修复；core 31 条单测、ui 256 通过（1 红＝master 同样红）、app/admin/auth/assistant/knowledge 单测全绿；app+admin build 绿（60 秒，含懒加载守卫） |
| AC-004 e2e（口径替换，见未覆盖） | 替代门：typecheck + 单测 + build（同上行） | 0 | 通过 | 分支栈本地 e2e 需要 jar+3307 隧道且写共享库；以更强的静态门替代并如实标注缺口（不是"跑了"。） |
| AC-005 线上只读冒烟 | node live-smoke.mjs（31 端点 ×3 轮 + 2 详情派生，串行） | 0 | 通过 | **无 ≥1s、无 ≥3s**；中位 53–351ms；详见「线上冒烟」节 |
| AC-006 分支边界 | git log --oneline master..HEAD、git status | 0 | 通过 | 分支 16 笔（含 merge 工件）；主树仅工件提交；未推送、未部署 |

## 线上冒烟（2026-11-03，只读）

目标：已部署实例（`dc9db0c`）。方式：登录一次（e2e-learner / admin），31 个 GET × 3 轮 + 知识条目详情与学习线详情两条派生请求，串行、无写操作、无 AI 计费、无沙箱执行。脚本在临时目录（未提交），凭据只进环境变量、不回显。

**性能结论：没有慢接口。**

- 全站中位区间 **53–351ms**；最慢中位 `/api/v1/ai/mcp-tokens` **351ms**，其次 `/api/v1/ops/admin/analytics/overview` 196ms、`/api/v1/ops/admin/summary` 156ms；
- 最大尖峰 `/api/v1/learn/tracks` 612ms（单次抖动），其余端点最大值均 < 450ms；
- **完全没有 ≥1s 的端点**——之前已知的慢路径（SQL 沙箱执行 3.5–4.2s）不在本轮冒烟范围（未触发容器）。

**发现的非 2xx（全部定性）**：

| 端点 | 状态 | 定性 |
| --- | --- | --- |
| `/api/v1/learning/graph`、`/learning/progress`、`/learning/lines`、`/learning/plan-items`、`/learn/tracks` | 500 ×3 轮 | **同一根因**：服务器日志（journalctl）三条一组的 `Unknown column 'e.line_kind'`，全部落在 `LearningProgressMapper.findVisibleByUser`——即 `61b005d1` 引入、随 `dc9db0c` 上线的缺陷。受影响面比测试环境看到的更广（5 个学习者可见接口）。**本分支的修复（65a30c54：JOIN knowledge_categories 取 c.line_kind）覆盖这一整族**——修复后的 `LearningProgressIntegrationTest/ConceptGraphIntegrationTest/CampMasteryIntegrationTest/MasteryBktFallbackIntegrationTest` 22 条全绿，且 5 个接口全部经由该查询 |
| `/`（首页） | 406 | **冒烟脚本自身的伪影**：脚本对所有请求发 `Accept: application/json`，HTML 首页被内容协商拒为 406；补测 `curl`（默认 Accept）**200 / 0.12s**，线上首页正常 |

顺带：日志里另有 `MissingServletRequestParameterException: 'date'` 的记录（属 400 类），与本次冒烟的失败项不对应，非本项结论。

## 未覆盖与已知限制（如实列出）

- **分支栈的本地 e2e 未跑**：本地 e2e 需要从分支构建 jar + 3307 隧道 + 写入共享库（dev 与 prod 同库）。以"九包 typecheck + 七包单测 + 两端 Vite 构建"作为替代门（全部通过），**残留风险 = 16 个被改页面的运行时行为只经过静态检查与逐处人工核对，未经过浏览器级回归**；合并后建议立即跑一次两端 e2e 补这一课。
- **F4（admin e2e session 收敛）缓做**：它要改的正是验收用的量具（10 个 spec 的 signIn），验收前夜不混两类失败。
- **F7 的完整合并缓做**：四页的时间窗选项集与默认窗本就不同，需逐页核对；只修了其中真实缺陷那一行（缺 `encodeURIComponent`，`+` 会被当空格）。
- **EmptyHint 未建**：空态 20 处图标/排版差异多，不造没有第二消费者的空件。
- **`jsonRequest(body, method)` 未建**（设计里的另一半）：逐行替换 `JSON_HEADERS` 风险最小，成对改写收益不足。
- **UserFixture / TestIds 未做**：承重差异（UTC 方言、前缀数字区间）与扫描建议一致。
- **外部既有红（非本项引入，未修）**：后端 4 条（ApiWalkthrough 覆盖、McpAdmin 工具数 19/21、Sandbox 配额 ×2）已在 WORK-061 报告登记；前端 `packages/ui tokens.test.ts` 令牌检查 1 条——`AnalyticsPage` 12 处调色板类（amber/sky/emerald/violet），**已去主树实测同红**。

## 反向验证

- T1 首轮跨行正则把非 SSE 语句吞进捕获组（4 文件编译失败），`git checkout` 回退后逐处手工重做——**判据（`asyncStarted` 只剩 MockMvcSse / body 只剩 MockMvcBodies）在重做后通过**，本报告引用的就是重做后的结果。
- F6 首轮把 import 插进多行 import 中间（4 文件），修复脚本后 typecheck 全过；F1/F2 各修了一轮导出/import 遗漏。以上三轮均如实留在 updates.md。
- B2 用"消失判据"抓到扫描漏网的第 10 处（`mcp.PatService.hash`），证明判据在起作用，也说明扫描不是全覆盖。

## 验收提示

- 本项未部署、未推送；分支 `feat/work-062-utils` 叠在 `feat/work-061-arch-guards` 之上，**合并顺序 061 → 062**。
- **线上 5 条 500 是活缺陷**：合并并把 65a30c54 那族修复发上线越早越好（纯后端约 75 秒）。