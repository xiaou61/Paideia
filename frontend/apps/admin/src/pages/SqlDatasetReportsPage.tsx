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
import { Flag } from 'lucide-react'
import { useState } from 'react'

import { api } from '../api'
import { errorMessage } from '@paideia/core'

/** 一条数据集举报。 */
interface DatasetReport {
  id: number
  datasetKey: string
  reporterUserId: number
  reason: string
  status: 'pending' | 'taken_down'
  createdAt: string | null
  handledAt: string | null
}

const STATUS_FILTERS = [
  { value: 'pending', label: '待处理' },
  { value: 'taken_down', label: '已下架' },
  { value: 'all', label: '全部' },
]

const list = (status: string) =>
  api.requestEnvelope<DatasetReport[]>(
    status === 'all'
      ? '/api/v1/sandbox/admin/sql-datasets/reports'
      : `/api/v1/sandbox/admin/sql-datasets/reports?status=${encodeURIComponent(status)}`,
  )

const takeDown = (key: string) =>
  api.requestEnvelope<null>(`/api/v1/sandbox/admin/sql-datasets/${encodeURIComponent(key)}/take-down`, {
    method: 'POST',
  })

/**
 * 数据集举报处理（WORK-035 T-11）。
 *
 * <p>与 `NoteReportsPage` 同形（那是这套治理的模板）：只做**看见**与**下架**，
 * 没有自动判定——"内容该不该撤"是人做的决定。
 *
 * <p>下架在这里直接做（不再二次确认）：这个页面上管理员已经明确点了"下架"，
 * 而目标是一个已经被举报的内容。数据集管理页那个确认是因为从"浏览列表"直接下架时
 * 缺少上下文。
 */
export function SqlDatasetReportsPage() {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState('pending')

  const reports = useQuery({
    queryKey: ['sql-dataset-reports', statusFilter],
    queryFn: () => list(statusFilter),
  })

  const takeDownMutation = useMutation({
    mutationFn: takeDown,
    onSuccess: () => {
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['sql-dataset-reports'] })
      void queryClient.invalidateQueries({ queryKey: ['sql-datasets'] })
    },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  })

  const rows = reports.data ?? []

  return (
    <div className="space-y-4">
      <PageHeader
        title="数据集举报"
        description="被举报的社区 SQL 数据集。下架后所有人立刻不能用它做题。"
      />

      {error === null ? null : (
        <Alert variant="destructive" data-testid="sql-dataset-reports-error">
          <AlertTitle>没能完成</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="px-0">
          <div className="border-border flex items-center justify-between gap-4 border-b px-(--card-spacing) py-3">
            <div>
              <h2 className="text-sm font-medium">举报清单</h2>
              <p className="text-muted-foreground mt-0.5 text-xs">
                同一个人对同一个数据集只留一条举报。
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Select
                items={STATUS_FILTERS}
                value={statusFilter}
                onValueChange={(value) => setStatusFilter(value as string)}
              >
                <SelectTrigger size="sm" className="w-28" data-testid="sql-dataset-reports-status-filter">
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
                <span className="text-muted-foreground text-xs" data-testid="sql-dataset-reports-total">
                  共 {rows.length} 条
                </span>
              ) : null}
            </div>
          </div>

          {reports.isLoading ? (
            <Spinner />
          ) : rows.length === 0 ? (
            <Empty data-testid="sql-dataset-reports-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <Flag />
                </EmptyMedia>
                <EmptyTitle>还没有人举报</EmptyTitle>
                <EmptyDescription>
                  学习者对社区数据集提交的举报会出现在这里等着处理。
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>数据集 key</TableHead>
                  <TableHead>理由</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody data-testid="sql-dataset-reports-table">
                {rows.map((report) => (
                  <TableRow key={report.id} data-testid={`sql-dataset-report-row-${report.id}`}>
                    <TableCell className="max-w-[16rem] truncate font-mono text-xs">
                      {report.datasetKey}
                    </TableCell>
                    <TableCell className="max-w-md whitespace-pre-wrap">{report.reason}</TableCell>
                    <TableCell>
                      <Badge variant={report.status === 'pending' ? 'warning' : 'secondary'}>
                        {report.status === 'pending' ? '待处理' : '已下架'}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      {report.status === 'pending' ? (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={takeDownMutation.isPending}
                          data-testid={`sql-dataset-report-takedown-${report.id}`}
                          onClick={() => takeDownMutation.mutate(report.datasetKey)}
                        >
                          下架
                        </Button>
                      ) : null}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
