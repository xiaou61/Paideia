---
artifact: proposal
work_id: WORK-036
work: AI 观测面板优化
status: approved
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 14:34:03 +0800
approver_role: CTO
---

# 方案 · AI 观测面板优化

需求与验收在 [requirements.md](requirements.md)，证据在 [research.md](research.md)。

## 一句话

在 WORK-022 已建成的三张表与采集端口之上，**补三样东西**：能看趋势的序列、能说明"这份统计可不可信"的自监控、以及一条用户质量信号。**不新增任何基础设施**。

## 为什么不是"接 Langfuse"

WORK-022 已经评估并否掉了。本轮重新核实的四条新事实（见 research.md 第三节）没有改变结论，反而加强了它：

- Langfuse v4 改了数据模型，**旧 ingestion API 已下线**，官方只支持走 OpenTelemetry Java SDK。要接就得引 OTel SDK 进来——那是新的批处理、重试、导出线程与采样，全是新的失败面。
- 自托管的四个有状态存储 + 两个容器，要装在已有 MySQL / RustFS / Firecrawl / Laya / Prometheus 的 4 核 16G 机器上。
- 我们是单进程应用，OTel 最核心的跨进程上下文传播完全用不上。
- `langfuse-java` 不是追踪 SDK，只是 API 客户端——接它不等于"接上了追踪"。

真正缺的不是后端，是**三个可以在现有表上算出来的视图**。

## 三个设计的核心取舍

### 一、分桶在 SQL 里做，不在 Java 里做

`GROUP BY` 一个时间桶表达式 + `ROW_NUMBER()` 算分位数，与 WORK-022 已有的 `TelemetrySummaryMapper` 完全同构。

**空桶补零在 Java 里做**：SQL 只能返回"有数据的桶"，缺的时段得在应用层按桶边界铺满。这一步是必需的——折线在无调用时段断开，看起来像数据丢失而不是没调用。分桶边界用 `Asia/Shanghai`（与速递的"今天"口径一致），**不用 UTC**，否则"今天"在界面上会从早上 8 点开始。

### 二、采集健康读内存，不读库

`RunWriteQueue` 已经有 `dropped` 与 `writeFailures` 两个 `AtomicLong`，加一个 `queue.size()` 与一个"最近一次成功写入时刻"（`AtomicReference<Instant>`）就够了。**不落库**：这些数字在进程内，重启即清零是正确的语义——它们描述的是"这个进程有没有在丢数据"，不是历史。

代价是**多实例部署时每个进程各有一份**。本期单实例，注释里写明这条限制。

### 三、反馈独立建表，且只允许枚举原因

一张 `ai_feedback`：

| 列 | 说明 |
| --- | --- |
| `id` | 自增 |
| `user_id` | 提出者 |
| `conversation_id` | 可空 |
| `message_id` | 助手消息 id（可空，非对话路径没有） |
| `trace_id` | 关联那一轮，可空（关掉采集时没有） |
| `kind` | 冗余场景，省一次 join |
| `rating` | `up` / `down` |
| `reason` | 仅 `down` 时有：`off_topic` / `too_verbose` / `incorrect` / `other` |
| `created_at` | |

**唯一键 `(user_id, trace_id)`** 让重复提交变成覆盖（`ON DUPLICATE KEY UPDATE`）而不是追加——与 WORK-013 的重复抑制口径一致。`trace_id` 为空时唯一键退化（MySQL 允许多个 NULL），所以关掉采集时同一用户可能留多条——**这是可接受的**，因为那时本来就没有轮次可挂，反馈只剩"这个用户点了踩"。

**不收自由文本**：那会把用户输入变成需要审核、需要防注入、需要展示转义的内容。四个枚举足够回答"哪方面不好"。

### 四、`parent_seq` 的落地方式

`SpanSpec` 加一个静态工厂 `child(String kind, String name, int parentSeq)`，`SpanRecord` 保留已有列。**只有工具步会用到**：`AgentScopeConversation` 在工具调用里已经有 `step`，如果将来工具内部再调模型（现在没有），子步骤才有内容。

**本期实际只做两件事**：让端口支持传 parent，并让详情页按层级缩进渲染。真正的子步骤内容现在没有产生点——`retrieval` 类型的步骤在仓库里**零使用**（`SpanSpec.retrieval` 定义了但没人调）。

**这与需求里的 REQ-014 有偏差**：需求写的是"把工具内部的检索/子调用表达成子步骤"。核对后发现**工具内部没有子调用可分**——工具就是一次 `AgentToolBridge.run`。所以本期的诚实做法是：**接口支持 + 渲染支持 + 一个测试证明它工作**，但不虚构一个不存在的子步骤。这条要在验证报告里如实写。

## 与既有实现的接触面

| 文件 | 改动 |
| --- | --- |
| `AiRunAdminService` | 加 `series()`、`health()`、`reconcile()`、`durationBreakdown()`；`overview` 加筛选参数与缓存/名义成本字段 |
| `TelemetrySummaryMapper` | 加按桶聚合、按天对账、按天 token 合计 |
| `AiRunMapper` | 加 `traceId` 前缀搜索参数 |
| `RunWriteQueue` | 暴露 `depth()` / `dropped()` / `writeFailures()` / `lastSuccessAt()` |
| `RunRecorder` / `SpanSpec` | 加子步骤工厂；`SpanRecord` 不变 |
| `AiRunAdminController` | 加 `/series`、`/health`、`/reconcile`；`overview` 加参数 |
| `Segmentation`（新） | 分桶边界计算，纯函数 |
| `ai_feedback`（新表 + mapper + service + controller） | C 组全部 |
| 学习者端 `conversation-panel.tsx` | 每条助手消息下方加赞/踩 |
| `AiRunsOverviewPage` / `AiRunsListPage` / 新 `AiFeedbackPage` | A、B、C 三组的界面 |

## 备选与未选

**备选一：把分桶放到前端算。** 让接口返回明细，前端自己按时间归并。未选：那是把一张百万行表拉进浏览器，且分位数无法在前端算（要全量）。

**备选二：为反馈单开一张与轮次无关的表，不关联 traceId。** 未选：那样反馈只能统计"多少人点了踩"，无法定位到"哪一轮、哪个场景、哪个提示词版本"，等于把最有价值的那半个维度丢了。

**备选三：用既有 `ai_messages` 加一列 `rating`。** 未选：一条回答可能被改判（先踩后赞），加列只能存最后一次且无法保留原因的时间线；且 `ai_messages` 是热表，加列要改它的写入路径。

## 风险

| 风险 | 处理 |
| --- | --- |
| 分桶边界算错导致折线出现假断崖 | 空桶补零单独一个纯函数 + AC-002 专门断言；时区固定 `Asia/Shanghai` 并写进测试 |
| 对账差值一上线就非零，被误判成计费 bug | 差值的方向与含义写在界面上；已知的正常差异来源（缓存命中不计费、失败轮次不扣分、价目表变更）列在说明里 |
| 反馈按钮被刷 | 唯一键天然去重；同一用户同一轮只留一条。真被刷再加频率上限 |
| 采集健康在多实例下每进程一份 | 注释写明；本期单实例 |
| 反馈表忘了登记注销级联 | `AccountDeletionCascadeIntegrationTest` 会红——这正是它的用途 |
