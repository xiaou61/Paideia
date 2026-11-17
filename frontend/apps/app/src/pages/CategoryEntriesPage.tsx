import {
  createKnowledgeApi,
  createLearningApi,
  fetchAllEntries,
  findCategoryPath,
  plainText,
  type CategoryNode,
} from '@paideia/knowledge'
import {
  AppShell,
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
import { ArrowRight, BookOpenCheck, Check, ChevronRight, FileText, Layers, Search } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { PageBreadcrumb } from '../components/PageBreadcrumb'
import { EntryListSkeleton } from '../components/skeletons'
import { OTHER_GROUP, TRACK_GROUPS, groupKeyOf } from '../learn-track-groups'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

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

/** 左栏某一段里的小分组（学习轨道按主题分、精读按书/源码分）。 */
interface RailGroup {
  key: string
  label: string
  items: CategoryNode[]
}

/** 左栏的三段：学习轨道 / 求职面经 / 精读·书籍·其他。 */
interface RailSection {
  key: string
  label: string
  hint: string
  items: CategoryNode[]
  groups: RailGroup[]
  defaultOpen: boolean
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

  /**
   * 「我」看过的条目（2026-11-11）。数据由打开条目时的浏览上报累积在服务端，这里只读。
   *
   * <p>`staleTime: 0` 是有意的：从条目页按后退回来会**重新挂载**本页，这一格必须重新拉，
   * 否则刚看完的那条不会变成「已读」——那正是这个功能唯一要表达的瞬间。
   */
  const viewed = useQuery({
    queryKey: ['knowledge-entry-views'],
    queryFn: () => knowledge.viewedEntries(),
    staleTime: 0,
  })
  const viewedIds = useMemo(
    () => new Set((viewed.data ?? []).map((row) => row.entryId)),
    [viewed.data],
  )

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

  /** 每个分类「已读 / 总数」的已读那一半。口径与上面的总数一致（含子分类）。 */
  const categoryReadCounts = useMemo(() => {
    const direct = new Map<number, number>()
    for (const entry of allEntries.data ?? []) {
      if (entry.categoryId === null || !viewedIds.has(entry.id)) continue
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
  }, [categories.data, allEntries.data, viewedIds])

  /**
   * 分页（2026-11-18）：原来"加载更多"每点一次就把列表拉长 24 条——滚得越长越找不到头，
   * 用户说的"界面无限"就是它。改成真正的翻页：每页固定条数，上一页 / 下一页 + 页码，
   * 换分类或换搜索词都回到第 1 页。翻页时保留上一页内容（placeholderData），避免闪一下骨架。
   */
  const PAGE_SIZE = 12
  const [page, setPage] = useState(1)
  useEffect(() => {
    setPage(1)
  }, [scopedId, keyword])
  const entries = useQuery({
    queryKey: ['knowledge-entries', scopedId, keyword, page],
    queryFn: () => knowledge.entries({ categoryId: scopedId, q: keyword, page, size: PAGE_SIZE }),
    placeholderData: (previousData) => previousData,
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

  /**
   * 学习轨道的真实集合（2026-11-17）。
   *
   * <p>分类表里的 `line_kind` 有历史漏标（操作系统 / 算法 / 人工智能 / 开源项目简讲 4 条明明是轨道
   * 却是 null），所以「是不是轨道」以 `/learn/tracks` 的 slug 为准，不依赖 `line_kind`。
   * 拿不到时退回 `line_kind`，只是短暂过渡，不会把行弄丢。
   */
  const tracks = useQuery({
    queryKey: ['learn-tracks'],
    queryFn: () => learning.tracks(),
    staleTime: 5 * 60 * 1000,
  })

  /**
   * 左栏的三段式结构（2026-11-17）：学习轨道 / 求职面经 / 精读·书籍·其他。
   *
   * <p>原来左栏把 53 个分类按树一口气平铺，轨道、面经、书籍、源码、遗留重复全混在一起，
   * 用户看不出"哪些是能按关卡学的、哪些只是长文"。这里只做**呈现层**的重新归类，不动任何数据：
   *
   * <ul>
   *   <li><b>学习轨道</b>：真轨道（40 条）按主题分组，默认折叠——它是「从零开始」的镜像入口，不是主浏览目标；</li>
   *   <li><b>求职面经</b>：名里带面经 / 面试 / 八股 的分类；</li>
   *   <li><b>精读·书籍·其他</b>：书籍详解、源码精读，以及剩下独立成篇的分类。</li>
   * </ul>
   *
   * <p>纯容器（有子分类、自身没有独立条目）不再占一行——它们的子项已经各自出现在对应段里。
   */
  const railSections = useMemo<RailSection[]>(() => {
    const trackSlugs = new Set((tracks.data ?? []).map((track) => track.slug))
    const isTrack = (node: CategoryNode): boolean =>
      trackSlugs.size > 0 ? trackSlugs.has(node.slug) : Boolean(node.lineKind)

    const trackItems: CategoryNode[] = []
    const interviewItems: CategoryNode[] = []
    const readingByParent = new Map<string, CategoryNode[]>()

    const walk = (nodes: CategoryNode[], parentLabel: string | null) => {
      for (const node of nodes) {
        if (isTrack(node)) {
          trackItems.push(node)
          continue
        }
        // 纯容器：不占行，直接展开子项（子项会按各自属性落到对应段）
        if (node.children.length > 0) {
          walk(node.children, node.name)
          continue
        }
        if (/面经|面试|八股/.test(node.name)) {
          interviewItems.push(node)
        } else {
          const key = parentLabel ?? '其他'
          const bucket = readingByParent.get(key) ?? []
          bucket.push(node)
          readingByParent.set(key, bucket)
        }
      }
    }
    walk(categories.data ?? [], null)

    const trackByGroup = new Map<string, CategoryNode[]>()
    for (const node of trackItems) {
      const key = groupKeyOf(node.slug)
      const bucket = trackByGroup.get(key) ?? []
      bucket.push(node)
      trackByGroup.set(key, bucket)
    }
    const trackGroups: RailGroup[] = []
    for (const def of TRACK_GROUPS) {
      const items = trackByGroup.get(def.key)
      if (items && items.length > 0) {
        // 主题内按编辑顺序（def.slugs）排，避免服务端返回顺序带来的忽前忽后
        items.sort((a, b) => def.slugs.indexOf(a.slug) - def.slugs.indexOf(b.slug))
        trackGroups.push({ key: def.key, label: def.label, items })
      }
    }
    const otherTracks = trackByGroup.get(OTHER_GROUP.key)
    if (otherTracks && otherTracks.length > 0) {
      trackGroups.push({ key: OTHER_GROUP.key, label: OTHER_GROUP.label, items: otherTracks })
    }

    const readingItems = Array.from(readingByParent.values()).flat()
    const preferredParents = ['书籍详解', '源码精读']
    const readingGroups: RailGroup[] = []
    for (const label of preferredParents) {
      const items = readingByParent.get(label)
      if (items && items.length > 0) readingGroups.push({ key: label, label, items })
    }
    // 其余非轨道叶子统一收进「其他」——不把它们的父容器名（如「计算机基础」）搬过来，
    // 免得和上面「学习轨道」里的同名主题混淆（例如遗留的重复分类「计算机网络」）。
    const restReading = Array.from(readingByParent.entries())
      .filter(([label]) => !preferredParents.includes(label))
      .flatMap(([, items]) => items)
    if (restReading.length > 0) readingGroups.push({ key: 'other-reading', label: '其他', items: restReading })

    return [
      {
        key: 'track',
        label: '学习轨道',
        hint: '带导读与关卡，可在「从零开始」按顺序学',
        items: trackItems,
        groups: trackGroups,
        defaultOpen: false,
      },
      {
        key: 'interview',
        label: '求职面经',
        hint: '八股与面试真题',
        items: interviewItems,
        groups: interviewItems.length > 0 ? [{ key: 'interview', label: '', items: interviewItems }] : [],
        defaultOpen: true,
      },
      {
        key: 'reading',
        label: '精读 · 书籍',
        hint: '书籍精读与独立长文',
        items: readingItems,
        groups: readingGroups,
        defaultOpen: true,
      },
    ]
  }, [categories.data, tracks.data])

  const items = entries.data?.items ?? []
  const total = entries.data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  // 全库条数先取聚合结果，没到就退回列表接口的 total（两者同源，避免左栏先显示 0 再跳数）
  const totalAll = allEntries.data?.length ?? entries.data?.total ?? 0
  const countsReady = allEntries.isSuccess

  // 页码越界时（例如数据变少）收回到最后一页，避免停在一个空页
  useEffect(() => {
    if (page > totalPages) setPage(totalPages)
  }, [page, totalPages])

  /** 翻页：夹在 [1, totalPages] 内，翻完把列表滚回顶部，否则停在页脚看不到新一页。 */
  const goPage = (next: number) => {
    const clamped = Math.min(Math.max(1, next), totalPages)
    if (clamped === page) return
    setPage(clamped)
    if (typeof document !== 'undefined') {
      document
        .querySelector('[data-testid="entry-list"]')
        ?.scrollIntoView({ block: 'start', behavior: 'smooth' })
    }
  }

  /** 页码按钮窗口：页数少就全列，多则「1 … 当前±1 … 末页」。 */
  const pageNumbers = useMemo<(number | '…')[]>(() => {
    if (totalPages <= 7) {
      return Array.from({ length: totalPages }, (_, index) => index + 1)
    }
    const out: (number | '…')[] = [1]
    const start = Math.max(2, page - 1)
    const end = Math.min(totalPages - 1, page + 1)
    if (start > 2) out.push('…')
    for (let value = start; value <= end; value += 1) out.push(value)
    if (end < totalPages - 1) out.push('…')
    out.push(totalPages)
    return out
  }, [page, totalPages])

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
          sections={railSections}
          counts={categoryCounts}
          readCounts={categoryReadCounts}
          totalRead={viewed.data?.length ?? 0}
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
                const read = viewedIds.has(item.id)
                return (
                  <li key={item.id}>
                    <Link
                      to={`/knowledge/entries/${item.id}`}
                      data-testid={`entry-link-${item.id}`}
                      className="group hover:bg-accent/40 focus-ring flex items-start gap-4 px-5 py-4 transition-colors"
                    >
                      <span className="min-w-0 flex-1">
                        {/*
                          已读的标题降到次文本色（面试鸭就是"做过的变灰"）。**但 hover 必须回到正文色**，
                          否则一行灰标题读起来像"已被禁用"，而不是"看过了但还能再看"。
                        */}
                        <span
                          data-testid="entry-title"
                          className={
                            'block leading-6 font-medium transition-colors group-hover:text-foreground ' +
                            (read ? 'text-muted-foreground' : 'text-foreground')
                          }
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
                      {read ? (
                        <span
                          data-testid="entry-read-mark"
                          title="你看过这条"
                          className="text-muted-foreground mt-0.5 flex shrink-0 items-center gap-1 text-xs"
                        >
                          <Check className="size-3.5" />
                          已读
                        </span>
                      ) : null}
                      <ChevronRight className="text-muted-foreground group-hover:text-foreground mt-1 size-4 shrink-0 transition-colors" />
                    </Link>
                  </li>
                )
              })}
            </ul>
          )}

          {entries.isSuccess && totalPages > 1 ? (
            <div className="mt-6 flex flex-col items-center gap-3" data-testid="entry-pagination">
              <div className="flex flex-wrap items-center justify-center gap-1.5">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page <= 1}
                  onClick={() => goPage(page - 1)}
                  data-testid="entry-page-prev"
                >
                  上一页
                </Button>
                {pageNumbers.map((value, index) =>
                  value === '…' ? (
                    <span key={`gap-${index}`} className="text-muted-foreground px-1 text-xs">
                      …
                    </span>
                  ) : (
                    <Button
                      key={value}
                      variant={value === page ? 'default' : 'outline'}
                      size="sm"
                      aria-current={value === page ? 'page' : undefined}
                      onClick={() => goPage(value)}
                      data-testid={`entry-page-${value}`}
                      className="min-w-9 tabular-nums"
                    >
                      {value}
                    </Button>
                  ),
                )}
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page >= totalPages}
                  onClick={() => goPage(page + 1)}
                  data-testid="entry-page-next"
                >
                  下一页
                </Button>
              </div>
              <span className="text-muted-foreground text-xs" data-testid="entry-page-info">
                第 {page} / {totalPages} 页 · 共 {total} 条
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
 * <p>2026-11-17 起左栏分三段（学习轨道 / 求职面经 / 精读·书籍·其他），不再把 53 个分类
 * 一口气平铺。段标题是可折叠按钮（非链接），所以 `category-grid` 里的第一个 `<a>` 仍是真实分类，
 * 保住既有 e2e「点第一个分类」的语义。
 *
 * <p>窄屏不渲染（父组件用 `hidden md:block` 藏掉），换成顶部横向标签——240px 的侧栏在手机上
 * 会把正文挤没。
 */
