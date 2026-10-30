import { createKnowledgeApi, createLearningApi, plainText } from '@paideia/knowledge'
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
import { FileCode2 } from 'lucide-react'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

/** 顶层父分类的 slug。与内容源总览的 `line_parent` 同一个值，改一处就要改另一处。 */
const SOURCE_ROOT = 'source'

/**
 * 源码精读的入口（WORK-057）：一个项目一张卡。
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
    queryFn: () => knowledge.entries({ categoryId: root?.id, size: 100 }),
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
    const items = entries.data?.items ?? []
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

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="源码精读"
        description="整份源码摆在旁边，一步一步讲；改一个条件，链路真的会变。"
      />

      {categories.isPending || entries.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="source-state">
          <Spinner />
          加载项目…
        </p>
      ) : null}
      {(categories.isError || entries.isError) && (
        <Alert variant="destructive" data-testid="source-state">
          <AlertTitle>项目加载失败</AlertTitle>
          <AlertDescription>
            {((categories.error ?? entries.error) as Error).message}
          </AlertDescription>
        </Alert>
      )}

      {categories.isSuccess && root === undefined ? (
        <Empty data-testid="source-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileCode2 />
            </EmptyMedia>
            <EmptyTitle>还没有源码精读项目</EmptyTitle>
            <EmptyDescription>内容导入后会出现在这里。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2" data-testid="source-projects">
        {projects.map(({ project, count, done, blurb }) => (
          <Link
            key={project.id}
            to={`/source/${project.slug}`}
            data-testid={`source-project-${project.slug}`}
            className="border-border bg-card hover:border-primary/40 group flex flex-col gap-3 rounded-xl border p-5 transition-colors"
          >
            <div className="flex items-start justify-between gap-3">
              <h2 className="group-hover:text-primary text-lg font-medium transition-colors">{project.name}</h2>
              <Badge variant="secondary">{count} 关</Badge>
            </div>
            <p className="text-muted-foreground line-clamp-2 min-h-10 text-sm">
              {blurb ?? '按顺序读，一关一个机制。'}
            </p>
            <p className="text-muted-foreground text-xs">
              {done === 0 ? '还没开始' : `已读 ${done} / ${count}`}
            </p>
          </Link>
        ))}
      </div>
    </AppShell>
  )
}
