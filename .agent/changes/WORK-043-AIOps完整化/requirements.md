---
artifact: requirements
work_id: WORK-043
work: AIOps 完整化：事件、基线、链路、SLO 与评测
status: approved
mode: strict
mode_reason: 跨 paideia-ai（AI 成功率指标与诊断证据）、paideia-app（事件/时间线/反馈/迁移）、paideia-account（登录指标）、paideia-web（trace id）、管理端前端、数据库迁移、Grafana 告警路由与部署（Tempo/OTel）；涉及公共响应头 X-Trace-Id、日志关联、告警语义、SLO 目标与诊断成本
workflow: full
depends_on: []
related_to: [WORK-042, WORK-028, WORK-036, WORK-009]
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 13:52:33 +0800
approver_role: CTO
---

# AIOps 完整化需求

## 用户原始诉求

2026-10-27 用户对 WORK-042 的复盘提出：「没有基线/异常检测 没有链路 没有告警关联与事件生命周期：没有反馈与评测 业务 SLO 缺失：这一些都是要做的……下一个 work 要完全补全这个操作，然后具体设计思路你可以去参考一些开源项目也好，还是什么架构也好。」

即：在 WORK-042 的「指标 + 日志 + 单次诊断」之上，补齐 **基线/异常检测、链路、告警关联与事件生命周期、反馈与评测、业务 SLO** 五块，作为一个完整的下一期。

## 已确认事实

- **WORK-042 已上线**：Prometheus 保留 30 天；Loki + Alloy 采集 `paideia/laya/rustfs/mysql/redis-server` 的 journald；6 条 Grafana 告警（4 旧 + 5xx + ERROR 日志）；管理端运维页有 AI 诊断区与 Loki 日志面板；诊断走「确定性证据 + 一次 DeepSeek 调用」，报告落 `ops_diagnoses`；三个只读工具通过管理员 MCP 暴露。
- **现有缺口**（用户指出的五项）：
  1. 检测只有静态阈值，没有基线/异常检测；30 天数据没有用于对比。
  2. 没有链路：只有 HTTP p95/avg、日志与主机指标；应用已有 `TraceIdFilter`，但证据里没有 trace id，5xx 无法按请求关联日志。
  3. 诊断是一次性的行：不跟告警 firing→resolved 的生命周期走，没有事件对象/时间线，同一根因可能重复诊断。
  4. 没有反馈与评测：报告好坏无人标记，没有金集，无法证明「越用越准」。
  5. 业务 SLO 缺失：AI 402/上游失败、积分结算失败、登录失败率、定时任务失败都没有进入告警；只有主机/中间件指标。
- **现有可复用的落点**：
  - `DefaultRunRecorder` 是所有 AI 调用的唯一完成点（`RunScope.finish`），可注入 `MeterRegistry` 发出 AI 成功率指标。
  - `LoginService.login` 是登录唯一入口；`CreditService.settleAs` 是结算短缺点；`ScheduledTaskMetrics` 已有 `paideia_scheduled_last_success`。
  - `OpsHookController` 已解析 Grafana webhook 的 `alerts[].labels`，但只取 alertname。
  - `TraceIdFilter` 使用 MDC `traceId` 与响应头 `X-Trace-Id`（32 位十六进制）。
- **技术事实（2026-10-27 核实）**：
  - Spring Boot 4.1.1 提供 `spring-boot-starter-opentelemetry`；`mvn dependency:get` 成功，POM 含 `micrometer-tracing-bridge-otel`、`micrometer-registry-otlp`、`spring-boot-micrometer-tracing-opentelemetry`。**不需要 OTel Java agent**。
  - Grafana Tempo 5.5k★、AGPL-3.0、仍在活跃提交；单机二进制支持本地文件存储与 OTLP。
  - Grafana OnCall OSS 仓库 **已归档**（2026-03-24），不作为选型。
  - Keep 12.4k★（license 非标准）主打扫警生命周期/去重/关联；Sloth 2.5k★、Pyrra 1.5k★（均 Apache-2.0、活跃）用于 SLO 规则生成。
  - OpenRCA（Microsoft，ICLR'25，444★，MIT）含 335 个真实故障、68GB 遥测；结论是即使工具增强的 LLM Agent 也只能解决少数案例——**评测预期必须务实**。
  - 服务器 4 核 / 15GB（可用约 7GB）/ 79GB 盘（剩约 46GB）；Tempo 需按 200–300MB 内存与数月 trace 磁盘做预算。
