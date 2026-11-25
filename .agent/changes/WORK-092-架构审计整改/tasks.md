---
artifact: tasks
work_id: WORK-092
work: 架构审计整改
status: approved
updated: 2026-11-25
approved_by: xiaou61
approved_at: 2026-11-25 14:42:04 +0800
approver_role: CTO
---

# 架构审计整改：实施任务（首批已完成；第二批待批准）

> 首批 TASK-001～003 已批准（2026-11-25 12:08）并完成。第二批 TASK-004～007 已批准（2026-11-25 13:58），正在实施。

## 依据

[需求](requirements.md)的 REQ-001～003、[提案](proposal.md)和[设计](design.md)的首批范围。REQ-004～007 仍留在需求中，不在本计划开工或验收。

## 全局约束

- 在 `work/WORK-092` 独立工作树实施，按编号执行、分别验证和本地提交。只提交本轮范围，不推送、不部署、不访问生产。
- 测试只用合成账号、合成工具及本地夹具。真实数据库测试仅通过 `scripts/test-db.sh`；没有确认隔离的测试库，不改用共享库或线上环境。浏览器检查使用本工作树独占的本地端口。

## 评审焦点

| 失败模式 | 任务 | 新增测试断言 |
| --- | --- | --- |
| 白名单无匹配被解释为不限制 | TASK-001 | `explicitGroupOutsideAllowlistIsNotVisible`、`deniedGroupDoesNotInvokeHandler` |
| 旧响应、旧续期错误或暂停的变更干扰新登录 | TASK-002 | `ignores refresh from a superseded session`、`does not retry a stale request in a new session`、两端 `切换账号后不沿用旧会话数据` |
| 正式模式启用开发回退，或未持有监听却继续初始化 | TASK-003 | `only_explicit_unbundled_debug_build_selects_dev_renderer`、`occupied_listener_cannot_start_embedded_renderer` |
| 非原定来源或子框架取得桥、开发模式读取正式缓存 | TASK-003 | `renderer_navigation_only_allows_expected_origin`、`dev_renderer_uses_ephemeral_cache`，以及窗口启动检查 |

## 任务

### TASK-001 | done | 收紧 MCP 的工具组授权

- 对应：REQ-001、AC-001；依赖：无。
- 修改：`backend/paideia-mcp/src/main/java/io/github/xiaou61/mcp/internal/{McpViews,McpViewRegistry,McpToolInvoker}.java`，及同模块的 `McpViewRegistryTest`、`McpToolInvokerTest`。
- 步骤：先写合成视图和无副作用 handler 的授权断言；再把无匹配与不限制区分开；在发现层早拒绝，在执行层独立拒绝；确认已有角色、默认组和读写用例通过。
- 验证：在 `backend/` 跑 `mvn -q -o -pl paideia-mcp,paideia-app -am -Dtest=McpViewsTest,McpViewRegistryTest,McpToolInvokerTest,ModularityTest -Dsurefire.failIfNoSpecifiedTests=false test`；实际 38 条通过，未启动数据库或外部 MCP 服务。

### TASK-002 | done | 隔离学习者端与管理端的登录会话

