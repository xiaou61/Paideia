import {
  MarkdownBody,
  createKnowledgeApi,
  createLearningApi,
  createServerProgressPort,
  findCategoryBySlug,
  orderChapters,
  stripDuplicateTitle,
  fetchAllEntries,
} from '@paideia/knowledge'
import {
  AppShell,
  Badge,
  ErrorAlert,
  LoadingRow,
  PageHeader,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, ChevronLeft } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { API_BASE_URL } from '../env'
import { readSourceMeta } from './source-meta'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

/**
 * 一章的阅读页（WORK-057）。
 *
 * <p>不复用 `/knowledge/entries/:id`：那个页面是"知识库条目"的语境（目录、自评、练习册），
 * 会把读者带回去。这里只组装正文 + 项目元信息 + 上一关/下一关；正文里的交互全部由
 * `source-walk` 块自己完成。
 */
export function SourceChapterPage() {
  const { project = '', entryId: rawId } = useParams<{ project: string; entryId: string }>()
  const entryId = Number(rawId)
  const valid = Number.isFinite(entryId) && entryId > 0

  const entry = useQuery({
    queryKey: ['knowledge-entry', entryId],
    queryFn: () => knowledge.entry(entryId),
    enabled: valid,
  })
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  const root = categories.data?.find((node) => node.slug === 'source')
  const projectNode = root === undefined ? undefined : (findCategoryBySlug(root.children, project) ?? undefined)

  const chaptersQuery = useQuery({
    queryKey: ['knowledge-entries', entry.data?.categoryId ?? null, '', 'source-chapters'],
    queryFn: () => fetchAllEntries(knowledge, { categoryId: entry.data?.categoryId }),
    enabled: entry.data?.categoryId !== null && entry.data?.categoryId !== undefined,
  })
  const chapters = useMemo(() => orderChapters(chaptersQuery.data ?? []), [chaptersQuery.data])
  const index = chapters.findIndex((chapter) => chapter.id === entryId)

  const overview = chapters.find((chapter) => chapter.sortOrder === 0)
  const overviewEntry = useQuery({
    queryKey: ['knowledge-entry', overview?.id ?? null],
    queryFn: () => knowledge.entry(overview?.id ?? 0),
    enabled: overview !== undefined && overview.id !== entryId,
    retry: false,
  })
  const metaSource = overview?.id === entryId ? entry.data?.body : overviewEntry.data?.body
  const meta = metaSource === undefined ? null : readSourceMeta(metaSource)

  const serverProgress = useQuery({
    queryKey: ['learning-entry', entryId],
    queryFn: () => learning.entry(entryId),
    enabled: valid,
    retry: false,
  })
  const progressPort = useMemo(() => {
    if (!valid || serverProgress.isPending || entry.data === undefined) {
      return undefined
    }
    return createServerProgressPort({
      entryId,
      title: entry.data.title,
      initial: serverProgress.data ?? null,
      report: (body) => learning.report(entryId, body),
      clear: () => learning.reset(entryId),
    })
  }, [valid, serverProgress.isPending, serverProgress.data, entry.data, entryId])

  const body = useMemo(
    () => stripDuplicateTitle(entry.data?.body ?? '', entry.data?.title),
    [entry.data?.body, entry.data?.title],
  )
  const previous = index > 0 ? chapters[index - 1] : undefined
  const next = index >= 0 && index < chapters.length - 1 ? chapters[index + 1] : undefined

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <nav className="text-muted-foreground mb-4 flex items-center gap-1.5 text-sm" aria-label="面包屑">
        <Link
          to="/source"
          className="hover:text-foreground inline-flex items-center gap-1 transition-colors"
        >
          <ChevronLeft className="size-4" />
          核心源码解析
        </Link>
        <span aria-hidden>/</span>
        <Link
          to={`/source/${project}`}
          className="hover:text-foreground truncate transition-colors"
          data-testid="back-to-project"
        >
          {projectNode?.name ?? '回到项目'}
        </Link>
        {index >= 0 ? (
          <>
            <span aria-hidden>/</span>
            <span className="text-foreground shrink-0">第 {index + 1} 关</span>
          </>
        ) : null}
      </nav>

      {entry.isPending ? (
        <LoadingRow label="加载章节…" testId="source-chapter-state" />
      ) : null}
      {entry.isError ? (
        <ErrorAlert title="章节加载失败" error={entry.error} testId="source-chapter-state" />
      ) : null}

      {entry.isSuccess ? (
        <>
          <PageHeader
            title={entry.data.title}
            description={
              <span className="flex flex-wrap items-center gap-2">
                {index >= 0 ? (
                  <Badge variant="secondary">
                    第 {index + 1} 关 / 共 {chapters.length} 关
                  </Badge>
                ) : null}
                {meta === null ? null : (
                  <>
                    <Badge variant="outline" className="font-mono">{meta.repo}</Badge>
                    <Badge variant="outline" className="font-mono">@ {meta.ref}</Badge>
                    <Badge variant="outline">{meta.license}</Badge>
                  </>
                )}
              </span>
            }
          />
          <MarkdownBody
            markdown={body}
            mediaBaseUrl={API_BASE_URL}
            entryId={entry.data.id}
            entryTitle={entry.data.title}
            progress={progressPort}
          />

          <nav className="border-border mt-10 grid gap-3 border-t pt-6 sm:grid-cols-2">
            {previous === undefined ? (
              <span className="hidden sm:block" aria-hidden />
            ) : (
              <Link
                to={`/source/${project}/${previous.id}`}
                data-testid="source-prev"
                className="border-border bg-card hover:border-primary/40 focus-ring group flex items-center gap-3 rounded-xl border p-4 transition-colors"
              >
                <ArrowLeft className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
                <span className="min-w-0">
                  <span className="text-muted-foreground block text-xs">上一关</span>
                  <span className="group-hover:text-primary block truncate text-sm transition-colors">
                    {previous.title}
                  </span>
                </span>
              </Link>
            )}
            {next === undefined ? (
              <span className="hidden sm:block" aria-hidden />
            ) : (
              <Link
                to={`/source/${project}/${next.id}`}
                data-testid="source-next"
                className="border-border bg-card hover:border-primary/40 focus-ring group flex items-center justify-end gap-3 rounded-xl border p-4 text-right transition-colors sm:col-start-2"
              >
                <span className="min-w-0">
                  <span className="text-muted-foreground block text-xs">下一关</span>
                  <span className="group-hover:text-primary block truncate text-sm transition-colors">
                    {next.title}
                  </span>
                </span>
                <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
              </Link>
            )}
          </nav>
        </>
      ) : null}
    </AppShell>
  )
}
