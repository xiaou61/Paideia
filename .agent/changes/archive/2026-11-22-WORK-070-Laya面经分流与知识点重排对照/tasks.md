---
artifact: tasks
work_id: WORK-070
work: Laya 面经分流与知识点重排对照
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 11:00:56 +0800
approver_role: owner
---

# 任务

四个纵向切片，每个都能单独验证；TASK-004 是前三个的回归门。

### TASK-001 | done | 面经按 topic 分流（REQ-001）

**改哪里**

- `EssayProperties`：新增 `categorySlugs: Map<String, String>`，默认空。
- `application.yml`：`paideia.ai.essay.category-slugs` 加一行注释与空默认（**不填 `essay-database`**，
  因为生产上那个分类还不存在；打开映射是运维动作）。
- `EssayCollectionService`：`resolveCategoryId()` 改成按 topic 解析，返回「分类 id + 实际 slug」；
  解析不到目标分类时回退配置里的 `category-slug`。

**接口**

```java
// EssayProperties
public Map<String, String> getCategorySlugs();
public void setCategorySlugs(Map<String, String> categorySlugs);

// EssayCollectionService（private）
private record CategoryTarget(long id, String slug) {}
private Optional<CategoryTarget> resolveCategory(String topic);
```

**可检查结果**

- 桩分类器返回 `database` + 测试库存在 `essay-database` → 新建条目落在它下面；
- 测试库不存在该分类 → 同一帖子落在 `essay-category`（测试配置里的 `category-slug`）；
- `EssayCollectionIntegrationTest` 原有断言不变。

### TASK-002 | done | 三个计数进汇总日志（REQ-002）

**改哪里**

- `TechEssayFilter`：方法从 `boolean isTechnical(String)` 改为返回
  `record Verdict(boolean technical, boolean askedModel, boolean changedByModel)`；
  现有测试改用新返回值。
- `EssayCollectionService`：累加三数，追加到最后那行 `log.info`。

**接口**

```java
// TechEssayFilter
public record Verdict(boolean technical, boolean askedModel, boolean changedByModel) {}
public Verdict judge(String text);
```

**可检查结果**

- 三种桩输入下三个计数分别为 (0,0,0)、(1,1,0)、(1,1,1)；
- 跑批日志形如：`… 跳过非技术 N 篇（边界 B / 问模型 M / 改判 K），…`。

### TASK-003 | done | 知识点重排离线对照（REQ-003）

**改哪里**

- 新增 `backend/paideia-knowledge/src/test/java/.../question/ConceptRerankLiveTest.java`：
  `@EnabledIfSystemProperty(named = "paideia.laya.live", matches = "true")`；
  读 `/golden/concept-gold-set.json`，每题先 `QuestionConceptSuggester.suggest(..., 10)`，
  再把 10 个候选作为 `criteria` 发一次 `choice`，按概率排序取前 3 算 Recall@3。
- 报告里记调用次数、总耗时、解析失败次数。

**接口**

```java
// 测试内部，产品代码零改动
record Answer(long conceptId, double probability) {}
List<Answer> rerank(List<QuestionConceptSuggester.Suggestion> candidates, String stem, List<String> options);
```

**可检查结果**

- 默认（无 `-Dpaideia.laya.live=true`）时该测试 skipped，不影响 `mvn test`；
- 真跑一次输出 `[AC-003] 基线 0.8302 / 重排后 X.XXXX`，并写进报告。

### TASK-004 | done | 回归门（REQ-004）

**可检查结果**

- `scripts/test-db.sh mvn -B -pl paideia-ai,paideia-knowledge,paideia-app -am test`（只到受影响模块）；
- `rg -n "LayaContentClassifier" backend --glob '!target'` 的输出里生产调用方仍只有 `TechEssayFilter`；
- 关闭开关下 `EssayCollectionIntegrationTest` 全绿。

## 完成定义

- 四个 AC 都在报告里有对应命令与实测输出；
- `managed`/`compact` 要求的三件工件齐（需求、任务、验证报告）；
- 本地提交在分支 `work-070-laya-split`，是否合入 `master`、是否部署由用户另行决定。
