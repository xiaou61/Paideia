---
artifact: test-report
work_id: WORK-061
work: 架构守卫与模块治理
status: passed
evidence: required
verified_commit: 65a30c54
created: 2026-11-02
updated: 2026-11-02
---

# 架构守卫与模块治理验证报告

P1–P5 五片与附带缺陷修复全部落地在分支 `feat/work-061-arch-guards`（worktree `.worktrees/work-061`）。
`status: passed` 的含义是：本项自己的验证面全部有实测证据、并全部通过；
**它不代表全量 478 条都绿**——全量里另有 4 条红属别的项遗留的 stale 断言，逐条列在「外部阻塞」。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 守卫三件进入切片且实际执行 | scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test | 0 | 通过 | Tests run 35 / Failures 0 / Errors 0 / Skipped 0；三件守卫各有运行数字（ApiContractTest 10、ApiSurfaceTest 7、AuthorizationIsolationTest 9）；实测 59–76 秒，AGENTS.md 注记「66 秒 / 35 条」 |
| AC-001 反向：守卫首跑即抓到既有缺陷 | 同上命令（在补 LOCAL_ONLY 声明之前跑） | 1 | 通过 | ApiSurfaceTest.everyEndpointDeclaresItsAccess 报 POST /api/v1/ops/hooks/grafana 无 @ApiAccess；根因是 `ApiAccess.Access` 缺「只许回环」一类，补 LOCAL_ONLY 后转绿 |
| AC-002 正向：四图 + README 计数一致 | node scripts/gen-arch-diagram.mjs --check | 0 | 通过 | 8 个 ✓（四张结构图 + README 四项计数） |
| AC-002 反向 A：改错端点数 | 把 README 端点数为 189 后跑 node scripts/gen-arch-diagram.mjs --check | 1 | 通过 | 报「端点数与控制器数 README=189/48，事实=190/48」并指名；改回后 exit 0、README 的 git diff 为空 |
| AC-002 反向 B：改错模块数 | 把 README 模块数为 12 后跑 node scripts/gen-arch-diagram.mjs --check | 1 | 通过 | 报「后端模块数 README=12，事实=11」；生成模式不读写 README |
| AC-003 第 12 个模块与结构图 | node scripts/gen-arch-diagram.mjs --check | 0 | 通过 | 12 个模块 / 38 条依赖；ops → platform、persistence、security、ai（无意外依赖） |
| AC-003 app 源码树不再含 ops | test ! -d backend/paideia-app/src/main/java/io/github/xiaou61/ops | 0 | 通过 | 目录不存在；30 个类以 git mv 平移、包名零改动（模块图不变，只换 Maven 归属） |
| AC-003 ops 面集成测试 | scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=Ops*,Analytics*,Tempo*,Loki*,ScheduledTaskMetrics* -Dsurefire.failIfNoSpecifiedTests=false test | 0 | 通过 | 117 条 / 0 失败 / 0 跳过（25 个类，含 OpsSummaryIntegrationTest） |
| AC-004 任务语义（六项子断言） | scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MemoryExtractionJobsIntegrationTest,MemoryAutoExtractionIntegrationTest,AccountDeletionCascadeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test | 0 | 通过 | 25 条 / 0 失败 / 0 跳过；新套件 8 条覆盖：回滚原子性（唯一键故障注入）、抢占单飞、2 倍退避与上限、卡死回收、成功后消失、注销清零、failed 保留回收、中继端到端重试成功 |
| AC-004 新迁移登记校验和 | mvn -B -pl paideia-app -am -Dtest=MigrationChecksumTest -Dmigration.checksums.update=true test | 0 | 通过 | 快照新增一行 `V20261102200000__create_memory_extraction_jobs.sql -472466624`；此后切片内的 MigrationChecksumTest 常绿 |
| AC-005 四类移出、四类留根 | ls backend/paideia-ai/src/main/java/io/github/xiaou61/ai | 0 | 通过 | 根 27 个文件（26 类型 + package-info），较改造前 31 个文件少 4；`ConversationTurn`/`NoteDraft`/`RunKind`/`RunOutcome` 被公开契约签名引用（AssistantConversation、MemoryExtractor、RunRecorder），按 design I-06 留根并在本报告注明 |
| AC-005 受影响模块测试 | scripts/test-db.sh mvn -B -pl paideia-ai -am test | 0 | 通过 | 385 条 / 0 失败 / 15 跳过（全部为环境门控用例） |
| AC-006 分片提交与边界 | git log --oneline master..HEAD | 0 | 通过 | 6 笔代码/脚本提交（P1、P2、P3、P4、P5、附带修）；主树只落工件与 README 订正；远端无该分支（未推送） |
| 附带：line_kind 线上缺陷修复 | scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=LearningProgressIntegrationTest,ConceptGraphIntegrationTest,CampMasteryIntegrationTest,MasteryBktFallbackIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test | 0 | 通过 | 22 条 / 0 失败（修复前这 8 条在全量里红，根因 Unknown column 'e.line_kind'；该错误由 61b005d1 引入且已随 dc9db0c 上线） |
| 全量后端（-T 1C）：本项相关面零红 | scripts/test-db.sh mvn -B -T 1C test | 1 | 通过 | 478 条：12 红中 8 条根因 line_kind（本项附带修掉，复跑 22/0），余 4 条为外部 stale 断言（见「外部阻塞」）；其余模块全绿 |

## 未覆盖与已知限制

- **前端、桌面壳与 e2e 未跑**：本项零前端改动（全部在后端 Java、脚本与文档）。
- **中继的运维可视性未接**：`MemoryExtractionRelay` 的失败只进日志，未接 `ScheduledTaskRecorder`（与 purger 们不一致），留作后续。
- **线程池满的退避会计一次尝试**：`executor_busy` 走与真失败同一条重排路径；罕见且有界，如实记录。
- **中继长跑未观察**：单测直调 `tick()`，60 秒 @Scheduled 周期在真实时钟下的表现未在本次实测。
- **`passed` 不等于全量 478 条全绿**：本项五片的验证面（切片 35、ops 面 117、记忆面 25、ai 模块 385）全绿，全量中的外部红逐条列在下一节。

## 外部阻塞（不属于本项，未修）

全量里剩余 4 条红，全部是别的项落地后未跟上的 stale 断言，归属明确：

- `SandboxIntegrationTest.statusReportsCapability` 与 `quotaExhaustion`：断言日配额 50，实际配置为 1000——配额提升来自别的项，测试未跟上。
- `McpAdminIntegrationTest.adminViewIsNotSharedWithLearner`：学习者默认视图断言 19 个工具、实际 21——需该工具目录的 owner 判断新增的两个工具该不该对学习者可见（可能是测试滞后，也可能是真的可见性回归）。
- `ApiWalkthroughTest.walksEveryEndpoint`：12 个新端点未被 walkthrough 覆盖（learning plan-items、ai memories candidates、sql-datasets catalog 与 visibility、ops incidents close、sandbox shell run）——这是 walkthrough 按设计给出的待办清单。

## 验收提示

- 本项未部署、未推送；分支 `feat/work-061-arch-guards` 停在 worktree `.worktrees/work-061`，合入与上线另行授权。
- 附带修复（line_kind）修的是**已上线缺陷**：合入越早越好；若希望先单独上线它，可从 `65a30c54` 摘出。