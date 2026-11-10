import { createKnowledgeApi, findCategoryPath, plainText } from '@paideia/knowledge'
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
import { ArrowRight, BookOpenCheck, FileText, Search } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { PageBreadcrumb } from '../components/PageBreadcrumb'
import { EntryGridSkeleton } from '../components/skeletons'
import { errorMessage } from '@paideia/core'

const knowledge = createKnowledgeApi(api)

/**
 * 某个分类（或全部）下的条目列表——分类那一层的**二级页**。
 *
 * <p>同一个组件服务两条路由：`/knowledge/categories/:categoryId`（某个分类）与 `/knowledge/entries`
 * （全部）。差别只有 `categoryId` 与标题，合成一个组件比复制两份真实。
 *
 * <p><b>检索是"回车/点按钮"而不是输入即搜</b>：中文输入法在拼字过程中会连续触发 change 事件，
 * 按字数防抖会把"并发"这种词的中间态（拼音或半截字）也发出去。提交式检索没有这个问题。
 *
 * <p><b>检索状态以地址栏（`?q=`）为准</b>：页头的搜索框按完回车就是跳到这个地址，而不是自己
 * 再实现一遍检索。好处是刷新、后退、把链接发给别人，看到的都还是同一批结果。输入框里的草稿
 * 是本地状态，地址栏变了就跟着变（否则"已经在列表页时从页头再搜一次"不会生效）。
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
   * 分页（2026-11-10）：原来固定 `size: 50`、也没有任何翻页入口——而页头明明白白写着
   * 「共 567 条」。超过 50 条的内容**只能靠搜索**摸到，等于其余五百多条在"全部条目"里不存在。
   * 这里改成"加载更多"：每点一次多要 50 条，页面上同时给出"已显示 M / N"。
   *
   * <p>没有做成页码跳转：这个页面的主要入口是分类与搜索，逐页翻完整库的人很少，
   * 而"加载更多"不会把已经看过的内容从眼前拿走。
   */
  const PAGE_SIZE = 50
  const [size, setSize] = useState(PAGE_SIZE)
  // 换分类或换关键词时回到第一批：否则会带着上一次的 150 条去查新条件
  useEffect(() => {
    setSize(PAGE_SIZE)
  }, [scopedId, keyword])
  const entries = useQuery({
    queryKey: ['knowledge-entries', scopedId, keyword, size],
    queryFn: () => knowledge.entries({ categoryId: scopedId, q: keyword, size }),
  })

  // 标题用当前分类名；分类还没加载出来时退回一句通用的
  /**
   * 从根到当前分类的整条路径（2026-11-09）。
   *
   * <p>原来这里写的是 `categories.data?.find(...)`——只在顶层找。二级分类（「操作系统」
   * 挂在「计算机基础」下）进来时找不到自己，标题退化成「分类」，面包屑也无从生成。
   */
  const path = useMemo(
    () => (scopedId === null ? [] : findCategoryPath(categories.data ?? [], scopedId)),
    [categories.data, scopedId],
  )
  const current = path.length === 0 ? undefined : path[path.length - 1]
  const title = scopedId === null ? '全部条目' : (current?.name ?? '分类')

  /** 关键词只写地址栏：查询键跟着它走，界面因此不会有第二份"当前检索词"。 */
  const applyKeyword = (next: string) => {
    const params = new URLSearchParams(searchParams)
    if (next === '') {
      params.delete('q')
    } else {
      params.set('q', next)
    }
    setSearchParams(params, { replace: true })
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    applyKeyword(draft.trim())
  }

  /**
   * 面包屑（2026-11-09）。搜索时最后一级换成「搜索：关键词」——
   * 否则 h1 还写着分类名，列表却已经跨分类了，人会以为自己在看这个分类的所有内容。
   * 中间层级始终可点，所以"清空搜索"只要点一下分类名就回去了。
   */
  const breadcrumb = useMemo(() => {
    const base = [{ label: '知识库', to: '/knowledge', testId: 'back-to-categories' }]
    const trail = path.map((node) => ({ label: node.name, to: `/knowledge/categories/${node.id}` }))
    if (keyword.trim() !== '') {
      return [...base, ...trail, { label: `搜索：${keyword.trim()}` }]
    }
    return scopedId === null ? [...base, { label: '全部条目' }] : [...base, ...trail]
  }, [path, keyword, scopedId])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      {/*
        面包屑（2026-11-09）：原来只有一行「‹ 所有分类」，进了二级分类就不知道自己在哪。
        现在 知识库 › [父级…] › 当前分类，中间每一级都能点回去；最后一项不可点。
        首页那一级（知识库）保留 back-to-categories 这个 testid，既有 e2e 仍然走同一条路。
      */}
      <PageBreadcrumb items={breadcrumb} />

      <PageHeader
        title={title}
        description={scopedId === null ? '按更新时间排列。' : '这个分类下的条目。'}
      />

      {/* 学习轨道的分类也能在知识库里翻，但"按关卡顺序走"只有 /learn 有；给一条明确的另一条路 */}
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

      <form onSubmit={onSubmit} className="mb-6 flex items-center gap-2">
        <div className="relative max-w-md flex-1">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
          <Input
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="搜索标题或正文"
            aria-label="搜索知识条目"
            data-testid="knowledge-search"
            className="h-10 rounded-full pl-9"
          />
        </div>
        <Button type="submit" data-testid="knowledge-search-submit">
          搜索
        </Button>
        {keyword === '' ? null : (
          <Button
            type="button"
            variant="ghost"
            data-testid="knowledge-search-clear"
            onClick={() => applyKeyword('')}
          >
            清除
          </Button>
        )}
        {entries.isSuccess ? (
          <span className="text-muted-foreground ml-auto text-xs" data-testid="entry-total">
            共 {entries.data.total} 条
          </span>
        ) : null}
      </form>

      {/* 骨架屏而不是转圈：位置先占住，数据到了文字直接填进去，页面不跳（2026-11-10） */}
      {entries.isPending ? <EntryGridSkeleton /> : null}
      {entries.isError ? (
        <ErrorAlert title="条目加载失败" error={entries.error} testId="entry-state" />
      ) : null}

      {/*
        空态要给出**下一条路**（2026-11-09）：只说"没有符合条件的条目"等于把用户留在原地。
        搜索为空时给"清空搜索"，分类为空时给"看全部条目"——都是这个页面上真能做到的事。
      */}
      {entries.isSuccess && entries.data.items.length === 0 ? (
        <Empty data-testid="entry-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileText />
            </EmptyMedia>
            <EmptyTitle>
              {keyword.trim() === '' ? '这里还没有条目' : `没有找到「${keyword.trim()}」`}
            </EmptyTitle>
            <EmptyDescription>
              {keyword.trim() === '' ? '换一个分类看看，或者直接浏览全库。' : '换一个词试试，或者去看全库内容。'}
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            {keyword.trim() === '' ? (
              <Button
                variant="outline"
                data-testid="entry-empty-all"
                render={<Link to="/knowledge/entries" />}
              >
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

      {entries.isSuccess && entries.data.items.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid="entry-list">
          {entries.data.items.map((item, index) => (
            <Link
              key={item.id}
              to={`/knowledge/entries/${item.id}`}
              data-testid={`entry-link-${item.id}`}
              className="border-border bg-card hover:border-primary/40 hover:shadow-card group animate-fade-up flex flex-col overflow-hidden rounded-xl border transition-all hover:-translate-y-0.5 focus-ring"
              style={{ animationDelay: `${index * 30}ms` }}
            >
              {/* 卡片封面：色块 + 分类。没有它，卡片就是一排"标题孤零零浮在空白里" */}
              <span className="tile-surface border-border flex h-20 items-center justify-between border-b px-4">
                {item.categoryName === null ? (
                  <span className="text-muted-foreground text-xs">未分类</span>
                ) : (
                  <Badge variant="secondary">{item.categoryName}</Badge>
                )}
                <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 transition-colors" />
              </span>
              <span className="flex flex-1 flex-col p-4">
                <span
                  data-testid="entry-title"
                  className="group-hover:text-primary line-clamp-2 font-medium transition-colors"
                >
                  {item.title}
                </span>
                {item.excerpt === null || plainText(item.excerpt) === '' ? null : (
                  <span className="text-muted-foreground mt-2 line-clamp-3 flex-1 text-sm leading-6">
                    {plainText(item.excerpt)}
                  </span>
                )}
                <span className="text-muted-foreground mt-3 text-xs">
                  {new Date(item.updatedAt).toLocaleDateString('zh-CN')}
                </span>
              </span>
            </Link>
          ))}
        </div>
      ) : null}

      {/* 还有没显示出来的就给出下一步：没有这个按钮时，超出 50 条的内容在页面上等于不存在 */}
      {entries.isSuccess && entries.data.items.length < entries.data.total ? (
        <div className="mt-8 flex flex-col items-center gap-2">
          <Button
            variant="outline"
            data-testid="entry-load-more"
            disabled={entries.isFetching}
            onClick={() => setSize((current) => current + PAGE_SIZE)}
          >
            {entries.isFetching ? '加载中…' : `加载更多（还有 ${entries.data.total - entries.data.items.length} 条）`}
          </Button>
          <span className="text-muted-foreground text-xs" data-testid="entry-shown-count">
            已显示 {entries.data.items.length} / {entries.data.total}
          </span>
        </div>
      ) : null}
    </AppShell>
  )
}
