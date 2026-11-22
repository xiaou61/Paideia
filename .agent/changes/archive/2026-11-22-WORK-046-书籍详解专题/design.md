---
artifact: design
work_id: WORK-046
work: 书籍详解专题
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-014, WORK-020]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 09:55:53 +0800
approver_role: CTO
---

# 设计 · 书籍详解专题

## 系统上下文

```
content/reading/books-man-month/*.md
        │  frontmatter: category / line_name / line_parent / line_parent_name / order
        ▼
scripts/import-course.mjs  ──管理端 API──►  knowledge_categories（books → books-man-month）
        │                                   knowledge_entries（sort_order = order）
        ▼
GET /api/v1/knowledge/categories  ──►  /books          书列表（新页面）
GET /api/v1/knowledge/entries     ──►  /books/:slug    章节目录（新页面）
                                   ──►  /knowledge/entries/:id（既有页面，不动）
```

没有新表、没有新端点、没有迁移。前端两个页面都是既有端点的只读消费者。

## 内容与 frontmatter 契约

目录仍是「一个目录 = 一条线」，书不平铺在 `content/reading/` 根下，而是带 `books-` 前缀：

```
content/reading/books-man-month/
  00-总览.md
  01-人月神话.md
  02-概念完整性.md
  03-第二系统效应.md
  04-没有银弹.md
```

`00-总览.md` 的 frontmatter 在既有四个字段之外新增两个：

```yaml
category: books-man-month        # 子分类 slug（既有字段）
line_name: 人月神话 (The Mythical Man-Month)
line_parent: books               # 新增：父分类 slug
line_parent_name: 书籍详解        # 新增：父分类不存在时用它建
line_sort: 200                   # 父分类内的站序，方便以后多本书排序
```

其余篇章只要 `category: books-man-month` 与 `order: N`，不需要父分类字段——
父分类只在总览里声明一次。

**为什么不做成 `content/reading/books/<book>/`**：导入脚本的扫描规则是"一级子目录 =
一条线"，`books/` 作为容器目录会被当成一条没有正文的线，同时它下面的书又扫不到。
改成前缀目录，扫描规则一个字都不用改。

## 导入脚本改动

改动集中在 `scripts/import-course.mjs` 的三处：

1. `lineSpecOf()` 多读两个字段：

```js
parent: meta.line_parent ?? null,
parentName: meta.line_parent_name ?? null,
```

2. `ensureCategory()` 在建子分类前解析父分类：

```js
const parentId = spec.parent === null ? null : await ensureParent(client, spec)
```

3. 新增 `ensureParent()`：在已取到的整棵分类树里按 slug 找父分类；找不到就
   `POST /categories`（`parentId: null`、`lineKind: null`、`sortOrder: 0`），
   名字取 `spec.parentName`，缺失时直接用 slug。**必须不带 `lineKind`**——
   带了它 `books` 就会变成一条学习线出现在 `/learn`，违反 REQ-004。

`ensureCategory` 里的 `parentId: null` 改成上面算出的 `parentId`。

幂等性不变：分类仍按 slug 找，找到就复用；父分类同理。

## 读接口的一处扩展

`sort_order` 已经在表里，只是没被读出来。四处小改：

| 位置 | 改动 |
| --- | --- |
| `EntryMapper.pagePublished` 的 SELECT | 增加 `sort_order` 列 |
| `EntryService.EntrySummary` | 增加 `Integer sortOrder` 字段 |
| `EntryService.listPublished` 的映射 | 传入 `row.getSortOrder()` |
| `frontend/packages/knowledge/src/types.ts` 的 `EntrySummary` | 增加 `sortOrder?: number \| null` |

`Entry` 实体本来就有 `sortOrder`（`Entry.java:22`），不需要改。

**接口形态快照会红一次**：`ApiWalkthroughTest` 逐字段比对 `api-shape.json`
（`ApiWalkthroughTest.java:877`），响应体多一个字段就是用例失败。确认是有意变更后，
用 `-Dapi.shape.update=true` 重跑生成新快照，再 review diff——不要整文件覆盖了事。

**排序不在 SQL 里改**：现有分类页与全部条目页都依赖 `published_at DESC`，改 ORDER BY 会
改变它们的表现。排序只在书籍章节页的客户端做：

```ts
const ordered = [...items].sort(
  (a, b) => (a.sortOrder ?? Number.MAX_SAFE_INTEGER) - (b.sortOrder ?? Number.MAX_SAFE_INTEGER)
    || a.id - b.id,
)
```

