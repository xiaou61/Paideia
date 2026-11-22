---
artifact: requirements
work_id: WORK-042
work: AIOps 自建诊断层
status: approved
mode: strict
mode_reason: 跨 paideia-ai（新增一次性诊断端口与流水种类）、paideia-app（证据采集、诊断编排、只读工具、webhook）、管理端前端、数据库迁移、部署（新增 Loki/Alloy 两个常驻服务与告警收件人）；同时涉及日志出站、凭据与循环触发，属于安全与部署边界变化
workflow: full
depends_on: []
related_to: [WORK-028, WORK-036, WORK-019, WORK-009]
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 18:11:29 +0800
approver_role: CTO
---

# AIOps 自建诊断层需求

## 用户原始诉求

2026-10-26 用户问：「我们目前的监控相关的建设做的怎么样呢？其实我想把主流的 AI OPS 这一套监控也放到我们的这个里面，你看看怎么来搞呢？联网搜搜主流的技术方案。」

在看到现状盘点与三条路线后，用户逐条裁决：「1， 自建 2可以上 3可以的接受。」
即：**诊断层自建**（不引入 HolmesGPT / Keep）、**上 Loki**、**接受脱敏后的日志出站到 DeepSeek**。

## 已确认事实

- **指标面已存在**（WORK-028）：Prometheus + node/mysqld/redis/blackbox exporter + 应用 Micrometer + 自定义定时任务指标 + textfile（systemd / Docker / 备份）。Prometheus 只保留 **3 天**（`deploy/ops/prometheus.service:22`），数据目录实测 96MB。
- **告警面已存在**：4 条 Grafana 规则（目标下线、磁盘 <15%、备份过期、Laya 内存 >90%）→ 邮件；规则必须显式写 `notification_settings.receiver`，表达式必须用 `bool` 修饰符（`deploy/ops/provisioning/alerting/paideia-rules.yaml` 的注释记录了两次实测教训）。
- **界面面已存在**：管理端 `/ops/panel` 七张四态卡片 + Grafana 面板内嵌（`frontend/apps/admin/src/pages/OpsPage.tsx`）；摘要判据只有一份（`OpsSummaryService`）。
- **AI 已能读运维摘要**：`admin_ops_summary` 是只读、ADMIN scope 的工具（`backend/paideia-app/src/main/java/io/github/xiaou61/ops/OpsAdminTools.java:37`），外部 MCP 用管理员 PAT 可以调；**内部学习者助手的视图只给 READ scope，看不到 ADMIN 工具**（`AgentToolBridge` 固定按 `USER` 角色取清单，既有行为）。它只返回当前快照，不含正在 firing 的告警，也不能查时序与日志。
- **日志面缺失**：应用只写 journald，没有日志存储与检索。生产实测 24 小时 **6216 行**，其中 WARN/ERROR **131 条**；journald 当前 495MB。日志量很小，Loki 的存储与内存开销可接受。
- **链路面缺失**：没有 OpenTelemetry / Tempo，无法回答"慢在哪一步"。
- **AI 一次性调用基建已存在**：`SingleShotModelCaller`（`paideia-ai/internal/provider`）、`CredentialService.access(userId)`、`CreditService` 的预留/结算、`RunRecorder` 的 `RunKind` 遥测。新诊断可以复用，不需要新框架。
- **服务器实测**（2026-10-26）：4 核 / 15GB（已用 8GB，可用 7GB）/ 79GB 盘剩 46GB；监控栈常驻约 190MB；Laya 2.76GB、MySQL 675MB、Paideia 480MB。
- **候选组件版本**（2026-10-26 GitHub）：Loki `v3.7.9`、Alloy `v1.21.2`；两者均为 Grafana 官方当前 release。Alloy 的 journal 读取要求 `alloy` 账号属于 `adm` 与 `systemd-journal` 组。
- **外部方案现状**：HolmesGPT 3.7k★ / Apache-2.0 / CNCF Sandbox，Keep 12.4k★（license 非标准），SigNoz 32.4k★，Netdata 81k★；用户已明确不采用外挂平台。

