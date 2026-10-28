import { createKnowledgeApi, findCategoryBySlug, orderChapters, plainText } from '@paideia/knowledge'
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
import { BookOpen, ChevronLeft, ChevronRight } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

/**
 * 一本书的章节目录（WORK-046）。
 *
 * <p>**不复用 `/knowledge/categories/:id`**：那个页面服务"浏览知识库"，按发布时间倒序、
 * 没有序号语义。给"按书里的顺序读"另做一个页面，两条用途不互相牵制。
 *
 * <p>分类按 **slug** 定位，不用数字 id——id 换库就变，slug 由内容源决定。
 */
export function BookChaptersPage() {
  const { slug } = useParams<{ slug: string }>()
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  // 书的分类挂在 `books` 下面，不在顶层：必须查整棵树，不是 categories.find()
  const book =
    categories.data === undefined
      ? undefined
      : (findCategoryBySlug(categories.data, slug ?? '') ?? undefined)

  const entries = useQuery({
    queryKey: ['knowledge-entries', book?.id ?? null, '', 'book'],
    queryFn: () => knowledge.entries({ categoryId: book?.id, size: 100 }),
    enabled: book !== undefined,
  })

  const chapters = useMemo(() => orderChapters(entries.data?.items ?? []), [entries.data])
  const overview = chapters.find((chapter) => chapter.sortOrder === 0)
  const blurb = overview?.excerpt ? plainText(overview.excerpt) : null

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <Link
        to="/books"
        className="text-muted-foreground hover:text-foreground -my-1 mb-4 inline-flex items-center gap-1 py-1 text-sm transition-colors"
        data-testid="back-to-books"
      >
        <ChevronLeft className="size-4" />
        所有书籍
      </Link>

      {categories.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="book-state">
          <Spinner />
          加载…
        </p>
      ) : null}
      {categories.isError ? (
        <Alert variant="destructive" data-testid="book-state">
          <AlertTitle>加载失败</AlertTitle>
          <AlertDescription>{(categories.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {categories.isSuccess && book === undefined ? (
        <Empty data-testid="book-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <BookOpen />
            </EmptyMedia>
            <EmptyTitle>这本书还没有收录</EmptyTitle>
            <EmptyDescription>地址里的 slug 对不上任何书籍分类，回到书单看看有哪些。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {book === undefined ? null : (
        <>
          <PageHeader
            title={book.name}
            description={blurb ?? '按顺序读，下一篇接上一篇。'}
          />

          <div className="mb-6 flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{chapters.length} 篇</Badge>
            <span className="text-muted-foreground text-xs">章节顺序由内容源决定，不按更新时间排</span>
          </div>

          {entries.isPending ? (
            <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="book-state">
              <Spinner />
              加载章节…
            </p>
          ) : null}

          {chapters.length === 0 && entries.isSuccess ? (
            <Empty data-testid="book-chapters-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <BookOpen />
                </EmptyMedia>
                <EmptyTitle>这本书还没有章节</EmptyTitle>
                <EmptyDescription>内容导入后会出现在这里。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : null}

          <ol className="divide-border divide-y" data-testid="book-chapters">
            {chapters.map((chapter) => (
              <li key={chapter.id}>
                <Link
                  to={`/knowledge/entries/${chapter.id}`}
                  data-testid={`book-chapter-${chapter.id}`}
                  className="hover:bg-muted/50 group flex items-center gap-4 px-1 py-4 transition-colors"
                >
                  {/* 序号用作者声明的 order；缺 order 的条目显示占位符而不是编一个假号 */}
                  <span className="text-muted-foreground w-8 shrink-0 text-right font-mono text-sm tabular-nums">
                    {typeof chapter.sortOrder === 'number' ? chapter.sortOrder + 1 : '·'}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="group-hover:text-primary block transition-colors">{chapter.title}</span>
                    {chapter.excerpt === null ? null : (
                      <span className="text-muted-foreground mt-1 line-clamp-1 block text-sm">
                        {plainText(chapter.excerpt)}
                      </span>
                    )}
                  </span>
                  <ChevronRight className="text-muted-foreground size-4 shrink-0" />
                </Link>
              </li>
            ))}
          </ol>
        </>
      )}
    </AppShell>
  )
}
