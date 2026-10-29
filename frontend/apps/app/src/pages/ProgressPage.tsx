import { useAssistantContext } from '@paideia/assistant'
import {
  createLearningApi,
  type ConceptNode,
  type EntryProgressView,
  type TrackSummary,
} from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { Brain, CalendarClock, ListChecks, Sparkles, TriangleAlert } from 'lucide-react'
import { useEffect, useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const learning = createLearningApi(api)

/** 每块最多展示几条：这是"记录"不是"报表"，多了反而看不出该做什么。 */
const LIMIT = 5

/**
 * 学习记录（WORK-053）。
 *
 * <p><b>这一页的定位</b>：把"我学到哪、卡在哪、该复习什么"摆出来，并且每个关键位置都能把 AI
 * 叫进来——它不是第二个 `/learn`（地图还在那边），也不重算任何数字：四块全部来自既有接口，
 * 前端只做拼接与截断。
 *
 * <p><b>为什么入口只有三个</b>：按钮越多越像演示。这三个是"用户当场就知道要问什么"的：
 * 卡关处、复习出题、解释推荐理由。
 */
export function ProgressPage() {
  const { setPageContext, ask } = useAssistantContext()

  const tracks = useQuery({ queryKey: ['learning-tracks'], queryFn: () => learning.tracks() })
  const progress = useQuery({ queryKey: ['learning-progress'], queryFn: () => learning.progress() })
  const reviews = useQuery({ queryKey: ['learning-reviews'], queryFn: () => learning.reviews() })
  const graph = useQuery({ queryKey: ['learning-graph'], queryFn: () => learning.graph() })

  // 四块摘要：既渲染在页面上，也作为助手上下文。**同一份来源**，不各算一次。
  const lines = useMemo(() => summarizeLines(tracks.data ?? []), [tracks.data])
  const failing = useMemo(() => summarizeFailing(progress.data ?? []), [progress.data])
  const dueReviews = useMemo(
    () => (reviews.data?.items ?? []).slice(0, LIMIT).map((item) => item.title),
    [reviews.data],
  )
  const weakConcepts = useMemo(() => weakestConcepts(graph.data?.concepts ?? []), [graph.data])

  // 页面上下文：离开时清掉，与 entryId 那套分开清理。
  useEffect(() => {
    setPageContext({
      kind: 'study-log',
      lines: lines.join('；'),
      failing: failing.map((item) => item.label).join('；'),
      dueReviews: dueReviews.join('、'),
      weakConcepts: weakConcepts.map((concept) => `${concept.name} ${concept.score.toFixed(2)}`).join('；'),
    })
    return () => setPageContext(null)
  }, [setPageContext, lines, failing, dueReviews, weakConcepts])

  const askFailing = (label: string) => {
    ask(`我在「${label}」上卡住了。我已经把学习记录给你了：先看我卡住的地方，再告诉我下一步该做什么——不要泛泛而谈。`)
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="学习记录"
        description="你学到哪、卡在哪、该复习什么。每一块都可以叫助手一起看——它会先问清楚，再给具体建议。"
      />

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Card data-testid="progress-lines">
          <CardContent className="pt-6">
            <SectionTitle icon={<ListChecks className="size-4" />} title="各线进度" hint={`${lines.length} 条线在走`} />
            {tracks.isPending ? <Loading /> : null}
            {tracks.isError ? <Failed message="进度加载失败" /> : null}
            {tracks.isSuccess && lines.length === 0 ? (
              <EmptyHint title="还没有开始任何一条线" description="去「从零开始」选一条，进度会出现在这里。" />
            ) : null}
            <ul className="mt-3 space-y-2" data-testid="progress-line-list">
              {lines.map((line) => (
                <li key={line.slug} className="flex items-center justify-between gap-3 text-sm">
                  <span className="min-w-0 truncate">{line.name}</span>
                  <span className="text-muted-foreground shrink-0 tabular-nums">
                    {line.passed}/{line.stage}
                  </span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card data-testid="progress-failing">
          <CardContent className="pt-6">
            <SectionTitle
              icon={<TriangleAlert className="size-4" />}
              title="卡住的关卡"
              hint="最近没拿满分的"
            />
            {progress.isPending ? <Loading /> : null}
            {progress.isError ? <Failed message="练习记录加载失败" /> : null}
            {progress.isSuccess && failing.length === 0 ? (
              <EmptyHint title="没有卡住的地方" description="做过的关卡都拿到满分了。" />
            ) : null}
            <ul className="mt-3 space-y-2">
              {failing.map((item) => (
                <li key={item.entryId} className="flex items-center justify-between gap-3 text-sm">
                  <span className="min-w-0 truncate">{item.title}</span>
                  <div className="flex shrink-0 items-center gap-2">
                    <Badge variant="secondary">{item.label}</Badge>
                    <Button
                      size="sm"
                      variant="outline"
                      data-testid={`progress-ask-failing-${item.entryId}`}
                      onClick={() => askFailing(item.title)}
                    >
                      问问
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card data-testid="progress-reviews">
          <CardContent className="pt-6">
            <SectionTitle
              icon={<CalendarClock className="size-4" />}
              title="到期复习"
              hint={reviews.data === undefined ? '' : `共 ${reviews.data.total} 条`}
            />
            {reviews.isPending ? <Loading /> : null}
            {reviews.isError ? <Failed message="复习队列加载失败" /> : null}
            {reviews.isSuccess && dueReviews.length === 0 ? (
              <EmptyHint title="今天没有到期的" description="复习按间隔重复排期，时候没到就不会出现。" />
            ) : null}
            {dueReviews.length > 0 ? (
              <>
                <ul className="mt-3 space-y-2 text-sm">
                  {dueReviews.map((title) => (
                    <li key={title} className="truncate">
                      {title}
                    </li>
                  ))}
                </ul>
                <Button
                  className="mt-3"
                  size="sm"
                  data-testid="progress-ask-review"
                  onClick={() =>
                    ask('按我的学习记录，从到期复习里挑 3 个最该先过的，各出一题考我；先别给答案。')
                  }
                >
                  <Sparkles className="size-4" />
                  让 AI 出复习题
                </Button>
              </>
            ) : null}
          </CardContent>
        </Card>

        <Card data-testid="progress-weak">
          <CardContent className="pt-6">
            <SectionTitle icon={<Brain className="size-4" />} title="最薄弱的概念" hint="掌握度最低" />
            {graph.isPending ? <Loading /> : null}
            {graph.isError ? <Failed message="掌握度加载失败" /> : null}
            {graph.isSuccess && weakConcepts.length === 0 ? (
              <EmptyHint title="还没有可评估的概念" description="做过练习与小测之后，这里会给出最该补的几个。" />
            ) : null}
            <ul className="mt-3 space-y-2 text-sm">
              {weakConcepts.map((concept) => (
                <li key={concept.id} className="flex items-center justify-between gap-3">
                  <span className="min-w-0 truncate">{concept.name}</span>
                  <span className="text-muted-foreground shrink-0 tabular-nums">
                    {concept.score.toFixed(2)}
                  </span>
                </li>
              ))}
            </ul>
            <Button
              className="mt-3"
              size="sm"
              variant="outline"
              data-testid="progress-ask-recommend"
              onClick={() =>
                ask('看我的学习记录：为什么现在推这几条线？如果我想换目标，该先动哪一条？')
              }
            >
              问为什么这样推荐
            </Button>
          </CardContent>
        </Card>
      </div>

      <p className="text-muted-foreground mt-6 text-xs">
        助手会读这一页上的四块记录。对话会消耗积分，问得越细花得越多。
        <Link className="ml-1 underline" to="/learn">
          去从零开始
        </Link>
      </p>
    </AppShell>
  )
}

function summarizeLines(tracks: TrackSummary[]): { slug: string; name: string; passed: number; stage: number }[] {
  return [...tracks]
    .filter((track) => track.stageCount > 0)
    .sort((a, b) => b.passedCount - a.passedCount || a.name.localeCompare(b.name))
    .slice(0, LIMIT)
    .map((track) => ({ slug: track.slug, name: track.name, passed: track.passedCount, stage: track.stageCount }))
}

/** 未满分的块才算"卡住"：满分说明这块过了，不该占版面。 */
function summarizeFailing(progress: EntryProgressView[]): { entryId: number; title: string; label: string }[] {
  const rows: { entryId: number; title: string; label: string }[] = []
  for (const entry of progress) {
    if (entry.status === 'passed') {
      continue
    }
    const blocks = [...Object.values(entry.challenges), ...Object.values(entry.quizzes)]
    const wrong = blocks.filter((block) => block.score < block.total)
    if (wrong.length === 0) {
      continue
    }
    rows.push({
      entryId: entry.entryId,
      title: entry.title,
      label: `${wrong.reduce((sum, block) => sum + (block.total - block.score), 0)} 处未满分`,
    })
  }
  return rows.slice(0, LIMIT)
}

function weakestConcepts(concepts: ConceptNode[]): ConceptNode[] {
  return [...concepts]
    .filter((concept) => concept.evidence.scoredEntries > 0 || concept.evidence.viewed > 0)
    .sort((a, b) => a.score - b.score)
    .slice(0, LIMIT)
}

function SectionTitle({ icon, title, hint }: { icon: React.ReactNode; title: string; hint: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <h2 className="flex items-center gap-2 text-base font-medium">
        {icon}
        {title}
      </h2>
      {hint === '' ? null : <span className="text-muted-foreground text-xs">{hint}</span>}
    </div>
  )
}

function Loading() {
  return (
    <p className="text-muted-foreground mt-3 flex items-center gap-2 text-sm">
      <Spinner />
      加载中…
    </p>
  )
}

function Failed({ message }: { message: string }) {
  return (
    <Alert className="mt-3" variant="destructive">
      <AlertTitle>{message}</AlertTitle>
      <AlertDescription>其余部分照常显示，刷新可重试。</AlertDescription>
    </Alert>
  )
}

function EmptyHint({ title, description }: { title: string; description: string }) {
  return (
    <Empty className="mt-3">
      <EmptyHeader>
        <EmptyTitle>{title}</EmptyTitle>
        <EmptyDescription>{description}</EmptyDescription>
      </EmptyHeader>
    </Empty>
  )
}