`shortcut: sort_order 为空的条目一律排最后，导入脚本总会写它，只有手工建的条目会缺。`

## 前端 `/books`

新文件 `frontend/apps/app/src/pages/BooksPage.tsx`。

数据：

```ts
const categories = useQuery({ queryKey: ['knowledge-categories'], queryFn: () => knowledge.categories() })
const root = categories.data?.find((node) => node.slug === 'books')
const entries = useQuery({
  queryKey: ['knowledge-entries', root?.id, '', 'books'],
  queryFn: () => knowledge.entries({ categoryId: root!.id, size: 100 }),
  enabled: root !== undefined,
})
```

后端对父分类会展开后代分类（`EntryService.java:92-98`），所以**一次请求拿到全部书的条目**，
按 `categoryId` 分组即可得到每本书的篇数与总览：

```ts
const grouped = Map<number, EntrySummary[]>
const overview = group.find((e) => e.sortOrder === 0)   // 总览由 order 0 标定
```

渲染：复用 `AppShell` + `PageHeader` + `@paideia/ui` 的卡片样式，网格两列（窄屏一列）。
每张卡片：书名、`共 N 篇`、总览摘要（`plainText(overview.excerpt)` 截断到一行）。
点卡片跳 `/books/${book.slug}`。

空态有三种，文案要分开，否则读者不知道是自己没找对还是内容没上线：

- 分类树还在加载 → Spinner。
- 树里没有 `books` 分类 → 「还没有收录书籍」+ 一句"由内容导入脚本建立"。
- 有 `books` 但没有子分类 → 「书籍分类已建，但还没有书」。

`data-testid`：`books-grid`、`book-card-<slug>`、`books-empty`。

## 前端 `/books/:slug`

新文件 `frontend/apps/app/src/pages/BookChaptersPage.tsx`。

```ts
const book = categories.data?.find((node) => node.slug === slug)   // 树里按 slug 找，不用 id
const entries = useQuery({ queryKey: ['knowledge-entries', book?.id, '', 'book'], ... })
const ordered = sortBySortOrder(entries.data?.items ?? [])
```

渲染：书页顶部显示书名与总览摘要（`order === 0` 那篇的 excerpt），下面是章节的有序列表，
每行 `序号 · 标题`，点进 `/knowledge/entries/:id`（既有页面，块渲染与进度都走它）。

序号直接用 `sortOrder + 1`；`sortOrder` 为空时显示 `·` 而不是数字，避免给出错的序号。

`data-testid`：`book-chapters`、`book-chapter-<entryId>`、`book-empty`。

**为什么不复用 `CategoryEntriesPage`**：那个页面有检索框、按发布时间倒序、且服务两条既有
路由；给它加一个排序开关会让浏览体验与阅读顺序互相牵制。新页面只做"按顺序列章节"。

## 导航与路由

`frontend/apps/app/src/router.tsx` 增加两条，都包在既有的登录门里：

```tsx
{ path: '/books', element: <BooksPage /> },
{ path: '/books/:slug', element: <BookChaptersPage /> },
```

`frontend/apps/app/src/components/LearnerHeader.tsx` 的 `NAV` 增加一项，放在「知识」组：

```ts
{ key: 'books' as const, group: 'knowledge' as const, to: '/books', label: '书籍详解', testId: 'learner-nav-books' },
```

**不改 `@paideia/identity` 的 `preferredNav`**：它未命中时该项排在组内最后，这正是新入口要的
位置。`books` 也不进 `preferredNav` 的联合类型，因为不需要按身份提前。`isActive` 用现有
前缀匹配，`/books/man-month` 会自动点亮入口。

## 已知限制

- **书没有结构化元信息**。作者、版次、ISBN 只存在于正文出处头，所以卡片上不显示作者。
  要结构化就得给 `knowledge_categories` 加列并改管理端表单，本期不做。
- **卡片篇数依赖单次请求上限**。`/books` 用 `size: 100` 一次取全部条目；条目总数超过 100
  时篇数会偏少。一期只有一本书（5 篇），不处理。
- **总览靠 `sortOrder === 0` 识别**。作者若把总览写成 `order: 1`，卡片会没有摘要回退到书名。
  这是内容侧约定，靠章节页显示的序号自查。
- **章节页不显示阅读时长**。条目表没有这个列。
- **`/books` 与 `/knowledge` 会出现同一批内容**（Q-002 已裁决接受）。
