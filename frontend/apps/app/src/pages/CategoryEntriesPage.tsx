import { createKnowledgeApi, fetchAllEntries, findCategoryPath, plainText, type CategoryNode } from '@paideia/knowledge'
import {
  AppShell,
  Badge,
  Button,
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  ErrorAlert,
  Input,
  PageHeader,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, BookOpenCheck, ChevronRight, FileText, Layers, Search } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { PageBreadcrumb } from '../components/PageBreadcrumb'
import { EntryListSkeleton } from '../components/skeletons'

const knowledge = createKnowledgeApi(api)

/**
 * 卡片/列表摘要（2026-11-11）。
 *
 * <p>后端给的 `excerpt` 是正文前 200 字原文，而很多条目正文第一句就是标题本身（面经类尤其明显：
 * 标题是问题，正文又把这句问题抄一遍）。直接显示会出现"摘要第一行和标题一模一样"，白占一行。
 * 这里把开头的重复标题削掉，并把紧跟的标点一并去掉。
 *
 * <p>只削**完全同前缀**的情况：措辞略有不同时不动它，免得把正常句子削掉半句。
 */
function cardExcerpt(excerpt: string | null, title: string): string {
  const text = plainText(excerpt ?? '').trim()
  if (text === '') return ''
  const head = title.trim()
  if (head !== '' && text.startsWith(head)) {
    return text.slice(head.length).replace(/^[\s，。：:；;、,.!?！？—–-]+/, '')
  }
  return text
}

interface FlatCategory {
  node: CategoryNode
  depth: number
}

/**
 * 知识库浏览页：**左侧分类目录 + 右侧条目列表**（2026-11-11 定稿）。
 *
 * <p>同一个组件服务三条路由：`/knowledge`（全部）、`/knowledge/entries`（全部）、
 * `/knowledge/categories/:categoryId`（某个分类）。三条走同一套两栏布局，差别只有右侧显示谁。
 *
 * <p><b>为什么是这个形态</b>（前面两版：色块卡片网格 → 面试鸭式密排表格 → 两列文章卡，用户都不满意）：
 *
 * <ul>
 *   <li><b>表格</b>：面试鸭每行有难度/标签等四个可扫字段，我们只有"标题 + 分类"，列大半是空的；</li>
 *   <li><b>卡片网格</b>：46 个分类铺成 46 张同尺寸卡，等于一堵卡墙，且每点一次分类才看得到内容；</li>
 *   <li><b>两栏目录</b>：分类本身当索引常驻左侧（带条目数），右侧立刻给出该分类的条目与**一句摘要**。
 *       不用跳转就能看清"这个分类有什么"，要深链接时左栏每一项仍是真 URL。</li>
 * </ul>
 *
 * <p>左栏在窄屏不是隐藏，而是变成顶部一条可横向滑动的分类标签（见 `md:hidden` 那一段）——
 * 手机上 240px 的侧栏会把正文挤没。
 */
