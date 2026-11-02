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
  Input,
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
import { formatDurationMs, formatLocalDateTime } from '@paideia/core'
import { ListOrdered } from 'lucide-react'
import { useState } from 'react'

import { api } from '../api'

/**
 * AI 调用明细（WORK-022 REQ-009 / REQ-010）。
 *
 * <p>列表按时间倒序，可按场景 / 状态 / 用户 / 模型筛。点一行展开该轮的**步骤表**——
 * 那才是回答"这一轮慢在哪"的地方：第 1 轮上游 800ms、工具 4.2s、第 2 轮 600ms。
 *
 * <p>**不展示正文与提示词**：本项刻意不采集它们（隐私与体积）。要看对话内容去会话历史。
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

interface RunPayload {
  seq: number
  requestBody: string | null
  responseBody: string | null
  requestBytes: number
  responseBytes: number
  truncated: boolean
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
  attrs: Record<string, unknown>
  spans: SpanView[]
  payloads: RunPayload[]
  payloadEnabled: boolean
  breakdown: Breakdown
}

interface Page<T> {
  total: number
  page: number
  size: number
  items: T[]
}

interface Meta {
  kinds: { name: string; count: number }[]
  models: { name: string; count: number }[]
  statuses: { name: string; count: number }[]
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

function ms(value: number | null): string {
  return formatDurationMs(value)
}

function when(value: string | null): string {
  return formatLocalDateTime(value)
}

/** 时间窗选项（WORK-030）。原先写死 7 天，改不了。 */
export const WINDOWS = [
  { value: '1h', label: '近 1 小时', hours: 1 },
  { value: 'today', label: '今天', hours: 24 },
  { value: '7d', label: '近 7 天', hours: 24 * 7 },
  { value: '30d', label: '近 30 天', hours: 24 * 30 },
] as const

/** 把窗口选项换成查询串。`hour` 传 null 表示用默认（近 7 天）。 */
function windowParams(hour?: string | null): string {
  const chosen = WINDOWS.find((option) => option.value === hour) ?? WINDOWS[2]
  const to = new Date()
  const from = new Date(to.getTime() - chosen.hours * 3600 * 1000)
  return `from=${from.toISOString()}&to=${to.toISOString()}`
}

const PAGE_SIZE = 50

