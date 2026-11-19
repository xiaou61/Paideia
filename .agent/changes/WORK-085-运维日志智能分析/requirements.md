---
work_id: WORK-085
work: 运维日志智能分析（AIOps 补齐）
artifact: requirements
status: approved
mode: strict
mode_reason: 触及运维数据面与诊断链路（Loki/Alloy 日志管线、paideia-ops 证据采集、可能的模板库持久化与告警规则），并改变「故障怎么被定位」这一对外可观察的运维行为；含日志脱敏与新增进程/端点的安全面，属架构/部署/数据变更。
workflow: full
depends_on: []
related_to: [WORK-028, WORK-042, WORK-043, WORK-049, WORK-022, WORK-009]
created: 2026-11-19
updated: 2026-11-19
approved_by: xiaou61
approved_at: 2026-11-19 10:17:47 +0800
approver_role: CTO
---

# 运维日志智能分析（AIOps 补齐）需求

## 背景与动机

用户 2026-11-19 分享了一篇 AIOps 文章《10亿条日志，最后只锁定3个根因》，并明确要求（原话）「新开一个 work 把这些点全部加进去进行优化，开始吧」。本工作项把文章里可迁移的 AIOps 方法论，逐条对照 Paideia **已有的运维体系**做差距分析，并把缺口纳入本项目优化。

**关键前提（读仓库后修正的认知）**：Paideia 并不是「只有指标、没有日志智能」的白地。WORK-028 已建 Prometheus + Grafana + 各 exporter + 备份；WORK-042 已建 **Loki + Alloy（journal→Loki）+ Tempo**，并把「指标 + 日志 + 链路 + 基线 + 告警 + 部署」采集成交给 LLM 的证据（`OpsEvidenceCollector` → `OpsDiagnoser`），由 `OpsDiagnosisService` 编排诊断并邮件回送 traceId；WORK-043 有诊断评分（-1/0/1，helpful 率），WORK-049 有事件复用与重启补诊断；WORK-022 已有 `TraceIdFilter`（每请求 traceId 写 MDC + `X-Trace-Id` 响应头，`application.yml` 的 `logging.pattern.correlation: "[%X{traceId:-}] "`，Loki derived field 跳 Tempo）。因此文章里的「采集」「AI 摘要」「关联」「闭环反馈」这几层**大部分已存在**；真正的缺口是中间两层——**日志模板聚类**与**基于模板频次的异常检测**，以及让这些结构化信号进入现有诊断/告警（而不是把原始日志直灌 LLM）。

## 已确认事实

- 日志链路：应用日志走 journald（`journalctl -u paideia`），经 **Alloy**（`deploy/ops/alloy-config.alloy` 的 `loki.source.journal "units"`，按 unit + level 打标签）推到 **Loki**（`deploy/ops/loki.service`、`loki-config.yaml`），Grafana 以 `paideia-loki` 数据源查询；`deploy/ops/ops-textfile.sh` 的 UNITS 列表含 `loki`、`alloy`。**不新增采集组件**是既有方向。
- Loki 规格（`deploy/ops/install-ops.sh:25` `LOKI_VERSION=3.7.9`；`loki-config.yaml`）：单机回环 3100、文件系统 + tsdb v13、`retention_period: 720h`（30 天）、`max_query_lookback: 720h`、不启用 ruler（告警统一在 Grafana）。**Loki 3.7.x 自带 pattern ingester（DPL/Drain 聚类）**，可把日志聚成模板并产出 `loki_patterns_*` 指标——这使「聚类放在 Loki 侧」可行；但当前 `loki-config.yaml` **未启用** `pattern_ingester`，需在设计阶段评估开启方式与内存成本。
- 诊断证据采集：`backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsEvidenceCollector.java` 用**固定查询目录**（`METRICS` 常量白名单，注释「所有查询都是常量：模型与管理员的输入不会进入 PromQL/LogQL 字符串」）采集，日志部分走 `LokiClient.queryRange`/`scalar`，已有 `LOG_ERROR_QUERY = sum(count_over_time({unit="paideia.service"} |~ "(?i)(error|exception|failed)" [5m]))` 与其 `offset 1w` 周基线；Loki 不可达时显式写「无匹配日志（或 Loki 不可达）」，不抛异常。
- LLM 诊断：`OpsDiagnoser`（接口，注释明确「只接受已经采集好、已经脱敏的证据，不自己去读 Prometheus / Loki / 日志文件」）；`OpsDiagnosisService` 负责冷却（告警按 alertName、事件按 incidentId）、单飞、落库、异步、失败不拦后续、邮件带 traceId。
- 现有缺口（对照文章五环节）：
  - 采集（有：Alloy/journal→Loki）；清洗（部分：Alloy relabel unit/level + 采集时脱敏与截断，但**无日志模板抽取**）；**聚类（无）**；**异常检测（无模板级，只有固定 LogQL 5m 错误计数与周对比）**；AI 摘要（有：`OpsDiagnoser`）；历史/知识（部分：事件时间线 + 评分，**无结构化知识检索**）；服务拓扑（单体，简单，未显式维护）。
