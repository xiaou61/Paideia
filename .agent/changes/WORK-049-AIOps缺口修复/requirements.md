---
artifact: requirements
work_id: WORK-049
work: AIOps 缺口修复：事件闭环、历史复用、告警关联与评测可信度
status: draft
mode: strict
mode_reason: 跨 paideia-app（事件/诊断/证据/控制器）、paideia-ai（提示词版本与金集）、管理端前端、Grafana provisioning 与部署脚本、生产演练；涉及事件生命周期语义、诊断成本、证据预算与运维权限
workflow: full
depends_on: []
related_to: [WORK-043, WORK-042, WORK-028, WORK-036]
created: 2026-10-28
updated: 2026-10-28
---

# AIOps 缺口修复需求

## 用户原始诉求

2026-10-28，用户先问「当前的 AIOps 架构是什么样、对比开源项目的优缺点」，在得到八条不足后要求：「不足的这些点，你感觉要怎么修复呢？给我新开一个 work 把这些不足的全部都给我说一下。」

本项把这八条不足逐条变成可验收的需求。**不新增 AIOps 能力面**，只把 WORK-042/043 留下的断点补上、把没有证据的承诺补成证据。

## 已确认事实

以下每条都在仓库或生产配置里核对过（2026-10-28）：

1. **事件关不上**：`deploy/ops/provisioning/alerting/contact-points.yaml:32` 的 webhook 收件人是 `disableResolveMessage: true`；`codenest-online` 的 `/etc/grafana/provisioning/alerting/contact-points.yaml` 复核仍是 `true`。而 `OpsHookController.java:89-96` 已实现 resolved 分支。生产上 resolved 不会到达应用 → `dedupe_key` 永不置空 → `first_seen` 不再代表本次故障开始，复发与持续分不开。
2. **没有手动关闭**：`OpsIncidentController` 只有 list / detail / notes 三个端点；事件卡住只能改库。
3. **历史不复用**：`OpsEvidenceCollector.collect()`（`OpsEvidenceCollector.java:84`）只采当前窗口；该类没有任何对 `ops_incidents` / `ops_diagnoses` 的查询。同一问题第二次出现仍从零推理、再花一次钱。
4. **告警不关联**：`OpsIncidentService` 只用指纹做 dedupe；`OpsHookController` 只在**同一条通知内**用 critical 抑制 warning。不同 alertname 的同一根因会形成多个事件；`OpsDiagnosisService` 的冷却是按事件/告警名，跨告警名不生效。
5. **基线只有比值**：`prometheus-rules.yml` 的 `paideia:baseline_*:ratio = current / clamp_min(baseline, …)`，没有方差口径；对波动变大、周期内抖动无能为力。
6. **阈值两处**：`OpsProperties.Thresholds` 的 `diskUsedError=0.85`、`unitMemoryError=0.90`、`backupStaleHours=36` 与 `paideia-rules.yaml` 里的「可用低于 15%」「Laya 内存 90%」「36 小时」各写一份；规则文件注释自己写着「改一处要同步另一处」。
7. **诊断与应用同生命周期**：`OpsDiagnosisService` 用 `applicationTaskExecutor`；webhook 目标就是 `127.0.0.1:8081`。应用整体不可用时收不到通知、也跑不了诊断；只剩 Grafana 邮件。
8. **评测没闭合**：金集 12 条（`golden-set.json`）；`testing/report.md` 明记 live 模式从未跑、真实高流量快烧未构造、`paideia_ai_runs_total` 生产为空。
9. **事件列表不分页**：`OpsIncidentService.list` 上限 100（`OpsIncidentService.java:185`），前端固定 `limit=50`（`IncidentsPage.tsx:87`）。
10. **日志单元写死**：`OpsEvidenceCollector.logs()` 的 switch 只有 `paideia` / `laya`（`:129-137`）。
11. **无深链**：事件页只有文本；去 Grafana 看同一时间窗要手工改时间范围。

## 用户决定

2026-10-28：

1. **范围**：上述不足全部纳入本项，一个 WORK 分阶段交付（P0 事件闭环 → P1 复用与关联 → P2 检测与恢复 → P3 评测与体验），不拆多个 WORK。
2. **不引入外部 AIOps 平台**：继续不部署 Keep / HolmesGPT / Grafana OnCall / Netdata / vmanomaly（沿用 WORK-043 的裁决）。
3. **自动化边界不变**：仍只给人工动作，不做自动重启、回滚或修复。
4. **关闭这件事必须由人兜底**：允许管理员手动关闭事件；但不引入分派、值班表与 ACK 状态机。
5. **Agent 式调查循环本期不上**（2026-10-28 用户答复「ok 暂时不用上」）。升级分水岭为三条中任意一条：服务数 ≥3、一个月内「证据不足」报告占比超过 1/4、人工 SSH 排查成为常态；届时单独立项，不在本项预留代码。

