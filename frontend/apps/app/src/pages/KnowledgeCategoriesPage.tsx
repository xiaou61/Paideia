import { createKnowledgeApi, fetchAllEntries, plainText, type CategoryNode, type EntrySummary } from '@paideia/knowledge'
import {
  AppShell,
  Badge,
  Button,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  ErrorAlert,
  PageHeader,
  Section,
  Skeleton,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, FolderOpen, Layers } from 'lucide-react'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

/**
 * 知识库首屏：**分类卡网格**，每张卡带上这个分类最新的 3 个标题。
 *
 * <p>原来把分类塞进一个 16rem 的侧栏里，一屏看得到七八条、每条只有名字，整页因此显得又空又单调
 * （用户反馈："分类这里就不能弄成二级页面那样吗……页面过于单调"）。改成"先看分类、点进去才是条目"
 * 之后，卡片能放下名字与条目去向，点击目标也大得多。
 *
 * <p><b>2026-11-11 第二次改版：表格 → 带标题预览的分类卡。</b>上一版做成了「分类 / 子分类 / 去向」
 * 的表格行，线上实测有两个致命问题：
 *
 * <ul>
 *   <li><b>46 个分类里 43 个没有子分类</b>——那一列就是 43 个「—」，整页像一张没填完的表；</li>
 *   <li>"子分类"本来也不是读者关心的东西。他来这儿是想知道<b>这个分类里有什么内容</b>。</li>
 * </ul>
 *
 * <p>所以把"子分类"整列换成**这个分类最新的 3 个标题**：那一列从"什么都没有"变成全页信息量
 * 最大的地方，而且它天然回答了"值不值得点进去"。数据一次取全（645 条，约 200KB，目录阅读页
 * 也是这么取的），按分类聚合后在本地切前 3 条。
 *
 * <p>排序按**条目数从多到少**：内容多的分类先出现，空分类沉到最底下（它们保留，因为那是
 * 结构的一部分，只是不该占首屏）。
 */
