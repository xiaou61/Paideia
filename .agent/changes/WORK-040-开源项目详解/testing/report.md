---
artifact: test-report
work_id: WORK-040
work: 开源项目详解
status: passed
evidence: required
verified_commit: 41f3c98 + 2026-10-27 内容导入
created: 2026-10-27
updated: 2026-10-27
accepted_by: xiaou61
accepted_at: 2026-10-29 13:20:45 +0800
---

# 开源项目详解验证报告

## 验证环境

- 本地：后端用 `scripts/test-db.sh` 派生独占库（`paideia_run_040_*`）起在 18090，导入两条线后验证。
- 生产：`codenest-online`，通过 `PAIDEIA_BASE_URL` 指向线上导入（管理员账号口令只在被忽略的
  `backend/config/application-local.yml` 里）。
- 内容：简讲 5 篇（`content/reading/open-source/`）+ 精讲 5 篇（`content/reading/sprig-deepdive/`）。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 简讲不出现在 `/learn` | `GET /api/v1/learn/tracks`（生产） | 0 | 通过 | 总共有 `sprig-deepdive` 等轨道，**无 `open-source`**；两次导入后仍然无 |
| AC-002 简讲在知识库可见、渲染正常 | `GET /knowledge/entries` + 条目详情（生产） | 0 | 通过 | `categoryName=开源项目简讲` 的条目 **5 条**；总览正文含 markdown 表格（`\| --- \|`）与 `/learn` 链接 |
| AC-003 精讲有轨道、关卡数一致 | `GET /learn/tracks`（生产） | 0 | 通过 | `sprig-deepdive` 存在，`stageCount=5`、`mode=theory`；5 关 = 1 总览 + 4 教学关，与目录文件数一致 |
| AC-004 每关有带 id 的 quiz、可判定通过 | `PUT /learning/entries/{id}` 上报满分（生产） | 0 | 通过 | `FuncMap` 关的 `quiz` id 为 `sprig-01-quiz`（3 题）；上报前 `in_progress` → 上报后 **`passed`**，`reviewStage=0`；验证后已 `DELETE` 重置 |
| AC-005 简讲里的 `/learn/<slug>` 链接可点 | 条目详情正文正则匹配（生产） | 0 | 通过 | 总览正文含 `[精讲 Sprig 模板引擎](/learn/sprig-deepdive)`；该路由已存在（`router.tsx:87`） |
| AC-006 二次导入幂等 | 两次连续导入（生产） | 0 | 通过 | 简讲 `created 0 / updated 0`；精讲 `created 0 / updated 0`（轨道行 `~ 轨道 sprig-deepdive（5 关，theory）` 是幂等 upsert） |
| AC-007 每条简讲含许可证/仓库/版本 | `grep` 四篇的「出处」节 | 0 | 通过 | `01-sqlite / 02-vite / 03-sqlglot / 04-tailwind` **各 1 处「许可证」、≥1 处「仓库」、1 处「对应版本」** |
| AC-008 不新增路由 | `git diff --stat -- frontend/apps/app/src/router.tsx` | 0 | 通过 | **无输出**（未改动） |
| 脚本 `--no-track` | dry-run 对照 | 0 | 通过 | 普通模式 `（open-source, course）`；`--no-track` 模式 `（open-source, 无 line_kind）` |

### 生产导入输出

```
open-source：5 课
  + 分类 开源项目简讲（id=51）
  + 开源项目怎么读
  + SQLite：一个文件就是整个数据库
  + Vite：为什么开发服务器能瞬间启动
  + SQLGlot：把 SQL 当成语法树来改
  + Tailwind CSS：把样式写在用到它的地方
created 5 / updated 0 / relations 0

sprig-deepdive：5 课
  + 分类 精讲 Sprig 模板引擎（id=52）
  + 精讲 Sprig：模板函数是怎么被注册进去的
  + FuncMap：函数是怎么进到模板里的
  + 管道、default 与「参数顺序坑」
  + 副作用与时间：为什么日期函数最容易出事
  + 边界：什么该放进模板，什么不该
  ~ 轨道 sprig-deepdive（5 关，theory）
created 5 / updated 0 / relations 0
```

