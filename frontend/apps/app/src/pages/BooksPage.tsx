import { createKnowledgeApi, plainText } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { BookOpen } from 'lucide-react'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

/** 书籍详解的根分类 slug。与内容源的 `line_parent` 同一个值，改一处就要改另一处。 */
const BOOKS_ROOT = 'books'

/**
 * 书籍详解（WORK-046）：以书为组织轴的入口。
 *
 * <p>数据全部来自既有两个读接口：分类树给"有哪些书"，条目列表给篇数与总览摘要。
 * 后端对父分类会**展开后代分类**，所以一次请求就拿到所有书的条目，不必每本书再发一次。
 *
 * <p>总览用 `sortOrder === 0` 标定（导入脚本按 frontmatter 的 order 写它）。
 * 手工建的条目没有这个字段，卡片就只显示书名与篇数——不猜。
 */
export function BooksPage() {
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  const root = categories.data?.find((node) => node.slug === BOOKS_ROOT)

  const entries = useQuery({
    queryKey: ['knowledge-entries', root?.id ?? null, '', 'books'],
    queryFn: () => knowledge.entries({ categoryId: root?.id, size: 100 }),
    enabled: root !== undefined,
  })

  const books = useMemo(() => {
    const items = entries.data?.items ?? []
    return (root?.children ?? []).map((book) => {
      const mine = items.filter((item) => item.categoryId === book.id)
      const overview = mine.find((item) => item.sortOrder === 0)
      return {
        book,
        count: mine.length,
        blurb: overview?.excerpt ? plainText(overview.excerpt) : null,
      }
    })
  }, [root, entries.data])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="书籍详解"
        description="跟着作者的思路走一遍：一本一本地读，不是一条一条地查。"
      />

      {categories.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="books-state">
          <Spinner />
          加载书单…
        </p>
      ) : null}
      {categories.isError ? (
        <Alert variant="destructive" data-testid="books-state">
          <AlertTitle>书单加载失败</AlertTitle>
          <AlertDescription>{(categories.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {categories.isSuccess && root === undefined ? (
        <Empty data-testid="books-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <BookOpen />
            </EmptyMedia>
            <EmptyTitle>还没有收录书籍</EmptyTitle>
            <EmptyDescription>
              书籍分类由内容导入脚本建立——在 content/reading/ 下写一本，跑一次导入就会出现。
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {root !== undefined && root.children.length === 0 ? (
        <Empty data-testid="books-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <BookOpen />
            </EmptyMedia>
            <EmptyTitle>书籍分类已建，但还没有书</EmptyTitle>
            <EmptyDescription>每本书是一个子分类，导入后由分类树自动带出。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {books.length > 0 ? (
        <div className="grid gap-4 sm:grid-cols-2" data-testid="books-grid">
          {books.map(({ book, count, blurb }, index) => (
            <Link
              key={book.id}
              to={`/books/${book.slug}`}
              data-testid={`book-card-${book.slug}`}
              className="border-border bg-card hover:border-primary/40 hover:shadow-card group animate-fade-up flex flex-col overflow-hidden rounded-xl border transition-all hover:-translate-y-0.5"
              style={{ animationDelay: `${index * 40}ms` }}
            >
              <span className="tile-surface border-border relative flex h-24 items-center justify-center border-b">
                <BookOpen className="text-primary/70 group-hover:text-primary size-8 transition-colors" />
                <Badge variant="secondary" className="absolute top-3 right-3">
                  共 {count} 篇
                </Badge>
              </span>
              <span className="flex flex-1 flex-col p-5">
                <span className="group-hover:text-primary text-lg transition-colors">{book.name}</span>
                {blurb === null ? null : (
                  <span className="text-muted-foreground mt-2 line-clamp-2 text-sm leading-6">{blurb}</span>
                )}
              </span>
            </Link>
          ))}
        </div>
      ) : null}
    </AppShell>
  )
}
