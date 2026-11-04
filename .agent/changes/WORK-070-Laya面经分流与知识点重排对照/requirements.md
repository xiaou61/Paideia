---
artifact: requirements
work_id: WORK-070
work: Laya 面经分流与知识点重排对照
status: approved
created: 2026-11-04
updated: 2026-11-04
mode: managed
mode_reason: 无迁移、无公共接口与安全面变化；改动集中在面经采集的落点与一行日志，另加一组只在测试里跑的离线对照
workflow: compact
approved_by: xiaou61
approved_at: 2026-11-04 11:00:56 +0800
approver_role: owner
---

# 需求

## 背景

Laya（System 1 决策模型）在本项目只有一条业务集成：面经抓取时判断「这条是不是技术帖」
（WORK-026）。`LayaContentClassifier` → `TechEssayFilter` → `EssayCollectionService`。

盘点后发现三件事：

1. **有一个答案已经在算，却没有任何读取方**。`LayaContentClassifier.parse` 解析了 `topic`
   （java / database / career / other），装进 `ContentClassification.topic`，然后**全仓库没有
   一处读它**（`.topic()` 的其它引用都在 `internal/camp` 包，属另一个同名方法）。
2. **模型到底改判过几条，没人知道**。`TechEssayFilter` 零日志零计数；而按现有代码它只可能
   在边界地带被问到（见「已确认事实」第 3 条），是否真的翻过任何一条结论目前无法回答。
3. **知识点候选这条路的现状被记错了**。此前口述「Recall@3 只有 0.396」，那是 WORK-033
   修复前的基线；`ConceptRecallTest` 现断言 ≥ 0.80，`testing/report.md` 记的实测是
   **0.8302**（159 题 / 56 概念），反向对照随机答对率 0.0440。

因此本项做两件真改动（分流、计数），再加一个**只出数字、不改产品**的对照实验。

## 已确认事实

1. `LayaContentClassifier` 的 `parse()` 只有 `choice == "technical"` 且概率
   ≥ `min-confidence` 时才返回非空；否则返回 empty，调用方退回规则。
2. `TechEssayFilter.MIN_DISTINCT_TERMS = 3`；技术词去重命中数 `hits ≥ 5` 直接判 true，
   `hits == 0` 直接判 false，只有 `hits ∈ 1..4` 才问模型。
3. **由 1、2 可推出：模型只能把 false 翻成 true，永远不能把 true 翻成 false。**
   `hits ∈ 3..4` 时规则本已判 true，模型即使返回也只会同意。
4. `CategoryTree.assemble` 不过滤空节点，`CategoryTree.Node` **不含条目数**——所以
   一个没有任何条目的分类会原样出现在学习者导航里。V24 的注释据此立过
   「不建空分类占位」的规矩，`application.yml` 也写明「不自动建分类」。
5. `MigrationSeedOverlapTest` 要求生产迁移往种子表插行必须显式给 `id` 且 ≥ 900，
   `V24` 是唯一历史豁免且注明「不要往这个名单里加新条目」。
6. 面经跑批结尾已有一行汇总日志（抓 / 跳过 / 抽出 / 新建 / 重复 / 失败）。
7. 延迟与容量：判定延迟随输入长度线性涨（4000 字约 4 秒），首次加载模型 16~22 秒，
   laya 常驻内存已到 2.55G / 3G 上限。**它只适合离线批处理。**

## 用户决定

- 2026-11-04：四项全做（分流、计数、回测、知识点重排）。
- 2026-11-04：了解 Recall@3 实为 0.8302 后，改为**知识点重排只做离线对照实验**，
  不把 Laya 加成第三条候选源进入产品代码。

## 实现选择

- **分流要额外问一次主题**。`TechEssayFilter` 原本只在边界地带（命中 1~4 种技术词）才问模型，
  命中 ≥5 种的帖子直接判 true、不调模型。但 `topic` 只有问过模型才有——照原样实现，绝大多数
  技术帖根本不会带 topic，分流形同虚设。所以 `judge(text, needTopic)` 在 `needTopic` 为真时，
  连规则已判定的技术帖也问一次（只取主题，结论仍按规则）。`needTopic` 由
  `category-slugs` 是否为空决定，**默认空 → 行为与改动前逐条一致**。
- **计数同时进日志与 `RunResult`**：日志给人看，`FilterStats` 给测试断言——只写日志的话
  AC-002 只能靠解析日志文本验证，那是脆弱证据。
- **实验不共享回归门的金集加载代码**：`ConceptRecallTest` 是回归门（断言 ≥0.80），
  `ConceptRerankLiveTest` 是一次性对照。耦合成一份夹具，等于让实验的改动有机会放松回归门。
- **不修 `TECH_TERMS` 的中文分支**：`\b` 与中文的 `\w` 语义冲突，中文词条实测从不命中
  （`MySQL索引失效` / `索引 事务` 命中数都是 0）。这是 WORK-026 的既有行为，修它会改变过滤
  精度、需要重新标注测量，不在本项范围，只记进报告。