- **现有部署形态**：单机、单实例、无负载均衡；Grafana 13.2.4；Loki/Alloy 已只绑回环；公网仍只有 8081。

## 用户决定

2026-10-27，用户在四个方向性选择上确认：

1. **交付范围**：一个 WORK 覆盖事件、基线、链路、SLO、评测五块，按 P0–P5 分阶段交付；不是只做其中一块。
2. **链路**：**Tempo + Spring Boot `spring-boot-starter-opentelemetry`**；不用 OTel Java agent。
3. **异常检测**：**PromQL/LogQL 基线优先**（1h/24h/7d + 短窗口确认）；本期不部署 vmanomaly/Netdata，留作升级路径。
4. **告警关联与事件生命周期**：**自建最小事件模型**（`ops_incidents` + `ops_incident_events`）；不引入 Keep，不用已归档的 Grafana OnCall。

SLO 初始目标与评测方式按推荐默认执行，均可配置：HTTP 可用性 99.5%、延迟 <1s 占 95%、AI 成功率 99%、登录成功率 99%、定时任务成功率 99%；评测采用确定性检查 + 人工评分。

## 实现选择

以下为 Agent 根据现状与仓库模式给出的默认选择；会改变部署/数据/安全的项列入「待确认问题」，批准方案即视为确认。

- **一个 WORK、五个 track、按阶段交付**，不拆成五个 WORK：它们共用事件/证据/报告模型，拆开会在迁移、接口与 UI 上反复改契约。
- **事件优先**：先落 `ops_incidents` + `ops_incident_events`，后续基线/链路/SLO 都挂到事件上。
- **基线优先用 PromQL/LogQL**：当前值 vs 1h/24h/7d 的 `avg_over_time`/`quantile_over_time`，加最小样本与短窗口确认；**暂不引入 vmanomaly/Netdata** 这类常驻 ML 服务。理由：单机、单应用、序列数少；30 天数据已足够做季节性基线；ML 服务的许可/内存/误报成本当前不划算。升级路径写入设计。
- **链路用 Spring Boot OTel starter + Tempo 单机**：不引入 Java agent；OTLP 走回环到 Tempo；日志通过 MDC 带 `traceId`/`spanId`，Grafana 用 derived field 关联。
- **事件模型自建**：`ops_incidents` 用告警指纹（alertname + 关键标签的 SHA-256）去重，状态 OPEN/RESOLVED；ACK 作为可选（单管理员场景先不做分派）。不部署 Keep（多一套 DB/UI，当前规则量用不上），不用已归档的 Grafana OnCall。
- **SLO 规则手写**：按 Google SRE 的 multi-window multi-burn-rate；不引入 Sloth/Pyrra 这类需要额外服务或 CRD 的生成器。引用它们作为规则正确性的对照。
- **评测走「确定性 + 人工」**：10–15 条金集，先做五节/引用/实体命中的确定性检查，再加人工评分；不在 CI 里跑真实模型，不做 LLM-as-judge 自动门禁（成本与稳定性都不合适）。
- **诊断证据 v2**：在现有摘要/指标/告警/日志/发版之上，增加基线对比、事件时间线、trace 摘要、SLO burn；总字符上限与脱敏规则继续生效。

## 背景

