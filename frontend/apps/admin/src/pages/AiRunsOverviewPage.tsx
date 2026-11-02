import {
  Badge,
  Card,
  CardContent,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { formatDurationMs } from '@paideia/core'
import { Activity } from 'lucide-react'
import { useState } from 'react'

import { api } from '../api'
import { TrendChart } from '../components/TrendChart'

/**
 * AI 调用概览（WORK-022 REQ-008，WORK-036 A/B 组）。
 *
 * <p>回答五个问题：调了多少次、成功多少、慢不慢、花了多少、错在哪。
 * WORK-036 追加三件：**趋势**（按桶的序列）、**这份统计可不可信**（丢弃/采样）、
 * **缓存省了多少与名义成本**。
 *
 * <p>**分位数在服务端算**（窗口函数），这里不做任何统计——前端只负责显示。
 */
interface CountBy {
  name: string
  count: number
}

interface Overview {
  from: string
  to: string
  clamped: boolean
  total: number
  byStatus: {
    ok: number
    failed: number
    timeout: number
    cancelled: number
    interrupted: number
    running: number
  }
  successRate: number
  durationP50: number | null
  durationP95: number | null
  ttftP50: number | null
  ttftP95: number | null
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  credits: number
  nominalCredits: number
  cacheSavedRatio: number
  sampling: number
  byKind: CountBy[]
  byModel: CountBy[]
  errorTypes: CountBy[]
  tools: CountBy[]
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

interface Health {
  enabled: boolean
  sampling: number
  queueDepth: number
  queueCapacity: number
  dropped: number
  writeFailures: number
  lastSuccessAt: string | null
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

/** 毫秒显示：小于 1 秒给 ms，否则给秒（一位小数）。空值给破折号，不显示 null。 */
function ms(value: number | null): string {
  return formatDurationMs(value)
}

/** 百分比：一位小数。0 次调用时显示破折号而不是 0%，那会被读成"全是失败"。 */
function percent(value: number, total: number): string {
  if (total === 0) {
    return '—'
  }
  return `${(value * 100).toFixed(1)}%`
}

function num(value: number): string {
  return value.toLocaleString('zh-CN')
}

function ratio(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

const WINDOWS = [
  { value: '24h', label: '近 24 小时', hours: 24 },
  { value: '7d', label: '近 7 天', hours: 24 * 7 },
  { value: '30d', label: '近 30 天', hours: 24 * 30 },
] as const

const QUERY_KINDS = [
  { value: '', label: '全部场景' },
  ...Object.entries(KIND_LABEL).map(([value, label]) => ({ value, label })),
]

function windowParams(choice: string): string {
  const chosen = WINDOWS.find((option) => option.value === choice) ?? WINDOWS[1]
  const to = new Date()
  const from = new Date(to.getTime() - chosen.hours * 3600 * 1000)
  return `from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`
}

export function AiRunsOverviewPage() {
  const [window, setWindow] = useState<string>('7d')
  const [kind, setKind] = useState('')
  const filter = kind === '' ? '' : `&kind=${encodeURIComponent(kind)}`

  const overview = useQuery({
    queryKey: ['ai-runs-overview', window, kind],
    queryFn: () => api.requestEnvelope<Overview>(
      `/api/v1/ai/admin/runs/overview?${windowParams(window)}${filter}`,
    ),
  })
  const series = useQuery({
    queryKey: ['ai-runs-series', window, kind],
    queryFn: () => api.requestEnvelope<Series>(
      `/api/v1/ai/admin/runs/series?${windowParams(window)}${filter}`,
    ),
  })
  const health = useQuery({
    queryKey: ['ai-runs-health'],
    queryFn: () => api.requestEnvelope<Health>('/api/v1/ai/admin/runs/health'),
    // 队列积压答的是"现在有没有在丢"，隔太久才看一次就失去意义
    refetchInterval: 60_000,
  })

  const data = overview.data
  const terminal = data === undefined
    ? 0
    : data.byStatus.ok + data.byStatus.failed + data.byStatus.timeout
      + data.byStatus.cancelled + data.byStatus.interrupted

  const cards = [
    { label: '调用次数', value: data === undefined ? '—' : num(data.total), testId: 'runs-total' },
    {
      label: '成功率',
      value: data === undefined ? '—' : percent(data.successRate, terminal),
      testId: 'runs-success-rate',
    },
    { label: '耗时 p50', value: data === undefined ? '—' : ms(data.durationP50), testId: 'runs-p50' },
    { label: '耗时 p95', value: data === undefined ? '—' : ms(data.durationP95), testId: 'runs-p95' },
    { label: '首字 p50', value: data === undefined ? '—' : ms(data.ttftP50), testId: 'runs-ttft' },
    {
      label: '扣分合计',
      value: data === undefined ? '—' : `${num(data.credits)} 分`,
      testId: 'runs-credits',
    },
  ]

  const droppedNow = (health.data?.dropped ?? 0) > 0 || (health.data?.writeFailures ?? 0) > 0
  const sampling = data?.sampling ?? health.data?.sampling ?? 1
  const points = series.data?.points ?? []

  return (
    <div className="space-y-4">
      <PageHeader
        title="AI 调用"
        description="每次 AI 调用都会留下一行记录，步骤级明细可到「调用明细」下钻。"
      />

      {droppedNow ? (
        <Card className="border-destructive/50">
          <CardContent className="py-3 text-xs" data-testid="runs-health-alert">
            <span className="text-destructive font-medium">以下统计不完整。</span>{' '}
            采集队列累计丢弃 {num(health.data?.dropped ?? 0)} 条、写失败{' '}
            {num(health.data?.writeFailures ?? 0)} 条。队列满时会丢数据以保证回答不被拖慢——
            这是设计如此，但意味着这里的数字比实际少。
          </CardContent>
        </Card>
      ) : null}

      {sampling < 1 ? (
        <Card>
          <CardContent className="py-3 text-xs" data-testid="runs-sampling-hint">
            当前采样率 <span className="tabular-nums">{ratio(sampling)}</span>：
            成功率按采样后的数据计算，而失败轮次全量保留，因此这个值会系统性偏低。
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 py-3">
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">时间区间</span>
            <Select
              items={WINDOWS.map((option) => ({ value: option.value, label: option.label }))}
              value={window}
              onValueChange={(value) => { setWindow(value as string) }}
            >
              <SelectTrigger size="sm" className="w-32" data-testid="overview-window">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {WINDOWS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">场景</span>
            <Select items={QUERY_KINDS} value={kind} onValueChange={(value) => { setKind(value as string) }}>
              <SelectTrigger size="sm" className="w-36" data-testid="overview-kind">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {QUERY_KINDS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          {health.data === undefined ? null : (
            <span className="text-muted-foreground ml-auto text-xs" data-testid="runs-health-inline">
              队列 {health.data.queueDepth} / {num(health.data.queueCapacity)}
              {health.data.enabled ? '' : '（采集已关闭）'}
            </span>
          )}
        </CardContent>
      </Card>

      {overview.isLoading ? <Spinner /> : null}

      {overview.isError ? (
        <Card>
          <CardContent className="text-destructive py-6 text-sm">
            读取失败：{overview.error instanceof Error ? overview.error.message : '未知原因'}
          </CardContent>
        </Card>
      ) : null}

      {data !== undefined ? (
        <>
          {data.clamped ? (
            <p className="text-muted-foreground text-xs" data-testid="runs-clamped">
              时间窗已收敛到保留期上限显示。
            </p>
          ) : null}

          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
            {cards.map((card) => (
              <Card key={card.label}>
                <CardContent className="py-4">
                  <p className="text-muted-foreground text-xs">{card.label}</p>
                  <p className="mt-1 text-2xl font-semibold tabular-nums" data-testid={card.testId}>
                    {card.value}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>

          {data.total === 0 ? (
            <Card>
              <Empty data-testid="runs-overview-empty">
                <EmptyHeader>
                  <EmptyMedia variant="icon">
                    <Activity />
                  </EmptyMedia>
                  <EmptyTitle>这段时间没有任何调用</EmptyTitle>
                  <EmptyDescription>有人用过 AI 之后，这里会有记录。</EmptyDescription>
                </EmptyHeader>
              </Empty>
            </Card>
          ) : (
            <>
              <Card data-testid="runs-trend">
                <CardContent className="space-y-4 py-4">
                  <h2 className="text-sm font-medium">
                    趋势（{series.data?.bucket === 'hour' ? '按小时' : '按天'}）
                  </h2>
                  <TrendChart
                    title="调用次数"
                    testId="runs-trend-total"
                    points={points.map((point) => ({ at: point.at, value: point.total }))}
                    format={(value) => (value === null ? '—' : num(value))}
                  />
                  <TrendChart
                    title="耗时 p95"
                    testId="runs-trend-p95"
                    points={points.map((point) => ({ at: point.at, value: point.durationP95 }))}
                    format={ms}
                  />
                  <TrendChart
                    title="失败数"
                    tone="danger"
                    testId="runs-trend-failed"
                    points={points.map((point) => ({ at: point.at, value: point.failed }))}
                    format={(value) => (value === null ? '—' : num(value))}
                  />
                </CardContent>
              </Card>

              <div className="grid gap-4 lg:grid-cols-2">
                <RankCard title="按场景" rows={data.byKind} label={(name) => KIND_LABEL[name] ?? name}
                  testId="runs-by-kind" />
                <RankCard title="错误类型" rows={data.errorTypes} label={(name) => name}
                  testId="runs-error-types" emptyHint="没有失败记录" />
              </div>
              <div className="grid gap-4 lg:grid-cols-2">
                <RankCard title="按模型" rows={data.byModel} label={(name) => name} testId="runs-by-model" />
                <RankCard title="工具调用" rows={data.tools} label={(name) => name}
                  testId="runs-tools" emptyHint="这段时间没有工具调用" />
              </div>

              <Card>
                <CardContent className="py-4">
                  <h2 className="text-sm font-medium">状态分布</h2>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <Badge variant="secondary">成功 {num(data.byStatus.ok)}</Badge>
                    <Badge variant="secondary">失败 {num(data.byStatus.failed)}</Badge>
                    <Badge variant="secondary">超时 {num(data.byStatus.timeout)}</Badge>
                    <Badge variant="secondary">中断 {num(data.byStatus.interrupted)}</Badge>
                    <Badge variant="secondary">进行中 {num(data.byStatus.running)}</Badge>
                  </div>
                  <p className="text-muted-foreground mt-3 text-xs" data-testid="runs-token-line">
                    输入 {num(data.inputTokens)} / 输出 {num(data.outputTokens)} / 缓存命中{' '}
                    {num(data.cacheReadTokens)} tokens，缓存节省{' '}
                    <span className="tabular-nums">{ratio(data.cacheSavedRatio)}</span>。
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs" data-testid="runs-cost-line">
                    实扣 {num(data.credits)} 分，按当前价目表应扣 {num(data.nominalCredits)} 分。
                    两者不等通常来自缓存命中不计费，或时间窗内改过价目表——这不是计费错误。
                  </p>
                  <p className="text-muted-foreground mt-3 text-xs">
                    「进行中」是仍在跑的轮次；长期停在这里说明有任务卡住或被进程以外的原因中断。
                  </p>
                </CardContent>
              </Card>
            </>
          )}
        </>
      ) : null}
    </div>
  )
}

/** 一个小排行表。空数据时给一句可读的提示，不是一张空表。 */
function RankCard({
  title,
  rows,
  label,
  testId,
  emptyHint = '暂无数据',
}: {
  title: string
  rows: CountBy[]
  label: (name: string) => string
  testId: string
  emptyHint?: string
}) {
  return (
    <Card>
      <CardContent className="px-0">
        <h2 className="px-(--card-spacing) pb-2 text-sm font-medium">{title}</h2>
        {rows.length === 0 ? (
          <p className="text-muted-foreground px-(--card-spacing) pb-4 text-xs" data-testid={testId}>
            {emptyHint}
          </p>
        ) : (
          <Table data-testid={testId}>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead className="text-right">次数</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.name}>
                  <TableCell>{label(row.name)}</TableCell>
                  <TableCell className="text-right tabular-nums">{num(row.count)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  )
}
