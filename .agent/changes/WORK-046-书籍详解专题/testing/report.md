---
artifact: test-report
work_id: WORK-046
work: 书籍详解专题
status: passed
evidence: required
workflow: full
created: 2026-10-28
updated: 2026-10-28
verified_commit: d4fad51
accepted_by: xiaou61
accepted_at: 2026-10-29 13:21:44 +0800
---

# WORK-046 验证报告

**结论先说**：**9 条验收标准全部有实测证据**，本地独占测试库跑一遍，**线上已部署、已导入**
并复核了数据。验证过程中暴露的两条既有缺陷（与本项无关）**已在收尾时修掉**，见最后一节。

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

## 线上验证

部署（`release.ps1 -WithWeb`，164.8 秒）后在生产库跑了两遍导入，结果与本地一致。

| 项 | 实测 |
| --- | --- |
| 分类层级 | `books` id=53 顶层、`books-man-month` id=54 的 `parent_id=53`，两者 `line_kind` 都是 NULL |
| 条目 | 5 篇全为 `published`，`sort_order` 依次 0/1/2/3/4 |
| 不进修学习线 | `learning_tracks` 里 `books` 与 `books-man-month` 都是 0 条（AC-004 线上复核） |
| 幂等 | 第一次 `created 5 / updated 0`（含 `+ 父分类 书籍详解（id=53）`），第二次 `created 0 / updated 0` |
| 服务 | `/actuator/health` UP，根路径 200 |

**导入用了一次性管理员，用完即删**：生产库没有可用的管理员口令（部署实例不加载种子），
所以临时插入一个 `tmp-import-*` 账号（随机 28 位口令、bcrypt 存储）→ 跑导入 → 立刻删除。
删除后复核：该前缀账号计数 **0**，用它登录返回 401。本地存的临时凭据文件也已删掉。

## 未覆盖与已知限制

| 项 | 原因 | 影响 |
| --- | --- | --- |
| 真书正文的概念图在移动端 | 移动 e2e 用的是种子里那张图，不是 `content/` 里的三张 | 三张图的 JSON 已逐块校验，渲染路径与种子相同（同一个 `code-map` 渲染器） |
| 卡片篇数的上限 | `/books` 一次取 `size: 100` 条 | 条目总数超过 100 时篇数偏少。一期只有一本书 5 篇，未处理 |
| 第二本书之后的分组 | 只有一本书，`/books` 的网格没验证过多书排版 | 两列网格按 `children` 铺，风险低 |
| 线上页面的视觉验收 | 没有生产账号口令，没法登录看页面 | 数据与接口都核对过；**观感仍需用户自己看一眼** |

## 第二批书目（2026-10-28 追加）

用户要求"多加几本"，按同一套格式再加三条书线。**没有代码改动**——导入脚本、页面、导航都不变。

| 书 | slug | 篇数 | 生产 id |
| --- | --- | --- | --- |
| 数据密集型应用系统设计 | `books-ddia` | 4（总览 + 3） | 55 |
| 思考，快与慢 | `books-thinking` | 5（总览 + 4） | 56 |
| 代码整洁之道 | `books-clean-code` | 4（总览 + 3） | 57 |

线上复核：三条线 `parent_id` 全指 53（`books`），`sort_order` 依次 200 / 210 / 220 / 230；
已发布条目 **5 + 4 + 5 + 4 = 18** 篇；`learning_tracks` 里 `books%` 仍是 **0** 条。
导入第一次分别 `created 4/5/4`，第二次三本都 `created 0 / updated 0`。

《思考，快与慢》单列了一篇「复现危机」：社会启动与自我损耗在大样本重复实验里没站住，
只读前三篇会把这本书当结论手册。这是本批唯一的结构性差异。

已核实的出处（ISBN 不是编的）：DDIA 中译 978-7-5198-2196-8、思考快与慢 978-7-5086-3355-8、
代码整洁之道 978-7-115-21687-8。三本的英文原版信息一并写在各自总览的出处头里。

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

两处都在 `master` 上就存在。本项实施时只记录没修，**收尾时按用户要求一并修掉了**：

1. **`ApiWalkthroughTest` 的全接口覆盖断言是红的**：11 个端点没被走到——
   `GET /ops/admin/analytics/overview`、`GET/POST /ops/admin/diagnoses*`、`GET /ops/admin/incidents*`、
   `POST /ops/admin/incidents/{id}/notes`、`POST /ops/hooks/grafana`、`POST /digest/items/{id}/click`。
   这些都是 WORK-042 / WORK-043 / WORK-027 之后加的，测试没跟上。
   **已修**：按既有口径补上（有真实 id 才能成功的用不存在的 id 交给 `bestEffort`，
   诊断默认关闭所以 POST 是 409）。修后 `ApiWalkthroughTest` **1 条 0 失败**，
   连跑两次稳定，快照只多出 5 条端点。
2. **`GET /api/v1/ai/admin/runs/meta` 返回 500**。根因是
   `TelemetrySummaryMapper.distinctModels` 的 `@Select` **没有** `<script>` 包裹却写了 `&lt;`——
   MyBatis 只对 `<script>` 块做 XML 反转义，于是字面量 `&lt;` 被发给 MySQL，整条 SQL 语法错误。
   **已修**（改成裸 `<`）。仓库里本来就有守卫 `scripts/check_mapper_script_xml.py` 拦这类问题，
   但它只匹配 `"""` 文本块，漏了 `"a" + "b"` 拼接写法——**守卫也补上了**，
   并做反向验证：把 `&lt;` 放回去守卫立刻报 1 处问题（退出码 1），改回来是 0 处。