### 生产 AC 验证输出

```
AC-001 简讲不在 /learn： PASS
AC-003 精讲轨道： PASS（5 关, theory）
AC-002 简讲条目数： 5 PASS
AC-005 /learn 链接： PASS（/learn/sprig-deepdive）
AC-002 含表格： PASS
AC-004 通关判定： PASS（reviewStage=0）
```

## 一、`--no-track` 为什么必须改两处

只跳过 `POST /tracks` 是不够的。分类带着 `lineKind='course'` 时，它仍会被 `normalizeLineKind`
当成一条学习线（`lineKind` 非空即是一条线，与有没有轨道行无关）。所以：

1. `parseArgs` 认 `--no-track` → `options.track = false`
2. `ensureCategory` 在该模式下传 `lineKind: null`

AC-001 验证的正是这两处都改了之后的结果：`GET /learn/tracks` 里既没有 `open-source` 轨道，
这个分类也不以"线"的身份出现。

## 二、两个阻断问题的定案依据

需求原本有 Q-001（讲哪些项目）与 Q-002（分类结构）两个阻断项，2026-10-27 按用户
"没有弄完的全部给我弄完"的授权定案：

- **Q-001 → 4 简讲 + 1 精讲**：SQLite、Vite、SQLGlot、Tailwind（简讲）+ Sprig（精讲）。
  判据不是"知名度"，而是**本仓库真实用过**。讲自己在用的东西才不会写成二手传言，这也是
  「出处可查」这条质量要求能落地的前提。
- **Q-002 → 平铺一个 `open-source` 分类**：首批 4 条按领域分会得到 4 个只有 1 条的分类，更难找。
  触发重评条件：条目超过 15 条，或出现三个以上明显领域聚簇。
- **Q-003 → 不配实战线**：Sprig 的"动手改它"需要一个真实 Go 模板项目做载体，本仓库没有；
  为一条首批内容新建 Go 沙箱不划算。

## 三、未覆盖与已知限制

1. **Sprig 的代码片段没有实际运行**。本机没有 Go 工具链，也没有真实的 Helm 项目；四关里的
   Go 代码用于解释机制（`FuncMap` 的构造、`must*` 的签名），不是可直接运行的完整程序。
   所有关于 Sprig 行为的陈述来自其公开文档与源码结构，**没有在本机复现过**。
2. **版本号的准确性依赖写作时查证**。四篇简写里的"对应版本"（SQLite 3.45、Vite 5.x、
   SQLGlot 25.x、Tailwind 4.x）是写稿时查的，**没有机制自动检测过期**——需求只记录了这条义务。
3. **精讲只写了一关的 quiz 被实际上报验证**（`sprig-01-quiz`）。另外三关的 quiz id 形态相同，
   但**没有逐关上报验证**。它们都带 `{"id":"sprig-0N-quiz"}`，符合 `theory` 规则的判定前提。
4. **没有跑学习者端 e2e 覆盖新内容**。`/learn` 与 `/knowledge` 的 e2e 在 WORK-038/WORK-041
   收尾时跑过（线上 7 passed），但**没有针对 `sprig-deepdive` 与 `open-source` 的专门用例**——
   本项不加路由、不加组件，只加内容，e2e 的既有用例已覆盖"轨道页能进、关卡能点"。
5. **简讲与精讲的互链只在正文里**，没有 UI 上的"相关精讲"卡片。需求只要求"普通链接"。

## 四、提交清单

| 提交 | 内容 |
| --- | --- |
| 本次 | `import-course.mjs` 的 `--no-track`；9 篇内容；四件工件与本报告 |

内容导入不是代码提交的一部分——它通过管理端 API 写进生产库，可随时用同一脚本幂等重跑。
