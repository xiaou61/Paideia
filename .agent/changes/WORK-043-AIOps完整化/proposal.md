---
artifact: proposal
work_id: WORK-043
work: AIOps 完整化：事件、基线、链路、SLO 与评测
status: approved
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 13:52:33 +0800
approver_role: CTO
---

# AIOps 完整化提案

## 摘要

在 WORK-042 的「指标 + 日志 + 单次诊断」之上，用**一个 WORK、五个 track、六个阶段**补齐：事件生命周期、基线与动态检测、链路、业务 SLO、反馈与评测。执行层（自动修复）仍然不做。

核心判断：**先做事件，再做检测与链路，最后做 SLO 与评测**。事件是其余四块挂载的骨架；没有它，基线告警和 trace 都只是又一批一次性信号。

## 推荐方案

### 总体架构

```
                     ┌────────────── Grafana Alerting ──────────────┐
                     │ 6 旧规则 + 基线规则 + SLO burn-rate 规则      │
                     └───────────────┬──────────────────────────────┘
                                     │ webhook (回环 + 令牌)
                                     ▼
                            OpsHookController
                                     │ upsert by fingerprint
                                     ▼
                     ops_incidents ── ops_incident_events
                          │                  │
                          │                  └─ 时间线（firing/resolved/diagnosis/note）
                          ▼
                   OpsDiagnosisService
                          │
        ┌─────────────────┼─────────────────┬──────────────────┐
        ▼                 ▼                 ▼                  ▼
   Prometheus 30d     Loki 30d          Tempo 30d          SLO 指标
   (基线查询)         (日志+traceId)    (OTel span)        (Micrometer)
        └─────────────────┴─────────────────┴──────────────────┘
                                     │
                                     ▼
                          证据 v2（12k 字符上限）
                                     │
                                     ▼
                          一次 DeepSeek 诊断 → ops_diagnoses
                                     │
                          ┌──────────┴──────────┐
                          ▼                     ▼
                    评分/反馈            金集重放评测
```

### 五个 track

| Track | 内容 | 关键落点 |
| --- | --- | --- |
| A 事件 | 指纹去重、OPEN/RESOLVED、时间线、诊断挂载、Grafana 分组/抑制 | `ops_incidents`、`ops_incident_events`、`OpsHookController`、运维页 |
| B 基线 | 1h/24h/7d 基线进入证据；动态基线告警；日志量基线 | `OpsEvidenceCollector`、`paideia-rules.yaml`、`promtool test rules` |
| C 链路 | Spring Boot OTel starter、Tempo、trace id 统一、日志跳转、trace 摘要 | `paideia-app` 依赖与配置、`TraceIdFilter`、`deploy/ops/tempo.*`、Loki derived field |
| D 评测 | 诊断评分、金集、重放、helpful 率 | `ops_diagnoses` 新增列、评测脚本/JUnit、运维页 |
| E SLO | HTTP/AI/登录/定时任务 SLI、burn-rate、SLO 面板 | `DefaultRunRecorder`、`LoginService`、`CreditService`、Grafana 规则与面板 |

### 为什么是这套

- **与现有栈同源**：Prometheus + Loki + Grafana + Tempo 是同一套 Grafana 生态；不引入第二套 UI/DB。
- **Spring Boot 4 原生支持**：`spring-boot-starter-opentelemetry` 已验证存在（Maven `dependency:get` 成功，POM 含 Micrometer Tracing OTel bridge），不需要 Java agent、不需要手写 OTel SDK。
- **基线不引新服务**：30 天指标已经在 Prometheus；用 `avg_over_time`/`quantile_over_time` + 短窗口确认即可覆盖「和平时比」的大部分场景。ML 异常检测留作升级路径，不在本期硬上。
- **评测务实**：OpenRCA 的结果说明 LLM RCA 本身很难；先做确定性检查与人工评分，才能知道「是我们的证据不够，还是模型不行」。

## 参考项目与架构（2026-10-27 核实）

