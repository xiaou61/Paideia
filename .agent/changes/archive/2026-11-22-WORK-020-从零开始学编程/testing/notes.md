# 验证记录（WORK-020）

本文件只记**实际跑过的命令与结果**；未跑的项标注"未验证"，不用推测填充。

## 2026-10-19 · 本地（临时库 paideia_course_check）

为了不污染 `paideia_test` 与本地开发库，本轮所有真导入都在一个独立空库上跑：
临时的 `paideia_course_check`（跑完已删），后端用 `--spring.profiles.active=local` 另起在 8090。

### 课程内容

```
python scripts/verify_course.py
→ 通过：26 个课程文件（含代码执行）
```

这条覆盖：frontmatter 七节顺序、误区 ≥2、`code-challenge`/`quiz`/`learning-path` 的 JSON 与上限、
以及每个 `<!-- run -->` 示例**真的执行**并逐字比对输出（Python 13 课 + Java 13 课，含中文输出）。

### 导入幂等

```
node scripts/import-course.mjs        → created 26 / updated 0 / relations 22
node scripts/import-course.mjs        → created 0 / updated 0 / relations 0
```

数据库复核（同一临时库）：
- `knowledge_entries` 32 行（26 课程 + 6 种子），**draft 只有 1 行**（种子那条草稿）；
- 两条线的 `sort_order` 都是 0–12 连续，13 站齐全；
- `python-from-zero` 的 `published_at` 随课号单调（所以"上一篇/下一篇"与课号一致）；
- `prerequisite` 关系 23 条（22 课程 + 1 种子）。

### 零代码新增一条线（AC-017）

在 `content/courses/algorithms/` 写一条 4 文件的线（总览 + 3 课），只跑
`node scripts/import-course.mjs --dir content/courses/algorithms`：

```
+ 分类 算法从零开始（验收用）（id=13）
created 4 / updated 0 / relations 0
```

`GET /api/v1/learning/lines` 随即返回三条线，第三条 `kind=course / stations=4`。
**导入前后 `git status` 统计（backend + frontend）都是 65 项，说明这条线没有碰任何源码。**

> 第一次做这个验收时暴露了一个真问题：导入脚本把 python/java 写死在 `CATEGORIES` 常量里，
> 加第三条线必须先改脚本——那不算零代码。已改成从总览 frontmatter 读
> `category / line_name / line_kind / line_sort`，脚本里不再出现任何一门课的名字。
> 验收结束后这条假线已删除（会把"验收用"内容带进正式库）。

### 学习记录端点

`LearningProgressIntegrationTest`（9 条，连真实 MySQL）全绿，覆盖：
通过判定只看分数、只答一半仍 in_progress、分数越界 400、跨用户隔离、重置回初态、
草稿条目 404、匿名 401、线清单只出 `line_kind` 非空的分类。

### TUTOR 提示词

`TutorPromptTest`（4 条）全绿：四条护栏文本存在、版本号与通用助手不同、
`ofOrDefault("tutor")` 命中、学情段落标注来源。

### e2e（本地，种子库 paideia_test）

```
pnpm --filter @paideia/app test:e2e     → 73 passed / 2 skipped / 0 failed（3.6 分钟）
pnpm --filter @paideia/admin test:e2e   → 19 passed / 0 failed（1.0 分钟）
```

新增 `apps/app/e2e/learn.spec.ts` 四条，全绿：
1. 首页「从零开始」进 `/learn`，卡片由服务端渲染（断言 `data-stations` / `data-passed`，不写死课名）；
2. 点卡片进第一站，条目页在取完学习记录后渲染正文；
3. 360px 下不横向溢出；
4. 既有 `source-reading` 分类以 `reading` 线出现在同一个清单的「读源码与专题」分组，且只出现一次（AC-018）。

**其中第 3 条当场抓到一个真缺陷**：卡片在 grid 里按 min-content 撑开，而"下一站：…"是
`truncate`（nowrap），360px 下溢出 **55px**。修法是给卡片加 `min-w-0`，已复测归零。

### 后端全量

```
cd backend && mvn -B test    → 191 执行 / 0 失败 / 4 跳过
```

（第一次跑出现 5 条红，全部是**与本次无关的既有问题**：另一会话正在写 WORK-021 的
`concept` 模块与 V19 迁移，导致 `ResumeEndpoints` 的上下文在那一轮起不来、
`MigrationChecksumTest` 报 V19 未登记、`AccountDeletionCascade` 报 `knowledge_entry_views`
未清理。等对面代码稳定后重跑，这 5 条自行消失；本次代码本身没有红。）

## 未验证（如实登记）

| 项 | 原因 |
| --- | --- |
| 线上导入与真实完成一课 | 未做：需要管理员凭据与发版，属 TASK-016，未执行 |
| 导师首次回复是否真的不给完整答案 | 无法自动断言（语义级）；`TutorPromptTest` 只保证护栏文本还在，见已知缺口 |
| 复习队列到期后的界面表现 | 单测覆盖阶梯计算；`/learn` 上"今天该复习"的计数由服务端给，未单独跑 e2e |
| 管理端写 `line_kind` / `sort_order` 的界面 | 后端与 e2e 都验了接口；管理端**没有**对应的表单控件——内容走导入脚本，界面编辑留到需要时 |
| `pnpm -r test` 全绿 | `packages/ui` 的令牌守卫当前红：命中的是另一会话新增的 `apps/app/src/components/graph/*`（WORK-021，未跟踪文件），不是本次改动。本次相关的 `knowledge`（32 条）、`identity`（3 条）全绿 |
