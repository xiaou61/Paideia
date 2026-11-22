---
artifact: tasks
work_id: WORK-047
work: 岗位地图与 AI 推荐
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-010, WORK-024, WORK-038]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 11:24:13 +0800
approver_role: CTO
---

# 任务 · 岗位地图与 AI 推荐

**排序原则**：先把地图与打分立住（S1），再上推荐卡（S2）。**S2 的形态就是 AI 不可用时的最终形态**，
所以必须先做完、先验收。S3 才接模型。S4 改措辞与收口。

**全局约束**：

- 不新建表、不加迁移。岗位存在既有的 `learner_profiles.target_role`。
- 映射与打分是**纯函数**，放在共享包里，测试不挂 React。
- 前端不硬编码轨道 id，一律按 slug。
- AI 只写理由，不排序。

## 任务状态

| 任务 | 名称 | 阶段 | 状态 |
| --- | --- | --- | --- |
| T-01 | 岗位映射与打分纯函数 | S1 | done |
| T-02 | `/learn` 三段式与岗位选择器 | S1 | done |
| T-03 | 规则理由与推荐卡（无 AI） | S2 | done |
| T-04 | 取消 `lastTouchedAt`（记为已知限制） | S2 | done |
| T-05 | 后端 `POST /learning/advice` | S3 | done |
| T-06 | 前端接入 AI 理由与按日缓存 | S3 | done |
| T-07 | 22 条轨道措辞改写 | S4 | done |
| T-08 | e2e 与验收报告 | S4 | done |

## S1 · 岗位地图与打分

### T-01 | done | 岗位映射与打分纯函数

- 文件：`frontend/packages/knowledge/src/role-map.ts`（新建）、`recommend.ts`（新建）、两个 `.test.ts`。
- 内容：`RoleKey` / `Tier` / 映射表；`scoreTracks(tracks, role, now)` 返回排序后的 `Score[]`，
  实现 DESIGN 里的公式与四条规则（排除已完成、排除无关卡、保一条核心、稳定排序）。
- 验收：
  - 单测覆盖**零进度 / 全部通过 / 岗位无内容 / 岗位为空**四种输入（AC-007）。
  - 未标岗位的 slug 落在 `optional`（兜底规则），有专门用例。
  - 同输入两次调用结果完全一致。
- 评审焦点：`now` 必须由参数注入，函数体里不能出现 `Date.now()`——否则测试会随运行日期漂移。

### T-02 | done | `/learn` 三段式与岗位选择器

- 文件：`frontend/apps/app/src/pages/LearnPage.tsx`。
- 内容：页头加岗位选择器（4 pill，`aria-pressed`）；把现有 `TrackSection` 按 tier 复用三次；
  岗位写回 `target_role`（走既有 `PUT /account/profile`），并用 react-query 的 `profile` 缓存。
- 验收：
  - 选后端后三段出现，核心段含 `java-from-zero` / `spring` / `redis`（AC-001）。
  - 切到「算法与 AI」，至少一个 slug 换段位（AC-002）。
  - 窄屏三段纵向堆叠，无横向溢出（AC-008）。
- 评审焦点：岗位切换不能触发整页 loading——轨道数据不变，只是重排，用 `useMemo` 而不是重新请求。

### T-03 | done | 规则理由与推荐卡（无 AI）

- 文件：`LearnPage.tsx`（或抽出 `RecommendCard.tsx`）。
- 内容：推荐卡渲染 Top 3 + 模板理由 + 进度 + 「推荐依据」折叠区。
- 验收：
  - 理由里出现具体数字或状态词（AC-004）。
  - 前端**强制模拟 AI 不可用**（把 advice 请求改成 500）时卡片仍在、无错误横幅（AC-005 的前半）。
- 评审焦点：理由模板里的数字必须来自入参，不能写死"6 关"这种常数。

### T-04 | done | 取消 `lastTouchedAt`（记为已知限制）

- 文件：`LearningLineService`、`learning-types.ts`、`api-shape.json`。
- 内容：`Line` 多一个 `lastTouchedAt`（最近一次进度更新时间，可空），供"最近动过"衰减用。
- **本项取消**：`learning/lines` 是公共读接口，为一次衰减加字段要连带快照、集成测试与前端类型；
  而"最近学过"只是在排序里减 15 分，缺了它推荐依然正确，只是偶尔会推一条昨天刚碰过的。
  记在报告的「未覆盖与已知限制」里，等这条接口下次真要改时一起加。
- 评审焦点：这是**公共读接口的字段新增**，`api-shape.json` 会红一次，用 `-Dapi.shape.update=true` 重录并逐行 review。

## S3 · AI 理由

### T-05 | done | 后端 `POST /learning/advice`

- 文件：`LearningController`、新的 `LearningAdviceService`、`ai` 模块的调用端口。
- 内容：入参校验（slug 必须存在、最多 3 条）→ 组装固定提示词 → 走积分预留结算 → 解析三条理由。
  失败一律 200 + `reasons: []`；参数非法 400。
- 验收：
  - 余额为 0 时返回空理由而不是 402 冒到前端（或 402 被前端吞掉，二者取一致口径并测）。
  - 未知 slug 返回 400。
  - 真实上游调用一次，确认返回三条各不超过 60 字。
- 评审焦点：提示词里**只能**拼这几个结构化字段，不能拼任何自由文本——这是注入面。

### T-06 | done | 前端接入 AI 理由与按日缓存

- 文件：`LearnPage.tsx` / `recommend` 相关。
- 内容：推荐卡先出规则结果，再异步请求 advice 补上理由；`localStorage` 按「岗位 + 自然日」缓存；
  「换一批」清缓存并重新请求。
- 验收：同一天同岗位第二次进入页面不发请求（AC-006）；AI 失败时理由回落到模板句（AC-005）。

## S4 · 措辞与收口

### T-07 | done | 22 条轨道措辞改写

- 文件：`content/reading/*/00-总览.md`、`content/courses/*/00-总览.md` 的 `line_summary`。
- 内容：从"讲哪几个知识点"改成"学了能干什么"，一条不超过 60 字。
- 验收：导入后线上 `learning_tracks.summary` 全部更新；每条都能回答"学了能干什么"。
- 评审焦点：这是一次**全量内容改动**，走导入脚本，不手改库。

### T-08 | done | e2e 与验收报告

- 文件：`frontend/apps/app/e2e/learn.spec.ts`（扩展）+ `testing/report.md`。
- 内容：岗位切换、三段成员、推荐卡存在、AI 降级。
- 验收：`playwright test e2e/learn.spec.ts` 全绿；报告逐条对 AC。

## 未覆盖（明确不做）

- 岗位的增删改界面（映射在代码里）。
- 推荐的历史记录与"为什么上周推荐了别的"。
- 条目级（非轨道）推荐。
- 前端岗位的内容补齐——这是独立的内容工程。
