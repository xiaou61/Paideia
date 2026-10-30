import {
  createKnowledgeApi,
  createLearningApi,
  findCategoryBySlug,
  orderChapters,
  plainText,
  stripDuplicateTitle,
} from '@paideia/knowledge'
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
import { ChevronLeft, ChevronRight, FileCode2 } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { readSourceMeta } from './source-meta'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

/**
 * 一个项目的章节列表（WORK-057）。
 *
 * <p>与书籍章节页同形：顺序由内容源的 `order` 决定，不按发布时间。顶部元信息从**总览条目**
 * 的第一个 `source-walk` 块里读（repo / ref / commit / license）——不另存一份会漂移的数据。
 */
export function SourceProjectPage() {
  const { slug = '' } = useParams<{ slug: string }>()
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  const root = categories.data?.find((node) => node.slug === 'source')
  const project =
    root === undefined ? undefined : (findCategoryBySlug(root.children, slug) ?? undefined)

  const entries = useQuery({
    queryKey: ['knowledge-entries', project?.id ?? null, '', 'source-project'],
    queryFn: () => knowledge.entries({ categoryId: project?.id, size: 100 }),
    enabled: project !== undefined,
  })
  const progress = useQuery({
    queryKey: ['learning-progress'],
    queryFn: () => learning.progress(),
    retry: false,
  })

  const chapters = useMemo(() => orderChapters(entries.data?.items ?? []), [entries.data])
  const overview = chapters.find((chapter) => chapter.sortOrder === 0)
  const overviewEntry = useQuery({
    queryKey: ['knowledge-entry', overview?.id ?? null],
    queryFn: () => knowledge.entry(overview?.id ?? 0),
    enabled: overview !== undefined,
    retry: false,
  })
  const meta = overviewEntry.data === undefined ? null : readSourceMeta(stripDuplicateTitle(overviewEntry.data.body, overviewEntry.data.title))
  const passed = useMemo(
    () =>
      new Set(
        (progress.data ?? []).filter((item) => item.status === 'passed').map((item) => item.entryId),
      ),
    [progress.data],
  )

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <Link
        to="/source"
        className="text-muted-foreground hover:text-foreground -my-1 mb-4 inline-flex items-center gap-1 py-1 text-sm transition-colors"
        data-testid="back-to-source"
      >
        <ChevronLeft className="size-4" />
        所有项目
      </Link>

      {categories.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="source-project-state">
          <Spinner />
          加载…
        </p>
      ) : null}
      {categories.isError ? (
        <Alert variant="destructive" data-testid="source-project-state">
          <AlertTitle>加载失败</AlertTitle>
          <AlertDescription>{(categories.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {categories.isSuccess && project === undefined ? (
        <Empty data-testid="source-project-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileCode2 />
            </EmptyMedia>
            <EmptyTitle>这个项目还没有收录</EmptyTitle>
            <EmptyDescription>地址里的 slug 对不上任何项目分类，回到项目列表看看有哪些。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {project === undefined ? null : (
        <>
          <PageHeader
            title={project.name}
            description={
              meta === null
                ? '按顺序读，一关一个机制。'
                : `${meta.repo} @ ${meta.ref} · ${meta.license} · commit ${meta.commit.slice(0, 7)}`
            }
          />

          <div className="mb-6 flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{chapters.length} 关</Badge>
            {meta === null ? null : (
              <a
                href={`https://github.com/${meta.repo}/tree/${meta.commit}`}
                target="_blank"
                rel="noreferrer noopener"
                className="text-primary text-xs underline underline-offset-2"
              >
                查看仓库 ↗
              </a>
            )}
            <span className="text-muted-foreground text-xs">版本锁定，行号与原文对得上</span>
          </div>

          {entries.isPending ? (
            <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="source-project-state">
              <Spinner />
              加载章节…
            </p>
          ) : null}

          <ol className="divide-border divide-y" data-testid="source-chapters">
            {chapters.map((chapter) => (
              <li key={chapter.id}>
                <Link
                  to={`/source/${slug}/${chapter.id}`}
                  data-testid={`source-chapter-${chapter.id}`}
                  className="hover:bg-muted/50 group flex items-center gap-4 px-1 py-4 transition-colors"
                >
                  <span className="text-muted-foreground w-8 shrink-0 text-right font-mono text-sm tabular-nums">
                    {typeof chapter.sortOrder === 'number' ? chapter.sortOrder + 1 : '·'}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="group-hover:text-primary block transition-colors">
                      {chapter.title}
                      {passed.has(chapter.id) ? (
                        <span className="text-success ml-2 text-xs font-normal">已读完</span>
                      ) : null}
                    </span>
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
