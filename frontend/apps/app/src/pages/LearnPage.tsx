import { createLearningApi } from '@paideia/knowledge'
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
import { ArrowRight, BookOpenCheck, Map, Play, Target } from 'lucide-react'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const learning = createLearningApi(api)

/**
 * 从零开始（WORK-024）。
 *
 * <p><b>这不再是知识库</b>：轨道是独立实体，入口、路径与数据都与 `/knowledge` 分开。
 * 用户明确要求"从零开始不要是知识库"——沿着一条轨道一关一关往下走，和"挑一篇文章读"
 * 是两件事，前者需要关卡、顺序与前置依赖，后者只需要分类与列表。
 *
 * <p>这一页只列轨道；点进去才是关卡地图（`/learn/:slug`）。**空轨道也列出来**：
 * 让它显示"内容准备中"比让它消失更像一个产品。
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

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        eyebrow="从零开始"
        title="挑一条轨道，一关一关往下走"
        description="每关一篇长文 + 可运行示例 + 小测。顺序是建议的走法，想先看哪一关都可以，隔天提醒复习。"
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

      {tracks.isSuccess && tracks.data.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid="learn-tracks">
          {tracks.data.map((track) => {
            const percent = track.stageCount === 0
              ? 0
              : Math.round((track.passedCount / track.stageCount) * 100)
            return (
              <Link
                key={track.id}
                to={`/learn/${track.slug}`}
                data-testid={`learn-track-${track.slug}`}
                className="border-border bg-card hover:border-primary/40 flex flex-col rounded-xl border p-5 transition-colors"
              >
                <div className="flex items-start gap-3">
                  <span className="bg-primary/10 text-primary flex size-10 shrink-0 items-center justify-center rounded-lg">
                    <BookOpenCheck className="size-5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <h2 className="text-base font-medium">{track.name}</h2>
                    <p className="text-muted-foreground mt-0.5 text-xs">
                      {track.stageCount} 关 · 已通关 {track.passedCount}
                    </p>
                  </div>
                </div>

                {track.summary === null ? null : (
                  <p className="text-muted-foreground mt-3 line-clamp-2 text-sm">{track.summary}</p>
                )}

                <div
                  className="bg-muted mt-4 h-1.5 w-full overflow-hidden rounded-full"
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={track.stageCount}
                  aria-valuenow={track.passedCount}
                  aria-label={`${track.name} 进度`}
                >
                  <div className="bg-primary h-full rounded-full transition-all" style={{ width: `${percent}%` }} />
                </div>

                <div className="mt-4 flex items-center justify-between gap-2">
                  <span className="text-muted-foreground min-w-0 truncate text-sm">
                    {track.stageCount === 0
                      ? '内容准备中'
                      : track.passedCount === track.stageCount
                        ? '全部通关'
                        : `下一关：${track.nextTitle ?? '—'}`}
                  </span>
                  <ArrowRight className="text-muted-foreground size-4 shrink-0" />
                </div>
              </Link>
            )
          })}
        </div>
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
