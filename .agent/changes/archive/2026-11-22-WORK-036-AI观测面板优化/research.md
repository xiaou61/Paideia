---
artifact: research
work_id: WORK-036
work: AI 观测面板优化
status: draft
created: 2026-10-23
updated: 2026-10-23
---

# AI 观测面板优化的调研事实

证据文件。需求与验收在 [requirements.md](requirements.md)。

## 一、WORK-022 已经做到了什么（2026-10-23 逐文件核对）

起草前先核对，避免把已有能力重复立项：

| 能力 | 现状 | 出处 |
| --- | --- | --- |
| 轮次记录 | 有。`kind` / `harness` / `model` / `prompt_version` / 状态 / 耗时 / 首字 / 队列等待 / 轮数 / 工具数 / 三类 token / 实扣积分 / `attrs` | `V21__create_ai_telemetry_tables.sql` |
| 步骤记录 | 有。`ai_run_spans` 含 `parent_seq` 列与 `kind`（llm/tool/retrieval/persist/billing） | 同上 |
| 完整载荷 | 有。请求体与响应体落 `ai_run_payloads`，30 天保留，超 1MB 存占位 | `V20261021181500__create_ai_run_payloads.sql` |
| 采集健壮性 | 有。有界队列 + 丢弃计数 + 写失败计数 + 失败只打日志 | `RunWriteQueue` |
| 概览统计 | 有。调用量、成功率、耗时 p50/p95、首字 p50/p95、token 合计、扣分合计、按场景/模型分布、错误类型排行、工具排行 | `AiRunAdminService.Overview` |
| 明细与下钻 | 有。按时间窗/场景/状态/用户/模型筛选分页，展开看步骤表与完整载荷 | `AiRunsListPage.tsx` |
| 保留与清理 | 有。`ai_runs` 90 天、`ai_run_payloads` 30 天，每小时分批清理，`running` 超 6 小时标 interrupted | `TelemetryRetentionPurger` |
| 采样 | 有。只对成功轮次采样，失败/超时/中断永远保留 | `TelemetryProperties` |
| 基础设施指标 | 有。Prometheus + Grafana + 四条告警（目标下线/磁盘/备份/Laya 内存） | WORK-028 |

## 二、缺口（本轮逐文件核对，2026-10-23）

| 项 | 事实 |
| --- | --- |
| 概览无时间序列 | `overview` 只返回**一个窗口内的聚合标量**。没有按桶的序列，因此"成功率这几天怎么走的、拐点在哪"看不出来，只能反复改时间窗看几个静态数字 |
| 概览无筛选维度 | `GET /api/v1/ai/admin/runs/overview` 只接受 `from` / `to`。**明细页有 kind/status/userId/model，概览一个都没有**——所以"哪个场景成功率高"必须先翻列表再自己心算 |
| 缓存命中未露出 | `ai_runs.cache_read_tokens` 与各 span 的同名列**在采集**，`RunSummary` 也带；但 `TelemetrySummaryMapper.Totals` 只合计 `input/output/credits`，概览页只显示"输入/输出 tokens"（`AiRunsOverviewPage.tsx:200`）。**采了但没显示** |
| 无任何图表 | `frontend/apps/admin/package.json` 与 `frontend/packages/ui/package.json` 均无图表库；全仓 `recharts` / `LineChart` / `sparkline` 零命中。概览页只有卡片、排行列表与状态分布条 |
| `parent_seq` 永远是 NULL | 列存在、mapper 会写、`SpanView` 会读，但 **`SpanSpec` 构造器只有 `(kind, name)`，没有 parent 参数**，`SpanRecord` 的 `parentSeq` 因此恒为 null。声明的层级从未落地 |
| 采集自监控未暴露 | `RunWriteQueue` 有 `dropped` 与 `writeFailures` 两个 `AtomicLong`，但**只在处理完一批时打一条日志**（`log.info("ai.telemetry.dropped ...")`），没有任何接口返回它们。队列满静默丢数据时，面板上的数字看起来完全正常 |
| 采样会扭曲成功率 | 采样只丢**成功**轮次，失败永远保留。一旦把 `sampling` 调小于 1，成功率的分子被砍、分母不变，**面板会系统性地低估成功率**。当前 1.0，所以还没显形 |
| 积分对账无实现 | `ai_runs.credits` 的注释写着"与积分流水做日级对账"，`RunRecorder.addCredits` 的注释写"与积分流水做日级对账"——但**全仓没有任何对账代码或接口**。这是一句承诺，不是事实 |
| 无用户反馈信号 | `ai_messages` 没有评分列，学习者端无点赞/点踩。**所有统计只回答"快不快、贵不贵"，回答不了"好不好"** |
| 明细页无精确搜索 | 只有四个下拉筛选加一个 `userId` 文本框；**没有按 `traceId` 直接定位**。日志里拿到 id 之后没有对应的查法 |
| 提示词版本只是记账 | `prompt_version` 落库，但版本内容在代码常量里（`ConversationPrompt.VERSION` 等）。改提示词必须改代码、提交、发版 |
| 无固定用例集与批量评测 | 最接近的是 `CampComparer`，但那是训练营的诊断卷/复测卷业务对比，不是"换提示词后跑同一批用例看回归" |
| 管理端鉴权 | `/api/v1/ai/admin/**` 已有 `hasRole("ADMIN")` 且排在 `/api/v1/ai/**` 之前；新增端点沿用该前缀即自动受保护 |
| 前端测试入口 | 管理端 e2e 走 `E2E_BASE_URL` 打线上（7 passed / 14.7 秒），**必须带 `MSYS_NO_PATHCONV=1`** |

