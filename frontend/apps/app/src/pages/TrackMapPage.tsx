import { Attach } from '@paideia/assistant'
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
import { ArrowLeft, Check, Milestone, Play } from 'lucide-react'
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
 * <p><b>形态是"路径"，不是列表</b>：一条贯穿全页的脊线，关卡节点落在脊线上，
 * 章节名是脊线上的里程碑。原来每关是一张独立的横条卡，26 关就是 26 张一模一样的条，
 * 看不出"这是一条路"。
 *
 * <p><b>为什么不做真 3D</b>（用户 2026-10-27 问能不能做 3D）：路径本身是线性的，
 * 3D 不增加任何信息，只会让后面的关卡被前面的挡住；26 个元素在 3D 空间里扫描成本
 * 反而上升；`transform: perspective` 会破坏焦点环与命中区（键盘与触屏都受影响）；
 * 窄屏下更糟。所以这里做的是**纵深**而不是透视：节点落在脊线上、已通关的实心、
 * 当前那关抬起来（阴影 + 描边），章节里程碑比关卡高一档。
 *
 * <p><b>没有锁定态</b>：任何一关都能直接点进去。顺序由脊线与编号表达，但不拦人。
 */
export function TrackMapPage() {
  const { slug = '' } = useParams<{ slug: string }>()
  const map = useQuery({
    queryKey: ['learning-track', slug],
    queryFn: () => learning.trackMap(slug),
    enabled: slug !== '',
  })
  // 配对提示要显示的是轨道的**名字**而不是 slug，所以顺带取一次列表；
  // `/learn` 已经取过同一份数据，正常路径下这是缓存命中。
  const theorySlug = map.data?.theoryTrackSlug ?? null
  const tracks = useQuery({
    queryKey: ['learning-tracks'],
    queryFn: () => learning.tracks(),
    enabled: theorySlug !== null,
  })
  const theoryName = tracks.data?.find((track) => track.slug === theorySlug)?.name ?? theorySlug
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
            title={map.data.name}
            description={
              // 进度数字是有用信息，但它属于"读到哪了"而不是"这一页叫什么"，
              // 所以并进导语，不占标题上方那一行。
              <>
                <span className="text-foreground font-medium">
                  {map.data.mode === 'practice' ? '实战' : '理论'} · {map.data.stageCount} 关 · 已通关{' '}
                  {map.data.passedCount}
                </span>
                <span className="mt-1 block">{map.data.summary ?? '沿着下面的关卡一关一关往下走。'}</span>
              </>
            }
          />

          {/* 配对提示：实战轨道指向它假定的理论轨道。只是建议，不限制进入（WORK-038）。 */}
          {map.data.theoryTrackSlug === null ? null : (
            <p className="text-muted-foreground mb-4 text-sm" data-testid="learn-theory-hint">
              建议先看{' '}
              <Link
                to={`/learn/${map.data.theoryTrackSlug}`}
                className="text-primary underline-offset-2 hover:underline"
              >
                {theoryName}
              </Link>
              ，不过想直接动手也可以。
            </p>
          )}

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

          {/*
            路径容器：`relative` 是脊线的定位基准。脊线是一条绝对定位的竖线，
            节点用负 margin 压在它上面，于是"关卡在路上"这件事是画出来的，不是靠文案说的。
            筛选后脊线仍然连续——它画在容器上，不画在每一格上。
          */}
          <div className="relative" data-testid="track-map">
            {/*
              脊线宽 2px 而不是 1px：1px 在浅灰底上几乎看不见，而节点带 4px 的背景色外圈
              （用来"切断"脊线、让节点显得压在线上），1px 会被这圈完全吃掉——实测改前
              节点之间是纯空白，根本看不出这是一条路。
            */}
            <span
              aria-hidden
              className="bg-border absolute top-3 bottom-3 left-[17px] w-0.5"
            />
            {map.data.chapters.map((chapter, chapterIndex) => (
              <section key={`${chapter.chapter}-${chapterIndex}`} className="relative">
                {chapter.chapter === '' ? null : (
                  // 章节是脊线上的里程碑：带背景色把脊线"打断"，图标压在线上
                  <h2 className="bg-background relative z-10 mb-1 flex items-center gap-2 py-1.5 text-sm font-semibold">
                    <Milestone className="text-primary size-4 shrink-0" />
                    {chapter.chapter}
                  </h2>
                )}
                <ol>
                  {chapter.stages
                    .filter((stage) => levelFilter === null || stage.level === levelFilter)
                    .map((stage) => (
                    <li key={stage.entryId} className="relative">
                      <StageCell
                        stage={stage}
                        trackSlug={map.data.slug}
                        trackName={map.data.name}
                      />
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
 * 路径上的一个关卡节点。
 *
 * <p>不是卡片：没有外框、没有独立背景，节点圆圈压在脊线上，标题写在右侧。
 * 当前可进的那一关抬起来（白底 + 描边 + 阴影），已通关的实心绿——**纵深靠状态表达**，
 * 这就是"地图"与"列表"的差别：一眼能看出走到哪了，以及下一步在哪。
 */
function StageCell({
  stage,
  trackSlug,
  trackName,
}: {
  stage: TrackStage
  trackSlug: string
  trackName: string
}) {
  const done = stage.status === 'done'
  const level = levelMeta(stage.level)

  return (
    // 外层是 div：里面要放「问助手」按钮（WORK-058），按钮套在链接里是嵌套可交互内容。
    // 关卡格本身仍是 <a>（e2e 与键盘都靠这一点），只是按钮成了它的兄弟节点。
    <div className="group relative flex items-center gap-3 py-2.5">
      <Link
        to={`/knowledge/entries/${stage.entryId}`}
        // 带上来源：正文页据此把"返回知识库"换成"返回这条轨道的关卡地图"
        state={{ fromTrackSlug: trackSlug, fromTrackName: trackName }}
        className={`flex min-w-0 flex-1 items-center gap-3 transition-colors ${
          done ? '' : 'hover:bg-accent/50 -mx-2 rounded-lg px-2'
        }`}
        data-testid={`stage-${stage.seq}`}
        data-status={stage.status}
      >
        {/* 节点：压在脊线上。ring 用背景色是为了"切断"脊线，让节点看起来在线上而不是被线穿过。
            尺寸 36px（原 40px）：节点之间的空隙是脊线唯一露出来的地方，节点越大空隙越小——
            实测 40px + py-1.5 时节点间只剩 2px，脊线完全看不见。 */}
        <span
          className={`relative z-10 flex size-9 shrink-0 items-center justify-center rounded-full ring-[3px] ring-background transition-transform group-hover:scale-105 ${
            done
              ? 'bg-success text-success-foreground'
              : 'bg-primary text-primary-foreground shadow-md'
          }`}
          data-testid={`stage-state-${stage.seq}`}
        >
          {done ? <Check className="size-3.5" /> : <Play className="size-3.5" />}
        </span>

        <span className="min-w-0 flex-1 py-1.5">
          <span className="flex flex-wrap items-center gap-2">
            <span className={`text-sm ${done ? 'text-muted-foreground' : 'font-medium'}`}>
              <span className="text-muted-foreground tabular-nums">{stage.seq}.</span> {stage.title}
            </span>
            <span
              className={`rounded border px-1.5 py-0.5 text-[10px] leading-none ${level.tone}`}
              data-testid={`stage-level-${stage.seq}`}
              title={level.hint}
            >
              {level.label}
            </span>
            {done ? <span className="text-success text-[11px]">已通关</span> : null}
          </span>
        </span>
      </Link>

      {/* 这一关可以被单独递给助手：不用先点进去——"卡在第 13 关"这类问题在选关时就有 */}
      <Attach
        type="level"
        id={`${trackSlug}/${stage.seq}`}
        label={`${trackName} · 第 ${stage.seq} 关 ${stage.title}`}
        excerpt={`难度：${level.label}；状态：${done ? '已通关' : '未通关'}`}
        compact
        className="shrink-0"
      />
    </div>
  )
}