export function CategoryEntriesPage() {
  const { categoryId } = useParams<{ categoryId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const scopedId = categoryId === undefined ? null : Number(categoryId)
  const keyword = searchParams.get('q') ?? ''
  const [draft, setDraft] = useState(keyword)

  useEffect(() => {
    setDraft(keyword)
  }, [keyword])

  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })

  /**
   * 左栏每个分类的条目数（含子分类）。
   *
   * <p>一次取全库来聚合（645 条约 200KB，目录阅读页同样做法，`staleTime` 5 分钟）：
   * 逐分类调计数接口是 46 次往返。数字本身很重要——它是"这个分类值不值得点"的第一信号。
   */
  const allEntries = useQuery({
    queryKey: ['knowledge-entries-all-compact'],
    queryFn: () => fetchAllEntries(knowledge),
    staleTime: 5 * 60 * 1000,
  })

  const categoryCounts = useMemo(() => {
    const direct = new Map<number, number>()
    for (const entry of allEntries.data ?? []) {
      if (entry.categoryId === null) continue
      direct.set(entry.categoryId, (direct.get(entry.categoryId) ?? 0) + 1)
    }
    const counts = new Map<number, number>()
    const walk = (node: CategoryNode): number => {
      const own = direct.get(node.id) ?? 0
      const nested = node.children.reduce((sum, child) => sum + walk(child), 0)
      const total = own + nested
      counts.set(node.id, total)
      return total
    }
    for (const node of categories.data ?? []) walk(node)
    return counts
  }, [categories.data, allEntries.data])

  /**
   * 分页（2026-11-10）：原来固定 `size: 50`、也没有翻页入口——"共 567 条"却只能靠搜索摸到其余。
   * 现在每点一次多要 24 条。两栏里右侧是一列行，24 条约一屏半，给"加载更多"留出意义。
   */
  const PAGE_SIZE = 24
  const [size, setSize] = useState(PAGE_SIZE)
  useEffect(() => {
    setSize(PAGE_SIZE)
  }, [scopedId, keyword])
  const entries = useQuery({
    queryKey: ['knowledge-entries', scopedId, keyword, size],
    queryFn: () => knowledge.entries({ categoryId: scopedId, q: keyword, size }),
  })

  const path = useMemo(
    () => (scopedId === null ? [] : findCategoryPath(categories.data ?? [], scopedId)),
    [categories.data, scopedId],
  )
  const current = path.length === 0 ? undefined : path[path.length - 1]
  const title = scopedId === null ? '全部条目' : (current?.name ?? '分类')

  const applyKeyword = (next: string) => {
    const params = new URLSearchParams(searchParams)
    if (next === '') params.delete('q')
    else params.set('q', next)
    setSearchParams(params, { replace: true })
  }
  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    applyKeyword(draft.trim())
  }

  const breadcrumb = useMemo(() => {
    const base = [{ label: '知识库', to: '/knowledge', testId: 'back-to-categories' }]
    const trail = path.map((node) => ({ label: node.name, to: `/knowledge/categories/${node.id}` }))
    if (keyword.trim() !== '') return [...base, ...trail, { label: `搜索：${keyword.trim()}` }]
    return scopedId === null ? [...base, { label: '全部条目' }] : [...base, ...trail]
  }, [path, keyword, scopedId])

  const flat = useMemo(() => {
    const out: FlatCategory[] = []
    const walk = (nodes: CategoryNode[], depth: number) => {
      for (const node of nodes) {
        out.push({ node, depth })
        walk(node.children, depth + 1)
      }
    }
    walk(categories.data ?? [], 0)
    return out
  }, [categories.data])

  const items = entries.data?.items ?? []
  const total = entries.data?.total ?? 0
  const truncated = entries.isSuccess && items.length < total
  // 全库条数先取聚合结果，没到就退回列表接口的 total（两者同源，避免左栏先显示 0 再跳数）
  const totalAll = allEntries.data?.length ?? entries.data?.total ?? 0
  const countsReady = allEntries.isSuccess

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageBreadcrumb items={breadcrumb} />

      <PageHeader
        title={title}
        description={
          scopedId === null ? '按更新时间排列；左边挑分类，右边就是它的内容。' : '这个分类下的条目。'
        }
      />

      {/* 学习轨道：同样的内容在「从零开始」里按关卡顺序排好了，给一条明确的另一条路 */}
      {current?.lineKind ? (
        <Link
          to={`/learn/${current.slug}`}
          data-testid="category-track-link"
          className="border-primary/30 bg-primary/[0.04] hover:border-primary/60 mb-6 flex items-center gap-3 rounded-xl border p-4 transition-colors"
        >
          <span className="bg-primary/10 text-primary flex size-9 shrink-0 items-center justify-center rounded-lg">
            <BookOpenCheck className="size-4" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium">这是一条学习轨道</span>
            <span className="text-muted-foreground mt-0.5 block text-xs">
              同样的内容在「从零开始」里按关卡顺序排好了，带进度与复习。
            </span>
          </span>
          <span className="text-primary flex shrink-0 items-center gap-1 text-sm">
            打开关卡地图
            <ArrowRight className="size-4" />
          </span>
        </Link>
      ) : null}

      <div className="grid gap-6 md:grid-cols-[15rem_minmax(0,1fr)]">
        {/* ── 左栏：分类目录（桌面常驻；窄屏换成下面的横向标签） ───────────────── */}
        <CategoryRail
          flat={flat}
          counts={categoryCounts}
          countsReady={countsReady}
          scopedId={scopedId}
          totalAll={totalAll}
          loading={categories.isPending}
          className="hidden md:block"
        />

        {/* ── 右栏：条目 ───────────────────────────────────────────────────── */}
        <div className="min-w-0">
          {/* 窄屏分类标签：与左栏同一份数据、同一批 URL，只是横向排 */}
          {/* 芯片高 44px：手机上这是本页最主要的可点目标，低于 44 就是可访问性回归（e2e 会拦） */}
          <div className="mb-4 -mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1 md:hidden" data-testid="category-chips">
            <Link
              to="/knowledge/entries"
              data-testid="category-chip-all"
              className={
                'inline-flex min-h-11 shrink-0 items-center rounded-full border px-3 text-xs ' +
                (scopedId === null ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground')
              }
            >
              全部 {totalAll}
            </Link>
            {flat.map(({ node }) => (
              <Link
                key={node.id}
                to={`/knowledge/categories/${node.id}`}
                data-testid={`category-chip-${node.id}`}
                className={
                  'inline-flex min-h-11 shrink-0 items-center rounded-full border px-3 text-xs ' +
                  (node.id === scopedId
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border text-muted-foreground')
                }
              >
                {node.name}
              </Link>
            ))}
          </div>

          <form onSubmit={onSubmit} className="mb-4 flex items-center gap-2">
            <div className="relative flex-1">
              <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
              <Input
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                placeholder="搜索标题或正文"
                aria-label="搜索知识条目"
                data-testid="knowledge-search"
                className="h-9 rounded-full pl-9"
              />
            </div>
            <Button type="submit" size="sm" data-testid="knowledge-search-submit">
              搜索
            </Button>
            {keyword === '' ? null : (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                data-testid="knowledge-search-clear"
                onClick={() => applyKeyword('')}
              >
                清除
              </Button>
            )}
            {entries.isSuccess ? (
              <span className="text-muted-foreground ml-auto shrink-0 text-xs" data-testid="entry-total">
                共 {total} 条
              </span>
            ) : null}
          </form>

          {entries.isPending ? <EntryListSkeleton count={6} /> : null}
          {entries.isError ? (
            <ErrorAlert
              title="条目加载失败"
              error={entries.error}
              testId="entry-state"
              onRetry={() => void entries.refetch()}
            />
          ) : null}

          {entries.isSuccess && items.length === 0 ? (
            <Empty data-testid="entry-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <FileText />
                </EmptyMedia>
                <EmptyTitle>
                  {keyword.trim() === '' ? '这里还没有条目' : `没有找到「${keyword.trim()}」`}
                </EmptyTitle>
                <EmptyDescription>
                  {keyword.trim() === '' ? '换个分类看看，或者直接浏览全库。' : '换一个词试试，或者去看全库内容。'}
                </EmptyDescription>
              </EmptyHeader>
              <EmptyContent>
                {keyword.trim() === '' ? (
                  <Button variant="outline" data-testid="entry-empty-all" render={<Link to="/knowledge/entries" />}>
                    看全部条目
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    data-testid="entry-empty-clear"
                    onClick={() => {
                      setDraft('')
                      applyKeyword('')
                    }}
                  >
                    清空搜索
                  </Button>
                )}
              </EmptyContent>
            </Empty>
          ) : null}

          {items.length === 0 ? null : (
            <ul
              className="border-border divide-border divide-y overflow-hidden rounded-xl border"
              data-testid="entry-list"
            >
              {items.map((item) => {
                const excerpt = cardExcerpt(item.excerpt, item.title)
                return (
                  <li key={item.id}>
                    <Link
                      to={`/knowledge/entries/${item.id}`}
                      data-testid={`entry-link-${item.id}`}
                      className="group hover:bg-accent/40 focus-ring flex items-start gap-4 px-5 py-4 transition-colors"
                    >
                      <span className="min-w-0 flex-1">
                        <span
                          data-testid="entry-title"
                          className="group-hover:text-primary block leading-6 font-medium transition-colors"
                        >
                          {item.title}
                        </span>
                        {/* 一句摘要：读者靠它判断"这条讲什么"，所以它必须紧贴标题、一行就够 */}
                        {excerpt === '' ? null : (
                          /* 不要加 `block`：`line-clamp-*` 自带 `display:-webkit-box`，被 block 覆盖后就不截断了 */
                          <span className="text-muted-foreground mt-1 line-clamp-1 text-sm leading-6">
                            {excerpt}
                          </span>
                        )}
                        <span className="text-muted-foreground mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
                          {scopedId === null && item.categoryName !== null ? (
                            <span>{item.categoryName}</span>
                          ) : null}
                          <span className="tabular-nums">{new Date(item.updatedAt).toLocaleDateString('zh-CN')}</span>
                        </span>
                      </span>
                      <ChevronRight className="text-muted-foreground group-hover:text-primary mt-1 size-4 shrink-0 transition-colors" />
                    </Link>
                  </li>
                )
              })}
            </ul>
          )}

          {truncated ? (
            <div className="mt-6 flex flex-col items-center gap-2">
              <Button
                variant="outline"
                data-testid="entry-load-more"
                disabled={entries.isFetching}
                onClick={() => setSize((value) => value + PAGE_SIZE)}
              >
                {entries.isFetching ? '加载中…' : `加载更多（还有 ${total - items.length} 条）`}
              </Button>
              <span className="text-muted-foreground text-xs" data-testid="entry-shown-count">
                已显示 {items.length} / {total}
              </span>
            </div>
          ) : null}
        </div>
      </div>
    </AppShell>
  )
}

