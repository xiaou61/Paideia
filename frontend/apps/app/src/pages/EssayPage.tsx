import { createKnowledgeApi, type EntrySummary, type PageResult } from '@paideia/knowledge'
import {
  AppShell,
  Badge,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  ErrorAlert,
  LoadingRow,
  PageHeader,
  Skeleton,
} from '@paideia/ui'
import { useQueries, useQuery, type UseQueryResult } from '@tanstack/react-query'
import { ArrowRight, Flame } from 'lucide-react'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { essayDirections, type EssayDirection } from '../essay'

const knowledge = createKnowledgeApi(api)

/** 每个方向在卡片里列几条最近入库的题。 */
const LATEST_SIZE = 4

/**
 * 面经题库（2026-11-10）。
 *
 * <p><b>为什么是独立一页</b>：面经原来是首页那条横幅 + 四个知识库分类，导航栏里没有入口——
 * 用户在页头找不到它，只能靠"回首页再往下看"。加了这一页之后导航「求职」组里有固定入口，
 * 四条方向各带最近入库的题，不用先点进分类才知道里面有没有新东西。
 *
 * <p><b>标题就是题目</b>（WORK-025 的约定）：抓取管线把原帖里的一道题写成一条条目，标题是问题，
 * 正文给结论与机制，底部留原帖链接。所以这里直接列标题，点到条目页就是答案，不另做一层"题面"。
 *
 * <p><b>条数不另发请求</b>：`entries` 的响应里带 `total`，与"最近几条"是同一次查询；
 * 首页那边用的 `categories/counts` 是给"只想要数字"的场合。这里两样都要，所以取前者。
 */