- 项目常驻规则约束（`.agent/rules/always.md`）：**本期不做向量化检索 / 语义检索 / RAG**（架构须留接入位，内容检索走 MySQL 全文）；AI 编排只允许 AgentScope Java；DeepSeek 上工具调用与结构化输出不能同次；平台持 Key、用户按积分、需记账与封顶；凭据/口令/令牌**永不进日志与响应体**；运维组件全部绑回环、公网仍只有 8081；新增进程须 systemd + 回环 + 内存实测。

## 用户决定

- 2026-11-19：把文章里的 AIOps 要点**全部纳入**本工作项并据此优化（用户原话「把这些点全部加进去进行优化，开始吧」）。
- 2026-11-19（范围拍板）：**本期按全量纳入**（REQ-001~010 全做，含 REQ-007 结构化知识闭环与 REQ-008 最小服务拓扑），不是最小闭环。
- 2026-11-19（技术方向）：**日志模板聚类放在 Loki/Alloy 侧扩展**实现，不在应用内重写聚类引擎、也不单开常驻服务。据此 REQ-002/003/004 的落点优先用 Loki 3.7 的 pattern ingester（DPL/Drain）产出模板与频次指标，应用侧只做「读指标 → 判异常 → 组证据」。

## 实现选择

以下为实现层已拍板的方向，细节与落点见 `design.md`（requirements 只记录决策，不写代码）：

- **聚类落点**：Loki/Alloy 侧扩展（Loki 3.7 `pattern_ingester`，DPL/Drain），应用内不重写聚类引擎、不单开常驻服务。
- **应用侧职责**：`paideia-ops` 只做「读模板/频次 → 判三类异常 → 组证据 → 触发既有诊断」，定时任务跑在现有应用 JVM 内（`@EnableScheduling` 已开），不新增 systemd 单元。
- **持久化**：模板/频次基线/异常事件落 MySQL（手写 MyBatis，新迁移 14 位时间戳）；REQ-007 知识闭环用 MySQL 全文检索，不建向量库。
- **告警**：复用 Grafana 告警与 `OpsDiagnosisService`（冷却/单飞/邮件/时间线），不新建告警通道；新增异常信号以 Micrometer 指标经既有 `/actuator/prometheus` 暴露。

## 目标

在**不推翻现有 WORK-028/042/043/049 体系**的前提下，补齐「日志模板聚类 + 模板频次异常检测」这一层，并把它作为结构化证据接入现有诊断与告警，使故障定位从「按关键词捞日志 + LLM 看原始行」升级为「看模板频次与异常模板 + LLM 只做解释与建议」。即落实文章最重要的一条纪律：**聚类先行、LLM 收尾，不把 TB 级原始日志喂模型**。

## 非目标

- 不替换现有 Prometheus/Grafana/Loki/Tempo，不新增第二套监控。
- 不做「AI 自动修复」——沿用「AI 给建议、人决策执行」，不形成建议孤岛（文章第五节/第八问）。
- 不做多机/集群版 AIOps；当前只有一台生产机，单机足够。
- 不在本期引入向量库/语义检索（项目规则），知识闭环用结构化检索落地，除非用户单独裁决放开。
- 不做全站日志归档系统大改造；沿用 journald→Alloy→Loki。

## 功能需求

- REQ-001 **日志结构化字段**：让应用日志稳定可解析（至少 service、level、timestamp、traceId、logger，必要时关键业务字段），供模板抽取与结构化检索；保持现有 journald→Alloy→Loki 链路，不为此新增采集组件。
- REQ-002 **日志模板抽取（聚类）**：对指定时间窗内日志按「语义模板」归并（Drain 类算法或等价方案），产出「模板 + 频次 + 首末出现时间」，并能量化压缩率。
- REQ-003 **模板频次基线**：为每个模板维护按时间（小时/星期）的频次基线，可查「当前值 vs 基线偏离」。
- REQ-004 **三类异常检测**：①模板频次显著偏离基线 ②新模板出现（从未见过/低频突现）③模板突然消失（服务静默，比报错更危险）。
- REQ-005 **结构化证据入诊断**：把「Top 模板 + 异常模板 + 时间线」作为结构化证据段进入 `OpsEvidenceCollector` / `OpsDiagnoser`，**由模型消费压缩后的模板摘要，不直灌原始日志**；受现有 `maxEvidenceChars` / `maxLogLines` 上限约束。
- REQ-006 **接入告警闭环**：异常模板信号可作为一条告警来源，触发既有 `OpsDiagnosisService`（复用冷却、单飞、邮件、事件时间线），不新建一套告警/通知。
- REQ-007 **知识闭环**：每次事件/诊断的结构化结论可沉淀与检索（至少落到可用 SQL / MySQL 全文检索的结构化历史，供下次诊断引用），不新建向量库。
- REQ-008 **最小服务拓扑**：单体形态下维护一份最小依赖拓扑（app → MySQL / Redis / RustFS / Laya / Tempo 等），供诊断关联与「影响面」说明；不引入 CMDB 工程。
- REQ-009 **可配置可降级**：聚类开关、窗口、阈值、模板库容量/保留、成本上限均可配；Loki 不可达或聚类未启用时，诊断照常进行并按现有「缺失即 UNKNOWN」如实标注，不报错。
- REQ-010 **观测与成本**：记录聚类/检测/LLM 每次的耗时、token 与判定，纳入现有诊断记账与 AI 计费（WORK-009）。

