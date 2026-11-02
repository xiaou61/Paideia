import {
  Badge,
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
import { formatLocalDateTime } from '@paideia/core'
import { useState } from 'react'
import { Receipt } from 'lucide-react'

import { api } from '../api'

/** 一行流水。与后端 `CreditService.LedgerRow` 同构。 */
interface LedgerRow {
  id: number
  userId: number
  delta: number
  kind: 'ADJUST' | 'RESERVE' | 'SETTLE' | 'REFUND'
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  refMessageId: number | null
  operatorUserId: number | null
  note: string | null
  createdAt: string | null
}

const KIND_LABEL: Record<string, string> = {
  ADJUST: '管理员调整',
  RESERVE: '预扣',
  SETTLE: '结算',
  REFUND: '退还',
}

/**
 * 分页 + 筛选（WORK-030）。原先写死 `limit=200`，既翻不了页也筛不了——
 * 一次调用在流水里是多行，200 行大概只够看几十次调用。
 */
const list = (kind: string, userId: string, page: number) => {
  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) })
  if (kind !== 'all') {
    params.set('kind', kind)
  }
  if (userId.trim() !== '') {
    params.set('userId', userId.trim())
  }
  return api.requestEnvelope<{ total: number; page: number; size: number; items: LedgerRow[] }>(
    `/api/v1/ai/admin/credits/ledger?${params.toString()}`,
  )
}

const PAGE_SIZE = 50

const KIND_FILTERS = [
  { value: 'all', label: '全部种类' },
  { value: 'ADJUST', label: '管理员调整' },
  { value: 'RESERVE', label: '预扣' },
  { value: 'SETTLE', label: '结算' },
  { value: 'REFUND', label: '退还' },
]

/** 时间按本机时区显示；解析不出来就原样显示，不显示 "Invalid Date"。 */
function when(value: string | null): string {
  return formatLocalDateTime(value)
}

/**
 * 用量审计（WORK-009 REQ-007 / AC-005 / AC-006）。
 *
 * <p>它回答三个问题：**谁用了多少、花了多少分、是谁给他发的分**。数据源只有一张
 * `ai_credit_ledger`——流水自带 model 与 token 数，所以**不需要 join 消息表**（精简那一路的
 * 用量本来也没有落库的地方，靠 join 会永远漏掉那一半消耗）。
 *
 * <p>四个种类各有含义，别混：`预扣` 是调用前占住的额度、`结算`/`退还` 是按真实用量结算的
 * 差额、`管理员调整` 是发放或回收。**一次调用在流水里是多行**——这正是"先预留后结算"的样子。
 *
 * <p>只读：审计页不做任何写操作，所以这里没有按钮。
 */
