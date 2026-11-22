---
artifact: design
work_id: WORK-025
work: 八股文专区
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-004, WORK-009, WORK-021, WORK-022, WORK-026]
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 15:36:51 +0800
approver_role: CTO
---

# 设计 · 八股文专区

## 系统上下文

```
EssayCollectionJob (@Scheduled, fixedDelay 24h, 仅 enabled=true 时注册)
        │
        ▼
EssayCollectionService.runOnce()
        │
        ├─ 1. 定位分类：listCategories() 里找 slug = essay-java
        ├─ 2. 取凭据：CredentialService.access()（平台凭据）
        ├─ 3. 抓取：EssaySource.fetchSince(now - lookbackDays, maxPages)
        │        └─ NowcoderEssaySource → POST /api/sparta/.../job/list
        ├─ 4. 过滤：TechEssayFilter.isTechnical(body)      ← WORK-026
        │        命中 0 种技术词 → 跳过（不花 AI）
        ├─ 5. 抽题：EssayExtractor.extract(post, access)
        │        └─ DeepSeek → EssayQuestionParser 逐条校验
        └─ 6. 入库：KnowledgeApi.createCollected(...)
                 └─ 按 (category_id, fingerprint) 判重
```

**依赖方向**：`ai → knowledge`。所以编排放在 `ai.internal.essay`，写入走 `KnowledgeApi` 的方法。
反过来把编排放 `knowledge` 会成环。

## 表结构变动（V24）

```sql
ALTER TABLE knowledge_entries
  ADD COLUMN source_url VARCHAR(512) NULL AFTER body,
  ADD COLUMN fingerprint VARCHAR(64) NULL AFTER source_url;

CREATE INDEX idx_knowledge_entries_fingerprint
  ON knowledge_entries (category_id, fingerprint);
```

**两列都可空** → 存量条目（手抄、课程导入）行为零变化。

**为什么不加唯一约束**：唯一约束建在可空列上时 MySQL 允许多个 NULL，但"指纹为空"的去重语义会依赖数据库而不是代码；判重放在写入路径上（先查后插），且**限定分类内**——同一道题出现在不同分类里是有意义的。

## 题目指纹

```
title → 去掉行首序号 → 去掉全部标点与空白 → 转小写 → SHA-256
```

例：「1、HashMap 为什么线程不安全？」与「hashmap为什么线程不安全」落到同一指纹。

**为什么按题目而不是按帖子**：同一道题会在不同面经里反复出现（"HashMap 为什么线程不安全"每天都有）。按帖子去重等于每天新增一条重复内容。

## 抓取通路（实测选定）

| 项 | 值 |
| --- | --- |
| 端点 | `POST https://gw-c.nowcoder.com/api/sparta/job-experience/experience/job/list` |
| 请求体 | `{"page":N,"pageSize":20,"jobId":639}` |
| pageSize | **硬上限 20**，填 50/100 返回"系统未知错误" |
| 正文 | `data.records[].momentData.content` |
| 时间 | `data.records[].momentData.createdAt`（epoch millis） |
| 标题 | `data.records[].momentData.title` |
| 过滤 | 只取 `contentType == 74`（面经帖）；250 无标题无时间无正文 |
| 翻页停止 | 遇到 `createdAt < since` 即停（列表按时间倒序） |

**为什么不用合集题目清单**：实测 `/exam/paper/<id>` 是 404 骨架、题目清单接口找不到。面经列表这条路反而直接带正文。

## 抽题提示词（本项最关键的一段）

**问题**：初版要求"抽出所有技术题、每篇解析 200~600 字"，实测输出被截断两次：

| max_tokens | 结果 |
| --- | --- |
| 4096 | 截在 JSON 中间（`Unterminated string` at char 2560） |
| 8192 | 同样吃满（`Unterminated string` at char 12843） |

根因：deepseek-flash 默认思考，**思考 token 与正文共用 `max_tokens`**。

**修法**：提示词里把输出规模压死——"**只挑 1 道题**、解析 150~300 字"，并加一句"写多了会被截断，宁可少而完整"。`MAX_TOKENS` 提到 16384 只作安全余量。

**修正后实测**：4.1 秒 / 输出 665 tokens / `finish=stop` / 合法 JSON / 解析 466 字。

三条硬约束（对应 REQ-008）：
1. 只提取技术问题，学校安排/薪资/职业规划/面试感受一律丢。
2. 不出现公司名、薪资数字、作者昵称。
3. 解析是自己的话，不是抄的。

## 入库正文形态

```markdown
## <题目>

<解析>

---

> 题目来源：[<帖子标题>](<原帖链接>)
```

**不放帖子原文**：里面有公司名、薪资、个人经历。逐字入库既是转载也不适合做学习内容。

## 失败与降级矩阵

| 情形 | 行为 |
| --- | --- |
| 未启用（`enabled=false`） | 定时任务 Bean 根本不注册 |
| 分类不存在 | 整次跳过，记日志，**不自动建分类** |
| 平台凭据未配置 | 整次跳过 |
| 抓取失败（网络/上游改结构） | 整次跳过并记日志，不抛给调度器 |
| 本次没有新帖 | 正常返回，不是错误 |
| 某篇非技术帖 | 跳过，**不消耗 AI 额度** |
| 某篇抽题失败 | 该篇计失败，其它篇照收 |
| 抽题返回空数组 | 合法结果（"这篇没技术题"） |
| 抽题返回空内容 | 故障——`BizException`，该篇计失败 |
| 单条题目字段不合格 | 只丢那一条，其余照收 |

## 安全边界

- 抓取地址、jobId、分类 slug 全部走配置，不硬编码。
- 提示词要求解析不抄原文、不出个人信息；`render()` 只拼题目与解析，**不拼帖子正文**。
- 前端外链 `rel="noreferrer noopener"`、`target="_blank"`。
- 抓取无凭据、无 Cookie，不涉及用户隐私数据。

## 关键取舍

| 取舍 | 选择 | 代价 |
| --- | --- | --- |
| 编排放 `ai` 还是 `knowledge` | `ai`（依赖方向决定） | `knowledge` 要开一个写入口 |
| 是否扣积分 | **不扣** | 成本不在产品积分面板可见；靠部署侧统计 |
| 一篇抽几道题 | **1 道** | 帖子里的其它题被丢掉；但换来了输出不截断 |
| 直接发布还是草稿 | **草稿** | 需要人去发布；换来质量兜底 |
| 建不建「平台精讲」 | **不建** | 一期只有真题一个分类 |

## 已知缺口

1. **定时任务未在生产启用**：`enabled: false`，要开需显式配置。
2. **没有 e2e**：没验"从专区点进去看到条目"这条界面路径。
3. **一篇只抽 1 道题**：为规避截断而牺牲覆盖率，将来可用分批调用提高。
4. **未接 WORK-022 遥测**：抽题的 token 消耗不在统计里。
5. **不扣积分**：平台成本只在部署侧可见。
6. **分类内判重的并发窗口**：两个任务同时抓到同一题理论上会双写；当前是单实例定时任务，未加唯一约束。
