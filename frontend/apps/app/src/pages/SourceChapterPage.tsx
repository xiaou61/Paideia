import { useAssistantContext } from '@paideia/assistant'
import {
  MarkdownBody,
  createKnowledgeApi,
  createLearningApi,
  createServerProgressPort,
  findCategoryBySlug,
  orderChapters,
  stripDuplicateTitle,
} from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight } from 'lucide-react'
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
  const { ask } = useAssistantContext()

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
    queryFn: () => knowledge.entries({ categoryId: entry.data?.categoryId, size: 100 }),
    enabled: entry.data?.categoryId !== null && entry.data?.categoryId !== undefined,
  })
  const chapters = useMemo(() => orderChapters(chaptersQuery.data?.items ?? []), [chaptersQuery.data])
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
      <Link
        to={`/source/${project}`}
        className="text-muted-foreground hover:text-foreground -my-1 mb-4 inline-flex items-center gap-1 py-1 text-sm transition-colors"
        data-testid="back-to-project"
      >
        <ChevronLeft className="size-4" />
        {projectNode?.name ?? '回到项目'}
      </Link>

      {entry.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="source-chapter-state">
          <Spinner />
          加载章节…
        </p>
      ) : null}
      {entry.isError ? (
        <Alert variant="destructive" data-testid="source-chapter-state">
          <AlertTitle>章节加载失败</AlertTitle>
          <AlertDescription>{(entry.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {entry.isSuccess ? (
        <>
          <PageHeader
            title={entry.data.title}
            description={
              meta === null
                ? undefined
                : `${meta.repo} @ ${meta.ref} · ${meta.license} · 第 ${index + 1} 关 / 共 ${chapters.length} 关`
            }
          />
          <MarkdownBody
            markdown={body}
            mediaBaseUrl={API_BASE_URL}
            entryId={entry.data.id}
            entryTitle={entry.data.title}
            onAskAssistant={ask}
            progress={progressPort}
          />

          <nav className="border-border mt-10 flex items-center justify-between gap-4 border-t pt-6">
            {previous === undefined ? (
              <span />
            ) : (
              <Link
                to={`/source/${project}/${previous.id}`}
                data-testid="source-prev"
                className="text-muted-foreground hover:text-foreground flex items-center gap-1 text-sm transition-colors"
              >
                <ChevronLeft className="size-4" />
                上一关 · {previous.title}
              </Link>
            )}
            {next === undefined ? (
              <span />
            ) : (
              <Link
                to={`/source/${project}/${next.id}`}
                data-testid="source-next"
                className="text-muted-foreground hover:text-foreground flex items-center gap-1 text-right text-sm transition-colors"
              >
                下一关 · {next.title}
                <ChevronRight className="size-4" />
              </Link>
            )}
          </nav>
        </>
      ) : null}
    </AppShell>
  )
}