export function AiRunsListPage() {
  const [kind, setKind] = useState('')
  const [status, setStatus] = useState('')
  const [userId, setUserId] = useState('')
  const [traceId, setTraceId] = useState('')
  const [hour, setHour] = useState<string>('7d')
  const [page, setPage] = useState(1)
  const [expanded, setExpanded] = useState<string | null>(null)

  const meta = useQuery({
    queryKey: ['ai-runs-meta', hour],
    queryFn: () => api.requestEnvelope<Meta>(`/api/v1/ai/admin/runs/meta?${windowParams(hour)}`),
  })

  const list = useQuery({
    queryKey: ['ai-runs-list', kind, status, userId, traceId, hour, page],
    queryFn: () => {
      const params = new URLSearchParams()
      if (kind !== '') params.set('kind', kind)
      if (status !== '') params.set('status', status)
      if (userId.trim() !== '') params.set('userId', userId.trim())
      if (traceId.trim() !== '') params.set('traceId', traceId.trim())
      params.set('page', String(page))
      params.set('size', String(PAGE_SIZE))
      return api.requestEnvelope<Page<RunSummary>>(
        `/api/v1/ai/admin/runs?${windowParams(hour)}&${params.toString()}`,
      )
    },
  })

  const rows = list.data?.items ?? []
  const totalPages = Math.max(1, Math.ceil((list.data?.total ?? 0) / PAGE_SIZE))

  return (
    <div className="space-y-4">
      <PageHeader
        title="调用明细"
        description="每一行是一轮调用。点开可看该轮的步骤耗时——「慢在上游还是慢在工具」只能在这里看出来。"
      />

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 py-3">
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">时间区间</span>
            <Select
              items={WINDOWS.map((option) => ({ value: option.value, label: option.label }))}
              value={hour}
              onValueChange={(value) => {
                setHour(value as string)
                setPage(1)
              }}
            >
              <SelectTrigger size="sm" className="w-28" data-testid="runs-filter-window">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {WINDOWS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">场景</span>
            <Select
              items={[{ value: '', label: '全部' }, ...(meta.data?.kinds ?? []).map((item) => ({
                value: item.name,
                label: KIND_LABEL[item.name] ?? item.name,
              }))]}
              value={kind}
              onValueChange={(value) => { setKind(value as string) }}
            >
              <SelectTrigger size="sm" className="w-36" data-testid="runs-filter-kind">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="">全部</SelectItem>
                {(meta.data?.kinds ?? []).map((item) => (
                  <SelectItem key={item.name} value={item.name}>
                    {KIND_LABEL[item.name] ?? item.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">状态</span>
            <Select
              items={[{ value: '', label: '全部' }, ...(meta.data?.statuses ?? []).map((item) => ({
                value: item.name,
                label: STATUS_LABEL[item.name] ?? item.name,
              }))]}
              value={status}
              onValueChange={(value) => { setStatus(value as string) }}
            >
              <SelectTrigger size="sm" className="w-28" data-testid="runs-filter-status">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="">全部</SelectItem>
                {(meta.data?.statuses ?? []).map((item) => (
                  <SelectItem key={item.name} value={item.name}>
                    {STATUS_LABEL[item.name] ?? item.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">用户 id</span>
            <Input
              className="h-9 w-32"
              data-testid="runs-filter-user"
              inputMode="numeric"
              placeholder="全部"
              value={userId}
              onChange={(event) => { setUserId(event.target.value) }}
            />
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">轮次 id</span>
            <Input
              className="h-9 w-40 font-mono"
              data-testid="runs-filter-trace"
              placeholder="前缀即可"
              value={traceId}
              onChange={(event) => { setTraceId(event.target.value); setPage(1) }}
            />
          </label>
          {list.isSuccess ? (
            <span className="text-muted-foreground ml-auto text-xs" data-testid="runs-list-total">
              共 {list.data.total} 条
            </span>
          ) : null}
        </CardContent>
      </Card>

      {list.isLoading ? <Spinner /> : null}

      {list.isSuccess && rows.length === 0 ? (
        <Card>
          <Empty data-testid="runs-list-empty">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <ListOrdered />
              </EmptyMedia>
              <EmptyTitle>没有符合条件的调用</EmptyTitle>
              <EmptyDescription>放宽筛选条件，或等有人用过 AI 之后再来看。</EmptyDescription>
            </EmptyHeader>
          </Empty>
        </Card>
      ) : null}

      {rows.length > 0 ? (
        <Card>
          <CardContent className="px-0">
            <Table data-testid="runs-table">
              <TableHeader>
                <TableRow>
                  <TableHead>时间</TableHead>
                  <TableHead>场景</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead className="text-right">耗时</TableHead>
                  <TableHead className="text-right">首字</TableHead>
                  <TableHead className="text-right">轮次/工具</TableHead>
                  <TableHead className="text-right">tokens</TableHead>
                  <TableHead className="text-right">扣分</TableHead>
                  <TableHead>用户</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <Row key={row.traceId} row={row} expanded={expanded === row.traceId}
                    onToggle={() => { setExpanded(expanded === row.traceId ? null : row.traceId) }} />
                ))}
              </TableBody>
            </Table>
            <div className="border-border text-muted-foreground flex items-center justify-between gap-4 border-t px-(--card-spacing) py-3 text-xs">
              <span>共 {list.data?.total ?? 0} 条</span>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="xs"
                  disabled={page <= 1}
                  onClick={() => setPage(page - 1)}
                  data-testid="runs-prev"
                >
                  上一页
                </Button>
                <span className="tabular-nums">
                  {page} / {totalPages}
                </span>
                <Button
                  variant="outline"
                  size="xs"
                  disabled={page >= totalPages}
                  onClick={() => setPage(page + 1)}
                  data-testid="runs-next"
                >
                  下一页
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      ) : null}
    </div>
  )
}

function Row({ row, expanded, onToggle }: { row: RunSummary; expanded: boolean; onToggle: () => void }) {
  return (
    <>
      <TableRow className="cursor-pointer" onClick={onToggle} data-testid={`runs-row-${row.traceId}`}>
        <TableCell className="text-xs whitespace-nowrap">{when(row.startedAt)}</TableCell>
        <TableCell className="text-xs">{KIND_LABEL[row.kind] ?? row.kind}</TableCell>
        <TableCell>
          <Badge variant={row.status === 'ok' ? 'secondary' : 'destructive'}>
            {STATUS_LABEL[row.status] ?? row.status}
          </Badge>
          {row.errorType === null ? null : (
            <span className="text-muted-foreground ml-1 text-[10px]">{row.errorType}</span>
          )}
        </TableCell>
        <TableCell className="text-right text-xs tabular-nums">{ms(row.durationMs)}</TableCell>
        <TableCell className="text-right text-xs tabular-nums">{ms(row.ttftMs)}</TableCell>
        <TableCell className="text-right text-xs tabular-nums">
          {row.llmRounds} / {row.toolCalls}
        </TableCell>
        <TableCell className="text-right text-xs tabular-nums">
          {row.inputTokens} / {row.outputTokens}
        </TableCell>
        <TableCell className="text-right text-xs tabular-nums">{row.credits}</TableCell>
        <TableCell className="text-xs tabular-nums">#{row.userId}</TableCell>
      </TableRow>
      {expanded ? (
        <TableRow>
          <TableCell colSpan={9} className="bg-muted/40">
            <RunSteps traceId={row.traceId} />
          </TableCell>
        </TableRow>
      ) : null}
    </>
  )
}

/** 展开后的步骤表。单独请求：列表不背这份数据。**导出**：反馈页也要复用它。 */
export function RunSteps({ traceId }: { traceId: string }) {
  const detail = useQuery({
    queryKey: ['ai-run-detail', traceId],
    queryFn: () => api.requestEnvelope<RunDetail>(`/api/v1/ai/admin/runs/${traceId}`),
  })

  if (detail.isLoading) {
    return <Spinner />
  }
  if (detail.isError || detail.data === undefined) {
    return <p className="text-destructive text-xs">读取这一轮的步骤失败。</p>
  }
  if (detail.data.spans.length === 0) {
    return (
      <p className="text-muted-foreground text-xs" data-testid="run-spans-empty">
        这一轮没有步骤记录（非对话路径通常只有一次调用）。
      </p>
    )
  }
  return (
    <div className="space-y-2 py-1" data-testid="run-spans">
      <BreakdownBar breakdown={detail.data.breakdown} />
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-12">#</TableHead>
            <TableHead>类型</TableHead>
            <TableHead>名称</TableHead>
            <TableHead>状态</TableHead>
            <TableHead className="text-right">耗时</TableHead>
            <TableHead className="text-right">首字</TableHead>
            <TableHead className="text-right">tokens</TableHead>
            <TableHead className="text-right">详情</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {detail.data.spans.map((span) => (
            <StepRow key={span.seq} span={span}
              payload={detail.data.payloads.find((p) => p.seq === span.seq) ?? null}
              payloadEnabled={detail.data.payloadEnabled} />
          ))}
        </TableBody>
      </Table>
      <div className="text-muted-foreground flex flex-wrap gap-3 text-[11px]">
        {Object.entries(detail.data.attrs).map(([key, value]) => (
          <span key={key}>
            {key}={String(value)}
          </span>
        ))}
      </div>
      <Button variant="outline" size="sm" onClick={() => { void detail.refetch() }}>
        刷新
      </Button>
    </div>
  )
}

/**
 * 一步的详情（WORK-030 阶段 B）。
 *
 * <p>点「查看」展开三块：**工具入参与返回**（在 attrs 里，截断过）、**完整请求**、**完整响应**
 * （在 payload 表里）。排查"它为什么答错"时，光看"成功了、耗时 300ms"没有用——
 * 得知道它收到什么、回了什么、模型到底看到了什么。
 */
function StepRow({ span, payload, payloadEnabled }: {
  span: SpanView
  payload: RunPayload | null
  payloadEnabled: boolean
}) {
  const [open, setOpen] = useState(false)
  const toolArgs = span.attrs?.toolArgs
  const toolResult = span.attrs?.toolResult
  const hasDetail = toolArgs !== undefined || toolResult !== undefined
    || payload !== null || !payloadEnabled

  return (
    <>
      <TableRow data-testid={`run-span-${span.seq}`}>
        <TableCell className="text-xs tabular-nums">{span.seq}</TableCell>
        <TableCell className="text-xs">{span.kind}</TableCell>
        {/* 子步骤按层级缩进（WORK-036 REQ-014）：parentSeq 非空就是挂在某条工具步下面 */}
        <TableCell className={span.parentSeq === null ? 'text-xs' : 'pl-6 text-xs'}>
          {span.parentSeq === null ? null : <span className="text-muted-foreground mr-1">└</span>}
          {span.name}
        </TableCell>
        <TableCell className="text-xs">
          {STATUS_LABEL[span.status] ?? span.status}
          {span.errorType === null ? null : (
            <span className="text-muted-foreground ml-1 text-[10px]">{span.errorType}</span>
          )}
        </TableCell>
        <TableCell className="text-right text-xs tabular-nums">{ms(span.durationMs)}</TableCell>
        <TableCell className="text-right text-xs tabular-nums">
          {span.ttftMs === null ? '—' : ms(span.ttftMs)}
        </TableCell>
        <TableCell className="text-right text-xs tabular-nums">
          {span.inputTokens} / {span.outputTokens}
        </TableCell>
        <TableCell className="text-right">
          {hasDetail ? (
            <Button variant="outline" size="xs" onClick={() => setOpen(!open)}
              data-testid={`span-detail-${span.seq}`}>
              {open ? '收起' : '查看'}
            </Button>
          ) : (
            <span className="text-muted-foreground text-[10px]">—</span>
          )}
        </TableCell>
      </TableRow>
      {open ? (
        <TableRow>
          <TableCell colSpan={8} className="bg-muted/40">
            <div className="space-y-3 py-1">
              {toolArgs === undefined && toolResult === undefined ? null : (
                <>
                  <PayloadBlock title="工具入参" text={String(toolArgs ?? '—')}
                    testId={`span-tool-args-${span.seq}`} />
                  <PayloadBlock title="工具返回" text={String(toolResult ?? '—')}
                    testId={`span-tool-result-${span.seq}`} />
                </>
              )}
              {payload === null ? (
                payloadEnabled ? null : (
                  <p className="text-muted-foreground text-xs" data-testid={`span-payload-off-${span.seq}`}>
                    本次未记录请求体与响应体（采集已停用）。
                  </p>
                )
              ) : payload.truncated ? (
                <p className="text-muted-foreground text-xs" data-testid={`span-payload-truncated-${span.seq}`}>
                  内容超过上限已省略：请求 {payload.requestBytes} 字节、响应 {payload.responseBytes} 字节。
                </p>
              ) : (
                <>
                  <PayloadBlock title="完整请求" text={pretty(payload.requestBody)}
                    testId={`span-payload-request-${span.seq}`} />
                  <PayloadBlock title="完整响应" text={pretty(payload.responseBody)}
                    testId={`span-payload-response-${span.seq}`} />
                </>
              )}
            </div>
          </TableCell>
        </TableRow>
      ) : null}
    </>
  )
}

/** 一块等宽可复制的文本。长内容限高滚动，不把整页撑开。 */
function PayloadBlock({ title, text, testId }: { title: string; text: string; testId: string }) {
  return (
    <div>
      <div className="text-muted-foreground mb-1 flex items-center justify-between text-[11px]">
        <span>{title}</span>
        <span className="tabular-nums">{text.length} 字符</span>
      </div>
      <pre
        className="bg-background max-h-72 overflow-auto rounded-md border p-2 text-[11px] whitespace-pre-wrap"
        data-testid={testId}
      >
        {text}
      </pre>
    </div>
  )
}

/** JSON 就美化，不是就原样——解析失败不该让这一块变空白。 */
function pretty(json: string | null): string {
  if (json === null) {
    return '—'
  }
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

/**
 * 耗时构成条（WORK-036 REQ-008）。
 *
 * <p>四段之和就是这轮的总耗时。看它的意义是**一眼定位慢在哪**：LLM 段长说明上游慢，
 * 工具段长说明某个工具拖住了，"其它"大说明时间花在了我们没单独计量的地方
 * （写库、序列化、SSE 推送）。"其它"为负已被服务端夹到 0，不会显示成负数。
 */
function BreakdownBar({ breakdown }: { breakdown: Breakdown }) {
  const segments = [
    { label: '排队', value: breakdown.queueMs, className: 'bg-muted-foreground/40' },
    { label: 'LLM', value: breakdown.llmMs, className: 'bg-primary' },
    { label: '工具', value: breakdown.toolMs, className: 'bg-chart-2' },
    { label: '其它', value: breakdown.otherMs, className: 'bg-destructive/60' },
  ]
  const total = breakdown.totalMs === 0 ? 1 : breakdown.totalMs
  return (
    <div className="space-y-1" data-testid="run-breakdown">
      <div className="bg-muted flex h-2 overflow-hidden rounded">
        {segments.map((segment) => (
          segment.value <= 0 ? null : (
            <div
              key={segment.label}
              className={segment.className}
              style={{ width: `${(segment.value / total) * 100}%` }}
              title={`${segment.label} ${ms(segment.value)}`}
            />
          )
        ))}
      </div>
      <p className="text-muted-foreground flex flex-wrap gap-3 text-[11px]">
        {segments.map((segment) => (
          <span key={segment.label}>{segment.label} {ms(segment.value)}</span>
        ))}
        <span>合计 {ms(breakdown.totalMs)}</span>
      </p>
    </div>
  )
}