## 实现选择

以下为 Agent 根据现状与仓库模式给出的默认做法；会改变范围或需要用户点头的列入「待确认问题」。

- **REQ-001 只改配置**：resolve 分支、集成测试都在，不再写代码。
- **REQ-002 复用手动关闭**：置 `RESOLVED`、`dedupe_key=NULL`、追加一条带 `actor_user_id` 的时间线；不加表、不加列、不写迁移。
- **REQ-003 用游标分页**：按 `(last_seen, id)` 做 keyset（`last_seen < ? OR (last_seen = ? AND id < ?)`），不用 OFFSET，避免深翻页。
- **REQ-004 历史只带结论**：同指纹最近 30 天最多 3 条；不足时补同 alertname 最近 2 条；每条只含发生时间、持续时长、上次报告结论首行（≤200 字）与评分，不带全文。
- **REQ-005 同期事件按窗口相交**：取 `first_seen`/`last_seen` 与本次告警窗口相交的其他 OPEN 事件，最多 5 条，只带 alertname/severity/首次时间。
- **REQ-006 关联窗口按 instance 匹配**：从近 10 分钟的 `DONE` 诊断 join 其事件，比较 `labels_json` 里的 instance；缺失时按全局。只在上一次 `DONE` 时生效，失败不拦。
- **REQ-007 纯 PromQL 的 z-score**：`(current - avg_over_time(x[7d])) / max(stddev_over_time(x[7d]), floor)`；分母下限防止低方差把 z 放大成无限。
- **REQ-008 阈值单一来源**：集中到 `deploy/ops/ops-thresholds.env`，`install-ops.sh` 渲染进 Grafana 规则；应用通过 systemd `EnvironmentFile` + Spring 宽松绑定（`PAIDEIA_OPS_THRESHOLDS_*`）读同一份。
- **REQ-009 补诊断有硬上限**：OPEN + 6 小时内创建 + 没有任何 DONE 记录 + 每次启动最多 1 条 + 开关默认开。
- **REQ-010/011 评测不进 CI**：金集扩到 ≥20；live 作为「改提示词或证据结构后的必跑步骤」，结果归档。
- **REQ-012 演练用临时阈值**：时间盒 15 分钟，先备份规则，演练后立即恢复；只在用户批准的窗口执行。
- **REQ-013 深链走已有反代**：`/api/v1/ops/grafana` + `from`/`to` 参数，不新增 Grafana 权限、不开新端口。
- **REQ-014 单元列表改配置**：`paideia.ops.log.units` 数组，配置本身即白名单，未知单元仍报参数错误。

## 背景

WORK-042 建了「确定性证据 + 一次诊断」，WORK-043 补了事件、基线、链路、SLO 与评分。骨架齐了，但闭环有三处断点（事件关不上、历史用不上、告警聚不拢），还有两处没有证据的承诺（live 评测没跑、快烧没真触发）。本项只补断点与证据。

## 目标

1. 事件能自动关闭，也能人工关闭；关闭后同一告警再触发是新事件。
2. 诊断能看到「上次同类问题怎么处理、结果如何」和「同一时段还有哪些告警」。
3. 同一实例的告警风暴不再按告警名重复花钱。
4. 基线除了比值还有 z-score；阈值只有一份来源，改了不会两边打架。
5. 应用重启后能把故障期间漏掉的诊断补上。
6. 「这套 AIOps 准不准」有 ≥20 条金集、跑过 live、真实快烧演练过一次。

## 非目标

- 不做自动修复、自动重启、自动回滚。
- 不做 agent 式调查循环（多轮工具调用）。本期仍是「一次采证 + 一次调用」；三条升级分水岭见「用户决定」第 5 条。
- 不部署 Keep / HolmesGPT / Netdata / vmanomaly；不引入常驻 ML 服务（z-score 是本次升级路径的上限）。
- **不把诊断移出应用进程**：应用整体不可用时 AIOps 仍然失明，这是已知并接受的限制；降级路径是 Grafana 邮件 + SSH。外部看门狗列为升级路径，不在本项。
- 不做按路由（uri）的 5xx/P95 基线：基数与告警噪音都不可控，留作升级路径。
- 不做事件分派、值班表、ACK 状态机、升级通知。
- 不做 LLM-as-judge 的 CI 门禁，不根据评分自动改提示词。
- 不改五节报告结构；任何提示词语义变化必须升 `OpsDiagnosisPrompt.VERSION`。