export function KnowledgeCategoriesPage() {
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  // 一次取全库（内部循环翻页）：分类卡要展示"每个分类最新的 3 条"，逐分类请求就是 46 次往返。
  // 目录阅读页（/knowledge/wiki）早就这么做，645 条约 200KB，一次拿完最省。
  const allEntries = useQuery({
    queryKey: ['knowledge-entries-all-compact'],
    queryFn: () => fetchAllEntries(knowledge),
    // 分类预览只是"引子"：5 分钟内不重复拉，避免来回切页时反复下载 200KB
    staleTime: 5 * 60 * 1000,
  })

  /** 分类卡：分类节点 + 它整棵子树里的条目（按更新时间倒序，取前 3 条做预览）。 */
  const groups = useMemo(() => {
    const byCategory = new Map<number, EntrySummary[]>()
    for (const entry of allEntries.data ?? []) {
      if (entry.categoryId === null) continue
      const bucket = byCategory.get(entry.categoryId)
      if (bucket === undefined) {
        byCategory.set(entry.categoryId, [entry])
      } else {
        bucket.push(entry)
      }
    }
    const collect = (node: CategoryNode): EntrySummary[] => {
      const own = byCategory.get(node.id) ?? []
      const nested = node.children.flatMap(collect)
      return own.concat(nested)
    }
    return (categories.data ?? [])
      .map((node) => {
        const entries = collect(node).sort(
          (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
        )
        return { node, entries, count: entries.length }
      })
      // 内容多的先露头；数量相同按名字。空分类留到最后，而不是一排「—」占满首屏
      .sort((a, b) => b.count - a.count || a.node.name.localeCompare(b.node.name, 'zh-CN'))
  }, [categories.data, allEntries.data])

  // 分类下面的"最近更新"：分类卡只有标题，想看"最近写了什么"的人还有第二条进路
  const recent = useQuery({
    queryKey: ['knowledge-entries', null, '', 'recent'],
    queryFn: () => knowledge.entries({ size: 6 }),
  })

  const loading = categories.isPending || allEntries.isPending

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="挑一个分类读起"
        description="分类是层级树；点进去是这个分类下的条目。不想挑分类就直奔全部条目。"
      />

      {/*
        快捷入口：它们是**动作**（去某个列表 / 换一种读法），不是**分类**。
        做成与分类卡同尺寸的大卡，扫视时会被当成"两个奇怪的分类"。
      */}
      <div className="mb-8 flex flex-wrap items-center gap-2">
        <Button variant="outline" data-testid="all-entries-card" render={<Link to="/knowledge/entries" />}>
          <Layers className="size-4" />
          全部条目
        </Button>
        <Button variant="outline" data-testid="wiki-reader-card" render={<Link to="/knowledge/wiki" />}>
          <FolderOpen className="size-4" />
          按目录阅读
        </Button>
      </div>

      {categories.isError ? (
        <ErrorAlert
          title="分类加载失败"
          error={categories.error}
          testId="category-state"
          onRetry={() => void categories.refetch()}
        />
      ) : null}

      {categories.isError ? null : loading ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" data-testid="category-state">
          <span className="sr-only">加载分类…</span>
          {['a', 'b', 'c', 'd', 'e', 'f'].map((key) => (
            <div key={key} className="border-border bg-card flex flex-col rounded-xl border p-5">
              <Skeleton className="h-4 w-2/3" />
              <Skeleton className="mt-2 h-3 w-24" />
              <Skeleton className="mt-4 h-3 w-full" />
              <Skeleton className="mt-2 h-3 w-5/6" />
              <Skeleton className="mt-2 h-3 w-3/4" />
            </div>
          ))}
        </div>
      ) : null}

      {!loading && groups.length === 0 ? (
        <Empty data-testid="category-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FolderOpen />
            </EmptyMedia>
            <EmptyTitle>还没有建分类</EmptyTitle>
            <EmptyDescription>分类由管理员在管理端建立。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {loading || groups.length === 0 ? null : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" data-testid="category-grid">
          {groups.map(({ node, entries, count }) => (
            <Link
              key={node.id}
              to={`/knowledge/categories/${node.id}`}
              data-testid={`category-card-${node.id}`}
              className="border-border bg-card hover:border-primary/40 hover:shadow-card group focus-ring flex flex-col rounded-xl border p-5 transition-all hover:-translate-y-0.5"
            >
              <span className="flex items-start justify-between gap-3">
                <span className="group-hover:text-primary min-w-0 truncate font-medium transition-colors">
                  {node.name}
                </span>
                {/* 学习轨道与普通分类混在一起，但前者有关卡顺序；标出来才知道去 /learn 有一条引导路线 */}
                {node.lineKind ? (
                  <Badge variant="secondary" className="shrink-0">
                    从零开始
                  </Badge>
                ) : null}
              </span>
              <span className="text-muted-foreground mt-1 text-xs">
                {count} 条内容
                {node.children.length === 0 ? null : ` · ${node.children.length} 个子分类`}
              </span>

              {/* 预览标题：这一列就是这张卡的全部价值——读者靠它判断"值不值得点进去" */}
              <span className="mt-3 flex flex-1 flex-col gap-1.5">
                {entries.length === 0 ? (
                  <span className="text-muted-foreground/70 text-xs">还没有内容</span>
                ) : (
                  entries.slice(0, 3).map((entry) => (
                    <span key={entry.id} className="text-muted-foreground block truncate text-sm">
                      {entry.title}
                    </span>
                  ))
                )}
              </span>

              <span className="text-primary mt-4 flex items-center gap-1 text-xs">
                {count === 0 ? '看看这个分类' : `查看全部 ${count} 条`}
                <ArrowRight className="size-3.5" />
              </span>
            </Link>
          ))}
        </div>
      )}

      {/*
        分类下面是"最近更新"。分类卡只有标题，想看"最近写了什么、谁写的"的人有第二条进路。
      */}
      {recent.isSuccess && recent.data.items.length > 0 ? (
        <Section
          title="最近更新"
          description="不挑分类也可以从这儿直接进。"
          className="mt-12"
          actions={
            <Button variant="ghost" size="sm" render={<Link to="/knowledge/entries" />}>
              全部条目
            </Button>
          }
        >
          <div className="border-border divide-border divide-y overflow-hidden rounded-xl border" data-testid="recent-updated">
            {recent.data.items.map((item) => (
              <Link
                key={item.id}
                to={`/knowledge/entries/${item.id}`}
                className="hover:bg-accent/40 group flex items-start gap-4 px-5 py-4 transition-colors"
              >
                <span className="min-w-0 flex-1">
                  <span className="group-hover:text-primary block font-medium transition-colors">
                    {item.title}
                  </span>
                  {item.excerpt === null || plainText(item.excerpt) === '' ? null : (
                    <span className="text-muted-foreground mt-1 line-clamp-2 block text-sm leading-6">
                      {plainText(item.excerpt)}
                    </span>
                  )}
                </span>
                <span className="flex shrink-0 items-center gap-3">
                  {item.categoryName === null ? null : (
                    <Badge variant="secondary">{item.categoryName}</Badge>
                  )}
                  <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 transition-colors" />
                </span>
              </Link>
            ))}
          </div>
        </Section>
      ) : null}
    </AppShell>
  )
}