## 质量要求

- 压缩指标可量化：给出真实日志窗口的「原始行数 → 模板数 → 压缩比」并附抽样核对。
- 诊断报告可解释、可复现：模板判定与异常原因要能对上具体日志行（保留可回溯的 traceId 关联）。
- 模板库与基线有容量上限与保留策略，不得无界增长。
- 脱敏：进入证据与模板库的内容不得含明文口令、令牌、验证码、个人信息（延续 `OpsDiagnoser`「已脱敏证据」约束）。
- 新增进程/端点遵守：绑回环、systemd 自启、内存有上限或实测记录、ADMIN 鉴权（若涉及管理端点）。

## 约束与依赖

- 依赖 WORK-028（监控底座）、WORK-042（Loki/Alloy/Tempo + 证据采集 + LLM 诊断）、WORK-043（评分）、WORK-049（事件复用/补诊断）、WORK-022（traceId/MDC）、WORK-009（计费）。
- 遵守「本期不做向量化检索/RAG」；REQ-007 用结构化检索实现，若要语义检索需用户单独裁决并在 `.agent/rules/always.md` 记录例外。
- DeepSeek 工具调用与结构化输出不能同次；诊断成本受积分与封顶约束。
- 日志脱敏是硬约束（口令/令牌/验证码/PII 不入证据、不入模板库、不入响应）。
- 不新增公网端口；安装/部署如需代理，按既有 `127.0.0.1:7890` 约定。

## 关联工作项

- WORK-028（运维监控面板）：本项在其指标/备份/告警之上补日志智能层，不重做其能力。
- WORK-042（Loki/Alloy/Tempo + AI 诊断）：本项填补其「只有固定 LogQL 错误计数、无模板聚类/异常检测」的空白，并复用其证据采集与 LLM 诊断骨架。
- WORK-043 / WORK-049：复用诊断评分、事件时间线、冷却/单飞/补诊断。
- WORK-022：复用 traceId/MDC 作为日志↔链路↔指标串联键。
- WORK-009：诊断/聚类的 LLM 调用纳入积分与记账。

## 验收标准

- AC-001 **聚类有效**：取一段真实日志窗口，模板化后条目数较原始行数显著下降（具体倍数在设计中定基线），抽样核对模板语义正确、可读。
- AC-002 **三类异常可检出**：分别构造「频次突增」「新模板出现」「模板消失」各一次，系统都能检出并给出原因（可复现）。
- AC-003 **证据不直灌**：一次诊断的报告里能看到模板频次/异常模板/时间线，且送给 LLM 的日志证据 token 受上限约束、不含原始整段日志。
- AC-004 **闭环触发**：注入一条异常模板信号能触发一次既有诊断，遵守冷却与单飞，不产生告警风暴。
- AC-005 **降级不崩**：Loki 不可达或聚类关闭时，诊断照常，报告标注「未知/未启用」而不报错。
- AC-006 **脱敏与安全**：证据与模板库抽样不含明文凭据/令牌/PII；新增端点/进程满足回环 + ADMIN + 自启要求。
- AC-007 **成本可控**：单次诊断的 token/耗时/费用上限生效，超限时按既定策略降级或跳过，不失控。

## 假设

- 单机、日志量适中（具体日量待确认），单机内做聚类足够；不假设 TB 级规模。
- 现有 journald→Alloy→Loki 链路可作为聚类输入源，无需另建日志存储。
- 聚类算法可在应用内轻量实现，或复用 Loki 侧能力；最终落点留 design 阶段决定。

## 待确认问题

- ~~Q1 本期范围~~ **已定**：全量纳入（REQ-001~010）。
- ~~Q2 聚类落点~~ **已定**：Loki/Alloy 侧扩展（优先走 Loki 3.7 pattern ingester）。
- Q3 **规模补充**：Loki 保留 30 天、单机已定；仍待确认「日日志量 / 典型错误日志量级」。若无准确数字，设计阶段按「先跑通、容量参数可配、上线后实测再调」处理，不作为阻塞项。
- Q4 **RAG 口径**：REQ-007 知识闭环默认用**结构化检索（MySQL 全文，遵守项目『本期不做向量化』规则）**实现；若确需语义检索，需用户单独裁决并在 `.agent/rules/always.md` 记录例外。
- Q5 **strict**：已按 strict 执行。
