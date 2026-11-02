---
artifact: requirements
work_id: WORK-061
work: 架构守卫与模块治理
status: approved
mode: strict
mode_reason: 触模块边界（拆 Maven 模块）、新增表与迁移（记忆提取任务表）、改 ai 公开面、改日常测试命令与架构图生成脚本；每一项都会改变后续所有会话的工作回路。
workflow: full
related_to: [WORK-013, WORK-009, WORK-060]
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 18:34:50 +0800
approver_role: CTO
---

# 架构守卫与模块治理需求

## 用户诉求（原话）

> 「你感觉这一套架构怎么样呢？这算是什么架构呢？」→（Agent 给出评估）
> 「那你觉得有什么优化点吗？」→（Agent 列出 P1–P5 五项）
> 「p1～p5全部优化吧开始吧 新开一个work我直接批准 你开一个worktree直接开始干就行了 开始吧」

P1–P5 是在上一条回复里**逐条给用户看过并获批准**的口径：

| 编号 | 口径 |
| --- | --- |
| P1 | 守卫进入日常回路：把 ApiSurfaceTest、ApiContractTest、AuthorizationIsolationTest 加进第二切片命令 |
| P2 | README 计数纳入 `gen-arch-diagram.mjs --check` |
| P3 | 从 paideia-app 拆出 `paideia-ops` 模块 |
| P4 | outbox/事件：补第一条必须最终一致的链路（Agent 已查明该链路在代码里明文登记：记忆提取失败不重跑） |
| P5 | 收紧 ai 公开面：8 个仅内部使用的根类型收进 internal |

## 已确认事实

| 项 | 事实 | 依据 |
| --- | --- | --- |
| 无 CI | 仓库没有 `.github/`，也没有 Jenkinsfile、.gitlab-ci.yml、azure-pipelines.yml；`ApiSurfaceTest` 注释里的「补偿手段在 CI」是声明不是事实 | `ls .github` 为空；ApiSurfaceTest.java 类注释 |
| 守卫被排除在日常回路外 | 三条守卫都在 `paideia-app`；切片一 `-pl '!paideia-app'` 直接排除该模块，切片二只跑 4 个指定类，均不含它们 | AGENTS.md「测试」节两条命令 |
| 守卫本身已存在且不弱 | ApiContractTest 已断言「声明为 ADMIN 的端点、学习者令牌必须 403」「非 ADMIN 不得 403」「匿名 401」「traceId/包络/404/405」；ApiSurfaceTest 已断言每个端点必须标 `@ApiAccess`（漏了构建失败） | ApiContractTest.java:73-147、ApiSurfaceTest.everyEndpointDeclaresItsAccess |
| README 计数会漂移 | 2026-11-02 手工订正 16 包 / 61 表 / 14 外键 / 190 端点 / 48 控制器；`--check` 只覆盖四张结构图，管不到 README | 本轮 commit cc491fd |
| ops 在装配模块里无边界 | 30 个类住在 `paideia-app` 的 `io.github.xiaou61.ops` 包；其仓内依赖只有 platform、security、ai 三个模块（无 knowledge/note/sandbox/account） | `rg import` 清单 |
| 现有跨模块副作用刻意同事务 | 注销级联与自评→记忆桥都用 `@TransactionalEventListener(BEFORE_COMMIT)`；注释明写「要么全删干净」「应当是同一件事」——原子性优先，不是最终一致 | UserDeletedEvent / EntryAssessmentChangedEvent javadoc |
| P4 的链路是已登记缺口 | `MemoryExtractionService` javadoc：「已知缺口（设计 §10 已登记）：抢到即推进游标 ⇒ 整理失败不重跑。重跑要引入重试状态机与"失败要不要计费"的一串问题，而漏一条记忆的代价远小于那套复杂度」 | MemoryExtractionService.java:32-38 |
| P4 计费口径已存在 | 失败且无用量时全额退回预留；管理员侧有对账接口 | CreditService.settle/refund、AiRunAdminService.reconcile |
| ai 公开面有真实成分 | ai 包根 30 个公开类型中 22 个被 app（含集成测试）使用；只有 8 个从未被 ai 之外引用：AssistantAnswerer、ContentClassification、ContentClassifier、ConversationTurn、NoteDraft、RunKind、RunOutcome、SummaryVisibility | 全仓 import 统计 |
| 迁移有校验和守卫 | 新迁移必须登记校验和，否则 MigrationChecksumTest 红；该测试已在第二切片里 | AGENTS.md 切片二命令 |