WORK-042 解决的是「看得见 + 能问一次」。它已经能在服务下线、磁盘、备份、5xx/ERROR 突增时给出可读的根因假设，但证据是「当前快照」，不是「与平时相比」；它不知道一个告警从什么时候开始、是否已经处理过；它也看不到单个请求内部的耗时分布。

主流 AIOps 的完整链路是：**数据 → 检测 → 关联 → 诊断 → 执行 → 反馈**。WORK-042 完成了数据与单次诊断；本项补检测（基线/SLO）、关联（事件）、诊断深度（链路）、反馈（评测），执行仍不做（安全边界）。

## 目标

1. 告警不再是一次性通知：同一根因形成事件，有开始/结束/时间线，诊断挂在事件上。
2. 诊断能回答「和平时比怎么样」：CPU/内存/磁盘/5xx/P95/日志量都有 1h/24h/7d 基线。
3. 能看到单个请求的链路：慢在哪一段、哪个 span 报错；日志能一键跳到 trace。
4. 能用业务 SLO 判断「平台对用户是否可用」：HTTP、AI、登录、定时任务，而不是只看主机。
5. 能回答「这套 AIOps 准不准」：反馈 + 金集 + 可重放的评测。
6. 现有 WORK-042 的行为（6 条告警、诊断、运维页、MCP 工具）不被破坏。

## 非目标

- 不做自动修复、自动重启、自动回滚（执行层仍为 0）。
- 不做多主机/K8s/多实例；仍是单机单实例。
- 不部署 Keep、Grafana OnCall、SigNoz、Datadog 等外部 AIOps/可观测平台。
- 不引入 vmanomaly/Netdata 等常驻 ML 异常检测服务（本期用基线；升级路径见设计）。
- 不做全链路分布式追踪到第三方 SaaS 的所有内部调用；只覆盖应用自身的 HTTP/JDBC/HTTP client 等标准 span。
- 不做 LLM-as-judge 的 CI 门禁；不做提示词自动优化。
- 不改现有 `X-Trace-Id` 的对外语义（仍是响应头；值改为 OTel trace id，见 REQ）。

## 功能需求

### Track A：事件与告警关联

- **REQ-001**：新增事件对象。指纹 = `alertname + 关键标签` 的 SHA-256；同一指纹的重复通知只更新 `last_seen`/计数，不新建事件。
- **REQ-002**：事件有生命周期：`OPEN` / `RESOLVED`（可选 `ACKNOWLEDGED`）；记录 `first_seen`、`last_seen`、`resolved_at`、告警次数。
- **REQ-003**：事件有追加式时间线：告警触发/恢复、诊断开始/完成/失败、人工备注、可选确认；时间线只追加，不原地改。
- **REQ-004**：Grafana webhook 按指纹 upsert 事件；`resolved` 关闭事件；重复 firing 不重复创建。
- **REQ-005**：诊断挂到事件上；同一 OPEN 事件在冷却窗口内不重复诊断；事件恢复后再次触发重新计冷却。
- **REQ-006**：管理端能看事件列表与时间线，并能从事件跳到对应诊断报告。
- **REQ-007**：Grafana 通知策略按 `alertname`/`severity` 分组，并配置至少一条抑制规则（例如目标下线时抑制同一实例的派生告警），避免同一根因刷屏。

### Track B：基线与异常检测

- **REQ-008**：诊断证据包含关键指标的当前值、1h/24h/7d 基线、变化倍数或 z 分数；至少覆盖 CPU、内存、磁盘、5xx、P95、ERROR 日志量。
- **REQ-009**：新增 3–5 条动态基线告警：当前值显著偏离「同一时段 7 天基线」时触发；必须带短窗口确认与 `for`，避免单点抖动。
- **REQ-010**：日志量基线告警：当前 5 分钟 ERROR 数 vs 上周同时段；低流量时不得误报。
- **REQ-011**：基线计算有最小样本要求；样本不足时返回「基线不足」而不是 0；NoData 不当作异常。
- **REQ-012**：基线的阈值/倍数可配置；关闭开关后规则与证据都退回静态阈值。