## 目标

- 让已经在算的 `topic` 真的被用上：面经按判定主题分流到不同分类，配错/没建分类时行为不变。
- 把「模型改判了多少条」变成跑批日志里的数字，让下一次「要不要扩面」有依据。
- 用同一套金集回答一个具体问题：在 bigram 取 top-10 之后，Laya 重排能不能超过 0.8302。

## 非目标

- 不改 `TechEssayFilter` 的阈值、技术词表与判定顺序。
- 不把 Laya 与已有的 LLM 打标（`QuestionTaggingService`）合并成一条路。
- 不做向量检索、不做 embedding。
- 不在任何在线交互路径上调用 Laya。

## 功能需求

### REQ-001 面经按判定主题分流

- 新增配置 `paideia.ai.essay.category-slugs`（topic → slug），缺省为空 map；
- 解析顺序：`category-slugs[topic]` 存在且分类存在 → 用它；否则回退 `category-slug`；
- **不自动建分类**，也**不在迁移里建分类**（见已确认事实 4、5）；
- 模型没判出 topic（返回 empty）时同样回退，行为与现在一致。

**AC-001** 在测试库里预先建好 `essay-database` 分类并把 `database` 映射指向它时，桩分类器返回
`topic=database` 的帖子落在该分类；分类不存在时该帖子仍落回 `category-slug` 指向的分类。

### REQ-002 统计模型实际改判了多少条

- `TechEssayFilter` 返回的不再只是布尔值，而是「结论 + 是否问了模型 + 模型是否改判」；
- 「改判」= 模型给出的结论与规则兜底会给出的结论不一致（由已确认事实 3，只可能是 false→true）；
- 跑批汇总日志补三个数：边界帖数、问到模型的次数、改判数。

**AC-002** 汇总日志同时输出三个数；单元测试用桩分类器分别构造「未进边界」「进边界但模型没给结论」
「进边界且模型改判」三种输入，断言三个计数各自正确。

### REQ-003 知识点候选重排的离线对照

- 在 `ConceptRecallTest` 同一份金集（56 概念 / 159 题）上：先用 `QuestionConceptSuggester`
  取 top-10，再让 Laya 在这 10 个候选里选 1（`choice`，criteria 用概念名 + 摘要）；
- 按 Laya 给的概率排序取前 3，算 Recall@3，与基线 0.8302 对照；
- 只在 `-Dpaideia.laya.live=true` 时运行，默认跳过（与 `LayaContentClassifierLiveTest` 同一手法）；
- **产品代码零改动**，结论写进 `testing/report.md`。

**AC-003** 报告里同时给出：基线 Recall@3 = 0.8302、Laya 重排后的 Recall@3、调用次数、总耗时；
并有一行明确结论「采用 / 不采用」。

### REQ-004 现有集成不被削弱

- `paideia.ai.decision.enabled=false`（仓库默认）时，分类器返回 empty、退回规则，
  入库结果与改动前逐条一致；
- 全仓库仍然只有一条 Laya 业务调用链。

**AC-004** 关闭开关跑 `EssayCollectionIntegrationTest` 全绿；`rg` 确认
`LayaContentClassifier` 的生产调用方仍只有 `TechEssayFilter`。

## 约束与依赖

- **不新建分类、不写迁移**：空分类会直接出现在学习者导航（已确认事实 4）。
  想启用分流是运维动作：先建好 `essay-database` 分类，再在部署配置里打开映射。
- 实验代码只放测试：不在 `paideia-knowledge` 的产品代码里引入对 `paideia-ai` 的依赖。
- 不在产品代码里为第二个用途抽象 `ContentClassifier`：目前只有一个实现、一个调用方。

## 关联工作项

- WORK-026（决策模型接入 Laya）：本项是它的收尾。
- WORK-025（面经抓取）：分流与计数都落在它的跑批上。
- WORK-033（题目知识点关联）：本项复用它的金集与 `QuestionConceptSuggester`。
- WORK-050（记忆的少而准）：`记忆候选预筛`若要做，归它，不并入本项。

## 验收标准

| AC | 内容 | 验证方式 |
| --- | --- | --- |
| AC-001 | topic 分流生效，分类缺失时回退 | 集成测试，两个分支各一条 |
| AC-002 | 三个计数出现在汇总日志且单元测试可证 | 单测 + 一次跑批日志 |
| AC-003 | 对照实验给出基线与重排后 Recall@3 及结论 | `testing/report.md` |
| AC-004 | 关闭开关后行为不变、调用链仍唯一 | 集成测试 + `rg` |

## 假设

- Laya 支持每次请求传入任意候选集（`questions.<id>.criteria`），现有实现已按此构造，
  但**未验证过 10 个中文候选**这一规模；实验里会记录实际返回形状，形状不符即记为不采用。
- 中文概念名作为 `criteria` 键与原实现用英文指令是否同样稳定，属于实验要回答的问题，不预设。

## 待确认问题

无。两项范围决定已在 2026-11-04 由用户作出。