- 对应：REQ-002、AC-002；依赖：无。
- 修改：`frontend/packages/auth/src/{token-store.ts,auth-context.tsx,authorized-fetch.ts}` 和既有认证测试；`frontend/apps/{app,admin}/src/main.tsx`；两端浏览器回归优先扩展 `frontend/apps/app/e2e/home.spec.ts` 和 `frontend/apps/admin/e2e/admin.spec.ts`。
- 接口：计划 `TokenStore.sessionId(): number` 与 `AuthContextValue.sessionId: number` 共享一个会话标识；保持应用的 API 客户端和对外请求契约不变。
- 步骤：用延迟 Promise 锁定旧认证/请求结果和例行续期行为；添加同一窗口中连续登录两个合成账号的回归；绑定 fetch 与认证结果的会话，重挂页面树并清理旧缓存；检查旧响应不产生新账号的重试、旧 UI 不复用及续期不重挂。
- 验证：在 `frontend/` 执行 `pnpm --filter @paideia/auth test` 和 `pnpm --filter @paideia/auth --filter @paideia/app --filter @paideia/admin typecheck`。两端回归使用本地浏览器和合成 HTTP 响应；使用现有真实后端 e2e 时，分别以 `../scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/home.spec.ts --project chromium` 和 `../scripts/test-db.sh pnpm --filter @paideia/admin test:e2e e2e/admin.spec.ts --project chromium` 启动，只在确认隔离测试库后串行运行。
- 实测：认证单测 22 条、知识进度定向单测 14 条、认证/知识/学习者端/管理端四个 TypeScript 检查通过。`E2E_CHANNEL=chrome pnpm --filter @paideia/app exec playwright test --config e2e-session/playwright.config.ts --repeat-each=2` 通过 4 次（两端各 2 次，合成 HTTP），包含迟到的管理端变更与例行令牌续期。没有确认独占数据库，因此未运行真实后端 e2e。
- 实现补充：简历临时草稿随会话查询缓存清理；浏览器本地阅读记录按账号存储，旧共享记录不自动归属任何账号，已经同步到服务端的进度不受此影响。已结束的会话不发送延迟进度批次。

### TASK-003 | done | 桌面正式版只加载自身的渲染服务

- 对应：REQ-003、AC-003；依赖：无（按顺序在 TASK-002 之后执行，共享前端行为已稳定）。
- 修改：`frontend/apps/desktop/src-tauri/src/main.rs`、`frontend/apps/desktop/src-tauri/Cargo.toml`、`frontend/apps/desktop/package.json`；在现有 Rust 测试模块扩充夹具。
- 步骤：验证正式模式始终要求拥有监听，显式调试模式才接 Vite；移除 HTTP 特征探测；先绑定后读取正式缓存和构建窗口；将开发缓存留在内存，停止开发进程注册正式协议；限制 WebView 和桥注入。
- 验证：在 `frontend/apps/desktop/src-tauri/` 执行 `cargo test --locked`、`cargo test --locked --features dev-renderer`、`cargo check --locked --release --features custom-protocol,dev-renderer`。验证打包嵌入资产所需 renderer 用 `pnpm --filter @paideia/desktop prepare-renderer` 生成，不构建安装包。用隔离的桌面测试环境检查窗口、冲突时的错误与既有 OAuth 入口；缺少隔离窗口或测试 OAuth 配置时明确列为未验，不动现有安装的缓存和系统协议归属。
- 实测：锁定依赖离线取包 `cargo fetch --locked --offline --target x86_64-pc-windows-msvc` 退出 0；`cargo test --locked`、`--features dev-renderer`、`--features custom-protocol,dev-renderer` 三组各 12 条测试全通过、0 警告；`cargo check --locked --release --features custom-protocol,dev-renderer` 通过。真实隔离窗口、OAuth 提供方回跳与账号绑定仍未在隔离用户环境复核。
- 实现补充：Windows 在绑定前启用地址独占。在桌面端将 OAuth 授权交给系统浏览器，返回账号安全页时重新查询绑定结果。Web 端保留原有整页跳转，后端 OAuth 协议不变；开发宿主隐藏第三方授权并拒绝消费正式握手。复查另发现开发实例仍继承正式 `paideia` 协议与深链 IPC：开发特性下不注册原生深链插件，`tauri.dev.json` 清空协议声明并单列 identifier，渲染页移除 `deep-link:default` 权限，冷启动参数也按同一开关过滤。

## 完成条件

- 三个切片通过各自检查并有本地提交；记录命令、实际测试结果、未验项和原因。AC-004～007 仍未验，不以本批结果关闭整个工作项。

