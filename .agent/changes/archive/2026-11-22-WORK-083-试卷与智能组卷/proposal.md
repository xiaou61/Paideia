---
artifact: proposal
work_id: WORK-083
work: 试卷与智能组卷
status: approved
mode: strict
workflow: full
created: 2026-11-18
updated: 2026-11-18
approved_by: xiaou61
approved_at: 2026-11-18 15:36:17 +0800
approver_role: CTO
---

# 试卷与智能组卷提案

## 摘要

需求要把「从零开始」里 387 篇 / 1147 道内嵌 `quiz` 小测，沉淀成可查询、可组卷、可刷题、可分析的题库与试卷能力。

**关键发现（读码结论）**：这个"题库"其实已经有半套骨架——`paideia-knowledge` 模块在 WORK-033 / WORK-034 里已经建成：

| 已有能力 | 落点 |
| --- | --- |
| 题目身份（不复制、稳定） | `QuestionCatalog.parse`：身份 = `entryId + blockKey + questionKey`，`fingerprint` 随题干/选项/答案变化 |
| 题目↔概念关系（可带权重与来源） | `question_concepts`（UNIQUE entry/block/question/concept，含 weight/source/confidence） |
| 服务端判分 + 逐题留痕 | `QuestionAttemptService.record` → `question_attempts`（无 `correct` 入参，客户端只报选项） |
| 概念掌握度（BKT） | `MasteryModel` + `concept_mastery_state`（user×concept 掌握概率、attempts、correct） |
| 题目难度（Elo，真实作答累积） | `question_difficulty`（fingerprint 主键，初始 1200，随作答更新） |
| 概念图谱与前置关系 | `knowledge_concepts` / `knowledge_entry_concepts` / `knowledge_concept_edges`（prerequisite） |
| 内容分类树（科目/课程线/章节） | `knowledge_entries.category_id` → `knowledge_categories`（parent_id 自引用层级） |
| 练习选题（按信息量/Elo/Mastery 选下一题） | `QuestionAttemptService.nextPractice` + `SelectionStrategyComparisonTest` |

用户问的「题目千丝万缕的联系」，其骨架已是：**题目 →（question_concepts）→ 概念 →（knowledge_concept_edges）→ 前置概念**，外加**题目 → 来源条目 → 分类树**与**题目 → 难度评分（真实作答累积）**。

缺的不是"从零建题库"，而是四件事：**可跨条目快速筛选的索引、面向人的分类/难度标签、试卷（组卷/交卷/成绩）、刷题界面**。

## 推荐方案

**核心原则：正文仍是唯一权威，不复制题目；新增"派生索引 + 标签/难度标注 + 试卷 + 刷题"四块，全部落在 `paideia-knowledge` 模块。**

1. **题库索引（派生，非第二真相）**：新增 `question_index(entry_id, block_key, question_key, fingerprint, option_count, difficulty_label, status, refreshed_at)`。内容导入/发布时重扫 `quiz` 块写入；**只存身份与派生元数据，不存题干/答案**（避免与正文漂移）。它让「跨条目按标签/难度筛选」「只刷未做」「组卷抽题」不必每次解析 480 篇正文。
2. **分类标签（以派生为主，少量显式）**：
   - 科目/课程线/章节 ← 条目 `category_id` 的祖先链（`knowledge_categories` 已是层级树）；
   - 知识点 ← `question_concepts` / `knowledge_concepts`；
   - 难度 ← `question_index.difficulty_label`（易/中/难；规则推断 + AI 一次性标注得到，后续用 `question_difficulty` 的 Elo 校准）；
   - 题型、来源讲义 ← 解析结果（单选）与来源条目。
   - 维度基本可从既有结构派生，因此**不新建重复的标签总表**；仅当需要跨维度自由标签时再加轻量 `question_tags`（列为可选）。
