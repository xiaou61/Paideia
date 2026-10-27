import { createLearningApi, type TrackSummary } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Card,
  CardContent,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import {
  ArrowRight,
  Binary,
  BookOpenCheck,
  Bot,
  Boxes,
  Braces,
  Brain,
  Bug,
  CheckCircle2,
  Coffee,
  Cpu,
  Code2,
  Database,
  FileCode2,
  FileSearch,
  FolderCode,
  GitBranch,
  Layers,
  Leaf,
  Map,
  Monitor,
  Network,
  Play,
  Puzzle,
  Table2,
  Target,
  Terminal,
  Workflow,
} from 'lucide-react'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const learning = createLearningApi(api)

/**
 * 每条轨道自己的图标。
 *
 * <p>**这是这一页最重要的一件事**：原来 17 条理论轨道全用同一个 `BookOpenCheck`，
 * 于是 12 张卡上的图标是 12 次重复的同一个装饰——不传递任何信息，只让页面看起来
 * "每张卡都长一样"。按主题给不同的字形之后，扫一眼就能定位到想找的那条。
 *
 * <p>按 slug 查而不是按 kind：kind 只有 reading/course 两种，区分不出主题。
 * 新加的轨道查不到就用兜底图标，不会因为漏配而渲染不出来。
 */
const TRACK_ICONS: Record<string, typeof BookOpenCheck> = {
  git: GitBranch,
  'python-from-zero': Braces,
  'java-from-zero': Coffee,
  'mysql-from-zero': Database,
  spring: Leaf,
  jvm: Cpu,
  'source-reading': FileSearch,
  'code-reading': FileCode2,
  'java-project': FolderCode,
  'java-debug': Bug,
  'sql-practice': Table2,
  redis: Layers,
  'network-basics': Network,
  linux: Terminal,
  concurrency: Workflow,
  os: Monitor,
  patterns: Puzzle,
  algorithms: Binary,
  ai: Brain,
  'system-design': Boxes,
  agents: Bot,
}

/**
 * 从零开始（WORK-024 / WORK-038）。
 *
 * <p><b>这不再是知识库</b>：轨道是独立实体，入口、路径与数据都与 `/knowledge` 分开。
 * 用户明确要求"从零开始不要是知识库"——沿着一条轨道一关一关往下走，和"挑一篇文章读"
 * 是两件事，前者需要关卡、顺序与前置依赖，后者只需要分类与列表。
 *
 * <p>**分成理论栏与实战栏**（WORK-038）：理论读完能解释，实战做完能验收。两栏只是分组，
 * 不做任何门禁——用户可以从任何一边进。空轨道也列出来：让它显示"内容准备中"
 * 比让它消失更像一个产品。
 */
