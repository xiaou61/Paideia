import { createKnowledgeApi, plainText } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Input,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, BookOpenCheck, ChevronLeft, FileText, Search } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

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

  const entries = useQuery({
    queryKey: ['knowledge-entries', scopedId, keyword],
    queryFn: () => knowledge.entries({ categoryId: scopedId, q: keyword, size: 50 }),
  })

  // 标题用当前分类名；分类还没加载出来时退回一句通用的
  const current = categories.data?.find((node) => node.id === scopedId)
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

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <Link
        to="/knowledge"
        // py-1 把命中区从 20px 抬到 28px（WCAG 2.5.8 要 24px）；-my-1 与 mb-4 一起保持原视觉间距。
        className="text-muted-foreground hover:text-foreground -my-1 mb-4 inline-flex items-center gap-1 py-1 text-sm transition-colors"
        data-testid="back-to-categories"
      >
        <ChevronLeft className="size-4" />
        所有分类
      </Link>

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

      {entries.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="entry-state">
          <Spinner />
          加载条目…
        </p>
      ) : null}
      {entries.isError ? (
        <Alert variant="destructive" data-testid="entry-state">
          <AlertTitle>条目加载失败</AlertTitle>
          <AlertDescription>{(entries.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {entries.isSuccess && entries.data.items.length === 0 ? (
        <Empty data-testid="entry-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileText />
            </EmptyMedia>
            <EmptyTitle>没有符合条件的条目</EmptyTitle>
            <EmptyDescription>换个关键词，或回到所有分类看看。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {entries.isSuccess && entries.data.items.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid="entry-list">
          {entries.data.items.map((item, index) => (
            <Link
              key={item.id}
              to={`/knowledge/entries/${item.id}`}
              data-testid={`entry-link-${item.id}`}
              className="border-border bg-card hover:border-primary/40 hover:shadow-card group animate-fade-up flex flex-col overflow-hidden rounded-xl border transition-all hover:-translate-y-0.5"
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
    </AppShell>
  )
}
