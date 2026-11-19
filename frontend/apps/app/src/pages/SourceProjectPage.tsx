import {
  createKnowledgeApi,
  createLearningApi,
  findCategoryBySlug,
  orderChapters,
  plainText,
  stripDuplicateTitle,
  fetchAllEntries,
} from '@paideia/knowledge'
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
  buttonVariants,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, CheckCircle2, ChevronLeft, ChevronRight, FileCode2 } from 'lucide-react'
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
  // 路由参数叫 `:project`；这里改名只是为了下面读起来像"项目 slug"
  const { project: slug = '' } = useParams<{ project: string }>()
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  const root = categories.data?.find((node) => node.slug === 'source')
  const project =
    root === undefined ? undefined : (findCategoryBySlug(root.children, slug) ?? undefined)

  const entries = useQuery({
    queryKey: ['knowledge-entries', project?.id ?? null, '', 'source-project'],
    queryFn: () => fetchAllEntries(knowledge, { categoryId: project?.id }),
    enabled: project !== undefined,
  })
  const progress = useQuery({
    queryKey: ['learning-progress'],
    queryFn: () => learning.progress(),
    retry: false,
  })

  const chapters = useMemo(() => orderChapters(entries.data ?? []), [entries.data])
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

  const done = chapters.filter((chapter) => passed.has(chapter.id)).length
  // "接着读"落在第一个没读完的关上；全读完就回到第一关重读。
  const resume = chapters.find((chapter) => !passed.has(chapter.id)) ?? chapters[0]

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <nav className="text-muted-foreground mb-4 flex items-center gap-1.5 text-sm" aria-label="面包屑">
        <Link
          to="/source"
          className="hover:text-foreground inline-flex items-center gap-1 transition-colors"
          data-testid="back-to-source"
        >
          <ChevronLeft className="size-4" />
          核心源码解析
        </Link>
        <span aria-hidden>/</span>
        <span className="text-foreground truncate">{project?.name ?? slug}</span>
      </nav>

      {categories.isPending ? (
        <LoadingRow label="加载…" testId="source-project-state" />
      ) : null}
      {categories.isError ? (
        <ErrorAlert title="加载失败" error={categories.error} testId="source-project-state" />
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
                : `${chapters.length} 关，按顺序读；行号全部对得上 ${meta.commit.slice(0, 7)} 这个提交。`
            }
            actions={
              resume === undefined ? undefined : (
                <Link
                  to={`/source/${slug}/${resume.id}`}
                  data-testid="source-resume"
                  className={buttonVariants({ size: 'sm' })}
                >
                  {done === 0 ? '从第 1 关开始' : done === chapters.length ? '从头重读' : `继续第 ${resume.sortOrder === 0 ? 1 : (resume.sortOrder ?? 0) + 1} 关`}
                  <ArrowRight className="size-3.5" data-icon="inline-end" />
                </Link>
              )
            }
          />

          <div className="mb-6 flex flex-wrap items-center gap-2" data-testid="source-project-meta">
            <Badge variant="outline">{chapters.length} 关</Badge>
            {meta === null ? null : (
              <>
                <Badge variant="outline" className="font-mono">{meta.repo}</Badge>
                <Badge variant="outline" className="font-mono">@ {meta.ref}</Badge>
                <Badge variant="outline">{meta.license}</Badge>
                <Badge variant="outline" className="font-mono">commit {meta.commit.slice(0, 7)}</Badge>
                <a
                  href={`https://github.com/${meta.repo}/tree/${meta.commit}`}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="text-primary text-xs underline underline-offset-2"
                >
                  查看仓库 ↗
                </a>
              </>
            )}
          </div>

          {done > 0 ? (
            <div className="mb-5 flex items-center gap-3">
              <div
                className="bg-muted h-1.5 w-40 overflow-hidden rounded-full"
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={chapters.length}
                aria-valuenow={done}
                aria-label={`${project.name} 进度`}
              >
                <div
                  className={`h-full rounded-full transition-all ${done === chapters.length ? 'bg-success' : 'bg-primary'}`}
                  style={{ width: `${Math.round((done / chapters.length) * 100)}%` }}
                />
              </div>
              <span className={done === chapters.length ? 'text-success text-xs' : 'text-muted-foreground text-xs'}>
                已读 {done} / {chapters.length} 关
              </span>
            </div>
          ) : null}

          {entries.isPending ? (
            <LoadingRow label="加载章节…" testId="source-project-state" />
          ) : null}

          <ol
            className="border-border bg-card divide-border divide-y overflow-hidden rounded-xl border"
            data-testid="source-chapters"
          >
            {chapters.map((chapter) => (
              <li key={chapter.id}>
                <Link
                  to={`/source/${slug}/${chapter.id}`}
                  data-testid={`source-chapter-${chapter.id}`}
                  className="hover:bg-muted/40 focus-ring group flex items-center gap-4 px-4 py-4 transition-colors"
                >
                  <span className="text-muted-foreground w-8 shrink-0 text-right font-mono text-sm tabular-nums">
                    {typeof chapter.sortOrder === 'number' ? chapter.sortOrder + 1 : '·'}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="group-hover:text-primary min-w-0 truncate transition-colors">
                        {chapter.title}
                      </span>
                      {passed.has(chapter.id) ? (
                        <span className="text-success flex shrink-0 items-center gap-1 text-xs">
                          <CheckCircle2 className="size-3.5" />
                          已读完
                        </span>
                      ) : null}
                    </span>
                    {chapter.excerpt === null ? null : (
                      <span className="text-muted-foreground mt-1 line-clamp-1 block text-sm">
                        {plainText(chapter.excerpt)}
                      </span>
                    )}
                  </span>
                  <ChevronRight className="text-muted-foreground group-hover:text-foreground size-4 shrink-0 transition-colors" />
                </Link>
              </li>
            ))}
          </ol>
        </>
      )}
    </AppShell>
  )
}
