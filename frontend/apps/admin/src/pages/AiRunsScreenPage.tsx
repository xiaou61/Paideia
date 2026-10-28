import {
  Badge,
  Button,
  Card,
  CardContent,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { Maximize2, Minimize2, Monitor, Pause, Play, RotateCcw } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'

import { api } from '../api'

/**
 * AI 调用大屏（24h / 7d 回放）。
 *
 * <p>与「调用概览」「调用明细」的分工：那两页回答"花了多少、错在哪"，这一页只做一件事——
 * **把已经发生的一轮一轮放给你看**：左边是这一时段的调用流，中间是选中那一轮的瀑布图
 * （按步骤真实时间比例逐条长出），右边是这一桶与这一轮的聚合数字。
 *
 * <p>三条刻意的取舍：
 * <ol>
 *   <li>**回放不是直播**。进行中的步骤没有落库（采集器在轮次结束时才写 spans），所以这里
 *       只放**已结束**的轮次；时间轴拖动选的是一个桶，不是某一秒。</li>
 *   <li>**只取最近 200 轮**（分页上限）。7 天内超过 200 轮时，时间轴上更早的桶会显示计数
 *       但没有可回放的卡片——所以本桶标签写的是"已加载 N 轮"，不冒充全量。</li>
 *   <li>**24 小时为空时自动退回 7 天**，并明确标出"已退回"。空着也是信息，但大屏的价值
 *       在于总有东西可看；退回只发生一次，之后用户可以自己切回去。</li>
 * </ol>
 */
interface RunSummary {
  traceId: string
  userId: number
  conversationId: number | null
  kind: string
  harness: string | null
  model: string | null
  status: string
  errorType: string | null
  startedAt: string
  endedAt: string | null
  durationMs: number | null
  ttftMs: number | null
  llmRounds: number
  toolCalls: number
  inputTokens: number
  outputTokens: number
  credits: number
}

interface SpanView {
  seq: number
  parentSeq: number | null
  kind: string
  name: string
  status: string
  errorType: string | null
  startedAt: string
  durationMs: number | null
  ttftMs: number | null
  inputTokens: number
  outputTokens: number
  attrs: Record<string, unknown>
}

interface Breakdown {
  queueMs: number
  llmMs: number
  toolMs: number
  otherMs: number
  totalMs: number
}

interface RunDetail {
  run: RunSummary
  spans: SpanView[]
  breakdown: Breakdown
}

interface SeriesPoint {
  at: string
  total: number
  ok: number
  failed: number
  timeout: number
  durationP50: number | null
  durationP95: number | null
  ttftP50: number | null
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  credits: number
}

interface Series {
  from: string
  to: string
  clamped: boolean
  bucket: 'hour' | 'day'
  zone: string
  sampling: number
  points: SeriesPoint[]
}

interface Page<T> {
  total: number
  page: number
  size: number
  items: T[]
}

const KIND_LABEL: Record<string, string> = {
  chat: '对话',
  memory_extract: '记忆整理',
  interview_review: '面试点评',
  mcp_ask: '外部问答',
  resume_import: '简历解析',
  resume_optimize: '简历优化',
  entry_summary: '条目精简',
}

const STATUS_LABEL: Record<string, string> = {
  ok: '成功',
  failed: '失败',
  timeout: '超时',
  cancelled: '取消',
  interrupted: '中断',
  running: '进行中',
}

/** 步骤条的颜色只用语义令牌：深色主题下自动跟着走，也不破坏对比度。 */
const SPAN_TONE: Record<string, string> = {
  llm: 'bg-primary',
  tool: 'bg-primary/45',
  billing: 'bg-muted-foreground/60',
  retrieval: 'bg-muted-foreground/45',
  persist: 'bg-muted-foreground/30',
}

const CAROUSEL_MS = 8000
/** 分页上限就是 200（后端 `PageQuery.MAX_SIZE`），再大只会被收敛。 */
const PLAYLIST_SIZE = 200
/** 一轮的瀑布最多播这么久，再长的轮次按比例压缩——大屏不该为一条慢调用停住。 */
const MAX_PLAY_MS = 6000
const MIN_PLAY_MS = 1200
const SPEEDS = [1, 2, 4] as const

function windowQuery(range: '24h' | '7d'): string {
  const hours = range === '24h' ? 24 : 24 * 7
  const to = new Date()
  const from = new Date(to.getTime() - hours * 3600 * 1000)
  return `from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`
}

function ms(value: number | null): string {
  if (value === null || value === undefined) {
    return '—'
  }
  return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`
}

function num(value: number): string {
  return value.toLocaleString('zh-CN')
}

function when(iso: string): string {
  const at = new Date(iso)
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString('zh-CN', { hour12: false })
}

function clock(msEpoch: number, bucket: 'hour' | 'day' | undefined): string {
  const at = new Date(msEpoch)
  if (Number.isNaN(at.getTime())) {
    return '—'
  }
  return bucket === 'day'
    ? at.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
    : at.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })
}

function percentile(values: number[], q: number): number | null {
  if (values.length === 0) {
    return null
  }
  const sorted = [...values].sort((left, right) => left - right)
  const index = Math.min(sorted.length - 1, Math.max(0, Math.floor(q * (sorted.length - 1))))
  return sorted[index] ?? null
}

export function AiRunsScreenPage() {
  const [range, setRange] = useState<'24h' | '7d'>('24h')
  const [fellBack, setFellBack] = useState(false)
  const [selectedBucket, setSelectedBucket] = useState<number | null>(null)
  const [focusTrace, setFocusTrace] = useState<string | null>(null)
  const [paused, setPaused] = useState(false)
  const [speed, setSpeed] = useState<number>(1)
  const [replay, setReplay] = useState(0)
  const [isFull, setIsFull] = useState(false)

  const series = useQuery({
    queryKey: ['ai-screen-series', range],
    queryFn: () => api.requestEnvelope<Series>(`/api/v1/ai/admin/runs/series?${windowQuery(range)}`),
    refetchInterval: 60_000,
  })
  const runs = useQuery({
    queryKey: ['ai-screen-runs', range],
    queryFn: () => api.requestEnvelope<Page<RunSummary>>(
      `/api/v1/ai/admin/runs?${windowQuery(range)}&page=1&size=${PLAYLIST_SIZE}`,
    ),
    refetchInterval: 60_000,
  })

  // 只退一次：退回之后用户手动切回 24 小时不该被立刻弹走
  const fellBackRef = useRef(false)
  useEffect(() => {
    if (fellBackRef.current || range !== '24h' || runs.data === undefined) {
      return
    }
    if (runs.data.items.length === 0) {
      fellBackRef.current = true
      setFellBack(true)
      setRange('7d')
    }
  }, [range, runs.data])

  const points = series.data?.points ?? []
  const bucketKind = series.data?.bucket
  const maxTotal = points.reduce((max, point) => Math.max(max, point.total), 0)

  /** 默认停在"最近一个有调用的桶"；整窗都没有就停在最后一桶，让空状态说得清楚。 */
  const autoBucket = useMemo(() => {
    for (let index = points.length - 1; index >= 0; index -= 1) {
      if ((points[index]?.total ?? 0) > 0) {
        return index
      }
    }
    return points.length - 1
  }, [points])
  const bucketIndex = selectedBucket === null
    ? autoBucket
    : Math.min(selectedBucket, points.length - 1)

  const bucketPoint = bucketIndex >= 0 ? points[bucketIndex] : undefined
  const nextPoint = points[bucketIndex + 1]
  const bucketStart = bucketPoint === undefined ? 0 : Date.parse(bucketPoint.at)
  const bucketEnd = bucketPoint === undefined
    ? 0
    : (nextPoint !== undefined
      ? Date.parse(nextPoint.at)
      : Date.parse(series.data?.to ?? bucketPoint.at))

  const bucketRuns = useMemo(() => {
    if (bucketPoint === undefined) {
      return []
    }
    return (runs.data?.items ?? []).filter((run) => {
      const at = Date.parse(run.startedAt)
      return at >= bucketStart && at < bucketEnd
    })
  }, [runs.data, bucketPoint, bucketStart, bucketEnd])

  const focusIndex = focusTrace === null
    ? 0
    : Math.max(0, bucketRuns.findIndex((run) => run.traceId === focusTrace))
  const current = bucketRuns[focusIndex] ?? null

  const detail = useQuery({
    queryKey: ['ai-run-detail', current?.traceId ?? ''],
    queryFn: () => api.requestEnvelope<RunDetail>(`/api/v1/ai/admin/runs/${current?.traceId}`),
    enabled: current !== null,
  })

  const indexRef = useRef(0)
  indexRef.current = focusIndex
  useEffect(() => {
    if (paused || bucketRuns.length <= 1) {
      return
    }
    const timer = setInterval(() => {
      const next = (indexRef.current + 1) % bucketRuns.length
      setFocusTrace(bucketRuns[next]?.traceId ?? null)
    }, CAROUSEL_MS)
    return () => clearInterval(timer)
  }, [paused, bucketRuns])

  const step = useCallback((delta: number) => {
    if (bucketRuns.length === 0) {
      return
    }
    const base = focusTrace === null
      ? 0
      : Math.max(0, bucketRuns.findIndex((run) => run.traceId === focusTrace))
    const next = (base + delta + bucketRuns.length) % bucketRuns.length
    setFocusTrace(bucketRuns[next]?.traceId ?? null)
  }, [bucketRuns, focusTrace])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const tag = (event.target as HTMLElement | null)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') {
        return
      }
      if (event.code === 'Space') {
        event.preventDefault()
        setPaused((value) => !value)
      } else if (event.code === 'ArrowRight') {
        step(1)
      } else if (event.code === 'ArrowLeft') {
        step(-1)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step])

  useEffect(() => {
    const onChange = () => { setIsFull(document.fullscreenElement !== null) }
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [])

  const toggleFull = () => {
    if (document.fullscreenElement !== null) {
      void document.exitFullscreen()
    } else {
      void document.documentElement.requestFullscreen()
    }
  }

  // 拖动时间轴 = 换桶。用指针捕获而不是 mouse 事件，触屏也能拖。
  const trackRef = useRef<HTMLDivElement | null>(null)
  const draggingRef = useRef(false)
  const pick = useCallback((clientX: number) => {
    const track = trackRef.current
    if (track === null || points.length === 0) {
      return
    }
    const rect = track.getBoundingClientRect()
    if (rect.width <= 0) {
      return
    }
    const ratio = (clientX - rect.left) / rect.width
    setSelectedBucket(Math.max(0, Math.min(points.length - 1, Math.floor(ratio * points.length))))
    setFocusTrace(null)
  }, [points.length])

  const durations = bucketRuns
    .map((run) => run.durationMs)
    .filter((value): value is number => value !== null)

  const tokensIn = bucketRuns.reduce((sum, run) => sum + run.inputTokens, 0)
  const tokensOut = bucketRuns.reduce((sum, run) => sum + run.outputTokens, 0)
  const credits = bucketRuns.reduce((sum, run) => sum + run.credits, 0)
  const failed = bucketRuns.filter((run) => run.status !== 'ok' && run.status !== 'running').length

  const truncated = runs.data !== undefined && runs.data.total > runs.data.items.length
  const windowEmpty = runs.data !== undefined && runs.data.items.length === 0

  return (
    <div className="bg-background text-foreground flex min-h-dvh flex-col gap-3 p-4 lg:h-dvh lg:overflow-hidden">
      <style>{'@keyframes pw-grow{from{width:0}to{width:var(--pw-w)}}'}</style>

      <header className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <div className="flex items-center gap-2">
          <Monitor className="size-5" />
          <h1 className="text-lg font-semibold">AI 调用大屏</h1>
        </div>
        <div className="flex gap-1">
          <Button size="sm" variant={range === '24h' ? 'default' : 'outline'}
            data-testid="screen-range-24h"
            onClick={() => { setRange('24h'); setSelectedBucket(null); setFocusTrace(null) }}>
            近 24 小时
          </Button>
          <Button size="sm" variant={range === '7d' ? 'default' : 'outline'}
            data-testid="screen-range-7d"
            onClick={() => { setRange('7d'); setSelectedBucket(null); setFocusTrace(null) }}>
            近 7 天
          </Button>
        </div>
        {fellBack ? (
          <Badge variant="secondary" data-testid="screen-fell-back">
            近 24 小时没有调用，已退回近 7 天
          </Badge>
        ) : null}
        <div className="ml-auto flex items-center gap-2">
          <span className="text-muted-foreground text-xs" data-testid="screen-carousel-state">
            {paused ? '轮播已暂停' : '轮播中 · 8 秒一轮'}
          </span>
          <Button size="sm" variant="outline" onClick={() => { setPaused((value) => !value) }}
            data-testid="screen-pause">
            {paused ? <Play /> : <Pause />}
          </Button>
          <Button size="sm" variant="outline" onClick={() => { setSpeed((value) => (value === 4 ? 1 : value * 2)) }}
            data-testid="screen-speed">
            {speed}×
          </Button>
          <Button size="sm" variant="outline" onClick={() => { setReplay((value) => value + 1) }}>
            <RotateCcw /> 重播
          </Button>
          <Button size="sm" variant="outline" onClick={toggleFull} data-testid="screen-fullscreen">
            {isFull ? <Minimize2 /> : <Maximize2 />} 全屏
          </Button>
        </div>
      </header>

      <Card data-testid="screen-timeline">
        <CardContent className="space-y-1 py-2">
          <div className="text-muted-foreground flex items-center justify-between text-[11px]">
            <span>
              {points.length === 0
                ? '时间轴'
                : `${clock(Date.parse(points[0]?.at ?? ''), bucketKind)} 起 · ${bucketKind === 'day' ? '按天' : '按小时'}`}
            </span>
            <span data-testid="screen-bucket-label">
              {bucketPoint === undefined
                ? '—'
                : `${clock(bucketStart, bucketKind)} — ${clock(bucketEnd, bucketKind)}，${bucketPoint.total} 轮`}
            </span>
          </div>
          <div
            ref={trackRef}
            className="border-border/60 relative flex h-20 cursor-crosshair touch-none items-end gap-[2px] border-b select-none"
            data-testid="screen-timeline-track"
            onPointerDown={(event) => {
              draggingRef.current = true
              event.currentTarget.setPointerCapture(event.pointerId)
              pick(event.clientX)
            }}
            onPointerMove={(event) => { if (draggingRef.current) { pick(event.clientX) } }}
            onPointerUp={(event) => {
              draggingRef.current = false
              event.currentTarget.releasePointerCapture(event.pointerId)
            }}
            onPointerCancel={() => { draggingRef.current = false }}
          >
            {points.map((point, index) => {
              // 留 12% 顶端余量：柱子顶到天花板的图读起来像"填色块"，不像柱状图
              const height = maxTotal === 0 ? 0 : (point.total / maxTotal) * 88
              const bad = point.total === 0
                ? 0
                : ((point.failed + point.timeout) / point.total) * 100
              if (point.total === 0) {
                return (
                  <div key={point.at} className="flex h-full flex-1 flex-col justify-end">
                    <div className="bg-muted-foreground/25 h-[2px] w-full rounded-t-full" />
                  </div>
                )
              }
              return (
                <div key={point.at} className="flex h-full flex-1 flex-col justify-end">
                  <div
                    className="w-full overflow-hidden rounded-t-[2px]"
                    style={{
                      height: `${height}%`,
                      minHeight: '3px',
                      opacity: index === bucketIndex ? 1 : 0.5,
                    }}
                  >
                    <div className="bg-destructive" style={{ height: `${bad}%` }} />
                    <div className="bg-primary" style={{ height: `${100 - bad}%` }} />
                  </div>
                </div>
              )
            })}
            {bucketIndex >= 0 && points.length > 0 ? (
              <div
                className="ring-primary/50 pointer-events-none absolute inset-y-0 rounded-sm ring-1"
                style={{
                  left: `${(bucketIndex / points.length) * 100}%`,
                  width: `${100 / points.length}%`,
                }}
              />
            ) : null}
          </div>
          {points.length > 0 ? (
            <div className="text-muted-foreground flex justify-between text-[10px] tabular-nums">
              <span>{clock(Date.parse(points[0]?.at ?? ''), bucketKind)}</span>
              <span>{clock(Date.parse(points[Math.floor(points.length / 2)]?.at ?? ''), bucketKind)}</span>
              <span>{clock(bucketEnd, bucketKind)}</span>
            </div>
          ) : null}
          <p className="text-muted-foreground text-[11px]">
            拖动时间轴换时段；红段是失败与超时。空格暂停轮播，左右方向键切换轮次。
          </p>
        </CardContent>
      </Card>

      {runs.isLoading || series.isLoading ? (
        <div className="flex flex-1 items-center justify-center"><Spinner /></div>
      ) : null}

      {runs.isError ? (
        <Card className="border-destructive/50">
          <CardContent className="text-destructive py-4 text-sm">
            读取失败：{runs.error instanceof Error ? runs.error.message : '未知原因'}
          </CardContent>
        </Card>
      ) : null}

      {windowEmpty ? (
        <Card className="flex-1">
          <Empty data-testid="screen-empty">
            <EmptyHeader>
              <EmptyMedia variant="icon"><Monitor /></EmptyMedia>
              <EmptyTitle>{range === '24h' ? '近 24 小时没有调用' : '近 7 天没有调用'}</EmptyTitle>
              <EmptyDescription>
                有人用过 AI 之后这里会有记录；更早的历史去「调用明细」把时间窗调到 30 天。
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </Card>
      ) : null}

      {!windowEmpty ? (
        <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-[300px_minmax(0,1fr)_260px]">
          <Card className="flex min-h-0 flex-col" data-testid="screen-feed">
            <CardContent className="flex min-h-0 flex-col gap-2 py-3">
              <div className="flex items-center justify-between text-xs">
                <span className="font-medium">调用流</span>
                <span className="text-muted-foreground" data-testid="screen-feed-count">
                  本桶 {bucketRuns.length} 轮{truncated ? ` / 仅最近 ${PLAYLIST_SIZE}` : ''}
                </span>
              </div>
              <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto pr-1">
                {bucketRuns.length === 0 ? (
                  <p className="text-muted-foreground py-6 text-center text-xs" data-testid="screen-feed-empty">
                    这个时段没有可回放的调用。
                    {truncated ? `列表只保留最近 ${PLAYLIST_SIZE} 轮，更早的请用「调用明细」。` : ''}
                  </p>
                ) : null}
                {bucketRuns.map((run, index) => {
                  const active = index === focusIndex
                  return (
                    <button
                      type="button"
                      key={run.traceId}
                      data-testid={`screen-feed-${run.traceId}`}
                      onClick={() => { setFocusTrace(run.traceId); setPaused(true) }}
                      className={[
                        'w-full rounded-md border px-2 py-1.5 text-left transition-colors',
                        active
                          ? 'border-primary bg-primary/5'
                          : 'border-border hover:bg-muted/50',
                      ].join(' ')}
                    >
                      <div className="flex items-center gap-1.5">
                        {active && !paused
                          ? <span className="bg-primary size-1.5 shrink-0 animate-pulse rounded-full" />
                          : null}
                        <Badge variant={run.status === 'ok' ? 'secondary' : 'destructive'}>
                          {KIND_LABEL[run.kind] ?? run.kind}
                        </Badge>
                        <span className="text-muted-foreground ml-auto text-[10px] tabular-nums">
                          {new Date(run.startedAt).toLocaleTimeString('zh-CN', { hour12: false })}
                        </span>
                      </div>
                      <div className="mt-1 text-[11px] tabular-nums">
                        {run.model ?? '—'} · {ms(run.durationMs)}
                        {run.errorType === null
                          ? ''
                          : <span className="text-destructive"> · {run.errorType}</span>}
                      </div>
                      <div className="text-muted-foreground text-[10px] tabular-nums">
                        {run.llmRounds} 轮 LLM · {run.toolCalls} 工具 ·{' '}
                        {num(run.inputTokens)}/{num(run.outputTokens)} tokens · {run.credits} 分
                      </div>
                    </button>
                  )
                })}
              </div>
            </CardContent>
          </Card>

          <Card className="flex min-h-0 flex-col" data-testid="screen-waterfall">
            <CardContent className="flex min-h-0 flex-1 flex-col gap-2 py-3">
              {current === null ? (
                <p className="text-muted-foreground flex flex-1 items-center justify-center text-sm">
                  选一轮看看它每一步花在哪。
                </p>
              ) : (
                <>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                    <Badge variant={current.status === 'ok' ? 'secondary' : 'destructive'}>
                      {STATUS_LABEL[current.status] ?? current.status}
                    </Badge>
                    <span className="font-medium">{KIND_LABEL[current.kind] ?? current.kind}</span>
                    <span className="text-muted-foreground">{current.model ?? '—'}</span>
                    <span className="text-muted-foreground">{when(current.startedAt)}</span>
                    <span className="tabular-nums">总 {ms(current.durationMs)}</span>
                    <span className="text-muted-foreground tabular-nums">首字 {ms(current.ttftMs)}</span>
                    <span className="text-muted-foreground ml-auto text-[10px]">
                      {current.traceId.slice(0, 8)} · {current.llmRounds} 轮 LLM · {current.toolCalls} 工具
                    </span>
                  </div>

                  {detail.isLoading ? <Spinner /> : null}
                  {detail.isError ? (
                    <p className="text-destructive text-xs">
                      步骤读取失败：{detail.error instanceof Error ? detail.error.message : '未知原因'}
                    </p>
                  ) : null}

                  {detail.data !== undefined && detail.data.spans.length === 0 ? (
                    <p className="text-muted-foreground text-xs" data-testid="screen-spans-empty">
                      这一轮没有步骤记录（采集关闭，或它在写入步骤前就结束了）。
                    </p>
                  ) : null}

                  {detail.data !== undefined && detail.data.spans.length > 0 ? (
                    <Waterfall
                      key={`${current.traceId}-${speed}-${replay}`}
                      spans={detail.data.spans}
                      runStartedAt={current.startedAt}
                      fallbackTotal={detail.data.breakdown.totalMs}
                      speed={speed}
                    />
                  ) : null}
                </>
              )}
            </CardContent>
          </Card>

          <Card className="flex min-h-0 flex-col" data-testid="screen-summary">
            <CardContent className="min-h-0 flex-1 space-y-3 overflow-y-auto py-3 text-xs">
              <div>
                <p className="mb-1 font-medium">本桶</p>
                <p className="text-muted-foreground text-[11px]">
                  {clock(bucketStart, bucketKind)} — {clock(bucketEnd, bucketKind)}
                </p>
                <dl className="mt-2 grid grid-cols-2 gap-x-2 gap-y-1 tabular-nums">
                  <dt className="text-muted-foreground">轮次</dt>
                  <dd className="text-right">{bucketRuns.length}</dd>
                  <dt className="text-muted-foreground">失败</dt>
                  <dd className={failed > 0 ? 'text-destructive text-right' : 'text-right'}>{failed}</dd>
                  <dt className="text-muted-foreground">耗时 p50</dt>
                  <dd className="text-right">{ms(percentile(durations, 0.5))}</dd>
                  <dt className="text-muted-foreground">耗时 p95</dt>
                  <dd className="text-right">{ms(percentile(durations, 0.95))}</dd>
                  <dt className="text-muted-foreground">token 入/出</dt>
                  <dd className="text-right">{num(tokensIn)}/{num(tokensOut)}</dd>
                  <dt className="text-muted-foreground">缓存命中</dt>
                  <dd className="text-right">{num(bucketPoint?.cacheReadTokens ?? 0)}</dd>
                  <dt className="text-muted-foreground">积分</dt>
                  <dd className="text-right">{num(credits)}</dd>
                </dl>
                <p className="text-muted-foreground mt-2 text-[10px]">
                  分位与合计按已加载的 {bucketRuns.length} 轮算，不是全量。
                </p>
              </div>

              {detail.data !== undefined ? (
                <div>
                  <p className="mb-1 font-medium">选中轮的耗时构成</p>
                  <div className="bg-muted flex h-2.5 w-full overflow-hidden rounded-sm">
                    <Segment value={detail.data.breakdown.queueMs} total={detail.data.breakdown.totalMs}
                      tone="bg-muted-foreground/25" />
                    <Segment value={detail.data.breakdown.llmMs} total={detail.data.breakdown.totalMs}
                      tone="bg-primary" />
                    <Segment value={detail.data.breakdown.toolMs} total={detail.data.breakdown.totalMs}
                      tone="bg-primary/45" />
                    <Segment value={detail.data.breakdown.otherMs} total={detail.data.breakdown.totalMs}
                      tone="bg-muted-foreground/12" />
                  </div>
                  <dl className="mt-2 grid grid-cols-2 gap-x-2 gap-y-1 tabular-nums">
                    <dt className="text-muted-foreground">排队</dt>
                    <dd className="text-right">{ms(detail.data.breakdown.queueMs)}</dd>
                    <dt className="text-muted-foreground">LLM</dt>
                    <dd className="text-right">{ms(detail.data.breakdown.llmMs)}</dd>
                    <dt className="text-muted-foreground">工具</dt>
                    <dd className="text-right">{ms(detail.data.breakdown.toolMs)}</dd>
                    <dt className="text-muted-foreground">其它</dt>
                    <dd className="text-right">{ms(detail.data.breakdown.otherMs)}</dd>
                  </dl>
                </div>
              ) : null}
            </CardContent>
          </Card>
        </div>
      ) : null}
    </div>
  )
}

/**
 * 一轮的瀑布图：每条步骤按它在整轮里的真实偏移与真实耗时从左往右长出。
 *
 * <p>动画是纯 CSS：`animation-delay` 对偏移、`animation-duration` 对耗时、关键帧只负责
 * 把宽度从 0 推到 `--pw-w`。**改速度/重播靠换 key 重挂载**——已结束的 CSS 动画不会因为
 * 改了 duration 就重跑，不重挂载会得到半截静态图。
 */
function Waterfall({ spans, runStartedAt, fallbackTotal, speed }: {
  spans: SpanView[]
  runStartedAt: string
  fallbackTotal: number
  speed: number
}) {
  const starts = spans.map((span) => Date.parse(span.startedAt)).filter((at) => !Number.isNaN(at))
  const origin = starts.length > 0 ? Math.min(...starts) : Date.parse(runStartedAt)
  const end = spans.reduce((max, span) => {
    const at = Date.parse(span.startedAt)
    return Number.isNaN(at) ? max : Math.max(max, at + (span.durationMs ?? 0))
  }, origin)
  const spanMs = Math.max(end - origin, fallbackTotal, 1)
  const playMs = Math.min(MAX_PLAY_MS, Math.max(MIN_PLAY_MS, spanMs)) / speed

  return (
    <div className="min-h-0 flex-1 space-y-1 overflow-y-auto" data-testid="screen-spans">
      {spans.map((span) => {
        const at = Date.parse(span.startedAt)
        const offset = Number.isNaN(at) ? 0 : Math.max(0, at - origin)
        const duration = span.durationMs ?? 0
        const left = (offset / spanMs) * 100
        const width = Math.max((duration / spanMs) * 100, 0.6)
        const tone = span.status === 'failed'
          ? 'bg-destructive'
          : (SPAN_TONE[span.kind] ?? 'bg-muted-foreground/30')
        const style = {
          left: `${left}%`,
          animationName: 'pw-grow',
          animationTimingFunction: 'linear',
          animationFillMode: 'both',
          animationDelay: `${(offset / spanMs) * playMs}ms`,
          animationDuration: `${Math.max((duration / spanMs) * playMs, 80)}ms`,
          '--pw-w': `${width}%`,
        } as CSSProperties
        return (
          <div key={span.seq} className="flex items-center gap-2" data-testid={`screen-span-${span.seq}`}>
            <div
              className="w-32 shrink-0 truncate text-[11px]"
              style={{ paddingLeft: span.parentSeq === null ? 0 : 12 }}
              title={span.name}
            >
              {span.parentSeq === null ? null : <span className="text-muted-foreground">└ </span>}
              {span.name}
            </div>
            <div className="bg-muted/40 relative h-4 flex-1 rounded-sm">
              <div className={`absolute inset-y-0.5 rounded-sm ${tone}`} style={style} />
            </div>
            <div className="w-16 shrink-0 text-right text-[11px] tabular-nums">{ms(span.durationMs)}</div>
            <div className="text-muted-foreground w-20 shrink-0 text-right text-[10px] tabular-nums">
              {span.inputTokens}/{span.outputTokens}
            </div>
          </div>
        )
      })}
    </div>
  )
}

function Segment({ value, total, tone }: { value: number; total: number; tone: string }) {
  if (value <= 0 || total <= 0) {
    return null
  }
  return <div className={tone} style={{ width: `${(value / total) * 100}%` }} />
}