## 第二批任务（REQ-004、REQ-005，已批准 2026-11-25 13:58）

### TASK-004 | done | 学习进度与笔记保存的并发正确性

- 对应：REQ-004、AC-004；依赖：无。
- 修改：`backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/learning/{LearningProgressService,LearningProgressMapper}.java`；`frontend/apps/app/src/pages/NoteWorkspacePage.tsx`；新增 `frontend/packages/knowledge/src/note-autosave.ts` 及其单测并从 `@paideia/knowledge` 导出（`DocEditor.tsx` 无需改：保存期间编辑框始终可编辑）。
- 步骤：先写并发与慢保存回归；学习侧改为确保行 + `SELECT … FOR UPDATE` 锁 + 按 id 更新（不迁移学习表）；笔记侧把自动保存抽成与框架无关的 `NoteAutosaver`（草稿版本、保存中补存最新、编辑中不用服务端结果覆盖本地、切换文档丢弃旧文档排队保存）。
- 实测：后端 `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=LearningProgressIntegrationTest` → `Tests run: 11, Failures: 0, Errors: 0`（含新增 `concurrentDisjointBlocksBothSurvive`：两线程不同块并发写，两块的 c1/c2 都保留）BUILD SUCCESS，隔离库，不触碰线上库；前端 `pnpm --filter @paideia/knowledge exec vitest run src/note-autosave.test.ts` 5 passed；`pnpm --filter @paideia/knowledge typecheck` 0、`pnpm --filter @paideia/app typecheck` 0。

### TASK-005 | done | AI 计费预约的持久幂等与恢复