function CategoryRail({
  sections,
  counts,
  readCounts,
  totalRead,
  countsReady,
  scopedId,
  totalAll,
  loading,
  className,
}: {
  sections: RailSection[]
  counts: Map<number, number>
  readCounts: Map<number, number>
  totalRead: number
  countsReady: boolean
  scopedId: number | null
  totalAll: number
  loading: boolean
  className?: string
}) {
  const [filter, setFilter] = useState('')
  const [closed, setClosed] = useState<Record<string, boolean>>({})
  const isOpen = (section: RailSection) =>
    closed[section.key] === undefined ? section.defaultOpen : !closed[section.key]
  const toggle = (section: RailSection) =>
    setClosed((prev) => ({ ...prev, [section.key]: isOpen(section) }))

  const keyword = filter.trim()
  const flatAll = useMemo(() => sections.flatMap((section) => section.items), [sections])
  const matched = useMemo(
    () => (keyword === '' ? [] : flatAll.filter((node) => node.name.includes(keyword))),
    [flatAll, keyword],
  )

  const renderRow = (node: CategoryNode) => {
    const active = node.id === scopedId
    const total = counts.get(node.id) ?? 0
    const read = readCounts.get(node.id) ?? 0
    return (
      <li key={node.id}>
        <Link
          to={`/knowledge/categories/${node.id}`}
          data-testid={`category-card-${node.id}`}
          title={node.name}
          className={
            'flex items-center justify-between gap-2 rounded-lg py-2 pr-3 pl-3 text-sm transition-colors ' +
            (active ? 'bg-primary/10 text-primary font-medium' : 'text-foreground hover:bg-accent/50')
          }
        >
          <span className="min-w-0 truncate">{node.name}</span>
          <span
            className="text-muted-foreground shrink-0 text-xs tabular-nums"
            title={countsReady ? `已读 ${read} / 共 ${total}` : undefined}
          >
            {/* 只显示「已读/总数」；空分类写一个 0 —— 「0/0」是噪音 */}
            {countsReady ? (total === 0 ? '0' : `${read}/${total}`) : ''}
          </span>
        </Link>
      </li>
    )
  }

  return (
    <aside className={className}>
      {/* 目录比一屏还长时不再把整页撑高：高度封顶、目录自己滚，页面整体长度交给右栏分页决定 */}
      <div className="md:sticky md:top-20 md:max-h-[calc(100vh-6rem)] md:overflow-y-auto md:pb-6 md:pr-1">
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
              <span
                className="text-muted-foreground shrink-0 text-xs tabular-nums"
                title={countsReady || totalAll > 0 ? `已读 ${totalRead} / 共 ${totalAll}` : undefined}
              >
                {countsReady || totalAll > 0 ? `${totalRead}/${totalAll}` : ''}
              </span>
            </Link>
            <ul className="space-y-0.5" data-testid="category-grid">
              {keyword !== '' ? (
                <>
                  {matched.map(renderRow)}
                  {matched.length === 0 ? (
                    <li className="text-muted-foreground px-3 py-2 text-xs">没有匹配的分类</li>
                  ) : null}
                </>
              ) : (
                sections.map((section) => {
                  const open = isOpen(section)
                  return (
                    <li key={section.key} className={section.key === 'track' ? '' : 'mt-2'}>
                      <button
                        type="button"
                        onClick={() => toggle(section)}
                        aria-expanded={open}
                        title={section.hint}
                        data-testid={`rail-section-${section.key}`}
                        className="text-muted-foreground hover:text-foreground focus-ring flex w-full items-center gap-1.5 rounded-lg px-2 py-1.5 text-xs font-medium"
                      >
                        <ChevronRight
                          className={
                            'size-3.5 shrink-0 transition-transform ' + (open ? 'rotate-90' : '')
                          }
                        />
                        <span className="truncate">{section.label}</span>
                        <span className="ml-auto tabular-nums opacity-70">{section.items.length}</span>
                      </button>
                      {open
                        ? section.groups.map((group) => (
                            <div key={group.key} className="mt-0.5">
                              {group.label === '' ? null : (
                                <p className="text-muted-foreground/80 px-3 pt-1 pb-0.5 text-[11px]">
                                  {group.label}
                                </p>
                              )}
                              <ul className="space-y-0.5">{group.items.map(renderRow)}</ul>
                            </div>
                          ))
                        : null}
                    </li>
                  )
                })
              )}
            </ul>
          </>
        )}
      </div>
    </aside>
  )
}