| 来源 | 事实 | 我们借什么 |
| --- | --- | --- |
| [Spring Boot Tracing 官方文档](https://docs.spring.io/spring-boot/reference/actuator/tracing.html) | Boot 4 用 Micrometer Tracing 自动配置；`spring-boot-starter-opentelemetry` 一站式引入 | 用 starter，不用 agent |
| [Google SRE Workbook：Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/) | 多窗口多 burn-rate 是推荐策略：2% 预算 1h / 5% 6h 快速告警，10% 3d 慢速工单；长窗口配 1/12 短窗口确认 | SLO 告警规则结构 |
| [Sloth](https://github.com/slok/sloth)（2.5k★，Apache-2.0，活跃） | 从 SLO 定义生成 Prometheus 规则 | 规则命名/窗口口径的对照，不部署 |
| [Pyrra](https://github.com/pyrra-dev/pyrra)（1.5k★，Apache-2.0，活跃） | Kubernetes 原生 SLO 管理 | 同上，不部署 |
| [Grafana Tempo](https://github.com/grafana/tempo)（5.5k★，AGPL-3.0，活跃） | 单机二进制、本地存储、OTLP 接收 | 链路后端 |
| [OpenTelemetry](https://opentelemetry.io/) | trace/span 语义与 W3C traceparent | 统一 trace id 与日志关联 |
| [Grafana OnCall](https://github.com/grafana/oncall)（3.8k★，**2026-03-24 归档**） | OSS 已停止 | **不选** |
| [Keep](https://github.com/keephq/keep)（12.4k★，license 非标准） | 告警生命周期、去重、关联、AI 摘要 | 借事件模型，不部署 |
| [OpenRCA](https://github.com/microsoft/OpenRCA)（Microsoft，ICLR'25，444★，MIT） | 335 个真实故障、68GB 遥测；工具增强的 LLM 仍只解决少数案例 | 评测预期、金集设计要务实 |
| [PagerDuty/Incident.io 事件模型] | 事件有 triggered/acknowledged/resolved 与时间线 | 状态机与 UI 参照 |

## 范围

### 做

- 事件对象、时间线、指纹去重、诊断挂载、Grafana 分组/抑制。
- 指标与日志的 1h/24h/7d 基线；动态基线告警；最小样本与短窗口确认。
- Spring Boot OTel starter + Tempo + trace id 统一 + 日志跳转 + trace 摘要。
- 诊断评分、金集、重放评测、helpful 率。
- 五类 SLI、源头 Micrometer 指标、burn-rate 告警、SLO 面板。
- 证据 v2 的优先级截断与脱敏。

### 不做

- 自动修复/重启/回滚。
- 多主机/K8s/多实例。
- Keep/OnCall/SigNoz/Datadog/vmanomaly/Netdata 部署。
- LLM-as-judge CI 门禁与提示词自动优化。
- 采集请求体、SQL 参数、Authorization/Cookie。

## 备选方案

### A. 自建事件模型（推荐）

最有力的理由：与现有 `ops_diagnoses`、工具、运维页同源；6–20 条规则不需要第二套平台。
放弃什么：Keep 现成的告警去重/工作流/多通道集成要自己实现一小部分；只做最小状态机。

### B. 部署 Keep 做告警中台

最有力的理由：现成的告警生命周期、去重、关联、130+ 集成、DeepSeek 后端。
为什么没选：需要自带 DB/UI/工作流；当前规则量与单管理员场景用不上；license 非标准，多一套要升级的服务。

### C. vmanomaly / Netdata 做异常检测

最有力的理由：真正的时序异常检测，能发现季节性/多指标异常。
为什么没选：vmanomaly 属企业版组件（需授权），Netdata 与 node-exporter 重复且不做日志/链路；当前序列数少，PromQL 基线足够先回答「和平时比」。升级触发条件：基线误报/漏报在真实故障中明显不够。

### D. OTel Java agent 而不是 starter

最有力的理由：零代码改动，自动埋点最全。
为什么没选：多一个 JVM 参数/二进制与版本管理；Spring Boot 4 的 starter 已原生覆盖 MVC/JDBC/HTTP client，且与 Micrometer/Actuator 同一套配置。

### E. Grafana OnCall / 五个独立 WORK

OnCall 已归档，不选。五个独立 WORK 会共享 `ops_incidents` 与证据 schema，拆开会在迁移顺序、接口和 UI 上反复改契约；因此一个 WORK 内分 track/phase。

## 仓库影响

| 区域 | 影响 |
| --- | --- |
| `backend/paideia-app/` | `ops` 包新增事件/时间线/反馈/评测端点与表；`OpsHookController` 扩为指纹 upsert；证据 v2；迁移 |
| `backend/paideia-ai/` | `DefaultRunRecorder` 发 AI 运行指标；诊断证据新增基线/trace 字段 |
| `backend/paideia-account/` | `LoginService` 登录结果指标 |
| `backend/paideia-web/` | `TraceIdFilter` 与 OTel trace id 对齐 |
| `frontend/apps/admin/` | 事件列表/时间线、诊断评分、SLO 摘要、helpful 率 |
| `deploy/ops/` | Tempo 单元/配置/数据源、trace-log derived field、基线/SLO 规则、install-ops.sh |
| `backend/paideia-app/pom.xml` | 引入 `spring-boot-starter-opentelemetry`（唯一新依赖） |
| 配置 | `management.otlp.tracing.*`、采样率、SLO 目标、基线开关；OTLP metrics 关闭，避免与 Prometheus 重复 |

## 交付拆分（六个阶段）

1. **P0 事件地基**（2–3 天）：表、指纹、webhook upsert、诊断挂载、事件 UI。其余四块都挂在这里。
2. **P1 基线**（1–2 天）：证据 v2 的 1h/24h/7d 对比、3–5 条动态基线告警、日志量基线、promtool 规则测试。
3. **P2 链路**（2–3 天）：OTel starter、Tempo、trace id 统一、日志跳转、trace 摘要。
4. **P3 SLO**（1–2 天）：SLI 指标、burn-rate 规则、SLO 面板。
5. **P4 评测**（2–3 天）：评分、金集、重放、helpful 率。
6. **P5 收口**（1–2 天）：证据上限/脱敏复核、全链路演练、部署与生产验证、文档。

合计约 **10–15 个工作日**，按切片提交与部署；不是一次发版。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| Tempo 吃内存/磁盘 | MemoryMax、30 天保留、采样率可降到 10%；部署前后记录 `free`/`df` |
| OTel 引入 trace id 与现有 `X-Trace-Id` 冲突 | 统一由 OTel 生成；`TraceIdFilter` 改为读取当前 span 的 trace id 并保留响应头；测试断言两者相等 |
| 基线告警在低流量/夜间误报 | 最小样本、`for` + 短窗口确认、NoData=OK；先用 `promtool test rules` 覆盖 |
| 事件指纹不稳定导致重复事件 | 指纹只取 alertname + 稳定标签（instance/job/severity），不含时间/摘要；单测覆盖 |
| SLO 指标高基数 | 只加 kind/outcome 等低基数标签；禁止用户 id/路径参数进标签 |
| 诊断成本上升 | 证据上限 12k；按事件冷却；金集评测手动跑；新字段按优先级截断 |
| 金集/反馈变成形式 | 金集必须来自真实或可复现故障；评测输出失败清单；helpful 率只做观察，不自动改提示词 |
| 一个 WORK 太大 | 按 P0–P5 切片，每片独立可验证；P0 完成即可先上线，后续阶段继续 |

## 验收映射

| 需求 | 验收 |
| --- | --- |
| REQ-001..007 | AC-001..003 / AC-015 |
| REQ-008..012 | AC-004..006 |
| REQ-013..018 | AC-007..009 / AC-016 |
| REQ-019..022 | AC-010..011 |
| REQ-023..028 | AC-012..014 |
| 全局 | AC-015 / AC-016 |

## 待决定事项

无。2026-10-27 用户已确认：Tempo + Spring Boot OTel starter、PromQL/LogQL 基线优先、自建事件模型、全量五块分阶段交付；SLO 目标与确定性+人工评测按推荐默认执行。