## 目标

1. 三条最强守卫进入「每次提交都跑」的回路（更新 AGENTS.md 第二切片命令），并实测到它们真的执行、不是跳过。
2. 架构档案索引（README）的计数漂移由 `--check` 机械拦住：数字与事实不符时退出码 1。
3. `paideia-ops` 成为第 12 个 Maven 模块；包名与类名零改动，装配模块不再承载一整块没有边界的运维面。
4. 关闭「记忆提取失败不重跑」缺口：持久任务行 + 中继重试 + 尝试上限，计费与退款口径不变。
5. ai 公开面收紧：8 个从未跨模块使用的根类型收进 internal。
6. 全部改动在 worktree 分支完成；主树保持干净；不部署、不推送。

## 非目标

- **不引 RabbitMQ、不做通用异步任务平台**：全项目没有跨进程消费者；D-14 的 AMQP 裁决仍不落地。P4 只针对记忆提取一条链路做进程内持久重试。
- **不建 CI**：GitHub Actions 跑集成测试需要 MySQL 服务容器与口令下发，不在本项；日常回路靠第二切片覆盖。
- **不改注销级联与自评桥的同一事务语义**：它们是原子性设计，比 outbox 更强，不动。
- **不动 `paideia-app` 的 console 包**（2 个类，不值得单独拆）。
- **不给 ai 模块内部再分层、不拆 ai 子模块、不动前端、不动前端包**。
- **不做 opentelemetry/指标面改造**：P3 只搬家，Prometheus 抓取、Grafana 反代、管理端 iframe 行为必须不变。

## 用户决定

| 编号 | 决定 | 时间 |
| --- | --- | --- |
| D-01 | P1–P5 全部做，按 Agent 给出的口径执行 | 2026-11-02 |
| D-02 | 新开 WORK 并由用户直接批准（原话「我直接批准」） | 2026-11-02 |
| D-03 | 开 worktree 直接开工 | 2026-11-02 |

## 实现选择

| 编号 | 选择 | 理由 |
| --- | --- | --- |
| I-01 | P1 只改 AGENTS.md 第二切片命令加三个类名，不建 CI | 一行改动即可让守卫进入每次提交；CI 的真实收益要等有服务容器方案，成本不成比例 |
| I-02 | P2 在 `--check` 分支增加 README 断言（正则取六个数），不把 README 改成生成物 | README 是索引不是生成物；只防漂移、不夺维护者的笔 |
| I-03 | P3 物理移动源码到新 Maven 模块，**包名 `io.github.xiaou61.ops` 不变** | 包名即 spring-modulith 的模块名，包名不变则模块图不变，只换 Maven 归属；集成测试留在 app（它们本来就是 app 级测试） |
| I-04 | P4 用「ai 自己的任务表 + `@Scheduled` 中继 + 条件 UPDATE 抢占 + 卡死超时回收」，不引 broker | 单消费者、单实例部署；抢占语义沿用仓内已有的条件 UPDATE 抢游标手法；跨进程需求出现时再升级为 broker，表结构可平移 |
| I-05 | P4 计费沿「失败无用量全额退款」；重试上限 3、指数退避、failed 行保留供诊断并纳入保留清理 | 与现有退款口径一致；成本有界；不留无界表 |
| I-06 | P5 只移 8 个类型；若某类型被其他根类型的公开签名引用则留在根并在报告里注明 | 机械、编译器与 ModularityTest 背书；不为好看破坏契约 |

## 功能需求

- **REQ-001**（P1）：AGENTS.md 的第二切片命令加入 `ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest`；该命令实测通过且三个类实际执行（非跳过）；命令旁的耗时/条数注记同步更新。
- **REQ-002**（P2）：`node scripts/gen-arch-diagram.mjs --check` 同时校验 `docs/architecture/README.md`「现有图」表中的计数（模块数、包数、表数、外键数、端点数、控制器数）；任一不符退出码为 1 并指出是哪一项。生成模式不修改 README。
- **REQ-003**（P3）：新建 `backend/paideia-ops` Maven 模块，`io.github.xiaou61.ops` 全部 30 个类从 paideia-app 迁入；app 以依赖引入；`backend/pom.xml` 模块清单更新；四张结构图重生成且 `--check` 通过。
- **REQ-004**（P4）：记忆提取在「抢到切片」的同一事务里写入持久任务行；异步执行前先条件 UPDATE 抢占；成功后删除任务行；失败按退避重排、超过尝试上限置 `failed` 并停；进程崩溃留下的 `running` 行由卡死超时回收重试；每次尝试的预留/结算/退款与现状口径一致；注销级联删除该表该用户的行；failed 行由既有保留清理机制回收。
- **REQ-005**（P5）：8 个仅内部使用的类型从 ai 包根移入对应 internal 子包；全仓编译通过、app 测试与 ModularityTest 通过。
- **REQ-006**：全部改动在 `.worktrees/work-061` 分支完成并分片提交；主树不携带本项源码改动；不部署、不推送；`.agent/history/updates.md` 记录每片的决策与验证。