## 用户决定

2026-10-26：

1. **诊断层自建**，复用现有助手/工具/积分/遥测基建；不部署 HolmesGPT、Keep、SigNoz、Netdata。
2. **引入 Loki + Alloy** 采集 journald 日志；保留期与 Prometheus 对齐到 30 天。
3. **接受脱敏后的日志内容出站到 DeepSeek**；脱敏是硬前提，不是可选项。

## 实现选择

- **诊断 = 确定性采集 + 单次模型调用**，不做多轮工具循环：证据由服务端按固定查询采集，模型只负责解释。理由：成本有上界、输出稳定、不踩 DeepSeek「工具调用与结构化输出不能同一次并用」的限制；单机栈的排查空间很小，工具循环的灵活性用不上。
- **模型不能写 PromQL / LogQL / shell**：只暴露预置查询目录与固定 selector，杜绝把自然语言变成任意查询。
- **两种触发**：管理端手动按钮、Grafana 告警 webhook。不做 24/7 轮询——没有异常时不该持续花钱。
- **三个只读工具**：`admin_ops_metrics`、`admin_ops_logs`、`admin_ops_alerts`，ADMIN scope，外部 MCP（管理员 PAT）可用；内部学习者助手按既有行为不暴露 ADMIN 工具。工具只读、幂等、不可破坏。
- **告警 webhook 只接受回环来源 + 共享令牌**；令牌放服务器 600 文件，经 systemd 环境变量注入，不进仓库。
- **冷却与单飞**：同一告警 10 分钟内只诊断一次；同一时刻最多 1 个诊断在跑。防止告警风暴把积分打穿。
- **报告落库**到新表 `ops_diagnoses`（一次诊断一行），管理端列表 + 详情展示 markdown；不额外发邮件（Grafana 已经把告警邮件发出去了）。
- **积分归属**：手动触发记在触发它的管理员；webhook 触发记在配置的管理员账号（`paideia.ops.diagnosis.user-id`）。余额不足直接失败，不发起上游请求。
- **发版标记用 textfile 指标**（`paideia_deploy_epoch_seconds` / `paideia_build_version`），不申请 Grafana annotation API 令牌——少一个长期凭据。
- **不做任何自动修复**：没有写工具、没有重启脚本、没有 runbook 执行器；诊断结果只给人看。

## 背景

主流 AIOps 拆开是五层：数据（指标/日志/链路）→ 检测（阈值/基线/ML）→ 关联（去重/收敛）→ 诊断（根因）→ 执行（修复）。

我们目前只有第一层的一半（指标）和第二层的最简形态（静态阈值），第三层在当前的告警量下不需要，第五层风险过大。**真正缺的是日志与诊断**。本项只补这两块，不追求"全套 AIOps"。

## 目标

1. 让运维数据可被 AI 使用：日志可查、指标留得住、发版时间可对照。
2. 告警发生时自动产出可读的根因假设与处置建议。
3. 管理员能随时手动发起一次诊断，并回看历史报告。
4. 现有四条告警、运维页与 `admin_ops_summary` 的行为不变。

## 非目标

- 不做分布式链路（Tempo / OpenTelemetry SDK）。
- 不做 ML 异常检测、告警去重/关联平台。
- 不做自动修复、自动重启、自动回滚。
- 不做多主机/多实例；仍是单机单实例。
- 不引入 HolmesGPT / Keep / SigNoz / Netdata。
- 不重做 Grafana 面板；只在运维页增加日志入口与诊断区。
- 不改变现有四条告警的阈值与语义。

## 用户与工作流程