export function AiCreditsAuditPage() {
  const [kind, setKind] = useState('all')
  const [userId, setUserId] = useState('')
  const [page, setPage] = useState(1)
  const rows = useQuery({
    queryKey: ['ai-credits-audit', kind, userId, page],
    queryFn: () => list(kind, userId, page),
  })
  const items = rows.data?.items ?? []
  const totalPages = Math.max(1, Math.ceil((rows.data?.total ?? 0) / PAGE_SIZE))

  return (
    <div className="space-y-4">
      <PageHeader
        title="用量审计"
        description="全平台的积分流水。一次调用会有多行（预扣 + 结算/退还），这是「先预留、后按实际用量结算」的正常形态。"
      />

      <Card>
        <CardContent className="px-0">
          <div className="border-border flex items-center justify-between gap-4 border-b px-(--card-spacing) py-3">
            <div>
              <h2 className="text-sm font-medium">最近的流水</h2>
              <p className="text-muted-foreground mt-0.5 text-xs">
                按时间倒序。消耗那一行的 token 数来自上游返回的真实用量。
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Select
                items={KIND_FILTERS}
                value={kind}
                onValueChange={(value) => {
                  setKind(value as string)
                  setPage(1)
                }}
              >
                <SelectTrigger size="sm" className="w-32" data-testid="credits-audit-kind">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {KIND_FILTERS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Input
                className="h-8 w-28"
                inputMode="numeric"
                placeholder="用户 id"
                value={userId}
                onChange={(event) => {
                  setUserId(event.target.value)
                  setPage(1)
                }}
                data-testid="credits-audit-user"
              />
              {rows.isSuccess ? (
                <span className="text-muted-foreground shrink-0 text-xs" data-testid="credits-audit-total">
                  共 {rows.data.total} 条
                </span>
              ) : null}
            </div>
          </div>

          {rows.isLoading ? (
            <Spinner />
          ) : items.length === 0 ? (
            <Empty data-testid="credits-audit-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <Receipt />
                </EmptyMedia>
                <EmptyTitle>还没有任何流水</EmptyTitle>
                <EmptyDescription>
                  有人用过 AI、或有管理员调整过积分之后，这里会有记录。
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <>
            {/* 窄屏卡片（WORK-016）：八列在 375px 上无法阅读，卡片按"谁 / 何时 / 变了多少"排 */}
            <ul className="divide-border divide-y md:hidden" data-testid="credits-audit-cards">
              {items.map((row) => (
                <li key={row.id} className="space-y-1.5 px-3 py-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-medium tabular-nums">用户 #{row.userId}</span>
                    <span
                      className={`text-sm font-medium tabular-nums ${row.delta < 0 ? 'text-destructive' : ''}`}
                    >
                      {row.delta > 0 ? `+${row.delta}` : row.delta}
                    </span>
                  </div>
                  <div className="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
                    <Badge
                      variant={row.kind === 'ADJUST' ? 'warning' : 'secondary'}
                      data-testid={`credits-audit-kind-card-${row.id}`}
                    >
                      {KIND_LABEL[row.kind] ?? row.kind}
                    </Badge>
                    <span className="tabular-nums">{when(row.createdAt)}</span>
                    {row.model === null ? null : <span>{row.model}</span>}
                    {row.inputTokens === null ? null : (
                      <span className="tabular-nums">
                        token {row.inputTokens} / {row.outputTokens ?? 0}
                      </span>
                    )}
                    {row.operatorUserId === null ? null : (
                      <span className="tabular-nums">操作人 #{row.operatorUserId}</span>
                    )}
                  </div>
                  {row.note === null ? null : (
                    <p className="text-muted-foreground text-xs">{row.note}</p>
                  )}
                </li>
              ))}
            </ul>

            <div className="hidden md:block">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>时间</TableHead>
                  <TableHead>用户</TableHead>
                  <TableHead>类型</TableHead>
                  <TableHead>模型</TableHead>
                  <TableHead className="text-right">token（入/出）</TableHead>
                  <TableHead className="text-right">积分</TableHead>
                  <TableHead>操作人</TableHead>
                  <TableHead>备注</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody data-testid="credits-audit-table">
                {items.map((row) => (
                  <TableRow key={row.id} data-testid={`credits-audit-row-${row.id}`}>
                    <TableCell className="text-nowrap text-xs">{when(row.createdAt)}</TableCell>
                    <TableCell className="tabular-nums">{row.userId}</TableCell>
                    <TableCell>
                      <Badge
                        variant={row.kind === 'ADJUST' ? 'warning' : 'secondary'}
                        data-testid={`credits-audit-kind-${row.id}`}
                      >
                        {KIND_LABEL[row.kind] ?? row.kind}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground text-xs">{row.model ?? '—'}</TableCell>
                    <TableCell className="text-right text-xs tabular-nums">
                      {row.inputTokens === null ? '—' : `${row.inputTokens} / ${row.outputTokens ?? 0}`}
                    </TableCell>
                    <TableCell
                      className={`text-right tabular-nums ${row.delta < 0 ? 'text-destructive' : ''}`}
                    >
                      {row.delta > 0 ? `+${row.delta}` : row.delta}
                    </TableCell>
                    <TableCell className="tabular-nums">{row.operatorUserId ?? '—'}</TableCell>
                    <TableCell className="text-muted-foreground max-w-xs truncate text-xs">
                      {row.note ?? '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            </div>
            <div className="border-border text-muted-foreground flex items-center justify-between gap-4 border-t px-(--card-spacing) py-3 text-xs">
              <span>共 {rows.data?.total ?? 0} 条</span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className="border-input rounded-md border px-2 py-1 disabled:opacity-50"
                  disabled={page <= 1}
                  onClick={() => setPage(page - 1)}
                  data-testid="credits-audit-prev"
                >
                  上一页
                </button>
                <span className="tabular-nums">{page} / {totalPages}</span>
                <button
                  type="button"
                  className="border-input rounded-md border px-2 py-1 disabled:opacity-50"
                  disabled={page >= totalPages}
                  onClick={() => setPage(page + 1)}
                  data-testid="credits-audit-next"
                >
                  下一页
                </button>
              </div>
            </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
