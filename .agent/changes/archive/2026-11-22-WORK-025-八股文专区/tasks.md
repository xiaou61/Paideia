---
artifact: tasks
work_id: WORK-025
work: 八股文专区
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-004, WORK-026]
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 15:36:57 +0800
approver_role: CTO
---

# 任务 · 八股文专区

**排序原则**：先让"能存"和"能抓"各自独立可验（S1/S2/S3），再验证最不确定的一段——**AI 抽题的输出能否稳定拿到合法 JSON**。那一步不成立，后面全部白做。

**全局约束**：不新增 Maven 依赖；迁移只做增量（V22 曾因改迁移导致线上起不来）；抓取地址走配置；不碰用户积分体系。

## 任务

### TASK-001 | done | 迁移 V24：两列 + 索引 + 分类

`knowledge_entries` 加 `source_url` / `fingerprint`，建 `(category_id, fingerprint)` 索引；幂等插入 `essay-java` 分类（`INSERT ... SELECT ... WHERE NOT EXISTS`）。

**验收**：集成测试能查出两列并写入；分类按 slug 可定位。

### TASK-002 | done | 写入入口 `KnowledgeApi.createCollected`

带指纹的写入 + 分类内判重。默认实现抛 `UnsupportedOperationException`（避免测试桩被迫实现）。

**验收**：同指纹写两次只产生一条，第二次返回 `created=false` 与原 id。

### TASK-003 | done | 抓取端口与牛客实现

`EssaySource` 端口 + `NowcoderEssaySource`。只取 `contentType=74`，翻页到越过下界即停。

**验收**：服务器侧真抓返回 14 篇面经帖、total 2000、0.54s。

### TASK-004 | done | 抽题提示词与解析校验

`EssayExtractionPrompt` + `EssayQuestionParser`（逐条校验，坏一条不废整篇）。

**验收**：12 条单测（空数组合法 / 空内容是故障 / 围栏剥离 / 单条丢弃）。

### TASK-005 | done | AI 调用封装

`EssayExtractor`：非流式、`nativeStructuredOutput(false)`、`GuardedHttpTransport`。

**验收**：真调上游返回合法 JSON（见 TASK-009 的实测）。

### TASK-006 | done | 编排服务

`EssayCollectionService`：抓 → 过滤 → 抽题 → 入库；四个上限（帖子数、题数、页数、超时）。

**验收**：各阶段失败都不抛给调用方；非技术帖不触发 AI。

### TASK-007 | done | 定时任务与条件装配

`EssayCollectionJob` + `EssayConfiguration`（`@ConditionalOnProperty`）。刻意不加 `@Component`——加了会绕过条件装配、每天空转。

**验收**：`enabled=false` 时 Bean 不存在。

### TASK-008 | done | 前端来源链接

`EntryDetail.sourceUrl` 可选字段；详情页显示「题目来源：原帖」，`rel="noreferrer noopener"`。

**验收**：`@paideia/knowledge` 类型检查通过。

### TASK-009 | done | 端到端真跑（含 token 截断修复）

真抓 → 真过滤 → 真调 DeepSeek → 拿到合法 JSON 与可用内容。

**验收**：4.1s / 665 tokens / `finish=stop` / 1 题 / 解析 466 字。**这一条同时验证了"提示词压到一道题"这个修正有效。**

## 评审焦点

| 失败模式 | 为什么值得单独看 | 现有覆盖 |
| --- | --- | --- |
| **输出被截断导致整篇作废** | 已实测触发两次；截断时 JSON 不合法，该篇直接失败 | 有 end-to-end 实测，但**没有自动回归**——上游换了模型或提示词改长，只会表现为"每天 0 产出" |
| **个人信息漏进正文** | 提示词约束是软约束，模型可能不遵守 | 无自动覆盖。`render()` 不拼原文降低了面，但解析里仍可能提到公司名 |
| 指纹撞车 | 不同题目落到同一指纹 → 后一条被当重复丢掉 | `differentQuestionsDoNotCollide` 只覆盖了一组样例 |
| 分类被改名 | 抓取按 slug 定位；分类被删则整次跳过 | 无覆盖，只靠日志 |
| 定时任务从未跑过 | `enabled=false` 默认关，部署时忘了开就静默无产出 | 无覆盖。开与不开都不报错 |
| 非技术帖被误判为技术 | 会白花 AI 额度 | `TechEssayFilterTest` 6 条覆盖 |

## 未包含

- 「平台精讲」分类与内容生成
- 管理端的面经采集面板
- e2e 覆盖专区浏览路径
- WORK-022 遥测接入
- 一篇多题（当前一篇只抽 1 道）
- 多语种来源（只有 Java，`jobId=639`）