3. **试卷**：新增 `papers`（归属用户、规则快照、状态、提交时间）+ `paper_items`（seq、题目身份、`fingerprint`、题干/选项/答案**快照**、concept、difficulty）。组卷 = **纯函数**：输入「概念/主题范围 × 难度分布 × 题量 × 去重与排除」，从 `question_index` + `question_concepts` 选题，按 `(category, entry_id, block_key, question_key, fingerprint)` 稳定排序 → 同输入同输出。题量不足如实说明。交卷后服务端按快照判分（沿用既有判分口径），按概念/难度聚合，**不出 0–100 总分**。
4. **刷题**：按分类标签筛一批题目身份（支持「只刷错题 / 只刷未做」）→ 逐题作答、即时给对错与解析，判分与留痕**复用 `question_attempts`**；不交卷、不生成试卷记录。选题可复用既有 `nextPractice` 策略（在标签过滤后的候选集上）。
5. **错题本**：由 `question_attempts` 每个题目身份「最新一次 `correct = false`」派生，天然支持按概念/章节重练。
6. **掌握度联动**：试卷与刷题作答经既有 `MasteryModel` 更新 `concept_mastery_state`（BKT），不改 `ConceptMastery` 权重与口径。

**为什么落在 `paideia-knowledge`**：题目、条目正文、概念、作答、掌握度全部已在该模块；放这里零跨模块依赖。`paideia-ai` 仍是训练营（AI 生成题），本项**不触碰**训练营。

**为什么这最满足需求**：它把需求里"抽取 1147 题成题库"落成"给同一批题加一层可查询派生视图"，**不反转 WORK-033「正文唯一权威」的既定决策**，也避免再把题目复制一份造成双写漂移；同时用最小新增表覆盖试卷/刷题/错题本/成绩全部闭环。

## 范围

**纳入（v1）**：题库索引与难度/分类标注；规则组卷与试卷快照；服务端判分；成绩与薄弱点分析（概念/难度维度）；刷题界面（标签筛选、逐题即时反馈、只刷错题/未做）；错题本；归属隔离与注销级联；标签/难度可维护（复用分类与概念的后台维护，难度桶固定）。

**不纳入**：导出打印、老师/班级/考试排期、AI 批量补题、主观题评分、向量检索/语义相似题推荐、排行榜/积分/徽章、多租户、跨用户对比。与训练营不合并、不改其行为。

## 备选方案

- **A. 全量题目表（复制题干/选项/答案入库）**：最直觉，题目可独立编辑状态/标签、查询最快。**未选**：产生第二份真相——内容一改，库里题干就与正文不一致；与 WORK-033「正文唯一权威 + fingerprint」直接冲突，需双写与对账。**放弃的收益**：题目脱离正文独立维护的便利（用 side-table 派生缓解）。
- **B. 完全运行时解析、不加任何索引**：零迁移、最简单。**未选**：跨条目组卷、只刷未做、难度分布都要全库解析 480 篇正文，代价高；且未作答题目没有难度筛选依据。**放弃的收益**：零 schema 变更。
- **C. 新建独立模块 `paideia-exam` / 独立库**：边界清晰。**未选**：题目、概念、掌握度、分类都依赖 `paideia-knowledge`，跨模块读写会把契约放大，且违反「非法跨模块依赖必须编译错误」。**放弃的收益**：模块隔离的纯粹性。
- **D. 维持现状（无试卷）**：不是可信选项——试卷正是本项目标。
- **E. 直接复用训练营的"营"模型承载试卷**：**未选**——营是 AI 临时出题、题目只属营、不复用题库，语义与"可复用题库组卷"相反；强行合并会污染训练营的计费与题目边界。

## 仓库影响

- **Flyway 迁移（新增，14 位时间戳命名，不写死号；只建结构不插业务数据）**：`question_index`、`papers`、`paper_items`（按设计可能再加 `paper_submissions`）。均 `user_id` 归属、无跨模块外键。
- **后端 `paideia-knowledge`**：新增 `internal/paper/*`（组卷纯函数、判分、成绩聚合）、`internal/practice/*`（标签筛题、选题策略复用）、`internal/question/QuestionIndex*`（刷新与查询）、`internal/question/QuestionTag*`（分类/难度派生）。扩 `QuestionAttemptService` / `nextPractice` 支持按标签过滤。
- **接口**：新增 `POST/GET /api/v1/learning/papers/**`、`/api/v1/learning/practice/**`、`/api/v1/learning/wrong/**` 等，全部 `@ApiAccess(AUTHENTICATED)`、归属取令牌、他人 id 404；进入契约与越权矩阵测试。
- **复用（不重写）**：`QuestionCatalog`、`question_attempts`、`question_concepts`、`question_difficulty`、`concept_mastery_state`、`knowledge_concepts/edges/categories`、`MasteryModel`、`nextPractice`。
- **内容管道**：`scripts/import-course.mjs` 或后端发布钩子刷新 `question_index`（只读正文解析，不写正文）。
- **前端 `frontend/packages/knowledge`**：刷题页、试卷页、错题本与成绩页；`frontend/apps/app/src/router.tsx` 新路由 + `LearnerHeader` NAV/账号菜单入口（遵守「加页面必须加入口」）；390px 窄屏无横向滚动。

