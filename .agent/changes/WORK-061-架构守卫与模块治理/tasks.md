---
artifact: tasks
work_id: WORK-061
work: 架构守卫与模块治理
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 18:34:50 +0800
approver_role: CTO
---

# 架构守卫与模块治理实施任务

## 依据

- `requirements.md`（REQ-001…006 / AC-001…006 / D-01…D-03 / I-01…I-06）
- `proposal.md`（P1–P5 的形态与备选取舍）
- `design.md`（任务表 DDL、中继状态机、README 断言、类型去向）

## 全局约束

- 全部改动在 worktree `.worktrees/work-061`（分支 `feat/work-061-arch-guards`），分片提交；主树不携带本项源码改动。
- 测试一律 `scripts/test-db.sh`；每片提交前跑受影响模块单测；涉及 app 的片跑第二条切片命令。
- 新迁移 14 位时间戳、登记校验和、只建结构不插业务数据。
- P4 不得改变：6 轮触发、三开关、判重覆盖、预留-结算-退款口径、注销硬删语义。
- P3 包名/类名/端点/配置前缀零改动；只允许换 Maven 归属与 pom。
- 不部署、不推送。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 守卫加了名字但实际被跳过（无 DB 口令） | TASK-001 | 目视切片二输出的 Tests run 与 Skipped 列 |
| README 断言漏项或误报正文数字 | TASK-002 | 反向验证：故意改错一个数字 → 退出 1 |
| P3 顺手引入未预期模块依赖 | TASK-003 | ModularityTest + pom 依赖面人查 |
| P3 搬家后 Prometheus 抓取/反代行为变化 | TASK-003 | OpsSummaryIntegrationTest、OpsDiagnosisServiceTest、端点清单条数不变 |
| 游标推进而任务行没写（或反之） | TASK-004 | 回滚测试：任务行插入失败时游标不动 |
| 中继与即时 dispatch 双跑同一件 | TASK-004 | 抢占单测：两次 claim 只有一次成功 |
| 崩溃后 running 行永远卡住 | TASK-004 | 卡死回收单测（locked_at 早于超时） |
| 重试造成双扣 | TASK-004 | 退款流水断言：失败尝试各退各的 |
| 新表漏进注销级联 | TASK-004 | AccountDeletionCascadeIntegrationTest 扩展 |
| 移类型后公开契约被破坏 | TASK-005 | 全仓编译 + app 集成测试 + ModularityTest |

## 任务

### TASK-001 | done | P1 守卫进入第二切片

