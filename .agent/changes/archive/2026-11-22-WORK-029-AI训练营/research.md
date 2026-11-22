---
artifact: research
work_id: WORK-029
work: AI训练营
status: active
created: 2026-10-20
updated: 2026-10-20
---

# AI训练营 · 调研与仓库核对

本文件记录 2026-10-20 的联网调研结果与仓库能力核对。`requirements.md` 只保留结论，细节与出处在这里。

## 一、用户提出的原始想法

> 「我们目前不是有这个简历系统，然后你可以让AI根据这个简历，生成一套就是比如说三天计划 七天计划，首先是根据系统生成一些简单的考试的试卷问题，然后根据答案，来生成薄弱点跟计划」

拆成四步就是：**简历 → 出卷 → 作答 → 薄弱点 → 计划**。本项把这个链条做完整，并补上原话里没提但缺了就不成立的一环：**复测**（不复测，用户永远不知道自己有没有变好）。

用户对形态的要求：**比较复杂一点、有点用**。

## 二、同类产品实际怎么做（2026-10-20 检索）

### 1. SkillScout AI（GitHub，技术技能测评）

五阶段流水线：**解析 JD + 简历 → 对话式测评（每技能限 8 题）→ 按技能打 1–10 分 → 生成周计划 → 导出 PDF**。
来源：https://github.com/RitikRK96/SkillScout-AI-Technical-Skill-Assessment-Agent

可抄的：**先测后计划，且打分按技能维度而不是总分**。
不可抄的：它的测评是「对话式」——每题都要一次模型调用，成本随题目线性增长，且评分不稳定（同一答案两次打分可能不同）。

### 2. MirrorAgent（GitHub，Spring AI Alibaba）

JD 分析 → 简历匹配 → 出题规划 → 逐题面试 → 实时评分 → 动态追问 → 能力评估 → 复习计划；用 Milvus + BM25 混合 RAG，并记录「与当前 JD 相关的历史薄弱点用于后续出题」。
来源：https://github.com/Zqyuaaan/MirrorAgent

可抄的：**薄弱点要跨场次留存，并反过来影响下一次出题**。
不可抄的：Milvus + 向量检索——本项目 D 级约束明确「本期不做向量检索」，且社区版 MySQL 不能排序 VECTOR。

### 3. N.O.V.A.（2026-10-17 发布的个人项目，与本项最接近）

关键机制逐条值得记下来：

- **「我不知道」也是一次作答**，记 0 分并进入错题本（把不确定变成学习机会）；
- **错题本按 1 → 2 → 4 → 7 → 15 → 30 天间隔复习**；
- **每道题的评分用指数移动平均（EMA）更新 topic 掌握度，近期表现权重更高**；
- **弱项驱动每日计划**，每场结束后重算 readiness；
- **推理模型 20–70 秒、JSON 会被截断**，于是加了「快模式」（关思考）+ **超时/坏 JSON 回退到人工校验题库，并在报告里标注这是回退结果**；
- 明确承认仍未解决：**「反馈是否真的有用」无法验证**。
- 一个工程纪律：**一个任务一个提示词，不写一个巨型提示词**，每个 agent 返回严格 JSON，这样可以测、可以单独替换。
- 原文里最痛的一条：**推理模型的延迟与 JSON 截断是最大 UX 问题**。

来源：https://dev.to/liyander_rishwanth_52c5f7/building-an-ai-interviewer-that-teaches-instead-of-just-evaluating-59ii

**与本项目已有裁决的重合与冲突**：
- 1/2/4/7/15/30 天的间隔复习 —— 本项目 `LearningProgressRules.INTERVAL_DAYS` 已经是 `1/3/7/21`，**同一套机制已经在跑，不要再造第二套**。
- EMA 更新掌握度 —— 本项目 `ConceptMastery` 用的是「加权平均 + 30 天半衰期」，且在注释里写明了「不用 BKT 是因为样本量个位数」。EMA 与加权平均在样本量小的时候差别很小，**不换**。
- 「坏 JSON 回退到人工题库并标注」—— 这条必须抄，是本项质量要求的来源。

### 4. Jasper.ai（Devpost，个性化面试准备）

它的**优先级引擎**思路是：先给岗位概念做重要度排序，再让用户自评信心，两者合成 focus score，按可用时间生成路线图。
来源：https://devpost.com/software/personalized-interview-prep

它自报的最大坑：**模型生成学习资源时会编造 URL**，解决方式是抓取验证链接。
→ 本项目的对策更彻底：**计划里的每一项必须锚定到库里真实存在的条目 id**，没有条目就明说没有，而不是让模型编一个。