## 质量要求

- **P4 计费**：不得因重试产生双扣；每次尝试独立「预留 → 按用量结算或全额退款」；失败尝试的退款必须落流水。
- **P4 原子性**：任务行写入与游标推进必须同一事务——不允许出现「游标动了但任务行没写」或反之。
- **P4 有界性**：单用户并发重试受既有限流约束；任务表有界（failed 行有回收路径）。
- **P3 兼容**：包名、类名、`@ApiAccess`、配置前缀（`ops.*`）、Prometheus/Grafana/Loki/Tempo 的 HTTP 客户端行为全部不变；只换 Maven 归属。
- **P2 精度**：断言只认「现有图」表里的六个数；正文其他数字不纳入（避免误报）。
- **全局**：新迁移文件用 14 位时间戳并登记校验和；每片提交前跑受影响切片（受影响的模块单测 + 第二切片）。

## 约束与依赖

- 测试入口一律 `scripts/test-db.sh`（独占测试库）；worktree 内需从主树复制 `backend/config/application-local.yml` 与 `remote-db.local.env`（被忽略文件).
- P4 的表必须进入注销级联清单；`AiUserDeletionListener` 增加一条删除。
- P3 的模块依赖方向只许 `ops → platform, persistence, security, ai`；app → ops。
- 迁移与种子分工：本项迁移只建结构（任务表），不插业务数据。

## 关联工作项

- **WORK-013**（记忆的自动提取与控制面）：P4 关闭其「失败不重跑」的登记缺口，其余行为（6 轮触发、三开关、判重覆盖）不变。
- **WORK-009**（AI 网关与积分配额）：P4 沿其预留-结算与退款口径，不改价目与闸。
- **WORK-060**（数据集工坊）：无关；不碰 sandbox 数据集路径。
- 与本项相关的架构评估结论（模块化单体、端口倒置、守卫现状）记录在本需求的「已确认事实」。

## 验收标准

- **AC-001**：跑更新后的第二切片命令，输出里 ApiSurfaceTest、ApiContractTest、AuthorizationIsolationTest 各自实际执行（有 Tests run 数字、Skipped 不包含它们），命令退出 0；AGENTS.md 注记的条数/耗时与实测一致。
- **AC-002**：把 README 里任一个计数故意改错，`node scripts/gen-arch-diagram.mjs --check` 退出 1 且指明该项；改回后退出 0。生成模式（不带 `--check`）不重写 README。
- **AC-003**：`backend/paideia-ops/` 存在且含 30 个类；`paideia-app` 源码树不再有 `io/github/xiaou61/ops` 目录；`ModularityTest` 通过；`gen-arch-diagram.mjs --check` 通过且 README 模块数为 12；app 编译与 ops 相关集成测试（OpsSummaryIntegrationTest 等）通过。
- **AC-004**：单测证明：① 入队与游标推进同事务（回滚时两者都不发生）；② 上游失败后任务行保留、退避时间与尝试数正确、预留被全额退回；③ 尝试达到上限后置 failed 且不再被中继拾取；④ `running` 超时行被回收重试；⑤ 成功后任务行删除；⑥ 注销该用户后任务行清零（集成测试）。
- **AC-005**：8 个类型不再出现在 ai 包根；`mvn -B test`（受影响模块）与 app 集成测试通过；ModularityTest 通过。
- **AC-006**：`git status` 在主树干净（仅 `.agent/` 记录类改动）；分支分片提交；未部署未推送（远端无该分支）。

## 假设

- 第二切片加入三条集成测试预计增加 20–40 秒（实测后以真实数字更新 AGENTS.md 注记）。
- 记忆提取重试可能产生候选重复，由既有判重机制覆盖，不额外去重。
- 单实例部署成立（现状就是），中继不需要分布式锁。

## 待确认问题

（无阻断项。用户已一次性批准 P1–P5 并授权直接开工；若 P4 的中继周期、尝试上限等参数需调整，随时可改，不构成阻断。）
