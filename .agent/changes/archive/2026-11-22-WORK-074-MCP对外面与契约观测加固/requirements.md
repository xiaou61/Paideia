---
artifact: requirements
work_id: WORK-074
work: MCP 对外面与契约观测加固
status: approved
created: 2026-11-05
updated: 2026-11-05
mode: strict
mode_reason: 触及公共接口（MCP 工具面 / 跨模块事件与端口）与模块架构（paideia-ai Named Interface），并新增跨模块观测契约，按风险路由属 strict/full
workflow: full
depends_on: []
related_to: [WORK-019, WORK-028]
approved_by: xiaou61
approved_at: 2026-11-05 16:41:50 +0800
approver_role: owner
---

# 需求

## 背景

这是"三条扩展性方向"里的 B 与 C，合并成一项：

- **B：MCP 当唯一对外扩展面**——外部世界（AI 客户端、其他系统、自动化）要接 Paideia 的能力，统一走 MCP 工具面，不再靠散装 REST 接口扩张。
- **C：契约 / 观测加厚**——让"接口与模块边界"有机器守卫，让"线上出了什么事"能从数据看出来。

现状（已在仓库核对）：

- 已有 **22 个 tool 实现文件，分布 7 个模块**（account 1、ai 7、knowledge 7、note 2、ops 1、app 1、sandbox 3）；SDK 在 `paideia-platform`（`PaideiaTool`/`ToolCatalog`/`ToolQuery`/`ToolScope`/`ToolResult` 等）；网关 `paideia-mcp` 已有视图（`/mcp/x/{toolset}/write`）、PAT、限流、用量与指标。**但 tool 级契约测试只有 `paideia-platform` 的 `ToolCatalogTest`。**
- 契约侧：`ApiContractTest` 运行时枚举全部端点核 `@ApiAccess` 与实际鉴权；另有 `ApiSurfaceTest`、`AuthorizationIsolationTest`、`ModularityTest`。但**跨模块事件/端口没有"谁能发/谁必须消费"的守卫**。
- 观测侧：`paideia-ops` 有 Loki/Prometheus/Tempo/Grafana 客户端 + `OpsDiagnosis` 自诊断 + Analytics + `McpMetrics` + 日志脱敏。但**"内容导入"与"MCP 工具调用"两个扩展面没有专门的固定指标/漂移告警**。
- `paideia-ai` 是共改热点（400 次提交里 45 次，且与 app 20、knowledge 9、sandbox 8 高频共改），是需要收窄对外边界的首要候选。

## 已确认事实

1. 22 个 tool 源文件、7 个模块；**运行时 `ToolCatalog.snapshot()` 实测注册 67 个 tool**（一个源文件常含多个工具）。网关与 SDK 已就绪（见上）。
2. `paideia-platform` 现有跨模块契约：`UserDeletedEvent`、`EntryAssessmentChangedEvent`、`RoleLookup`、`ScheduledTaskRecorder`、`ApiAccess` 等。
3. 架构存档口径：190 个端点 / 48 个控制器；项目当前**没有 CI**，发布走 `scripts/release.ps1`（WORK-073 已给它加了 `-ValidateOnly` 内容校验）。
4. `ModularityTest` 只校验 Spring Modulith 边界（循环依赖、跨模块访问 internal）；不校验事件发布/消费契约。
5. 实测：跨模块 `import io.github.xiaou61.ai.internal.*` **只出现在 `paideia-app` 的测试代码**；生产模块均只用 `paideia-ai` 包根 API。故 `paideia-ai` 的生产边界当前干净，Named Interface 主要是"固化 + 加守卫防回归"。
6. `McpMetrics`（WORK-044）**已**用 Micrometer/Prometheus 提供 `paideia.mcp.tool.calls{toolset,result}`、`duration{tool}`、`denied{reason}`；Analytics 是管理端只读聚合（`/api/v1/ops/admin/analytics/overview`，固定 14 天窗）。即：MCP 工具调用观测已有底子，内容导入观测需新建。

## 用户决定

- 2026-11-05：先把 B、C 合并为**一个** WORK 推进（不分别开两个）。
- 2026-11-05：一个 WORK 内**分两期，先 B（MCP 对外面）后 C（契约观测）**。
- 2026-11-05：tool 契约测试做**无副作用边界测试**——只发畸形 / 越权 / scope 不足的请求，不写库、不花积分。
- 2026-11-05：`paideia-ai` 以**现有包根为对外 API 面**，加"生产代码不得 `import ai.internal`（测试代码豁免）"的构建守卫；不再进一步收窄成子集。
- 2026-11-05：观测落点——MCP 工具调用指标**扩展现有 `McpMetrics`（Prometheus）**；内容导入指标进 **Analytics**（管理端）。
- 2026-11-05：导入漂移告警**复用 Grafana 告警**（Prometheus 指标 + Grafana 规则 → 邮件，与 WORK-028 一致）。