export function LearnPage() {
  const tracks = useQuery({
    queryKey: ['learning-tracks'],
    queryFn: () => learning.tracks(),
  })

  /**
   * 「继续上次」选哪条：优先**已经开始但没走完**的（有进度说明用户在意它），
   * 否则取第一条还没开始的。已全部通关的不进继续条——那是在催人重刷。
   */
  const next = useMemo(() => {
    const list = tracks.data ?? []
    const started = list.find(
      (track) => track.passedCount > 0 && track.passedCount < track.stageCount && track.nextTitle !== null,
    )
    if (started !== undefined) {
      return started
    }
    return list.find((track) => track.nextTitle !== null) ?? null
  }, [tracks.data])

  const theory = useMemo(
    () => (tracks.data ?? []).filter((track) => track.mode !== 'practice'),
    [tracks.data],
  )
  const practice = useMemo(
    () => (tracks.data ?? []).filter((track) => track.mode === 'practice'),
    [tracks.data],
  )

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="挑一条轨道，一关一关往下走"
        description="理论读完能解释，实战做完能验收。顺序是建议的走法，想先看哪一关都可以，隔天提醒复习。"
      />

      {tracks.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="learn-state">
          <Spinner />
          加载学习轨道…
        </p>
      ) : null}

      {tracks.isError ? (
        <Alert variant="destructive" data-testid="learn-state">
          <AlertTitle>学习轨道加载失败</AlertTitle>
          <AlertDescription>{(tracks.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {tracks.isSuccess && tracks.data.length === 0 ? (
        <Alert data-testid="learn-empty">
          <AlertTitle>还没有学习轨道</AlertTitle>
          <AlertDescription>内容导入后这里会出现。</AlertDescription>
        </Alert>
      ) : null}

      {/* 「继续上次」：4 条轨道里真正该先点的只有一条。让用户自己在卡片里找"下一关"
          等于每进来一次都要重新读一遍全部卡片。 */}
      {tracks.isSuccess && next !== null ? (
        <Link
          to={`/learn/${next.slug}`}
          data-testid="learn-continue"
          className="border-primary/30 bg-primary/[0.04] hover:border-primary/60 group mb-6 flex items-center gap-4 rounded-xl border p-4 transition-colors sm:p-5"
        >
          <span className="bg-primary/10 text-primary flex size-11 shrink-0 items-center justify-center rounded-lg">
            <Play className="size-5" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="text-muted-foreground block text-xs">继续上次</span>
            <span className="mt-0.5 block truncate text-base font-medium">{next.name}</span>
            <span className="text-muted-foreground mt-0.5 block truncate text-sm">
              {next.passedCount === 0
                ? `从这里开始：${next.nextTitle ?? '—'}`
                : `已过 ${next.passedCount}/${next.stageCount} 关 · 下一关：${next.nextTitle ?? '—'}`}
            </span>
          </span>
          <ArrowRight className="text-muted-foreground group-hover:text-primary size-5 shrink-0 transition-colors" />
        </Link>
      ) : null}

      {tracks.isSuccess && theory.length > 0 ? (
        <TrackSection
          testId="learn-theory"
          title="理论"
          hint="读完能解释、能判断、能做对选择题。"
          tracks={theory}
        />
      ) : null}

      {tracks.isSuccess ? (
        <TrackSection
          testId="learn-practice"
          title="实战"
          hint="做完有一个能验收的东西：跑出正确结果的查询、通过的检查、能演示的功能。"
          tracks={practice}
          emptyText="实战线正在准备中——先把理论读完，动手的内容会陆续放进来。"
        />
      ) : null}

      {/* 训练营（WORK-029）：它是"还没有任何学习记录的人"的入口，
          所以和轨道并列出现在这一页，而不是只藏在简历页里。 */}
      <Link
        to="/camp"
        data-testid="learn-camp-entry"
        className="border-border bg-card hover:border-primary/40 mt-6 flex items-center gap-4 rounded-xl border p-5 transition-colors"
      >
        <span className="bg-primary/10 text-primary flex size-10 shrink-0 items-center justify-center rounded-lg">
          <Target className="size-5" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-base font-medium">用简历开一个 AI 训练营</span>
          <span className="text-muted-foreground mt-0.5 block text-sm">
            先做诊断题，按薄弱点排每天学什么；走完再复测一次，两次并排看变化。
          </span>
        </span>
        <ArrowRight className="text-muted-foreground size-4 shrink-0" />
      </Link>

      <Card className="mt-6">
        <CardContent className="flex items-start gap-3 py-4">
          <Map className="text-muted-foreground mt-0.5 size-4 shrink-0" />
          <p className="text-muted-foreground text-sm">
            想看自己的学习痕迹？<Link to="/graph" className="text-primary underline-offset-2 hover:underline">
              知识图谱
            </Link>
            会按概念显示掌握度与待复习项。
          </p>
        </CardContent>
      </Card>
    </AppShell>
  )
}

/** 一栏轨道：理论或实战。空的时候给明确空状态，不留一片空白。 */
function TrackSection({
  testId,
  title,
  hint,
  tracks,
  emptyText,
}: {
  testId: string
  title: string
  hint: string
  tracks: TrackSummary[]
  emptyText?: string
}) {
  const passed = tracks.reduce((sum, track) => sum + track.passedCount, 0)
  const stages = tracks.reduce((sum, track) => sum + track.stageCount, 0)
  return (
    <section className="mt-2 mb-8" data-testid={testId}>
      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 className="text-lg font-semibold">{title}</h2>
        <span className="text-muted-foreground text-sm" data-testid={`${testId}-count`}>
          {tracks.length} 条 · {stages} 关 · 已通关 {passed}
        </span>
      </div>
      <p className="text-muted-foreground mb-4 text-sm">{hint}</p>
      {tracks.length === 0 ? (
        <p className="border-border text-muted-foreground rounded-xl border border-dashed p-5 text-sm"
           data-testid={`${testId}-empty`}>
          {emptyText ?? '还没有内容。'}
        </p>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {tracks.map((track) => (
            <TrackCard key={track.id} track={track} />
          ))}
        </div>
      )}
    </section>
  )
}

/**
 * 一张轨道卡。
 *
 * <p>三条与"千篇一律"直接相关的决定：
 * <ol>
 *   <li>**图标按轨道区分**（见 `TRACK_ICONS`），不再所有理论轨道共用一个书图标。</li>
 *   <li>**没开始的轨道不画进度条**。0% 的灰条读起来像"这里有个坏掉的元素"，
 *       而且 17 条里 15 条都是 0，于是整页变成 15 条一模一样的灰线。没进度就
 *       换成"8 关待开始"的文案，有进度才画条——进度条从此是一个有信息量的信号。</li>
 *   <li>**正在学的那条给状态徽章**（已通关 / 进行中 / 待开始）。一屏 12 张卡里
 *       只有一条是"你正在做的"，它必须自己站出来。</li>
 * </ol>
 */
function TrackCard({ track }: { track: TrackSummary }) {
  const percent = track.stageCount === 0
    ? 0
    : Math.round((track.passedCount / track.stageCount) * 100)
  const Icon = TRACK_ICONS[track.slug] ?? (track.mode === 'practice' ? Code2 : BookOpenCheck)
  const done = track.stageCount > 0 && track.passedCount === track.stageCount
  const started = track.passedCount > 0 && !done
  return (
    <Link
      to={`/learn/${track.slug}`}
      data-testid={`learn-track-${track.slug}`}
      data-mode={track.mode}
      data-progress={done ? 'done' : started ? 'started' : 'idle'}
      className="border-border bg-card hover:border-primary/40 hover:shadow-card group flex h-full flex-col rounded-xl border p-5 transition-all"
    >
      <div className="flex items-start gap-3">
        <span
          className={`flex size-10 shrink-0 items-center justify-center rounded-lg ${
            // 有进度的用品牌色底，没开始的用中性底：颜色本身就是"这条和你有关"
            started || done ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'
          }`}
        >
          <Icon className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="min-w-0 truncate text-base font-medium">{track.name}</h3>
            {done ? (
              <span className="text-success flex shrink-0 items-center gap-1 text-xs">
                <CheckCircle2 className="size-3.5" />
                已通关
              </span>
            ) : started ? (
              <span className="text-primary shrink-0 text-xs">进行中</span>
            ) : null}
          </div>
          <p className="text-muted-foreground mt-0.5 text-xs">
            {track.stageCount === 0
              ? '内容准备中'
              : started || done
                ? `${track.stageCount} 关 · 已通关 ${track.passedCount}`
                : `${track.stageCount} 关待开始`}
          </p>
        </div>
      </div>

      {track.summary === null ? null : (
        <p className="text-muted-foreground mt-3 line-clamp-2 text-sm">{track.summary}</p>
      )}

      {/* 只有真开始过才画进度条：0% 的灰条在 15 张卡上重复，等于没有进度信息 */}
      {started || done ? (
        <div
          className="bg-muted mt-4 h-1.5 w-full overflow-hidden rounded-full"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={track.stageCount}
          aria-valuenow={track.passedCount}
          aria-label={`${track.name} 进度`}
        >
          <div
            className={`h-full rounded-full transition-all ${done ? 'bg-success' : 'bg-primary'}`}
            style={{ width: `${percent}%` }}
          />
        </div>
      ) : (
        <div className="mt-4 h-1.5" aria-hidden />
      )}

      {/* mt-auto 把"下一关"压到卡片底部：摘要行数不同时，同排卡片的这一行才对得齐 */}
      <div className="mt-auto flex items-center justify-between gap-2 pt-4">
        <span className="text-muted-foreground min-w-0 truncate text-sm">
          {track.stageCount === 0
            ? '内容准备中'
            : done
              ? '全部通关'
              : `下一关：${track.nextTitle ?? '—'}`}
        </span>
        <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
      </div>
    </Link>
  )
}