### Track C：链路

- **REQ-013**：引入 Spring Boot OTel starter，应用通过 OTLP 把 trace 发到本机 Tempo；采样率可配置（默认当前低流量 100%）。
- **REQ-014**：Tempo 以 systemd 单机单元运行，只绑回环，保留期与 Prometheus/Loki 对齐（30 天），有 `MemoryMax` 与磁盘上限。
- **REQ-015**：trace id 统一：`X-Trace-Id` 响应头与日志里的 `traceId` 都是 OTel trace id；不再生成第二个互不相干的 id。
- **REQ-016**：Loki 日志带 `traceId`/`spanId`；Grafana 日志行能跳转到 Tempo trace。
- **REQ-017**：诊断证据增加 trace 摘要：事件窗口内最慢的若干 span、错误 span、对应 trace id；有硬上限。
- **REQ-018**：trace 证据只保留 span 名、服务名、耗时、状态与白名单属性；不采集请求体、SQL 参数、Authorization/Cookie。

### Track D：反馈与评测

- **REQ-019**：每份诊断可标记「准 / 部分准 / 不准」并写一句备注；评分落库，管理端可见。
- **REQ-020**：建立 10–15 条金集（历史/合成事件 + 证据快照 + 期望根因实体），可离线重放。
- **REQ-021**：重放评测做确定性检查：五节齐全、引用了期望的指标/日志/实体、没有写操作；可选人工评分，不做 CI 真实模型门禁。
- **REQ-022**：管理端显示诊断的 helpful 率（按评分汇总），但**不**根据反馈自动改提示词。

### Track E：业务 SLO

- **REQ-023**：定义并暴露 SLI：HTTP 可用性（排除 actuator 与 4xx）、HTTP 延迟（<1s 占比）、AI 成功率（排除 402 余额不足）、登录成功率、定时任务成功率。
- **REQ-024**：在源头补 Micrometer 指标：`paideia_ai_runs_total{kind,outcome}`、登录尝试结果、积分结算不足；不新增高基数标签。
- **REQ-025**：按 Google SRE multi-window multi-burn-rate 配 4–6 条 SLO 告警（快速 burn + 慢速 burn），阈值可配置。
- **REQ-026**：运维页/Grafana 增加 SLO 面板：错误预算剩余、burn rate、各 SLI 趋势。
- **REQ-027**：SLO 告警与现有告警走同一条事件/诊断/通知路径。
- **REQ-028**：SLO 目标初始值可配置；没有样本时不得判定为违反。

## 质量要求

- 新增服务（Tempo）只监听回环；常驻内存目标 ≤300MB；trace 磁盘 30 天目标 ≤5GB（按当前流量估算，实测后记录）。
- OTel 接入的 CPU/内存开销可观测；采样率可降到 10% 而不改代码。
- 事件指纹稳定：重复通知、应用重启、Grafana 重启都不产生重复事件。
- 基线查询有界：不使用全量 range scan，单次诊断的基线查询 ≤10 条。
- SLO 规则在无流量/低流量时不误报；每条规则有最小样本。
- 诊断证据总字符上限不因新增基线/trace 而突破 12k；超出时按优先级截断（摘要 > 指标 > 事件 > trace > 日志）。
- 反馈与评测不进入线上请求路径；重放评测不在 CI 自动运行。
- 所有新增表/接口沿用现有 `ApiResponse`、ADMIN 权限、UTC 时间与 14 位迁移命名。
- 保持 `X-Trace-Id` 响应头可用；现有客户端不需要改。

## 约束与依赖