export function EssayPage() {
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  const directions = useMemo(() => essayDirections(categories.data ?? []), [categories.data])
  /**
   * 四个方向各取最近几条。**按方向分别查**而不是查全库再筛：
   * 全库最近 N 条里可能一条面经都没有（课程会成批发布），筛完就是一张空列表。
   */
  const latest = useQueries({
    queries: directions.map((direction) => ({
      queryKey: ['knowledge-entries', direction.category.id, '', 'essay-latest'],
      queryFn: () => knowledge.entries({ categoryId: direction.category.id, size: LATEST_SIZE }),
      staleTime: 60_000,
    })),
  })

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="面经题库"
        description="每天从牛客面经里抓一轮，只留技术题，AI 逐题写出结论与机制。四个方向各挑一张卡片进去，最近入库的题就列在下面。"
      />

      {categories.isPending ? <LoadingRow label="加载面经方向…" testId="essay-state" /> : null}
      {categories.isError ? (
        <ErrorAlert
          title="面经方向加载失败"
          error={categories.error}
          testId="essay-state"
          onRetry={() => void categories.refetch()}
        />
      ) : null}

      {categories.isSuccess && directions.length === 0 ? (
        <Empty data-testid="essay-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <Flame />
            </EmptyMedia>
            <EmptyTitle>还没建面经分类</EmptyTitle>
            <EmptyDescription>
              抓取任务按分类落库（essay-java / essay-frontend / essay-testing / essay-data）。
              分类建好之后，这里会出现四个方向。
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {directions.length > 0 ? (
        <div className="grid gap-4 lg:grid-cols-2" data-testid="essay-directions">
          {directions.map((direction, index) => (
            <DirectionCard key={direction.slug} direction={direction} query={latest[index]} />
          ))}
        </div>
      ) : null}

      {/*
        来源说明（2026-11-10）。抓来的内容与站内自产的课程混在同一个知识库里，
        不写清楚"这些题从哪来、为什么可信"，读者会把它当成随便攒的题库。
      */}
      <section className="border-border bg-card mt-10 rounded-xl border p-5" data-testid="essay-origin">
        <h2 className="text-base font-semibold">这些题是怎么来的</h2>
        <ul className="text-muted-foreground mt-3 space-y-2 text-sm leading-6">
          <li>
            每天自动跑一次：按 Java 后端 / 前端 / 测试 / 数据四个合集抓牛客面经版的新帖，
            默认回溯两天——定时任务漏跑一次也不会丢内容。
          </li>
          <li>
            只留技术题：面完的感慨、「几面几 offer」这类帖子在入库前就被过滤掉；
            一篇帖子最多抽 5 道，每题一份解析。
          </li>
          <li>每条都带原帖：正文底部有来源链接，结论可以回原处核对。</li>
        </ul>
      </section>
    </AppShell>
  )
}

/**
 * 一个方向：标题 + 条数 + 最近几条题目。
 *
 * <p>卡片本身**不是**一个链接——里面每一行题目都要能单独点，套一层 `<a>` 会出现嵌套链接。
 * 所以只有方向名和底部那行是去分类页的，中间是条目链接。
 */
function DirectionCard({
  direction,
  query,
}: {
  direction: EssayDirection
  query: UseQueryResult<PageResult<EntrySummary>> | undefined
}) {
  const to = `/knowledge/categories/${direction.category.id}`
  const total = query?.data?.total
  const items = query?.data?.items ?? []

  return (
    <article
      data-testid={`essay-direction-${direction.slug}`}
      className="border-border bg-card hover:shadow-card flex flex-col overflow-hidden rounded-xl border transition-shadow"
    >
      <header className="flex items-start justify-between gap-3 px-5 pt-5 pb-3">
        <div className="min-w-0">
          <Link to={to} className="group inline-flex items-center gap-1.5">
            <h2 className="group-hover:text-primary text-lg font-semibold transition-colors">
              {direction.label}
            </h2>
            <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 transition-colors" />
          </Link>
          <p className="text-muted-foreground mt-1 text-xs">{direction.category.name}</p>
        </div>
        {/* 0 道时不显示徽章：空分类上挂一个「0 道」比不显示更让人以为坏了 */}
        {total === undefined || total === 0 ? null : (
          <Badge variant="secondary" className="shrink-0 tabular-nums">
            {total} 道
          </Badge>
        )}
      </header>

      <ul className="divide-border divide-y" data-testid={`essay-latest-${direction.slug}`}>
        {query === undefined || query.isPending ? (
          <li className="space-y-2 px-5 py-3.5" aria-busy="true">
            <Skeleton className="h-4 w-4/5" />
            <Skeleton className="h-3 w-2/5" />
          </li>
        ) : null}

        {query?.isError ? (
          <li className="text-muted-foreground px-5 py-4 text-sm">
            题目加载失败。
            <button
              type="button"
              className="text-primary ml-1 underline-offset-2 hover:underline"
              onClick={() => void query.refetch()}
            >
              重试
            </button>
          </li>
        ) : null}

        {query?.isSuccess && items.length === 0 ? (
          <li className="text-muted-foreground px-5 py-4 text-sm">
            这个方向还没抓到题，抓取任务下一次跑完就会有。
          </li>
        ) : null}

        {items.map((item) => (
          <li key={item.id}>
            <Link
              to={`/knowledge/entries/${item.id}`}
              data-testid={`essay-item-${item.id}`}
              className="hover:bg-accent/40 group flex items-start px-5 py-3 transition-colors"
            >
              <span className="min-w-0 flex-1">
                <span className="group-hover:text-primary line-clamp-2 block text-sm font-medium transition-colors">
                  {item.title}
                </span>
                <span className="text-muted-foreground mt-1 block text-xs">
                  {new Date(item.updatedAt).toLocaleDateString('zh-CN')}
                </span>
              </span>
            </Link>
          </li>
        ))}
      </ul>

      <div className="border-border mt-auto border-t px-5 py-3">
        <Link to={to} className="text-primary inline-flex items-center gap-1 text-sm">
          进入「{direction.label}」全部题目
          <ArrowRight className="size-3.5" />
        </Link>
      </div>
    </article>
  )
}