## 用户与工作流程

管理员：收到邮件 → 打开事件页 → 看时间线、同期事件、历史同类 → 读诊断 → 评分 → 手动关闭或等 resolved。

发布前：改了提示词或证据结构 → 跑 live 金集 → 结果写进当次 `testing/report.md`。

每季度：维护窗口内做一次快烧演练（临时阈值），确认「规则 firing → 事件 → 诊断」整条路径。

## 功能需求

### Track A：事件闭环

- **REQ-001**：Grafana 的 webhook 收件人必须投递 resolved；事件在 resolved 到达时置 `RESOLVED`、`dedupe_key=NULL`、追加时间线；之后再 firing 必须是新事件。
- **REQ-002**：管理员能在事件页手动关闭一个 OPEN 事件；关闭后的状态、`dedupe_key`、时间线与自动关闭一致，时间线记录操作者。
- **REQ-003**：事件列表支持按状态与时间范围筛选，并能取到 100 条之后的历史；默认仍按 `last_seen` 倒序。

### Track B：证据复用与关联

- **REQ-004**：诊断证据增加「历史同类事件」段：同指纹最近 30 天最多 3 条，不足时补同 alertname 最近 2 条；每条只含发生时间、持续时长、上次报告结论首行与评分；无历史时明写「无历史同类事件」。
- **REQ-005**：诊断证据增加「同期其他 OPEN 事件」段：与本次告警窗口相交的其他事件最多 5 条，含 alertname/severity/首次时间；没有时明写「无同期事件」。
- **REQ-006**：同一实例在关联窗口（默认 600s，可配）内已有 `DONE` 诊断时，新告警不再新开诊断，只在事件时间线写「复用诊断 #N」；上一次诊断失败时不拦。REQ-005 保证被跳过的那条告警仍会出现在证据里。

### Track C：检测质量

- **REQ-007**：关键指标基线增加 z-score（7 天均值与标准差），与现有比值并列进入证据与动态告警；标准差有下限、样本不足时仍写「基线不足」。
- **REQ-008**：应用阈值与 Grafana 规则阈值来自同一份配置文件；改一个值并重启两侧后，摘要与告警的判定一致。

### Track D：恢复能力

- **REQ-009**：应用重启后，对「OPEN、6 小时内创建、没有任何 DONE 诊断」的事件补一次诊断；每次启动最多 1 条；有开关（默认开）；补诊断在时间线标注来源。

### Track E：评测闭环

- **REQ-010**：金集扩到 ≥20 条，覆盖每类告警（目标下线/磁盘/备份/内存/5xx/ERROR/CPU 基线/5xx 基线/P95 基线/日志基线/SLO 快烧/慢烧）与每种降级（Loki 不可达、Tempo 不可达、基线不足、SLI 无流量、Prometheus 不可达）。
- **REQ-011**：live 模式可一条命令跑完整金集并输出通过率与失败清单；结果路径与结论进入当次测试报告；不接入 CI。
- **REQ-012**：在生产维护窗口用临时阈值触发一次真实 SLO 规则 firing，走完「Grafana → webhook → 事件 → 诊断 DONE」，15 分钟内恢复原阈值；过程与回滚证据归档。

### Track F：体验与扩展

- **REQ-013**：事件详情提供「在 Grafana 打开该事件时间窗」与「查该实例的错误日志」两个深链，走已有反代，不新增 Grafana 权限。
- **REQ-014**：日志单元从代码里的枚举改为配置列表（默认 `paideia`、`laya`），保留白名单校验；新增单元只改配置并重启。

## 质量要求

- 诊断证据总量仍 ≤12k 字符；新增两段（历史 + 同期）合计 ≤2000 字符，超出时先截历史再截同期。
- 每次诊断新增的数据库查询 ≤5 条，且全部命中索引（`fingerprint`、`status+last_seen`）。
- 手动关闭幂等：重复调用不产生第二条关闭时间线。
- 关联窗口、补诊断开关、z-score 下限都可配置，均有默认值。
- 不新增公网端口；深链不携带凭据；Grafana 仍只经应用反代。
- 所有新端点沿用 `ApiResponse` 与 ADMIN 权限；时间仍存 UTC。

## 约束与依赖

