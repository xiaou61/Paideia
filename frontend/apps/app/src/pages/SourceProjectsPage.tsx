import { createKnowledgeApi, createLearningApi, fetchAllEntries, plainText } from '@paideia/knowledge'
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
  LoadingRow,
  PageHeader,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Bot, Boxes, Database, FileCode2, GitBranch, Puzzle, type LucideIcon } from 'lucide-react'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { errorMessage } from '@paideia/core'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

/** 顶层父分类的 slug。与内容源总览的 `line_parent` 同一个值，改一处就要改另一处。 */
const SOURCE_ROOT = 'source'

/**
 * 每个项目一个图标。只影响观感，认不出的 slug 落到默认的代码文件图标——
 * 新收一个项目时忘了登记也不会渲染失败。
 */
const PROJECT_ICONS: Record<string, LucideIcon> = {
  'dsh-core': Boxes,
  'mybatis-source': Database,
  pi: Bot,
  'sprig-deepdive': Puzzle,
  'open-source': GitBranch,
}

/**
 * 核心源码解析的入口（WORK-057）：一个项目一张卡。
 *
 * <p>数据来自既有两个读接口：分类树给"有哪些项目"，条目列表给篇数与总览摘要。
 * 进度复用学习记录（`/learning/progress`），与章节页显示的是同一份。
 */
export function SourceProjectsPage() {
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  const root = categories.data?.find((node) => node.slug === SOURCE_ROOT)

  const entries = useQuery({
    queryKey: ['knowledge-entries', root?.id ?? null, '', 'source'],
    queryFn: () => fetchAllEntries(knowledge, { categoryId: root?.id }),
    enabled: root !== undefined,
  })
  const progress = useQuery({
    queryKey: ['learning-progress'],
    queryFn: () => learning.progress(),
    retry: false,
  })

  const passed = useMemo(
    () => new Set((progress.data ?? []).filter((item) => item.status === 'passed').map((item) => item.entryId)),
    [progress.data],
  )

  const projects = useMemo(() => {
    const items = entries.data ?? []
    return (root?.children ?? []).map((project) => {
      const mine = items.filter((item) => item.categoryId === project.id)
      const overview = mine.find((item) => item.sortOrder === 0)
      return {
        project,
        count: mine.length,
        done: mine.filter((item) => passed.has(item.id)).length,
        blurb: overview?.excerpt ? plainText(overview.excerpt) : null,
      }
    })
  }, [root, entries.data, passed])

  // 顶部那条总进度：只有真的读过才画百分比，0% 的时候只报数不画条。
  const total = projects.reduce((sum, item) => sum + item.count, 0)
  const doneTotal = projects.reduce((sum, item) => sum + item.done, 0)

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="核心源码解析"
        description="把真实项目的源码钉在某个版本上，一关只讲一个机制：左边是原文行号，右边是流程图，点流程回到代码。"
        actions={
          total === 0 ? undefined : (
            <span className="text-muted-foreground hidden text-sm sm:inline">
              {projects.length} 个项目 · {total} 关
            </span>
          )
        }
      />

      {/* 没有 `source` 根分类时 `entries` 被禁用，而被禁用的 query 永远停在 isPending——
          不判 root 就会和下面的空态同时出现、且转圈永不消失（2026-11-02 审查抓到）。 */}
      {categories.isPending || (root !== undefined && entries.isPending) ? (
        <LoadingRow label="加载项目…" testId="source-state" />
      ) : null}
      {(categories.isError || entries.isError) && (
        <Alert variant="destructive" data-testid="source-state">
          <AlertTitle>项目加载失败</AlertTitle>
          <AlertDescription>
            {errorMessage(categories.error ?? entries.error)}
          </AlertDescription>
        </Alert>
      )}

      {categories.isSuccess && root === undefined ? (
        <Empty data-testid="source-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileCode2 />
            </EmptyMedia>
            <EmptyTitle>还没有核心源码解析项目</EmptyTitle>
            <EmptyDescription>内容导入后会出现在这里。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {doneTotal > 0 ? (
        <div className="mb-6 flex items-center gap-3" data-testid="source-summary">
          <div
            className="bg-muted h-1.5 w-40 overflow-hidden rounded-full"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={total}
            aria-valuenow={doneTotal}
            aria-label="源码解析总进度"
          >
            <div
              className="bg-primary h-full rounded-full transition-all"
              style={{ width: `${Math.round((doneTotal / total) * 100)}%` }}
            />
          </div>
          <span className="text-muted-foreground text-xs">已读 {doneTotal} / {total} 关</span>
        </div>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2" data-testid="source-projects">
        {projects.map(({ project, count, done, blurb }) => {
          const Icon = PROJECT_ICONS[project.slug] ?? FileCode2
          const finished = count > 0 && done === count
          const started = done > 0 && !finished
          return (
            <Link
              key={project.id}
              to={`/source/${project.slug}`}
              data-testid={`source-project-${project.slug}`}
              data-progress={finished ? 'done' : started ? 'started' : 'idle'}
              className="border-border bg-card hover:border-primary/40 hover:shadow-card focus-ring group animate-fade-up flex h-full flex-col rounded-xl border p-5 transition-all hover:-translate-y-0.5"
            >
              <div className="flex items-start gap-3">
                <span
                  className={`flex size-10 shrink-0 items-center justify-center rounded-lg ${
                    started || finished ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'
                  }`}
                >
                  <Icon className="size-5" />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center justify-between gap-2">
                    <h2 className="group-hover:text-primary min-w-0 truncate text-base font-medium transition-colors">
                      {project.name}
                    </h2>
                    <Badge variant="secondary" className="shrink-0">
                      {count} 关
                    </Badge>
                  </div>
                  <p className="text-muted-foreground mt-1.5 line-clamp-2 text-sm leading-6">
                    {blurb ?? '按顺序读，一关一个机制。'}
                  </p>
                </div>
              </div>

              {started || finished ? (
                <div
                  className="bg-muted mt-4 h-1.5 w-full overflow-hidden rounded-full"
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={count}
                  aria-valuenow={done}
                  aria-label={`${project.name} 进度`}
                >
                  <div
                    className={`h-full rounded-full transition-all ${finished ? 'bg-success' : 'bg-primary'}`}
                    style={{ width: `${Math.round((done / count) * 100)}%` }}
                  />
                </div>
              ) : (
                <div className="mt-4 h-1.5" aria-hidden />
              )}

              <div className="mt-auto flex items-center justify-between gap-3 pt-4 text-xs">
                <span className={finished ? 'text-success' : 'text-muted-foreground'}>
                  {finished ? '已读完' : done === 0 ? '还没开始' : `已读 ${done} / ${count}`}
                </span>
                <span className="text-primary flex items-center gap-1">
                  {finished ? '再翻一遍' : done === 0 ? '从第 1 关开始' : '接着读'}
                  <ArrowRight className="size-3.5 transition-transform group-hover:translate-x-0.5" />
                </span>
              </div>
            </Link>
          )
        })}
      </div>
    </AppShell>
  )
}
