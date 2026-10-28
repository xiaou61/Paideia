---
artifact: test-report
work_id: WORK-046
work: 书籍详解专题
status: passed
evidence: required
workflow: full
created: 2026-10-28
updated: 2026-10-28
verified_commit: ae3d9b8
---

# WORK-046 验证报告

**结论先说**：**9 条验收标准全部有实测证据**，全部在本地独占测试库上跑，**线上尚未部署、
尚未导入**。另有两条与本项无关的既有缺陷在验证过程中暴露，单独记在最后一节，没有顺手修。

## 验证环境

| 项 | 值 |
| --- | --- |
| 工作区 | `git worktree` 独立分支 `work-046-books`，主树 `master` 保持不动 |
| 后端 | 本机 JDK 25；`scripts/test-db.sh` 每次分配独占库 `paideia_run_<时间>_<pid>`，成功后自动删 |
| 前端 | pnpm + vitest 5.0.3 + Vite；`pnpm --filter` 只跑受影响的包 |
| e2e | Playwright，`PAIDEIA_BACKEND_PORT=8091 PAIDEIA_APP_PORT=5191`（与并发会话错开端口） |

## 验证结果

三组内环全绿，e2e 两轮各 20 条全绿，导入链路跑了两遍幂等。唯一红的是 `ApiWalkthroughTest`，
原因是本项之前就存在的两个缺陷（见最后一节），与本项改动无关。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 页头有独立入口，桌面与移动端都可点 | `playwright test e2e/books.spec.ts` + `--project=mobile e2e/layout.mobile.spec.ts` | 0 | passed | 桌面：点 `learner-nav-books` 后 URL 为 `/#/books`，标题「书籍详解」；移动端抽屉里 `learner-nav-books-drawer` 可见 |
| AC-002 书卡篇数与内容目录一致 | 同上 + 独占库导入后查 API | 0 | passed | e2e：种子书卡显示「共 3 篇」，与 V956 的 3 条一致；独占库导入真实内容后 `GET /knowledge/entries?categoryId=<书>` 返回 **5 条**，与目录下 5 个 md 一致 |
| AC-003 章节按 sort_order 升序，首条是总览 | 同上 | 0 | passed | e2e：`book-chapter-*` 共 3 行且**首条含「总览」、序号 1**；API 原样返回是 `4,3,2,1,0`（发布时间倒序），客户端重排后首条为「人月神话 · 总览」 |
| AC-004 书籍不出现在 `/learn` | 独占库导入后 `GET /api/v1/learning/lines` | 0 | passed | 两次导入后 `lines` 里既没有 `books` 也没有 `books-man-month`；分类树里 `books` 与 `books-man-month` 的 `lineKind` 都是 `null` |
| AC-005 五篇可读，含出处头、概念图、小测 | 围栏块校验脚本 + 内容源 | 0 | passed | 5 篇均在，每篇首段是出处头；围栏块校验：3 张 `code-map`（6/4/7 个节点，边都指向存在的节点）+ 2 个 `quiz`（2 题 / 3 题，answer 未越界）；`04-没有银弹` 末节即"2026 年还成立吗" |
| AC-006 连续两次导入幂等 | `node scripts/import-course.mjs --dir content/reading/books-man-month --no-track` ×2 | 0 | passed | 第一次 `created 2 / updated 3`（3 篇与测试种子同名，按标题更新），第二次 `created 0 / updated 0` |
| AC-007 父分类挂载正确、重复导入不重复建 | 同上 + 独立内容源的建父分类验证 | 0 | passed | 真实内容：`books` 根分类下 `children` 恰 1 个、`lineKind=null`。另用一个父分类**不存在**的临时内容源跑了两遍：第一遍 `+ 父分类 检查用根（id=9203）` / `+ 分类 检查用书（id=9204）`，第二遍 `created 0 / updated 0`，树里 `books-check-root` 只出现 1 次 |
| AC-008 `books.spec.ts` 覆盖入口、书卡、章节 | `playwright test e2e/books.spec.ts` | 0 | passed | 4 条全绿：入口可达 / 书卡带篇数 / 章节首条为总览且有序号 / 章节能点进正文 |
| AC-009 窄屏不横向溢出 | `--project=mobile e2e/layout.mobile.spec.ts` | 0 | passed | `/books` 与 `/books/books-man-month` 进了 11 条路由的溢出卖烟；另加一条"从章节点进正文"，种子里那篇带一张 `code-map` 与一段引用块（出处头的正文形态），断言 `documentElement.scrollWidth - clientWidth ≤ 1` |

## 未覆盖与已知限制

| 项 | 原因 | 影响 |
| --- | --- | --- |
| 线上导入 | 本项未部署；生产库没有 `books` 分类 | 生产上 `/books` 会显示「还没有收录书籍」空态，直到部署后跑一次导入 |
| 真书正文的概念图在移动端 | 移动 e2e 用的是种子里那张图，不是 `content/` 里的三张 | 三张图的 JSON 已逐块校验，渲染路径与种子相同（同一个 `code-map` 渲染器） |
| 卡片篇数的上限 | `/books` 一次取 `size: 100` 条 | 条目总数超过 100 时篇数偏少。一期只有一本书 5 篇，未处理 |
| 第二本书之后的分组 | 只有一本书，`/books` 的网格没验证过多书排版 | 两列网格按 `children` 铺，风险低 |

## 与设计的一致性

设计里的五条取舍都按原样落地：

1. **书放在分类树里，没有新表**，也没有新接口。
2. **章节顺序用 `sort_order`**，排序在客户端做，`ORDER BY published_at DESC` 一行没改。
3. **章节页是独立页面**，没有复用 `/knowledge/categories/:id`。
4. **导航指向 `/books`**，不指向数字 id；分类解析全部按 slug。
5. **没有新增围栏块**：概念图用 `code-map`（标题覆写），先猜后看用 `quiz`。

设计之外多改了一处：分类查找从 `categories.find()` 换成递归的 `findCategoryBySlug`。
设计里写的是"按 slug 从树里定位"，实现时漏了"书挂在 books 下面"这一层——**e2e 第一次跑就
红了**，`book-chapters` 一行都没有。修在 `category-tree.ts`，带 4 条单测。

## 不在本项范围的发现

两处都在 `master` 上就存在，本项没有修，只记录：

1. **`ApiWalkthroughTest` 的全接口覆盖断言是红的**：11 个端点没被走到——
   `GET /ops/admin/analytics/overview`、`GET/POST /ops/admin/diagnoses*`、`GET /ops/admin/incidents*`、
   `POST /ops/admin/incidents/{id}/notes`、`POST /ops/hooks/grafana`、`POST /digest/items/{id}/click`。
   这些都是 WORK-042 / WORK-043 / WORK-027 之后加的，测试没跟上。
2. **`GET /api/v1/ai/admin/runs/meta` 返回 500**（`code=50000`）。所以重录 `api-shape.json` 时
   这条端点整个缺席。

重录快照需要绕开第 1 条：临时把 `assertFullCoverage()` 注掉跑了一次，**跑完立即还原**
（`git checkout --`，未进任何提交）。第 2 条只记录，没有排查。