## 交付拆分

1. **题库索引 + 分类/难度派生**：刷新任务、`question_index`、难度 label；可单测（387/1147 计数、fingerprint 变化触发重刷）。
2. **试卷模型 + 组卷纯函数 + 判分/成绩**：`papers`/`paper_items`、确定性组卷、快照判分、按概念/难度分析（不泄题、不出总分）。
3. **刷题界面**：标签筛选、逐题即时反馈、只刷错题/未做；复用 `question_attempts`。
4. **错题本 + 成绩页**：由 `question_attempts`/`paper_items` 派生展示。
5. **掌握度联动与收尾**：BKT 更新、迁移与契约测试、窄屏、路由↔NAV 自查。

## 风险与缓解

- **难度标注主观/无数据**：规则推断得初值 + AI 一次性标注 + 人工抽检；组卷只用"易/中/难"桶，不用原始 Elo 小数；随真实作答用 `question_difficulty` 校准。
- **索引与正文漂移**：读取前用 `fingerprint` 校验，不一致即重解析该条目并重写索引；不把题干/答案放进索引。
- **"不建题库"决策被误读**：文档明确——正文唯一权威，`question_index` 仅派生身份与元数据，不复制题目。
- **组卷可复现**：稳定排序键 `(category, entry_id, block_key, question_key, fingerprint)`；随机化若需要，用固定种子并在规则快照记录。
- **试卷快照与内容更新**：`paper_items` 存 fingerprint + 答案快照；交卷用快照判分，内容更新不改已生成试卷，标注"来源已更新"供重做。
- **前端入口遗漏**：把"路由 ↔ NAV/账号菜单逐条对齐"写进验收清单。

## 验收映射

| 需求 | 方案落点 | 验收 |
| --- | --- | --- |
| REQ-001 抽取 | 刷新任务扫 387 篇 quiz → `question_index` | AC-001 计数 387/1147、坏块计数 |
| REQ-002 元数据 | index 存身份/题型/难度/状态；概念经 question_concepts | AC-004 |
| REQ-003 概念关系 | 复用 question_concepts + knowledge_concept_edges | AC-005 |
| REQ-004 规则组卷 | 纯函数组卷 + 稳定排序 | AC-002 可复现 |
| REQ-005 作答判分 | 服务端按快照判分、不泄答案 | AC-003 不泄题 |
| REQ-006 成绩分析 | 按概念/难度聚合，不出总分 | AC-004 |
| REQ-007 错题回流 | question_attempts 最新 correct=false | AC-005/006 |
| REQ-008 归属隔离 | 令牌过滤、他人 id 404、注销级联 | AC-006 |
| REQ-009 分类标签 | 派生维度（category/concept/difficulty/type/source） | AC-007 |
| REQ-010 刷题界面 | 标签筛题 + 逐题即时判定 + 只刷错题/未做 | AC-007/008 |
| REQ-011 共用题库 | 刷题/组卷/错题读同一 index+概念+判分 | AC-008 |

## 待决定事项

- **D1 难度来源**：规则推断 / AI 一次性标注 / 纯 Elo。推荐：规则推断 + AI 标注得 `difficulty_label`，用真实作答 Elo 校准。
- **D2 是否落 `question_index` 索引**：推荐落（换来跨条目筛选与组卷性能）；备选为"按筛选范围运行时解析"（更省 schema、更慢）。
- **D3 是否允许自由标签（新增 `question_tags`）**：推荐 v1 只做派生维度，自由标签留后。
- **D4 前端归属**：推荐扩展 `frontend/packages/knowledge`；备选新建 `packages/practice`。
- **D5 试卷是否冻结答案快照**：推荐冻结（内容更新不影响已发试卷）。
