---
artifact: test-report
work_id: WORK-070
work: Laya 面经分流与知识点重排对照
status: passed
evidence: required
created: 2026-11-04
updated: 2026-11-04
---

# 验证报告

## 证据矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 topic 分流与分类缺失回退 | `scripts/test-db.sh mvn -B -pl paideia-ai -am -Dtest=EssayCollectionRoutingTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 5/5 通过：`routesToMappedCategoryWhenItExists` 落 id=2；`fallsBackWhenMappedCategoryIsMissing` 回退 id=1 且 `routed=0` |
| AC-002 三个计数进汇总日志且可断言 | 同上 | 0 | passed | 5 条日志全部含 `（边界 B / 问模型 M / 改判 K / 分流 R）`；三种桩输入分别为 0/0/0、1/1/0、1/1/1 |
| AC-003 基线 vs Laya 重排 Recall@3 | `mvn -B -pl paideia-knowledge -am -Dtest=ConceptRerankLiveTest -Dpaideia.laya.live=true -Dpaideia.laya.base-url=http://127.0.0.1:8091 test`（经 SSH 隧道打生产 laya） | 0 | passed | 基线 **0.8302**（top1 0.6164）／重排后 **0.6730**（top1 0.3774）；159 次调用、154 成功、5 失败、89.8 秒。结论：**不采用** |
| AC-004 关闭开关行为不变、调用链唯一 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=EssayCollectionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`；`rg -n "LayaContentClassifier" backend` | 0 | passed | 3/3 通过；生产调用方仍只有 `TechEssayFilter`（唯一注入 `ContentClassifier` 的类型） |
| 默认不跑实验 | `mvn -B -pl paideia-knowledge -am -Dtest=ConceptRecallTest,ConceptRerankLiveTest ... test` | 0 | passed | 回归门 2/2 通过；`ConceptRerankLiveTest` 1 条 skipped（未给 `-Dpaideia.laya.live=true`） |

## AC-001 / AC-002 细节

新增 `EssayCollectionRoutingTest`（5 条），用桩替掉抓取、抽题与凭据，只验「选了哪个分类」与「几个计数」：

- `routesToMappedCategoryWhenItExists`：`{"database":"essay-database"}` + 该分类存在 → 入库 categoryId = 2；
- `fallsBackWhenMappedCategoryIsMissing`：映射配了但分类没建 → 回退默认分类 id = 1，且 `routed = 0`；
- `noMappingKeepsPreviousBehaviourAndNeverAsksForTopic`：`category-slugs` 为空 → 不额外问主题、`askedModel = 0`；
- `countsBorderlineModelCallsAndOverrides`：边界帖 + 模型判技术 → `borderline=1 / askedModel=1 / changedByModel=1`；
- `modelUnavailableFallsBackToRuleWithoutCountingAnOverride`：模型返回空 → 按规则判非技术，不计改判。

真实日志（测试运行时捕获，未改写）：

```text
八股文抓取完成：抓 1 篇，跳过非技术 0 篇（边界 0 / 问模型 0 / 改判 0 / 分流 0），抽出 1 题，新建 1 条，重复 0 条，失败 0 篇
八股文抓取完成：抓 1 篇，跳过非技术 1 篇（边界 1 / 问模型 1 / 改判 0 / 分流 0），抽出 0 题，新建 0 条，重复 0 条，失败 0 篇
八股文抓取完成：抓 1 篇，跳过非技术 0 篇（边界 0 / 问模型 1 / 改判 0 / 分流 1），抽出 1 题，新建 1 条，重复 0 条，失败 0 篇
八股文抓取完成：抓 1 篇，跳过非技术 0 篇（边界 1 / 问模型 1 / 改判 1 / 分流 0），抽出 1 题，新建 1 条，重复 0 条，失败 0 篇
```

## AC-003 细节：知识点重排对照

新增 `ConceptRerankLiveTest`，默认跳过（`@EnabledIfSystemProperty`）。做法：`QuestionConceptSuggester`
先取 top-10，再把 10 个候选（id → 概念名 + 摘要）作为 `choice` 的 `criteria` 发给 laya-serve，
按返回的概率排序取前 3。

**执行方式**：SSH 隧道把生产机的 `127.0.0.1:8091` 映射到本机，`LAYA_API_KEY` 从部署配置读进
环境变量（未打印、未入库、未落盘）。健康检查 `tunnel_health=200`。

实测输出：

```text
[AC-003] 金集 159 题 / 56 概念，候选池 = bigram top-10
[AC-003] 基线       Recall@3 = 0.8302（top1 0.6164）
[AC-003] Laya 重排   Recall@3 = 0.6730（top1 0.3774）
[AC-003] 调用 159 次，成功 154，失败 5，耗时 89.8 秒
```

**结论：不采用。** 重排后比基线低 15.7 个百分点，top1 也低 23.9 个百分点。即便按"只算 154 次
成功调用"这种对 Laya 更有利的口径，也只有 0.6948，仍明显低于 0.8302。产品代码因此零改动——
按 REQ-003 的约定，这个实验只出数字。

## 发现的既有缺陷（不在本项范围，未修）

`TechEssayFilter.TECH_TERMS` 的中文分支带 `\b`，而 Java 默认 `\w` 不含中文字符，所以**中文技术词
从不命中**：

```text
MySQL索引失效 -> 0
说说事务隔离级别 -> 0
线程池怎么配 -> 0
索引 事务     -> 0
```

后果：`countDistinctTerms` 实际只统计英文词。实测「1、HashMap为什么线程不安全 2、说说JVM内存模型
3、MySQL索引失效的场景 4、Spring事务传播」的命中数是 **4** 而不是 8，于是这类明显的技术帖也会
落进边界地带去问模型——**这意味着"改判/边界"计数在线上会很高，而不是很低**。

修法是把中文分支拆出 `\b`（英文仍保留边界），但这会改变过滤精度、需要重新标注测量，因此只记录，
不并入本项。这也是下一次「要不要动 Laya」之前该先做的事。

## 未覆盖与已知限制

- **AC-001 只验到"选中的分类 id"**：`createCollected` 的写入路径本身由
  `EssayCollectionIntegrationTest` 与 `KnowledgeApi.createCollected` 的既有用例覆盖，本项没有
  再跑一条"真库 + 真分类"的端到端用例。
- **分流在生产上仍是休眠的**：`essay-database` 分类不存在，部署配置也没有 `category-slugs`
  （线上 `application.yml` 的 `decision.enabled` 为 true，但 essay 段没有这个新键，绑定到默认空 map）。
  要启用是运维动作：建分类 → 配映射 → 重跑批次。
- **AC-003 的金集是按课程结构推导的标注**（见 `ConceptRecallTest` 的说明），不是人工精标；
  它只能说明"算法自洽"，不能说明真实学习者身上的效果。
- 5 次调用失败未分类（超时/解析失败/非 200 未分别统计），实验里全部按"未答对"计入。
