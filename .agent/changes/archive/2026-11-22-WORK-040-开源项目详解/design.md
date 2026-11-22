---
artifact: design
work_id: WORK-040
work: 开源项目详解
status: approved
mode: managed
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 18:18:17 +0800
approver_role: CTO
---

# 开源项目详解设计

## 决策与约束

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D-01 | 简讲分类 `open-source`，`lineKind=null` | `lineKind` 非空即被当成学习线；简讲没有通关概念 |
| D-02 | 精讲轨道 `sprig-deepdive`，`line_kind: reading`、`line_mode` 缺省 theory | reading 是载体形态；theory 规则只看小测，符合"每关一个机制 + 小测" |
| D-03 | 只改 `import-course.mjs`，不动后端 | 后端 API 已支持 `lineKind: null` 与按 mode 判定 |
| D-04 | 首批 4 简讲 + 1 精讲 | 覆盖率不是目标；判据是"本仓库真实用过" |
| D-05 | 简讲平铺一个分类 | 首批 4 条按领域分会得到 3 个只有 1 条的分类 |
| D-06 | 不新增路由、不新增围栏块 | 现有 `/knowledge` 与 `/learn` 已能承载 |

## 脚本改动

`scripts/import-course.mjs`，两处：

```js
// 1. parseArgs 认 --no-track
const options = { ..., track: true }
else if (arg === '--no-track') options.track = false

// 2. 该模式下分类不打 line_kind
lineKind: options.track === false ? null : spec.kind,
```

已有的 `options.track !== false` 判断不动——它本来就在，只是没人能把它设成 false。

**为什么 `lineKind` 也要改**：只跳过 `POST /tracks` 是不够的。分类带着 `lineKind='course'` 时，
`/learn` 侧的轨道列表虽然查不到它（因为没建轨道行），但分类本身会被别的读路径当作"一条线"
（`normalizeLineKind` 的语义）。两处都改才叫"简讲不是一条线"。

## 数据形态

### 简讲（`content/reading/open-source/`）

| 文件 | order | 主题 |
| --- | --- | --- |
| `00-总览.md` | 0 | 这一组怎么用、为什么必须写许可证与版本、简讲与精讲的区别 |
| `01-sqlite.md` | 1 | 单文件数据库：取舍、WAL、写入串行、什么时候别用 |
| `02-vite.md` | 2 | 开发/打包两套工具：原生 ESM、依赖预构建、为什么热更新与项目规模无关 |
| `03-sqlglot.md` | 3 | SQL 语法树：方言一等公民、改写即改树、为什么我们不用它判题 |
| `04-tailwind.md` | 4 | 工具类：原子化、构建时生成、设计令牌、`@source` 指错目录静默缺样式 |

每条固定六段：一句话定位 / 解决什么问题 / 核心机制 / 什么时候该用 / 什么时候别用 / 替代品 / 出处。

`00-总览.md` 的 frontmatter **不写 `line_kind`**——`lineSpecOf` 里 `kind: meta.line_kind ?? 'course'`
的缺省值最终会被 `--no-track` 覆盖成 `null`。

### 精讲（`content/reading/sprig-deepdive/`）

| 文件 | order | 关 | quiz id |
| --- | --- | --- | --- |
| `00-总览.md` | 0 | 这条线讲什么、四关安排、前置 | — |
| `01-funcmap.md` | 1 | FuncMap 机制、注册时机、`must*` 约定 | `sprig-01-quiz` |
| `02-pipeline-default.md` | 2 | 管道语义、`default` 参数顺序、零值陷阱 | `sprig-02-quiz` |
| `03-side-effects.md` | 3 | `now`/`env`/`rand*` 的不纯，产物不可复现 | `sprig-03-quiz` |
| `04-boundaries.md` | 4 | 什么该放进模板、四个越界案例 | `sprig-04-quiz` |

**每关都必须有带 `id` 的 `quiz`**：`LearningProgressRules` 的 theory 规则是
`!quizzes.isEmpty() && allStrong(quizzes)`，没有带 id 的块这一关永远过不了。

`line_kind: reading`（读文章）、`line_mode` 缺省（= theory）、`line_theory` 不写（简讲不是轨道）。

## 互链

- 简讲总览末尾指向精讲：`[精讲 Sprig](/learn/sprig-deepdive)`。
- 精讲总览「和简讲的关系」一节链回知识库分类（纯 markdown 链接，不用 `learning-path` 块——
  那个块要硬编码 `entryId`，换库就失效）。

## 导入与验证

```bash
# 简讲：不建轨道
node scripts/import-course.mjs --no-track --dir content/reading/open-source

# 精讲：建轨道
node scripts/import-course.mjs --dir content/reading/sprig-deepdive
```

验证顺序（对应 AC）：

1. `GET /api/v1/learn/tracks` → 有 `sprig-deepdive`（4 关），**无** `open-source`。
2. `GET /api/v1/knowledge/entries?q=SQLite` → 简讲条目在 `open-source` 分类下。
3. `GET /learn/sprig-deepdive` → 4 关，每关有 quiz。
4. 上报一次满分进度 → `learning_progress.status='passed'` 且 `review_due_at` 有值。
5. 二次导入 → `created 0 / updated 0`。

## 回滚

内容可删（管理端）或改 `status: draft` 重新导入；脚本改动向后兼容（不传 `--no-track` 时行为不变）。
无迁移、无 schema 改动，回滚无数据风险。

## 已知限制

- 本机没有 Go 工具链与真实 Helm 项目，Sprig 的示例代码片段**没有实际运行**；它们用于解释机制，
  不是可直接运行的完整程序。
- 简讲的"版本"由作者在写作时查证写入；**没有机制自动检测版本过期**（需求只记录该义务）。
- 精讲的 `code` 片段不带 permalink 到具体 commit（Sprig 不是本仓库代码，permalink 意义有限）。
