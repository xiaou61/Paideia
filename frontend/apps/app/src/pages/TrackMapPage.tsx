import { createLearningApi, type TrackStage } from '@paideia/knowledge'
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
import { ArrowLeft, Check, Play } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const learning = createLearningApi(api)

/**
 * 难度标识（WORK-024）。
 *
 * <p>用户要的是"一眼看出哪些适合初学者、哪些要有基础"，所以难度必须**写在关卡格子上**，
 * 而不是藏在设置里。文案用大白话（入门/进阶/深入），不用 beginner/intermediate/advanced。
 */
const BEGINNER = { label: '入门', hint: '不需要任何基础', tone: 'bg-success/10 text-success border-success/30' }
const INTERMEDIATE = { label: '进阶', hint: '需要看过前面的内容', tone: 'bg-info/10 text-info border-info/30' }
const ADVANCED = { label: '深入', hint: '有一定基础再读，会讲实现细节', tone: 'bg-warning/10 text-warning border-warning/30' }

const LEVEL_META: Record<string, typeof BEGINNER> = {
  beginner: BEGINNER,
  intermediate: INTERMEDIATE,
  advanced: ADVANCED,
}

function levelMeta(level: string): typeof BEGINNER {
  return LEVEL_META[level] ?? BEGINNER
}

/**
 * 一条轨道的关卡地图（WORK-024）。
 *
 * <p><b>形态是关卡，不是列表</b>：每一关是一格，格与格之间用连线表达先后；三种状态
 * 一眼可辨——已通关（实心 + 对勾）、当前可进（高亮 + 播放）、被前置锁住（灰 + 锁）。
 * 用户要的就是"闯关/图谱"这种感觉，而不是又一张文章列表。
 *
 * <p>**连线用 CSS 画，不引图库**：依赖图只有"上一关 → 这一关"这一种形状（轨道是有序的），
 * 为它引入 sigma/graphology 是几十 KB 换个直上直下的竖线。
 *
 * <p>章节按服务端给的分组显示；没有章节名的（空串）不显示标题。
 */
export function TrackMapPage() {
  const { slug = '' } = useParams<{ slug: string }>()
  const map = useQuery({
    queryKey: ['learning-track', slug],
    queryFn: () => learning.trackMap(slug),
    enabled: slug !== '',
  })
  // 难度筛选：null = 全部。用户要能"只看入门"或"只看深入"，
  // 而地图本身不因此重排（顺序就是学习路径，筛选只减不加）。
  const [levelFilter, setLevelFilter] = useState<string | null>(null)

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <div className="mb-4">
        <Link
          to="/learn"
          className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 text-sm"
          data-testid="back-to-tracks"
        >
          <ArrowLeft className="size-4" />
          全部轨道
        </Link>
      </div>

      {map.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm">
          <Spinner />
          加载关卡…
        </p>
      ) : null}

      {map.isError ? (
        <Alert variant="destructive">
          <AlertTitle>这条轨道打不开</AlertTitle>
          <AlertDescription>{(map.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {map.isSuccess ? (
        <>
          <PageHeader
            eyebrow={`${map.data.stageCount} 关 · 已通关 ${map.data.passedCount}`}
            title={map.data.name}
            description={map.data.summary ?? '沿着下面的关卡一关一关往下走。'}
          />

          {/* 难度筛选：把"快速入门"与"深入"分开看 */}
          <div className="mb-4 flex flex-wrap items-center gap-2" data-testid="level-filter">
            <span className="text-muted-foreground text-xs">难度</span>
            <button
              type="button"
              data-testid="level-filter-all"
              onClick={() => { setLevelFilter(null) }}
              className={`rounded-md border px-2.5 py-1 text-xs transition-colors ${
                levelFilter === null ? 'border-primary bg-primary/10 text-primary' : 'border-border'
              }`}
            >
              全部 {map.data.stageCount}
            </button>
            {(['beginner', 'intermediate', 'advanced'] as const).map((level) => {
              const total = map.data.chapters
                .flatMap((chapter) => chapter.stages)
                .filter((stage) => stage.level === level).length
              if (total === 0) {
                return null
              }
              return (
                <button
                  key={level}
                  type="button"
                  data-testid={`level-filter-${level}`}
                  onClick={() => { setLevelFilter(level) }}
                  className={`rounded-md border px-2.5 py-1 text-xs transition-colors ${
                    levelFilter === level ? levelMeta(level).tone : 'border-border'
                  }`}
                >
                  {levelMeta(level).label} {total}
                </button>
              )
            })}
          </div>

          <div className="space-y-6" data-testid="track-map">
            {map.data.chapters.map((chapter, chapterIndex) => (
              <section key={`${chapter.chapter}-${chapterIndex}`}>
                {chapter.chapter === '' ? null : (
                  <h2 className="text-muted-foreground mb-3 text-xs font-medium tracking-wider uppercase">
                    {chapter.chapter}
                  </h2>
                )}
                <ol className="space-y-0">
                  {chapter.stages
                    .filter((stage) => levelFilter === null || stage.level === levelFilter)
                    .map((stage, index) => (
                    <li key={stage.entryId} className="relative">
                      {/* 连线：从上一格底部到这一格顶部。第一格不画。 */}
                      {index > 0 ? (
                        <span
                          aria-hidden
                          className="bg-border absolute top-0 left-[19px] h-6 w-px -translate-y-full"
                        />
                      ) : null}
                      <StageCell stage={stage} />
                    </li>
                  ))}
                </ol>
              </section>
            ))}
          </div>
        </>
      ) : null}
    </AppShell>
  )
}

/**
 * 一格关卡。两种状态：已通关（实心 + 对勾）与可开始（高亮 + 播放）。
 *
 * <p><b>没有锁定态</b>：任何一关都能直接点进去。顺序由格子的排列与连线表达，
 * 但不拦人——想跳着看某一关的人不该被挡住。
 */
function StageCell({ stage }: { stage: TrackStage }) {
  const done = stage.status === 'done'

  const icon = done ? <Check className="size-4" /> : <Play className="size-4" />

  const circle = done
    ? 'bg-success text-success-foreground'
    : 'bg-primary text-primary-foreground'

  const body = (
    <>
      <span
        className={`flex size-10 shrink-0 items-center justify-center rounded-full ${circle}`}
        data-testid={`stage-state-${stage.seq}`}
      >
        {icon}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">
            {stage.seq}. {stage.title}
          </span>
          {/* 难度就写在标题旁边：用户要的是"一眼看出哪些适合初学者" */}
          <span
            className={`rounded border px-1.5 py-0.5 text-[10px] leading-none ${levelMeta(stage.level).tone}`}
            data-testid={`stage-level-${stage.seq}`}
            title={levelMeta(stage.level).hint}
          >
            {levelMeta(stage.level).label}
          </span>
        </span>
        <span className="text-muted-foreground mt-0.5 block text-xs">
          {done ? '已通关' : '可以开始'}
        </span>
      </span>
    </>
  )

  return (
    <Link
      to={`/knowledge/entries/${stage.entryId}`}
      className="border-border bg-card hover:border-primary/40 flex items-center gap-3 rounded-lg border px-3 py-3 transition-colors"
      data-testid={`stage-${stage.seq}`}
      data-status={stage.status}
    >
      {body}
    </Link>
  )
}