- 对应：REQ-001、AC-001
- 修改：`AGENTS.md` 测试节的第二切片命令，`-Dtest=` 列表加入 `ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest`；跑一次实测后把条数/耗时注记改成真实数字。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false test` → 0 failed 且三个类有 Tests run 数字。
- 完成记录：首跑即红——`ApiSurfaceTest` 抓到 `POST /api/v1/ops/hooks/grafana` 从未声明访问级别。根因是 `ApiAccess.Access` 只有四种级别、缺「只许回环」这一类；补 `LOCAL_ONLY` + 给 `OpsHookController` 加类级声明 + `ApiContractTest` 新增「非回环 + 已认证 → 403、回环只有 JWT → 401」对照（先按 401 写错过一次，实测纠正）。最终实测 **35 条 / 66 秒 / exit 0**；顺带在 AGENTS.md 两条切片命令补了「必须在 backend 下跑」的说明。

### TASK-002 | done | P2 README 计数断言

- 对应：REQ-002、AC-002
- 修改：`scripts/gen-arch-diagram.mjs` 的 `--check` 分支增加 README 六项计数断言（见 design）；生成模式不碰 README。
- 验证：`--check` 通过；故意改错一个数字 → 退出 1 且指明项；改回 → 退出 0；`git diff docs/architecture/README.md` 为空。
- 完成记录：顺带修掉一个既有坑——比较前归一 CRLF（Windows 检出是 CRLF、生成物是 LF，**任何新 worktree 里 `--check` 原来恒假红**，主树恰好 LF 所以从没暴露）。实测：正向 exit 0（8 个 ✓）；反向改错端点数/模块数各 exit 1 并指名；恢复后 exit 0、README 无 diff。

### TASK-003 | done | P3 拆出 paideia-ops 模块

- 对应：REQ-003、AC-003
- 修改：新建 `backend/paideia-ops/pom.xml`；`io/github/xiaou61/ops` 30 个类从 app 迁入；`backend/pom.xml` modules 加一项；`paideia-app/pom.xml` 加依赖；重生成结构图；README 模块数 11 → 12。
- 依赖：TASK-002（README 断言先就位，模块数变化必须红→改绿，作为守卫的首个真实用例）。
- 验证：`mvn -B -pl paideia-ops -am test`；第二切片（ModularityTest 起）；`node scripts/gen-arch-diagram.mjs --check`；`node scripts/check-diagrams.mjs`；app 相关集成测试（Ops 三件）。
- 完成记录：30 个类 `git mv` 且**包名零改动**（包名即 modulith 模块名，模块图不变、只换 Maven 归属）；依赖面 = platform / persistence / security / ai（与设计一致，无意外依赖）；根 pom 还需补 `dependencyManagement`（第一次编译报 version missing，属于套件结构而非代码问题）。分片验证：**P2 断言第一次真上岗**——加模块后 `--check` 如实报 01/06 两图漂移 + README 模块数 11≠12，改 README、重生成后 exit 0；mermaid 语法 9 图块全过（借主树依赖跑 `check-diagrams.mjs .worktrees/work-061/docs`）；第二切片 35 条 0 红；app 级 ops 面 **117 条 / 0 失败 / 0 跳过**（含 OpsSummary 等集成件）。ops 模块自身无测试，测试都在 app 级。

### TASK-004 | done | P4 记忆提取持久重试

- 对应：REQ-004、AC-004
- 修改：迁移 `V2026...__create_memory_extraction_jobs.sql` + 校验和登记；`MemoryExtractionJobMapper`、`MemoryExtractionJobs`、`MemoryExtractionRelay` 新建；`MemoryExtractionService` 入队与失败处理改造；`MemoryProperties` 加参数；`AiUserDeletionListener` 与 `MemoryRetentionPurger` 扩展。
- 接口：见 design「任务表」。
- 测试：`MemoryExtractionJobsTest`（抢占/退避/上限/卡死回收/删除）；`MemoryExtractionServiceTest`（扩展：失败留行、线程池满留行、与游标同事务）；`AccountDeletionCascadeIntegrationTest`（扩展：任务行清零）。
- 完成记录：迁移 `V20261102200000`（62 张表，README 与 05 图同步更新，P2 断言再次如实报红→改绿）；`ExtractionSlice` 增 `fromMessageId` + `ConversationService.materializeSlice` 重试按边界重读；`scheduleIfDue` 改为「同一事务抢游标+入队→dispatch」；线程池满从**丢弃**改为**记一次失败退避重排**；无凭据/会话过期/欠费/记忆被关四种"无意义"情形收掉任务行（口径与旧行为一致，但留痕）。旧测试 `extractorFailureIsSwallowed` 的口径按新语义改写（这是 requirements 明示的变更，不是偷偷改绿）。实测：新套件 `MemoryExtractionJobsIntegrationTest` 8 条、`MemoryAutoExtractionIntegrationTest` 13 条、`AccountDeletionCascadeIntegrationTest` 4 条，合计 **25 条 / 0 失败 / 0 跳过**；守卫切片 35 条 0 红；`--check` 与 mermaid 语法全绿。

### TASK-005 | done | P5 收紧 ai 公开面

- 对应：REQ-005、AC-005
- 修改：8 个类型按 design 的去向表移入 internal；如遇公开签名引用则留在根并在报告注明。
- 验证：`ls` 包根剩 22 个类型（若全部可移）；受影响模块 test + app 集成测试 + ModularityTest。
- 完成记录：**按 design I-06 的口径只移了 4 个**——`AssistantAnswerer` → internal/conversation、`ContentClassification`/`ContentClassifier` → internal/decision、`SummaryVisibility` → internal/summary（根类型 30 → 26 个）；`ConversationTurn`/`NoteDraft`/`RunKind`/`RunOutcome` **留在根**：它们被根公开契约的签名引用（`AssistantConversation`、`MemoryExtractor`、`RunRecorder`），移走会让公开面暴露 internal 类型，I-06 明文规定这种情形留根并在报告注明。机械改造用一个只做包名/import 替换的脚本完成；移出的文件补了对根类型的 import（原来在根包不用写）。实测：ai 模块 **385 条 / 0 失败 / 15 跳过**（BUILD SUCCESS）、守卫切片 35 条 0 红、`--check` exit 0。

### TASK-006 | pending | 验证与报告

- 对应：AC-001…006
- 内容：跑齐全部自动化检查；写 `testing/report.md`（五列证据矩阵、覆盖全部 AC）；更新 `.agent/history/updates.md`；确认主树干净、未推送。
- 依赖：TASK-001…005。