它的一条经验值得抄：「**优先级比数量重要**」，以及「**低信心 ≠ 高优先**」（不紧急的弱项不必排前面）。

### 5. 干粮（App Store，中文，JD → 学习方案）

玩法：贴 JD → AI 分析考察点 → **只补问你的基础** → 学习方案上**逐条标明哪些学、哪些你已会、哪些不用学** → 关关配讲义 → **测验闸门：不过关不解锁下一关** → 费曼终验（你来讲，AI 装外行抬杠）。
来源：https://apps.apple.com/cn/app/%E5%B9%B2%E7%B2%AE-ai-%E5%B8%A6%E4%BD%A0%E7%B3%BB%E7%BB%9F%E5%AD%A6%E4%BC%9A%E4%BB%BB%E4%BD%95%E9%A2%86%E5%9F%9F/id6802591663

可抄的两条：**计划要显式标出「不用学」的部分**（不标，用户会以为每一条都要学，于是放弃）；**测验闸门**（有通过判据，计划才有推进感）。
「两个 AI 互相挑刺」不抄——多一次调用、多一份不稳定性，收益未验证。

### 6. Tail Villain（Devpost 黑客松）

完整闭环：Understand → Learn → Practice → Evaluate → Review → Improve，核心亮点是 **active-recall 复习调度 + 报告展示「哪些到期、哪些在变强」**，而不是让学完的主题消失。
来源：https://devpost.com/software/tail-villain

可抄的：**报告要显示「在变强」而不只是「还弱」**——只显示弱项会让人一直觉得自己很差。

### 7. 认知诊断的学术侧（用于判断能不能上自适应出题）

- 计算机化自适应诊断测验（CD-CAT）**依赖高质量大题库**，且要靠作答数据估计题目参数、标定知识点属性向量。
  来源：万方《认知诊断试题参数估计与知识点标定的自适应设计研究》D02054226
- 平行卷（parallel forms）是「测量学习增益」的标准做法：需要足够宽的题库，才能在不重复暴露同一题的前提下出多套等价卷。
  来源：Frontiers in Education, 2022, *Developing progress monitoring measures: Parallel test construction from the item-up*
- 一道题的知识点掌握力度应按难度分段赋权（易/中/难各自一条函数），而不是做对就加一个固定分。
  来源：专利 CN 知识点掌握情况诊断方法（2021）

**结论**：一个用户、十几道题的数据量**做不了参数估计**，CAT 与 IRT 都不成立。能做而且诚实的是：**按难度分档各出固定题量、平行卷复测、按档位看对错分布**。这与 `ConceptMastery` 注释里「样本量够不上模型复杂度时的诚实选择」是同一个判断。

## 三、仓库能力核对（2026-10-20 逐项读码）

| 能力 | 位置 | 现状 | 本项是否复用 |
| --- | --- | --- | --- |
| 简历结构化数据 | `backend/paideia-ai/.../ai/ResumeData.java`、`frontend/packages/resume/src/types.ts` | 有 skills / work / projects / awards / selfEvaluation；`plainText` 存在文档行上供事实闸门用 | **复用**，只读 |
| 简历端点 | `ResumeController`（`/api/v1/ai/resumes/**`） | 已上线；`AUTHENTICATED`；归属取自令牌，拿不到统一 404 | 复用，不新增简历端点 |
| 简历体检 | `ResumeCheckup` | **纯代码、不计费、不调模型** | 复用它的口径（不编造分数） |
| 概念 | `knowledge_concepts`、`knowledge_entry_concepts` | 管理员手工维护，不做自动抽取 | **复用为薄弱点与计划的锚点** |
| 掌握度 | `ConceptMastery.compute` | **纯函数**：分数权重 0.5、自评 0.3、浏览兜底 0.4、30 天半衰期、`PASS_RATIO=0.8` | **复用，只加一路证据** |
| 图谱聚合 | `ConceptGraphService.build` | 三路证据按 entryId 索引；`weakest(userId, limit)` 已供工具调用 | 复用其取数方式 |
| 学习记录 | `learning_progress`（V18） | `challenges_json`/`quizzes_json` 存最高分；80% 通过；**复习阶梯 1/3/7/21 天已实现** | **复用，绝不新建复习调度** |
| 学习轨道 | `learning_tracks` + `learning_track_stages`（V22） | 关卡正文仍是 `knowledge_entries` | 不复用其导航层；计划挂在条目上 |
| 测验围栏块 | `frontend/packages/ui/src/markdown/renderers/quiz.tsx` | 条目正文里的 `quiz` 块，做完经 `ProgressPort` 上报 `learning_progress` | **复用**：计划里的「做这一站的题」就是它 |
| 八股文 | `backend/paideia-ai/.../internal/essay/` | 抓取 + 抽取 + 解析后入库为 knowledge_entries | 不接入 v1（见非目标） |
| 计费 | `CreditService.reserve/settle/settleResume` | 预留-结算、余额不足 402 且不发上游请求 | **复用**；新增一个流水种类 |
| 遥测 | `RunKind`、`RunRecorder`、`RunSpec` | 「加一条路径 = 加一个常量」 | 新增一个常量 |
| 接口契约 | `paideia-app/src/test/resources/api-shape.json` | 新增 `/api/v1/**` 必须声明并对照真实行为 | 遵守 |
| 迁移 | 当前最大 V24 | 「取执行时的下一个，不写死」 | V25 起 |