## 三、Langfuse 对比（2026-10-23 联网核实）

- 2026-01-16 **ClickHouse 收购 Langfuse**；官方声明 MIT 不变、自托管仍是一等路径，仅 SCIM / 审计日志 / 保留策略 / 企业 SLA 需商业授权。
- Langfuse v4 改为 observations-first 数据模型，**旧的 ingestion API 与一批 v3 端点已下线**，Langfuse Cloud 上的旧端点服务到 2026-11-16。
- `langfuse-java` **不是追踪 SDK**，只是自动生成的 API 客户端（读提示词/数据集/评分）；官方明确追踪必须走 OpenTelemetry Java SDK 导出到 OTLP/HTTP 端点，且 span 要带 `gen_ai.*` 属性才会被渲染成"模型调用"。
- 自托管要 Postgres + ClickHouse + Redis/Valkey + S3 四个存储加 web/worker 两个容器。本机生产机 4 核 16G 已跑 MySQL / RustFS / Firecrawl / Laya / Prometheus 全家桶。

## 四、Langfuse 能力与我们现状的逐项对照

| Langfuse 能力 | 我们的现状 | 本项是否追 |
| --- | --- | --- |
| 时间序列曲线与保存视图 | 无图表、无序列 | 追（A 组）。内联 SVG 即可 |
| 按任意维度切片看聚合 | 概览无筛选，只有明细有 | 追（A 组） |
| 每 token 真实单价与跨模型成本对比 | 自配价目表，只记积分 | 部分（A 组名义/实扣双数）。完整模型定价表不追 |
| 数据集 + 实验 + LLM-as-judge + 人工标注 | 只有训练营业务对比 | **不追**。见需求的非目标与升级触发条件 |
| 提示词注册表（网页编辑、发版、灰度） | 版本号是代码常量 | **不追**。见升级触发条件 |
| Session 聚合视图 | 有 `conversation_id` 列与过滤，无聚合页 | 不追（价值低于 A/B 组） |
| trace 瀑布图 | 步骤表（平铺） | 部分（D 组做层级缩进） |
| 用户反馈/标注队列 | 无 | 追（C 组），只做最小闭环 |

**结论沿用 WORK-022**：不引入 Langfuse 本身，只借它的能力形状。理由见 requirements.md 的非目标。
