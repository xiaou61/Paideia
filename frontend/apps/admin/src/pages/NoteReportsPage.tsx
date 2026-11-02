import {
  Alert,
  AlertDescription,
  AlertTitle,
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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ShieldCheck } from 'lucide-react'
import { useState } from 'react'

import { api } from '../api'
import { ConfirmDeleteDialog } from '../knowledge-shared'
import { errorMessage } from '@paideia/core'

/** 一条举报。`noteTitle` 可能为空——笔记可能已经被作者删了。 */
interface Report {
  id: number
  shareId: string
  noteTitle: string | null
  reason: string
  status: 'pending' | 'taken_down'
  createdAt: string | null
}

/**
 * 状态筛选（WORK-030）：后端 `GET /reports?status=` 早就支持，前端一直没传——
 * 于是待处理与已下架混在一张表里，全靠人眼分辨。这里接上，且**默认只看待处理**
 * （那才是需要动手的那一批）。
 */
const list = (status: string) =>
  api.requestEnvelope<Report[]>(
    status === 'all'
      ? '/api/v1/notes/admin/reports'
      : `/api/v1/notes/admin/reports?status=${encodeURIComponent(status)}`,
  )

const STATUS_FILTERS = [
  { value: 'pending', label: '待处理' },
  { value: 'taken_down', label: '已下架' },
  { value: 'all', label: '全部' },
]
const takedown = (id: number) =>
  api.requestEnvelope<boolean>(`/api/v1/notes/admin/reports/${id}/takedown`, { method: 'POST' })

/**
 * 举报处理（用户裁决 11：由管理员处理，不做自动判定）。
 *
 * <p>页面只做两件事：**看见**与**下架**。没有自动判定——"内容该不该撤"是人做的决定，
 * 系统只负责把举报按"待处理优先、早的在前"排好，以及让下架**立刻生效**。
 *
 * <p>下架是二次确认的：它不可逆（撤回链接不进回收站），而且影响的是别人能不能看到一份内容。
 */
export function NoteReportsPage() {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const [statusFilter, setStatusFilter] = useState('pending')
  const reports = useQuery({
    queryKey: ['note-reports', statusFilter],
    queryFn: () => list(statusFilter),
  })

  /** 待确认下架的那条。原生 `window.confirm` 在这里与整站观感不一致，也带不了上下文。 */
  const [pending, setPending] = useState<{ id: number; title: string } | null>(null)

  const remove = useMutation({
    mutationFn: takedown,
    onSuccess: () => {
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['note-reports'] })
    },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  })

  return (
    <div className="space-y-4">
      <PageHeader title="举报处理" description="被举报的分享链接。下架后链接立刻不可读，且不可恢复。" />

      {error === null ? null : (
        <Alert variant="destructive" data-testid="reports-error">
          <AlertTitle>没能完成</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="px-0">
          <div className="border-border flex items-center justify-between gap-4 border-b px-(--card-spacing) py-3">
            <div>
              <h2 className="text-sm font-medium">被举报的分享</h2>
              <p className="text-muted-foreground mt-0.5 text-xs">
                下架后链接立刻不可读，且不可恢复。
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Select
                items={STATUS_FILTERS}
                value={statusFilter}
                onValueChange={(value) => setStatusFilter(value as string)}
              >
                <SelectTrigger size="sm" className="w-28" data-testid="reports-status-filter">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {STATUS_FILTERS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {reports.isSuccess ? (
                <span className="text-muted-foreground text-xs" data-testid="reports-total">
                  共 {reports.data.length} 条
                </span>
              ) : null}
            </div>
          </div>
          {reports.isLoading ? (
            <Spinner />
          ) : (reports.data ?? []).length === 0 ? (
            <Empty data-testid="reports-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <ShieldCheck />
                </EmptyMedia>
                <EmptyTitle>还没有人举报</EmptyTitle>
                <EmptyDescription>学习者分享出去的笔记如果有人举报，会出现在这里等着处理。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <>
            {/* 窄屏卡片（WORK-016）：理由可能较长，卡片里换行显示，不与"下架"按钮抢同一行 */}
            <ul className="divide-border divide-y md:hidden" data-testid="reports-cards">
              {(reports.data ?? []).map((report) => (
                <li key={report.id} className="space-y-2 px-3 py-3">
                  <div className="flex items-start justify-between gap-2">
                    <span className="min-w-0 flex-1 text-sm font-medium">
                      {report.noteTitle ?? '（笔记已删除）'}
                    </span>
                    <Badge
                      variant={report.status === 'pending' ? 'warning' : 'secondary'}
                      data-testid={`report-status-card-${report.id}`}
                    >
                      {report.status === 'pending' ? '待处理' : '已下架'}
                    </Badge>
                  </div>
                  <p className="text-muted-foreground text-xs whitespace-pre-wrap">{report.reason}</p>
                  {report.status === 'pending' ? (
                    <Button
                      size="sm"
                      variant="outline"
                      className="w-full"
                      disabled={remove.isPending}
                      data-testid={`report-takedown-card-${report.id}`}
                      onClick={() => setPending({ id: report.id, title: report.noteTitle ?? '（笔记已删除）' })}
                    >
                      下架
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>

            <div className="hidden md:block">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>笔记</TableHead>
                  <TableHead>理由</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody data-testid="reports-table">
                {(reports.data ?? []).map((report) => (
                  <TableRow key={report.id} data-testid={`report-row-${report.id}`}>
                    <TableCell className="max-w-xs truncate">{report.noteTitle ?? '（笔记已删除）'}</TableCell>
                    <TableCell className="max-w-md whitespace-pre-wrap">{report.reason}</TableCell>
                    <TableCell>
                      <Badge
                        variant={report.status === 'pending' ? 'warning' : 'secondary'}
                        data-testid={`report-status-${report.id}`}
                      >
                        {report.status === 'pending' ? '待处理' : '已下架'}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      {report.status === 'pending' ? (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={remove.isPending}
                          data-testid={`report-takedown-${report.id}`}
                          onClick={() => setPending({ id: report.id, title: report.noteTitle ?? '（笔记已删除）' })}
                        >
                          下架
                        </Button>
                      ) : null}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            </div>
            </>
          )}
        </CardContent>
      </Card>

      <ConfirmDeleteDialog
        open={pending !== null}
        title="确认下架这条分享？"
        label="分享"
        name={pending?.title ?? ''}
        confirmLabel="下架"
        hint="链接会立刻失效，已经拿到链接的人也打不开了。"
        busy={remove.isPending}
        onCancel={() => setPending(null)}
        onConfirm={() => {
          if (pending !== null) {
            remove.mutate(pending.id)
          }
        }}
      />
    </div>
  )
}
