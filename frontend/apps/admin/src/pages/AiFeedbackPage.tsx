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
import { formatLocalDateTime } from '@paideia/core'
import { MessageSquareWarning } from 'lucide-react'
import { useState } from 'react'

import { api } from '../api'
import { RunSteps } from './AiRunsListPage'

/**
 * 助手回答的反馈（WORK-036 C 组）。
 *
 * <p>这是**唯一回答"回答好不好"**的页面：其余统计只能说快不快、贵不贵。
 * 点开一条能直接看到那一轮的完整请求与响应（复用调用明细的步骤组件），
 * 于是"用户说答非所问"与"模型当时看到了什么"在同一屏里。
 */
interface FeedbackRow {
  id: number
  userId: number
  conversationId: number | null
  messageId: number | null
  traceId: string | null
  kind: string | null
  rating: 'up' | 'down'
  reason: string | null
  createdAt: string
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

const REASON_LABEL: Record<string, string> = {
  off_topic: '答非所问',
  too_verbose: '太啰嗦',
  incorrect: '内容有错',
  other: '其它',
}

const WINDOWS = [
  { value: '24h', label: '近 24 小时', hours: 24 },
  { value: '7d', label: '近 7 天', hours: 24 * 7 },
  { value: '30d', label: '近 30 天', hours: 24 * 30 },
] as const

const PAGE_SIZE = 50

function windowParams(choice: string): string {
  const chosen = WINDOWS.find((option) => option.value === choice) ?? WINDOWS[1]
  const to = new Date()
  const from = new Date(to.getTime() - chosen.hours * 3600 * 1000)
  return `from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`
}

function when(value: string): string {
  return formatLocalDateTime(value)
}

export function AiFeedbackPage() {
  const [hour, setHour] = useState<string>('7d')
  const [rating, setRating] = useState('')
  const [reason, setReason] = useState('')
  const [page, setPage] = useState(1)
  const [expanded, setExpanded] = useState<string | null>(null)

  const list = useQuery({
    queryKey: ['ai-feedback-list', hour, rating, reason, page],
    queryFn: () => {
      const params = new URLSearchParams()
      if (rating !== '') params.set('rating', rating)
      if (reason !== '') params.set('reason', reason)
      params.set('page', String(page))
      params.set('size', String(PAGE_SIZE))
      return api.requestEnvelope<Page<FeedbackRow>>(
        `/api/v1/ai/feedback/admin?${windowParams(hour)}&${params.toString()}`,
      )
    },
  })

  const rows = list.data?.items ?? []
  const totalPages = Math.max(1, Math.ceil((list.data?.total ?? 0) / PAGE_SIZE))

  return (
    <div className="space-y-4">
      <PageHeader
        title="回答反馈"
        description="学习者对助手回答的赞与踩。点开一条能看到那一轮的完整请求与响应——「答非所问」到底是没给上下文，还是提示词不对，只能这样看出来。"
      />

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 py-3">
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">时间区间</span>
            <Select
              items={WINDOWS.map((option) => ({ value: option.value, label: option.label }))}
              value={hour}
              onValueChange={(value) => { setHour(value as string); setPage(1) }}
            >
              <SelectTrigger size="sm" className="w-28" data-testid="feedback-filter-window">
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
            <span className="text-muted-foreground block">评价</span>
            <Select
              items={[{ value: '', label: '全部' }, { value: 'down', label: '踩' }, { value: 'up', label: '赞' }]}
              value={rating}
              onValueChange={(value) => { setRating(value as string); setPage(1) }}
            >
              <SelectTrigger size="sm" className="w-24" data-testid="feedback-filter-rating">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="">全部</SelectItem>
                <SelectItem value="down">踩</SelectItem>
                <SelectItem value="up">赞</SelectItem>
              </SelectContent>
            </Select>
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground block">原因</span>
            <Select
              items={[
                { value: '', label: '全部' },
                ...Object.entries(REASON_LABEL).map(([value, label]) => ({ value, label })),
              ]}
              value={reason}
              onValueChange={(value) => { setReason(value as string); setPage(1) }}
            >
              <SelectTrigger size="sm" className="w-28" data-testid="feedback-filter-reason">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="">全部</SelectItem>
                {Object.entries(REASON_LABEL).map(([value, label]) => (
                  <SelectItem key={value} value={value}>{label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          {list.isSuccess ? (
            <span className="text-muted-foreground ml-auto text-xs" data-testid="feedback-total">
              共 {list.data.total} 条
            </span>
          ) : null}
        </CardContent>
      </Card>

      {list.isLoading ? <Spinner /> : null}

      {list.isSuccess && rows.length === 0 ? (
        <Card>
          <Empty data-testid="feedback-empty">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <MessageSquareWarning />
              </EmptyMedia>
              <EmptyTitle>还没有反馈</EmptyTitle>
              <EmptyDescription>学习者在回答下方点赞或点踩之后，这里会有记录。</EmptyDescription>
            </EmptyHeader>
          </Empty>
        </Card>
      ) : null}

      {rows.length > 0 ? (
        <Card>
          <CardContent className="px-0">
            <Table data-testid="feedback-table">
              <TableHeader>
                <TableRow>
                  <TableHead>时间</TableHead>
                  <TableHead>评价</TableHead>
                  <TableHead>原因</TableHead>
                  <TableHead>场景</TableHead>
                  <TableHead>用户</TableHead>
                  <TableHead>轮次</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <FeedbackTableRow key={row.id} row={row}
                    expanded={expanded === String(row.id)}
                    onToggle={() => { setExpanded(expanded === String(row.id) ? null : String(row.id)) }} />
                ))}
              </TableBody>
            </Table>
            <div className="border-border text-muted-foreground flex items-center justify-between gap-4 border-t px-(--card-spacing) py-3 text-xs">
              <span>共 {list.data?.total ?? 0} 条</span>
              <div className="flex items-center gap-2">
                <Button variant="outline" size="xs" disabled={page <= 1}
                  onClick={() => setPage(page - 1)} data-testid="feedback-prev">
                  上一页
                </Button>
                <span className="tabular-nums">{page} / {totalPages}</span>
                <Button variant="outline" size="xs" disabled={page >= totalPages}
                  onClick={() => setPage(page + 1)} data-testid="feedback-next">
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

function FeedbackTableRow({ row, expanded, onToggle }: {
  row: FeedbackRow
  expanded: boolean
  onToggle: () => void
}) {
  return (
    <>
      <TableRow className="cursor-pointer" onClick={onToggle} data-testid={`feedback-row-${row.id}`}>
        <TableCell className="text-xs whitespace-nowrap">{when(row.createdAt)}</TableCell>
        <TableCell>
          <Badge variant={row.rating === 'down' ? 'destructive' : 'secondary'}>
            {row.rating === 'down' ? '踩' : '赞'}
          </Badge>
        </TableCell>
        <TableCell className="text-xs">
          {row.reason === null ? '—' : REASON_LABEL[row.reason] ?? row.reason}
        </TableCell>
        <TableCell className="text-xs">
          {row.kind === null ? '—' : KIND_LABEL[row.kind] ?? row.kind}
        </TableCell>
        <TableCell className="text-xs tabular-nums">#{row.userId}</TableCell>
        <TableCell className="text-muted-foreground font-mono text-[10px]">
          {row.traceId === null ? '未关联（采集关闭）' : row.traceId.slice(0, 12)}
        </TableCell>
      </TableRow>
      {expanded ? (
        <TableRow>
          <TableCell colSpan={6} className="bg-muted/40">
            {row.traceId === null ? (
              <p className="text-muted-foreground text-xs">
                这条反馈产生时采集是关闭的，没有对应的轮次记录可看。
              </p>
            ) : (
              <RunSteps traceId={row.traceId} />
            )}
          </TableCell>
        </TableRow>
      ) : null}
    </>
  )
}