- Spring Boot 4.1.1；新增 `spring-boot-starter-opentelemetry`（已验证存在），不引入 Java agent。
- 仍只使用 MySQL；迁移 14 位时间戳，只建结构、不插业务数据；改前查同表冲突。
- Grafana 13.2.4 / Prometheus / Loki / Alloy 的现有部署纪律不变：只绑回环、独立单元、MemoryMax。
- 诊断仍走 `SingleShotModelCaller` + `GuardedHttpTransport`；不新增模型供应商。
- 公开仓库：OTLP/Tempo 地址、SLO 目标与令牌都不含凭据；仓库只放占位。
- 部署：Tempo 由 `install-ops.sh` 安装；`release.ps1` 仍只重启 `paideia`。
- 服务器余量有限：新增服务前先记录当前 `free -g` 与磁盘；Tempo 设 `MemoryMax`，trace 保留期可降。
- 测试：后端切片走 `scripts/test-db.sh`；Prometheus 规则用 `promtool test rules`；前端只跑受影响 e2e 文件。

## 关联工作项

- **WORK-042**：本项的直接前置与复用对象（指标/日志/诊断/工具/运维页）。
- **WORK-028**：监控栈与部署纪律的来源。
- **WORK-036**：AI 观测面板；本项的 AI 成功率指标与它互补（一个看成本/延迟，一个看 SLO）。
- **WORK-009**：积分与结算；本项只加「结算不足」指标，不改计费规则。
- 无硬依赖；以上均已完成或已上线。

## 验收标准

- **AC-001**：同一告警的重复 webhook 只产生一个事件；`resolved` 后事件关闭；再次 firing 创建新事件。
- **AC-002**：事件时间线按时间顺序列出触发、恢复、诊断、备注；只追加。
- **AC-003**：诊断挂在事件上；同一 OPEN 事件冷却窗口内第二次触发不建新诊断。
- **AC-004**：诊断证据包含至少 3 个指标的 1h/24h/7d 基线对比；缺失时写「基线不足」。
- **AC-005**：动态基线告警规则被 Grafana 加载，且 `promtool test rules` 覆盖「正常/尖峰/样本不足」三种输入。
- **AC-006**：日志量基线规则在空闲时 `NoData=OK`，不产生告警。
- **AC-007**：Tempo 单元 active、只绑回环；Grafana 能按 trace id 查到一次真实请求的 span。
- **AC-008**：`X-Trace-Id` 与日志 `traceId` 等于 OTel trace id；Loki 行能跳到 Tempo。
- **AC-009**：诊断证据含 trace 摘要（最慢/错误 span），且不含请求体/凭据。
- **AC-010**：诊断评分落库并在管理端显示；helpful 率可见。
- **AC-011**：金集重放跑通 10–15 条，确定性检查有明确通过/失败清单；失败项有记录。
- **AC-012**：AI/HTTP/登录/定时任务的 SLI 指标可查；无样本时 SLO 规则不误报。
- **AC-013**：4–6 条 SLO burn-rate 规则被 Grafana 加载；构造一次预算快速燃烧时能触发。
- **AC-014**：SLO 告警触发后走事件 + 诊断路径，报告能引用 burn rate 与 SLI。
- **AC-015**：WORK-042 的 6 条告警、诊断、运维页、MCP 工具行为不变；受影响测试 0 failed。
- **AC-016**：没有新增公网端口；Tempo/OTLP 只监听回环；新增诊断证据不泄露请求体或凭据。

## 假设

- 当前流量很低，100% 采样在可接受开销内；若开销明显，可降到 10–20%。
- Tempo 的 trace 磁盘增长在 30 天内 ≤5GB；超过则降保留期或采样率。
- 现有 HTTP 直方图（WORK-042 已开启）足以计算延迟 SLI。
- 单管理员场景下不需要事件分派/值班表；ACK 可选。
- 金集由现有两次真实诊断 + 历史故障/合成故障组成，规模 10–15 条即可回答「有没有变差」。

## 待确认问题

无。链路、异常检测路线、事件模型、交付范围与 SLO/评测默认值已由用户于 2026-10-27 确认；其余为实现选择，理由记录在 `proposal.md` 与 `design.md`。