- **管理员 A（手动）**：打开运维页 → 点「AI 诊断」→ 可选择填一句关注点 → 页面出现"诊断中"→ 完成后显示报告与历史列表。
- **管理员 A（自动）**：Grafana 规则 firing → 邮件照旧 → webhook 打到应用 → 应用在冷却允许时自动诊断 → 报告出现在运维页。
- **外部助手（ZCode 等）**：用管理员 PAT 连 `/mcp`，问「最近一小时 5xx 多少」→ 调 `admin_ops_metrics`；问「有没有 error 日志」→ 调 `admin_ops_logs`。

## 功能需求

- **REQ-001**：Prometheus 保留期从 3 天提高到 30 天，且重启后生效值可查询。
- **REQ-002**：Loki 以 systemd 单元常驻，只监听回环，保留 30 天；Alloy 只采集白名单 unit 的 journald 日志（`paideia`、`laya`、`rustfs`、`mysql`、`redis-server`），打上 `unit` 标签。
- **REQ-003**：Grafana 增加 Loki 数据源；管理端运维页提供日志查询入口（打开 Grafana Explore 或日志面板）。
- **REQ-004**：新增两条告警：应用 HTTP 5xx 比例超过阈值、应用 ERROR 日志在 5 分钟内超过阈值。告警仍走现有邮件通道。
- **REQ-005**：发版成功后写入部署时间与版本指标，诊断证据与面板可读。
- **REQ-006**：新增三个只读工具（metrics / logs / alerts），ADMIN scope；非管理员不可见、不可调；外部 PAT 需 admin scope + ADMIN 角色。
- **REQ-007**：管理端运维页支持手动发起诊断、查看进行中状态、查看历史列表与报告详情。
- **REQ-008**：Grafana firing 告警可通过 webhook 触发诊断；resolved 告警不触发；错误令牌与外部来源被拒绝；失败不影响告警邮件。
- **REQ-009**：诊断证据由服务端确定性采集，包含：运维摘要、关键指标、最近 ERROR/WARN 日志、当前 firing 告警、最近发版时间；总字符数有硬上限。
- **REQ-010**：日志出站前必须脱敏（邮箱、Bearer、JWT、password/token/secret/api_key 赋值、长十六进制串），并去掉控制字符；日志内容在提示词中标记为不可信数据。
- **REQ-011**：诊断消耗走现有积分预留/结算；新增独立流水种类；余额不足时诊断标记失败且不发起上游请求；调用登记到 AI 遥测。
- **REQ-012**：报告固定包含「结论」「证据」「可能原因」「建议动作」「需要人工确认」五节，并引用证据中的具体数值/时间。
- **REQ-013**：配置可关闭诊断、调整冷却窗口、webhook 令牌、诊断账号与保留条数；关闭时手动与自动触发都得到明确提示。
- **REQ-014**：新增能力不提供任何写操作或修复动作；工具与端点都是只读。

## 质量要求

- 诊断异步执行，手动请求在 1 秒内返回 `202` 与诊断 id；上游超时上限 120 秒。
- 证据总大小 ≤ 12,000 字符；单条日志 ≤ 500 字符；日志最多 60 条。
- 脱敏对已知模式必须全覆盖，并有单元测试；无法识别的长敏感串按保守策略截断。
- webhook 令牌比较使用常量时间比较；请求体超过 256KB 直接拒绝。
- 新增常驻服务只监听 `127.0.0.1`，各自设置 `MemoryMax`，不改变公网端口（仍只有 8081）。
- 不新增 Java 运行时依赖；Loki/Alloy 是服务器二进制，不进仓库。
- 诊断失败原因写入 `error_message`，页面可见；不得静默失败。
- 管理端新增控件可用键盘操作，状态不只靠颜色表达。

## 约束与依赖