## 实现选择

（strict/full：待需求批准后在 proposal/design 落。）

## 目标

**B 线**
1. 一份"对外能力清单 + 纪律"：列全部 tool 的 toolset / scope / 读或写；规定新对外能力优先 MCP tool，REST 只服务自家前端。
2. 给现有注册工具（实测 67 个）补 tool 级契约测试（参数校验 / scope / 结果形状 / 错误码）。
3. 一次收敛审计：找出"该做 tool 却做成 REST"或 tool 与 REST 重复的，出清单与处置。

**C 线**
4. 跨模块事件与端口的"谁能发 / 谁必须消费"守卫。
5. 把 `paideia-ai` 对其它模块开放的能力收窄成显式 API 包（Named Interface），加"外部只能用 API 包"的守卫。
6. 给"内容导入""MCP 工具调用"两个扩展面加固定指标（导入成功/跳过/冲突；每 tool 调用量/失败率/积分），接进 Analytics。
7. "导入漂移"告警（源内容改了但线上未导入）。

## 非目标

- 不重写 MCP 网关（视图/PAT/限流/用量保持）。
- 不把 REST 迁到 MCP（REST 继续服务自家前端）。
- 不引入新的监控栈（沿用 Prometheus/Grafana + Analytics）。
- 不做运行时插件加载（那不在本期主题内）。

## 功能需求

- REQ-001（B）对外能力清单 + 纪律，覆盖全部 tool 的 toolset/scope/读写。
- REQ-002（B）tool 级契约测试：每个 tool 的坏参数/越权/scope 不足/结果形状/错误码。
- REQ-003（B）收敛审计清单：REST↔tool 重复或错位的条目与处置。
- REQ-004（C）跨模块事件/端口守卫：谁能发、谁必须消费，越界可证。
- REQ-005（C）`paideia-ai` 以现有包根为对外 API 面，加"生产代码不得 import `ai.internal`（测试豁免）"的构建守卫。
- REQ-006（C）内容导入指标进 Analytics（成功/跳过/冲突）；MCP 工具调用指标扩展现有 `McpMetrics`（Prometheus：调用量/失败率/耗时/拒绝原因）。
- REQ-007（C）导入漂移告警：以 Prometheus 指标 + Grafana 规则实现（复用 WORK-028 告警通道），可触发。

## 质量要求

- 守卫类必须"坏样例会红"（反向可证），不能只写正向断言。
- 指标要能被查询/展示，不能只埋点无出口。
- 不改变现有用户可见行为；不改网关鉴权语义。

## 约束与依赖

- 遵循 `.agent/rules/always.md`：模块化单体 + Spring Modulith；不引入新框架；公开仓库保密。
- 与 WORK-073 的 `check-content.mjs` 共享"内容导入"指标口径（导入成功/跳过/冲突）。

## 关联工作项

- `related_to: WORK-019`（MCP 网关与工具面由它建立）、`WORK-028`（Prometheus/Grafana 监控与 ops 页由它建立）；本项在两处之上加治理与加固，可独立交付。
- `depends_on`：无。

## 验收标准

- AC-001：对外能力清单覆盖全部注册工具（实测 67 个），每项 toolset/scope/读写齐全。
- AC-002：每个 tool 有契约测试；坏参数/越权/scope 不足均被拦（**无副作用**，不写库、不花积分），给出可运行命令。
- AC-003：收敛审计清单产出，每项有处置结论。
- AC-004：跨模块事件/端口守卫存在；构造一个非法发送/消费样例会让检查变红。
- AC-005：`paideia-ai` 生产代码只用包根 API；越界 `import ai.internal` 导致构建失败（反向可证；测试代码豁免）。
- AC-006：MCP 工具指标可在 Prometheus/Grafana 查询（调用量/失败率/耗时/拒绝原因）；内容导入指标在管理端 Analytics 可见（给出查询或页面证据）。
- AC-007：导入漂移告警规则存在并可被触发（构造一次"源改了但未导入"，Grafana 规则进入 firing / 发邮件）。

## 假设

- 注册工具清单与模块归属以 `ToolCatalog.snapshot()` 实测为准（当前 67 个）。
- 指标落点可复用现有 Analytics/`McpMetrics`，不新建监控组件。

## 待确认问题

- 无。5 个问题（分期 / 测试边界 / ai 边界 / 指标落点 / 告警渠道）均已确认，见「用户决定」。