- 对应：REQ-005、AC-005；依赖：无。
- 修改：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/billing/{CreditService,CreditLedgerMapper}.java`、新增 `CreditReservationMapper.java`、`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/web/AssistantConversationController.java`、恢复调度；新增迁移 `V2026112514xxxx__add_ai_credit_reservations.sql`。
- 步骤：先写幂等与恢复回归；建预约表与台账 `round_id` 唯一键；`reserve` 记录 round_id；结算/退款幂等；控制器改用 roundId；加恢复任务。
- 验证：隔离库 `scripts/test-db.sh` 跑结算/退款幂等、重复恢复、并发；迁移在零数据新库通过。
- 实测：迁移 `V20261125141000__add_ai_credit_reservations.sql`（新建 `ai_credit_reservations` + `ai_credit_ledger.round_id` 唯一键）。隔离库 `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=AiCreditIntegrationTest,AccountDeletionCascadeIntegrationTest,ModularityTest -Dsurefire.failIfNoSpecifiedTests=false test` → `Tests run: 13, Failures: 0, Errors: 0` BUILD SUCCESS（AiCredit 8 条含新增 `settleRoundIsIdempotent`、`refundRoundIsIdempotent`、`staleReservationIsRecovered`；AccountDeletionCascade 4 条含预约行注销归零；Modularity 1 条）；`mvn -q -o -T 1C -pl paideia-ai,paideia-app -am -DskipTests compile` 退出 0。
- 实现补充（切片边界）：新增 `reserveRound`/`settleRound`/`refundRound`/`staleReservations` 与恢复任务，**只把被审计的会话路径**（`ConversationService.prepare` + `AssistantConversationController.settleOnce`）切到持久 round_id；其余约 8 个 AI 服务（简历/训练营/复盘/外部问答等）仍用旧 `reserve/settle/refund`，不随本轮改动——这是刻意的切片边界，避免一次性改动未覆盖测试的结算点；崩溃恢复只保证会话路径，其余路径按现状。`settled[0]` 内存闸仍保留（省一次 DB 往返），真正幂等由 `ai_credit_reservations` 状态 CAS 保证。

### TASK-006 | done | 知识导入中断的可恢复与 fencing

- 对应：REQ-005、AC-005；依赖：无。
- 修改：`backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/importing/{KnowledgeImportService,KnowledgeImportMapper}.java`；新增迁移 `V2026112514xxxx__add_knowledge_import_lease.sql`。
- 步骤：先写中断/过期覆盖回归；领取写 `claim_token`+时间+次数；结果写需令牌匹配；超时回收 `requeueStale`。
- 验证：隔离库 `scripts/test-db.sh` 跑中断恢复、过期 worker 不覆盖、指纹去重。
- 实测：迁移 `V20261125142000__add_knowledge_import_lease.sql`（`claim_token` / `processing_since` / `attempts` + `(status, processing_since)` 索引）。隔离库 `../scripts/test-db.sh mvn -B -pl paideia-ai,paideia-app -am -Dtest=KnowledgeImportServiceTest,KnowledgeImportJobTest,KnowledgeImportRecoveryIntegrationTest,KnowledgeImportFlowIntegrationTest,ModularityTest -Dsurefire.failIfNoSpecifiedTests=false test` → BUILD SUCCESS：`KnowledgeImportServiceTest` 6 条、`KnowledgeImportJobTest` 3 条（单飞/吞异常）、新增 `KnowledgeImportRecoveryIntegrationTest` 2 条（过期令牌写结果被丢弃不覆盖；超时 processing 被回收为 pending、旧令牌失效、可重新领取）、既有 `KnowledgeImportFlowIntegrationTest` 3 条（草稿落库/指纹去重/RAG，回归通过）、`ModularityTest` 1 条。
- 实现补充：`processPending` 每轮先 `requeueStale(now - processingTimeout)` 把「领取后被杀」的行退回 pending 再消费；`processOne` 用 UUID 令牌领取，所有 `updateResult` 都带令牌做 fencing；超时阈值 `${paideia.ai.import.processing-timeout-ms:600000}`。指纹去重仍由 `KnowledgeApi.createCollected` 保证，所以「回收后重跑」不会产生重复草稿。

### TASK-007 | done | SSE 未完整结束不显示正常完成

- 对应：REQ-005、AC-005；依赖：无。
- 修改：`frontend/packages/assistant/src/{sse.ts,client.ts}`、`frontend/apps/app/src/pages/InterviewPage.tsx`。
- 步骤：先写截断流回归；`readEventStream` 暴露终止事件；页面据终止状态提示未完整；abort 不报错。
- 验证：`pnpm --filter @paideia/assistant test`（新增）与 `pnpm --filter @paideia/app typecheck`；合成流，不调用真实模型。
- 实测：`pnpm --filter @paideia/assistant test` → 4 个文件 24 条通过（`sse.test.ts` 11 条含"截断流未终止""error 也算终止"；新增 `client.test.ts` 3 条：done 不补错并按 input/output/total 归一、无终止断流补一条"未能完整结束"错误、服务端已发 error 不叠加）。`pnpm --filter @paideia/app typecheck` 0。
- 实现补充：`readEventStream` 现返回 `StreamOutcome{terminated}`（收到 `done` 或 `error` 即 true；无响应体/仅正文即断为 false）；`sendMessage` 在 `!terminated` 时合成一条 `{type:'error',message:'回答未能完整结束（连接中断），请重试'}` 事件。面试页原有 `event.type==='error' → setError` 已据终止状态给出"没成功"提示、`busy` 正常收尾且不留"假装完成"的空气泡，故 `InterviewPage.tsx` 无需改动（原设计第 15 项对该文件的功能要求由既有处理满足）；abort 仍以异常抛出、不合成错误。

## 第三批任务（REQ-006、REQ-007，已批准 2026-11-25 14:41）

### TASK-008 | done | 发布失败整体回滚与并发串行化

- 对应：REQ-006、AC-006；依赖：无。
- 修改：`scripts/release.ps1`；新增 `scripts/deploy/remote-deploy.sh`、`scripts/deploy/test-remote-deploy.sh`；`docs/architecture/03-deployment.md`。
- 步骤：先把远端状态机外置为可测脚本并保持 `PHASE`/`DEPLOY_OK`/`DEPLOY_FAILED` 契约；停服后快照 jar+web+清单；健康失败整体还原、成功才推进并清理；原子 `mkdir` 发布锁串行化；补迁移边界说明。先写隔离模拟测试再改脚本。
- 验证：`scripts/deploy/test-remote-deploy.sh`（Git Bash）成功晋升、健康失败字节一致回滚、并发锁拒绝；`release.ps1 -ValidateOnly` 通过；不连服务器、不部署。
- 实测（2026-11-25）：远端状态机移到 `scripts/deploy/remote-deploy.sh`，`release.ps1` 改为读取模板替换占位符并新增启动时"仅回代码、不降级 schema"回滚边界提示 → `promPath` 常量。`bash scripts/deploy/test-remote-deploy.sh` 21 项断言全过（场景 1 健康通过：DEPLOY_OK、jar/web/双清单推进、标记写入、快照与锁清理；场景 2 健康失败：DEPLOY_FAILED、jar 逐字节回 `OLDJAR`、web 新文件消失回旧版、双清单未推进、无残留；场景 3 持锁：'另一个发布' 直接失败、jar/web 未被触碰）。`release.ps1` PowerShell 语法解析 0 错误；`git diff --check` 通过。未连服务器、未部署。
- 实现补充：并发锁用可移植的原子 `mkdir .deploy.lock.d` + pid 存活探测（持锁进程已死则清死锁重抢），不依赖服务器是否装 `flock`；快照用 `.rollback/`（app.jar + web/ + 两份清单），健康失败时三者一起还原。

### TASK-009 | done | 最小架构漂移检查与运维核查清单

- 对应：REQ-007、AC-007；依赖：无。
- 修改：重新生成 `docs/architecture/05-database-tables.md`、`06-api-surface.md` 并对齐 `README.md` 计数；新增 `.github/workflows/arch-guard.yml`、`docs/architecture/ops-verification.md`。
- 步骤：先让 `gen-arch-diagram.mjs --check` 归零；再加 CI 复跑图语法/漂移/内容/`ModularityTest`；写运维四项核查清单（标注未核实、需线上授权）。
- 验证：`node scripts/gen-arch-diagram.mjs --check`、`node scripts/check-diagrams.mjs`、`node scripts/check-content.mjs --scope content` 均 0；`ModularityTest` 通过；`arch-guard.yml` YAML 解析通过并用本地等价命令验证（GitHub 未实际运行，明确标注）。
- 实测（2026-11-25）：`node scripts/gen-arch-diagram.mjs` 重新生成 01/04/05/06（05 现为 79 表/16 外键，06 为 235 端点/56 控制器，含本轮新增 `ai_credit_reservations` 与导入租约列），`--check` 四项全绿并核对 README 计数（已把 77/233/55 改为 79/235/56）。`node scripts/check-diagrams.mjs` 14 图块可解析（646 文件）；`node scripts/check-content.mjs --scope content` 0 error/0 warning（478 篇）。`.github/workflows/arch-guard.yml` 用 Python pyyaml `safe_load` 解析通过，jobs = arch-guard / modularity；GitHub 未实际运行，作为本地等价命令（node 三件 + `ModularityTest`）已验证，并注明需 owner 设为 required check 才真正拦截。新增 `docs/architecture/ops-verification.md`（备份异地/隔离恢复/HTTPS/端点暴露四项，均标「未核实·需线上授权」）。
- 实现补充：`ModularityTest` 未在本轮重新跑全量（此前批次已通过、本批只改文档/CI/运维清单），CI 里作为独立 job；`arch-guard.yml` 使用 actions/checkout@v4、setup-node 22、setup-java temurin 25，仅 Node 标准库脚本无需 pnpm install。