### 已经存在、因此**不要重造**的四件事

1. **复习排期**：`LearningProgressRules.INTERVAL_DAYS = {1,3,7,21}` + `review_due_at` 已经在按通过情况推进。训练营不需要自己的复习调度器，计划的「复习」任务直接读 `review_due_at`。
2. **通过判据**：80% 门槛 + 「每类块都要有记录」两条已经在 `LearningProgressRules.passed` 里，训练营的「这一项算不算达标」用同一口径。
3. **掌握度计算**：`ConceptMastery` 是纯函数且有单测纪律。训练营只是给它多喂一路证据，不另写一套打分。
4. **作答上报**：条目里的 quiz 块已经会把分数写进 `learning_progress`。训练营的每日任务只要指向条目，用户做题这一动作自动产生证据。

### 三条只有读代码才知道的约束

1. **DeepSeek 的工具调用与结构化输出不能在同一次调用里并用**（记忆里已记）。本项的出题是**纯结构化输出、不带工具**，天然符合；但如果将来要「边出题边查库」，必须拆成两次调用。
2. **思考 token 会吃掉 `max_tokens`，导致静默返回空内容**。出题调用必须显式设上限并在空返回时当作失败而不是「0 道题」。
3. **`ResumeOptimizationService` 的写法给出了一次计费调用的完整模板**：`requireAccess` → `credits.reserve` → `recorder.begin(RunSpec.of(kind, userId))` → 调模型 → 落库 → `settleX` → `run.finish`，`catch` 里 `settleOrRefund`。本项照抄这个骨架，不发明第二套。

## 四、由此得出的设计主张（三条）

1. **AI 只负责出题，计划由纯函数算出来。**
   理由：模型写计划会编造不存在的学习资源（Jasper.ai 自报的 URL 幻觉），而且同样输入每次给不同结果，用户无法判断自己有没有进步。计划必须锚定库里真实条目 id，且同样输入必须得到同样计划——这样才能对比和写测试。

2. **不做自适应出题（CAT），改用「分档平行卷」。**
   理由：一个人十几道题的数据量估不出题目参数。替代方案是每题声明难度、每主题按易/中/难各出固定题量，复测用同一档位分布的另一套题——这样「诊断 vs 结营」才是可比的，而且这个可比性可以写成测试。

3. **薄弱点必须由数字算出来，不能由模型叙述。**
   理由：`ResumeKeywordClassifier` 的三桶已经确立了这条纪律（「模型自评他会不会是幻觉温床」）。本项同样：模型给选项和答案，对错由服务端比对，薄弱点由「错几题 / 共几题」聚合，模型不参与判定谁弱。

## 五、明确不采纳的玩法

| 玩法 | 出处 | 不采纳的理由 |
| --- | --- | --- |
| 对话式逐题测评 | SkillScout | 每道题一次模型调用，成本线性增长且评分不可复现 |
| Milvus / 向量检索做相似题 | MirrorAgent | 项目级约束本期不做向量检索 |
| 两个 AI 互评 | 干粮 | 多一次调用与一份不稳定性，收益未验证 |
| 语音面试 / 情绪分析 | MOSS | 属于 WORK-010 的面试面，本项不是面试 |
| EMA 替换加权平均 | N.O.V.A. | 小样本下与现算法差异极小，换掉要重跑全部掌握度测试 |
| 排行榜 / 徽章 / 等级 | 职通车等 | 用户已明确不要游戏化产物（WORK-021 非目标同款） |
| 每日推送提醒 | 通用做法 | 需要 outbox + 邮件链路，与本项核心价值无关，明确砍掉 |
