import { createKnowledgeApi, findCategoryPath } from '@paideia/knowledge'
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
import { EntryListSkeleton } from '../components/skeletons'

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
 *
 * <p><b>2026-11-11 第三次改版：卡片网格 → 面试鸭式的密排表格</b>。前两版都在"卡片"这条路
 * 上加东西（先是分类徽章 + 几行小字，后是色块封面 + 摘要），用户反馈仍然"不太好看"。问题
 * 不在卡片本身，在于**用卡片装一份目录**：一屏只放得下 6 条、每张卡顶着一块与内容无关的
 * 色块、摘要被截断三次才轮到标题被读到。参考面试鸭的题库列表（`#question-table`：序号 +
 * 题目 + 难度 + 标签的表格行）后改成"**一栏密排的行**"：
 *
 * <ul>
 *   <li><b>没有封面</b>：封面色块只承载一个分类名，而这个分类名整页都一样，是最典型的
 *       "占了位置不给信息"。</li>
 *   <li><b>序号 + 单列标题</b>：条目本身就是一个个问题，扫标题是唯一的挑选方式；两行截断
 *       比三行摘要更早把标题读完。</li>
 *   <li><b>元信息右对齐成列</b>：分类与更新时间对齐成两列固定宽度的列，眼睛能纵向扫；
 *       卡片里它们是散落的徽章，读不出"这是一批同类数据"。</li>
 *   <li><b>表头 + 分隔线 + 整行悬停</b>：行与行只用 1px 细线，取消阴影与位移，50 条也不吵。</li>
 * </ul>
 *
 * <p>摘要（excerpt）在这一版不再出现在列表里：面试鸭的行里也只有标题。正文摘要仍在条目页，
 * 列表要的是"快速认出是哪一题"，不是"读半句话"。
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
   * 这里改成"加载更多"：每点一次多要 50 条，表尾同时给出"已显示 M / 共 N 条"。
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

  /**
   * 「分类」这一列什么时候出现（2026-11-11）。
   *
   * <p>只在**这一屏的分类名不唯一**时才有意义：进了「八股文·面经真题」这种没有子分类的叶子
   * 分类，每条的分类名都是同一行字，整列就是 12 次重复——那正是上一版卡片顶部那条
   * 「八股文·面经真题 →」给人的观感。判据用的是"这个分类下有没有子分类"，而不是"返回的条目
   * 里分类名是否都一样"：后者要等数据到手才知道，表头会先少一列再补回来，页面抖一下。
   */
  const showCategoryColumn = scopedId === null || (current?.children.length ?? 0) > 0

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

  const items = entries.data?.items ?? []
  const total = entries.data?.total ?? 0
  const truncated = entries.isSuccess && items.length < total
  // 加载中也要画出表头：先立住表格结构，数据到了只填行，整块不下沉（2026-11-11）
  const showTableHead = entries.isPending || items.length > 0

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

      <form onSubmit={onSubmit} className="mb-4 flex items-center gap-2">
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
      </form>

      {entries.isError ? (
        <ErrorAlert
          title="条目加载失败"
          error={entries.error}
          testId="entry-state"
          onRetry={() => void entries.refetch()}
        />
      ) : null}

      {/*
        整块列表是一张**带表头的单卡**（2026-11-11）：表头、行、表尾共享同一条 1px 外框，
        而不是 50 张各自带框的卡片——后者在页面上就是 50 个边框接缝，滚动时尤其吵。
      */}
      {entries.isError ? null : (
        <div
          className="border-border bg-card overflow-hidden rounded-xl border"
          data-testid="entry-panel"
        >
          {showTableHead ? (
            <div className="border-border bg-muted/50 text-muted-foreground flex h-9 items-center gap-4 border-b px-4 text-xs">
              {/* 序号列没有标签：一个"#"比空着更啰嗦，位置本身就是说明 */}
              <span aria-hidden className="w-6 shrink-0" />
              <span className="min-w-0 flex-1">题目</span>
              {showCategoryColumn ? (
                <span className="hidden w-40 shrink-0 md:block">分类</span>
              ) : null}
              <span className="hidden w-20 shrink-0 text-right sm:block">更新</span>
            </div>
          ) : null}

          {/* 骨架屏而不是转圈：位置先占住，数据到了文字直接填进去，页面不跳（2026-11-10） */}
          {entries.isPending ? <EntryListSkeleton count={8} /> : null}

          {/*
            空态要给出**下一条路**（2026-11-09）：只说"没有符合条件的条目"等于把用户留在原地。
            搜索为空时给"清空搜索"，分类为空时给"看全部条目"——都是这个页面上真能做到的事。
            空态放在表格体内而不是替换整张卡：搜索框与表头还在原位，改一个词就能接着看。
          */}
          {entries.isSuccess && items.length === 0 ? (
            <Empty className="border-0 py-16" data-testid="entry-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <FileText />
                </EmptyMedia>
                <EmptyTitle>
                  {keyword.trim() === '' ? '这里还没有条目' : `没有找到「${keyword.trim()}」`}
                </EmptyTitle>
                <EmptyDescription>
                  {keyword.trim() === ''
                    ? '换一个分类看看，或者直接浏览全库。'
                    : '换一个词试试，或者去看全库内容。'}
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

          {items.length === 0 ? null : (
            <div data-testid="entry-list" className="divide-border divide-y">
              {items.map((item, index) => {
                const updated = new Date(item.updatedAt).toLocaleDateString('zh-CN')
                return (
                  <Link
                    key={item.id}
                    to={`/knowledge/entries/${item.id}`}
                    data-testid={`entry-link-${item.id}`}
                    className="focus-ring group hover:bg-accent/40 flex items-center gap-4 px-4 py-3 transition-colors"
                  >
                    {/* 行号是这一版新增的：密排列表里"第几条"是最便宜的定位锚，也是与参考样式最像的一处 */}
                    <span
                      aria-hidden
                      className="text-muted-foreground w-6 shrink-0 text-right text-xs tabular-nums"
                    >
                      {index + 1}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span
                        data-testid="entry-title"
                        className="group-hover:text-primary line-clamp-2 block leading-6 font-medium transition-colors"
                      >
                        {item.title}
                      </span>
                      {/* 窄屏放不下右侧两列，就把它们折到标题下面——直接隐藏等于这条信息不存在 */}
                      <span className="text-muted-foreground mt-1 flex flex-wrap items-center gap-x-1.5 text-xs sm:hidden">
                        {showCategoryColumn && item.categoryName !== null ? (
                          <>
                            <span className="min-w-0 truncate">{item.categoryName}</span>
                            <span aria-hidden>·</span>
                          </>
                        ) : null}
                        <span className="tabular-nums">{updated}</span>
                      </span>
                    </span>
                    {showCategoryColumn ? (
                      /*
                        分类名长度不可控（种子里有「人月神话 (The Mythical Man-Month)」这种），
                        所以列宽固定、徽章 `max-w-full` + 内层 `truncate`：不这么写徽章会按内容
                        撑宽，压到「更新」列上去——1440 下实测压出过日期与徽章重叠的乱码。
                        完整名字留在 title 里，窄屏那条元信息行也照样给全文。
                      */
                      <span
                        className="hidden w-40 shrink-0 md:block"
                        title={item.categoryName ?? undefined}
                      >
                        {item.categoryName === null ? (
                          <span className="text-muted-foreground text-xs">未分类</span>
                        ) : (
                          <Badge variant="secondary" className="max-w-full">
                            <span className="min-w-0 truncate">{item.categoryName}</span>
                          </Badge>
                        )}
                      </span>
                    ) : null}
                    <span className="text-muted-foreground hidden w-20 shrink-0 text-right text-xs tabular-nums sm:block">
                      {updated}
                    </span>
                  </Link>
                )
              })}
            </div>
          )}

          {/* 表尾：总数常驻，超出已加载的部分才给"加载更多" */}
          {entries.isSuccess ? (
            <div className="border-border flex flex-wrap items-center justify-between gap-2 border-t px-4 py-2.5">
              <span className="text-muted-foreground text-xs" data-testid="entry-total">
                {truncated ? (
                  <>
                    已显示 <span data-testid="entry-shown-count">{items.length}</span> / 共 {total} 条
                  </>
                ) : (
                  <>共 {total} 条</>
                )}
              </span>
              {truncated ? (
                <Button
                  size="sm"
                  variant="outline"
                  data-testid="entry-load-more"
                  disabled={entries.isFetching}
                  onClick={() => setSize((value) => value + PAGE_SIZE)}
                >
                  {entries.isFetching ? '加载中…' : `加载更多（还有 ${total - items.length} 条）`}
                </Button>
              ) : null}
            </div>
          ) : null}
        </div>
      )}
    </AppShell>
  )
}