- 预计零迁移；若确需加列，用 14 位时间戳并先查同表冲突。
- Grafana 收件人改动必须重新 provision 并重启 Grafana，不能只改仓库文件。
- 阈值文件放服务器 600 权限目录，仓库只放示例与渲染/校验脚本。
- 生产演练需要用户批准与时间窗口；演练期间邮件仍会发送，需提前知会。
- 测试走 `scripts/test-db.sh`；规则改动跑 `promtool check/test rules`；前端只跑受影响 e2e 文件。
- 复用 WORK-043 已建的事件表、诊断表与评分列，不新建表。

## 关联工作项

- **WORK-043**：直接前置，本项修的是它留下的断点；无硬依赖（已完成）。
- **WORK-042 / WORK-028**：诊断证据与监控栈的来源。
- **WORK-036**：AI 观测面板；本项不碰其指标口径。

## 验收标准

- **AC-001**：生产上对一个测试告警依次投递 firing → resolved → firing，得到 2 个事件（1 OPEN、1 RESOLVED），时间线含 FIRED/RESOLVED，第二个事件 `first_seen` 是第二次触发时间。
- **AC-002**：对一条 OPEN 事件手动关闭后：状态 RESOLVED、`dedupe_key` 为空、时间线含操作者；重复关闭不新增时间线。
- **AC-003**：造 120 条历史事件后，列表能翻到第 2 页；按 OPEN 与时间范围筛选结果正确。
- **AC-004**：同一指纹第二次触发时，证据中出现上次事件日期与结论首行；无同类时写「无历史同类事件」；证据长度 ≤12k。
- **AC-005**：三条不同 alertname 在 5 分钟内触发，证据里出现另外两条的 alertname 与首次时间。
- **AC-006**：同一实例 10 分钟内三条不同告警，只有 1 次模型调用；后两条事件时间线写「复用诊断 #N」。
- **AC-007**：z-score 规则被 Grafana 加载；`promtool test rules` 覆盖「z>3 触发 / 方差大不触发 / 样本不足不触发」；证据行同时含 ratio 与 z。
- **AC-008**：修改阈值文件中的一个值并重启两侧，`/api/v1/ops/admin/summary` 与 Grafana 规则读到的数值一致；不一致时校验脚本非零退出。
- **AC-009**：模拟一次重启：一条 6 小时内 OPEN 且无 DONE 诊断的事件被补诊断；已有 DONE 的不补；开关关闭后不补；每次启动不超过 1 条。
- **AC-010**：金集 ≥20 条，prompt 模式全绿，且至少覆盖 6 类降级。
- **AC-011**：live 模式跑完 ≥20 条，产出通过率与失败清单，路径与结论出现在测试报告里。
- **AC-012**：演练当次：SLO 规则从 inactive 变 firing，webhook 收到、事件建立、诊断 DONE；恢复阈值后规则回到 inactive；全过程有命令与时间戳证据。
- **AC-013**：事件详情点「在 Grafana 打开」落到覆盖该事件时间窗的面板；点「查日志」落到对应时段的错误日志结果。
- **AC-014**：配置新增一个单元后（只改配置 + 重启），该单元的日志查询可用；未知单元仍返回参数错误。
- **AC-015**：WORK-043 的现有行为不退化：事件指纹、冷却、单飞、证据预算、评分、MCP 工具的相关测试 0 failed。

## 假设

- 事件量很低（每天 <20），历史查询不需要额外索引以外的手段。
- 单实例部署下「同一实例」基本等于全局，关联窗口不会误伤多环境场景。
- Grafana 13 的 provisioning 在重启后能切换 `disableResolveMessage` 并生效。
- 7 天标准差在低流量序列上可能为 0，用分母下限保护后不会产生无限 z 值。
- 演练在低谷进行，演练期间不做其他变更。

## 待确认问题

1. **快烧演练允许在生产临时改阈值吗？** 建议允许：维护窗口 15 分钟，先备份规则、演练后立即恢复，期间不接受其他发布。若不接受，AC-012 退化为 promtool 合成用例，并在报告里明确「端到端未验证」。
2. **关联窗口默认 600 秒合适吗？** 建议 600s，与现有诊断冷却一致；代价是 10 分钟内真正的第二个根因不会立即得到独立报告，只会出现在同一条证据里。
3. **历史同类是否跨实例？** 建议不跨：先同指纹，不足 3 条再补同 alertname（可跨实例），每条标明实例，避免把别处的结论当成本机的。

若以上按建议默认执行，批准需求即视为确认。