/**
 * 左侧分类目录：**真链接**（每一项指向 `/knowledge/categories/:id`），不是前端切换。
 *
 * <p>做成链接而不是按钮，是为了保住深链接与后退：`/knowledge/categories/12` 可以发给别人，
 * 刷新、后退都落到同一页。前端切换看着快，但地址栏不动，用户"回到上一个分类"只能靠记忆。
 *
 * <p>窄屏不渲染（父组件用 `hidden md:block` 藏掉），换成顶部横向标签——240px 的侧栏在手机上
 * 会把正文挤没。
 */
function CategoryRail({
  flat,
  counts,
  countsReady,
  scopedId,
  totalAll,
  loading,
  className,
}: {
  flat: FlatCategory[]
  counts: Map<number, number>
  countsReady: boolean
  scopedId: number | null
  totalAll: number
  loading: boolean
  className?: string
}) {
  const [filter, setFilter] = useState('')
  const shown = filter.trim() === '' ? flat : flat.filter(({ node }) => node.name.includes(filter.trim()))
  return (
    <aside className={className}>
      <div className="md:sticky md:top-20">
        <div className="relative mb-2">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2" />
          <Input
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="筛选分类"
            aria-label="筛选分类"
            className="h-8 rounded-full pl-8 text-xs"
          />
        </div>
        {loading ? (
          <p className="text-muted-foreground px-2 py-3 text-xs" data-testid="category-state">
            加载分类…
          </p>
        ) : (
          <>
            {/*
              「全部条目」刻意放在 `category-grid` **外面**：它是"不挑分类"这个动作，不是分类。
              顺带保住既有 e2e 的语义——`category-grid` 里的第一个链接必须是一个真实分类
              （否则"点第一个分类"会点到"全部条目"上）。
            */}
            <Link
              to="/knowledge/entries"
              data-testid="all-entries-card"
              className={
                'mb-1 flex items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm transition-colors ' +
                (scopedId === null
                  ? 'bg-primary/10 text-primary font-medium'
                  : 'text-foreground hover:bg-accent/50')
              }
            >
              <span className="flex min-w-0 items-center gap-1.5">
                <Layers className="size-3.5 shrink-0" />
                <span className="truncate">全部条目</span>
              </span>
              <span className="text-muted-foreground shrink-0 text-xs tabular-nums">
                {countsReady || totalAll > 0 ? totalAll : ''}
              </span>
            </Link>
            <ul className="space-y-0.5" data-testid="category-grid">
            {shown.map(({ node, depth }) => {
              const active = node.id === scopedId
              return (
                <li key={node.id}>
                  <Link
                    to={`/knowledge/categories/${node.id}`}
                    data-testid={`category-card-${node.id}`}
                    title={node.name}
                    className={
                      'flex items-center justify-between gap-2 rounded-lg py-2 pr-3 text-sm transition-colors ' +
                      (active
                        ? 'bg-primary/10 text-primary font-medium'
                        : 'text-foreground hover:bg-accent/50')
                    }
                    style={{ paddingLeft: `${12 + depth * 12}px` }}
                  >
                    <span className="min-w-0 truncate">{node.name}</span>
                    <span className="flex shrink-0 items-center gap-1">
                      {node.lineKind ? (
                        <Badge variant="secondary" className="h-4 px-1.5 text-[10px]">
                          轨道
                        </Badge>
                      ) : null}
                      <span className="text-muted-foreground text-xs tabular-nums">
                        {countsReady ? (counts.get(node.id) ?? 0) : ''}
                      </span>
                    </span>
                  </Link>
                </li>
              )
            })}
            {shown.length === 0 ? (
              <li className="text-muted-foreground px-3 py-2 text-xs">没有匹配的分类</li>
            ) : null}
            </ul>
          </>
        )}
      </div>
    </aside>
  )
}