- 遵守 WORK-028 的部署纪律：所有监控组件只绑回环、独立 systemd 单元、`MemoryMax`、`install-ops.sh` 必须带代理。
- `release.ps1` 只重启 `paideia`，不重启监控单元；Loki/Alloy 由 `install-ops.sh` 安装与更新。
- 迁移文件名用 14 位时间戳；只建结构，不插业务数据；改前先 `git log --all -- backend/paideia-app/src/main/resources/db/migration` 查同表冲突。
- 公开仓库：webhook 令牌、管理员 id、服务器地址一律不入库；仓库只放占位。
- 模型调用必须走 `SingleShotModelCaller` + `GuardedHttpTransport`（SSRF 第二道闸），不得另起 HTTP 客户端。
- AI 积分与遥测沿用 WORK-009 / WORK-022 的语义；新流水种类不改表结构。
- 工具名必须是 `<toolset>_<verb_object>` 且全小写下划线（DeepSeek 限制）。

## 关联工作项

- **WORK-028（运维监控面板）**：本项在它的指标/告警/运维页之上扩展，不替换。
- **WORK-036（AI 观测面板）**：诊断调用会以新的 `kind` 出现在 AI 观测里；本项不改面板。
- **WORK-019（MCP 网关与工具目录）**：三个新工具通过同一目录暴露；权限由 ADMIN scope 与角色共同约束。
- **WORK-009（AI 网关与积分配额）**：诊断消耗积分，新增独立流水种类。
- 无硬依赖；以上均已完成。

## 验收标准

- **AC-001**：生产 Prometheus 运行时保留期显示 2592000 秒（30 天），且配置文件中不再是 3 天。
- **AC-002**：Loki 与 Alloy 单元 `active`，只监听 `127.0.0.1`；用 Loki API 能查到 `unit="paideia.service"` 的最近日志。
- **AC-003**：Grafana 的 Loki 数据源健康检查通过；运维页能打开日志查询入口。
- **AC-004**：两条新告警规则已被 Grafana 加载，其表达式在正常状态下有确定值（不是 NoData）。
- **AC-005**：一次发版后 `paideia_deploy_epoch_seconds` 与 `paideia_build_version` 可被 Prometheus 查到。
- **AC-006**：ADMIN 能看到并调用三个新工具；非管理员在 MCP 视图中看不到、直接调用被拒；三个工具均声明只读。
- **AC-007**：管理端手动诊断返回 202，完成后列表与详情显示 markdown 报告；失败时显示错误原因。
- **AC-008**：带正确令牌的回环 webhook 能触发诊断；错误令牌返回 401；非回环来源被拒；resolved 事件不产生诊断。
- **AC-009**：脱敏单元测试覆盖邮箱、Bearer、JWT、password/token/secret/api_key 赋值、长十六进制串；输入输出对比无残留。
- **AC-010**：余额为 0 的账号触发诊断时，诊断行 `FAILED`，且模型调用计数为 0（测试用假上游断言）。
- **AC-011**：同一告警在冷却窗口内第二次触发不产生新诊断行。
- **AC-012**：报告包含五个固定小节，并至少引用一条指标数值、一条日志原文（脱敏后）与发版时间（若有）。
- **AC-013**：关闭开关后，手动端点返回明确错误码/文案，webhook 只记录跳过、不创建诊断行。
- **AC-014**：新增端点与工具中没有写操作；`readOnly` 断言与端点清单可核对。
- **AC-015**：受影响的后端测试通过（0 failed，skipped 单独说明）；管理端 e2e 覆盖运维页诊断区。

## 假设

- Loki/Alloy 的 GitHub release 二进制能经服务器现有代理下载；Ubuntu 24.04 的 glibc 满足要求。
- journald 对白名单 unit 暴露 `__journal__systemd_unit` 字段，Alloy 可据此打 `unit` 标签。
- 管理员账号有足够积分；生产部署时会把管理员 id 配进 `paideia.ops.diagnosis.user-id`。
- 4 核 / 7GB 可用内存能容纳 Loki + Alloy（按同类部署估算合计 300–500MB），并在单元上设 `MemoryMax` 兜底。
- DeepSeek 对纯文本诊断（不带工具、不用结构化输出）稳定返回非空内容。

## 待确认问题

无。三项会改变范围的决定已由用户于 2026-10-26 裁决；其余为实现选择，已记录理由。
