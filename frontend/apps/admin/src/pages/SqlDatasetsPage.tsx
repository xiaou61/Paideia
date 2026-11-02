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
import { Database } from 'lucide-react'
import { useState } from 'react'

import { api } from '../api'
import { ConfirmDeleteDialog } from '../knowledge-shared'
import { errorMessage } from '@paideia/core'

/** 社区数据集的一行（管理端视角）。 */
interface CommunityDataset {
  datasetKey: string
  name: string
  ownerUserId: number | null
  /** 上传者用户名；作者注销后是「已注销用户」。 */
  ownerName: string
  tableCount: number
  rowCount: number
  reportCount: number
}

const STATUS_FILTERS = [
  { value: 'published', label: '已发布' },
  { value: 'taken_down', label: '已下架' },
  { value: 'all', label: '全部' },
]

const list = (status: string) =>
  api.requestEnvelope<CommunityDataset[]>(
    status === 'all'
      ? '/api/v1/sandbox/admin/sql-datasets'
      : `/api/v1/sandbox/admin/sql-datasets?status=${encodeURIComponent(status)}`,
  )

const takeDown = (key: string) =>
  api.requestEnvelope<null>(`/api/v1/sandbox/admin/sql-datasets/${encodeURIComponent(key)}/take-down`, {
    method: 'POST',
  })

const restore = (key: string) =>
  api.requestEnvelope<null>(`/api/v1/sandbox/admin/sql-datasets/${encodeURIComponent(key)}/restore`, {
    method: 'POST',
  })

/**
 * 数据集管理（WORK-035 T-11）。
 *
 * <p><b>只能下架与恢复，不能编辑</b>：内容属于作者。管理员能做的只是让它不可见——
 * 与 `note_reports` 的治理口径一致（举报 → 下架，不是"管理员改写内容"）。
 *
 * <p><b>下架是二次确认的</b>：它会让所有正在用它做题的人立刻失败，而那些人不知道发生了什么。
 */
export function SqlDatasetsPage() {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState('published')
  const [pending, setPending] = useState<{ key: string; name: string } | null>(null)

  const datasets = useQuery({
    queryKey: ['sql-datasets', statusFilter],
    queryFn: () => list(statusFilter),
  })

  const takeDownMutation = useMutation({
    mutationFn: takeDown,
    onSuccess: () => {
      setError(null)
      setPending(null)
      void queryClient.invalidateQueries({ queryKey: ['sql-datasets'] })
    },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  })

  const restoreMutation = useMutation({
    mutationFn: restore,
    onSuccess: () => {
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['sql-datasets'] })
    },
    onError: (cause: unknown) => setError(errorMessage(cause)),
  })

  const rows = datasets.data ?? []

  return (
    <div className="space-y-4">
      <PageHeader
        title="SQL 数据集"
        description="学习者发布到社区的数据集。下架后所有人立刻不能用它做题，作者仍保留它。"
      />

      {error === null ? null : (
        <Alert variant="destructive" data-testid="sql-datasets-error">
          <AlertTitle>没能完成</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="px-0">
          <div className="border-border flex items-center justify-between gap-4 border-b px-(--card-spacing) py-3">
            <div>
              <h2 className="text-sm font-medium">社区数据集</h2>
              <p className="text-muted-foreground mt-0.5 text-xs">
                举报数来自学习者的举报；下架会同时把这些举报标成已处理。
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Select
                items={STATUS_FILTERS}
                value={statusFilter}
                onValueChange={(value) => setStatusFilter(value as string)}
              >
                <SelectTrigger size="sm" className="w-28" data-testid="sql-datasets-status-filter">
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
              {datasets.isSuccess ? (
                <span className="text-muted-foreground text-xs" data-testid="sql-datasets-total">
                  共 {rows.length} 个
                </span>
              ) : null}
            </div>
          </div>

          {datasets.isLoading ? (
            <Spinner />
          ) : rows.length === 0 ? (
            <Empty data-testid="sql-datasets-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <Database />
                </EmptyMedia>
                <EmptyTitle>还没有社区数据集</EmptyTitle>
                <EmptyDescription>
                  学习者把自己的私人数据集发布出来之后，会出现在这里。
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead>上传者</TableHead>
                  <TableHead>规模</TableHead>
                  <TableHead>举报</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody data-testid="sql-datasets-table">
                {rows.map((row) => (
                  <TableRow key={row.datasetKey} data-testid={`sql-dataset-row-${row.datasetKey}`}>
                    <TableCell className="max-w-xs truncate font-medium">{row.name}</TableCell>
                    <TableCell className="text-muted-foreground text-xs">{row.ownerName}</TableCell>
                    <TableCell className="text-muted-foreground text-xs">
                      {row.tableCount} 表 / {row.rowCount} 条插入语句
                    </TableCell>
                    <TableCell>
                      {row.reportCount === 0 ? (
                        <span className="text-muted-foreground text-xs">无</span>
                      ) : (
                        <Badge variant="warning">{row.reportCount} 条</Badge>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={takeDownMutation.isPending}
                          data-testid={`sql-dataset-takedown-${row.datasetKey}`}
                          onClick={() => setPending({ key: row.datasetKey, name: row.name })}
                        >
                          下架
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={restoreMutation.isPending}
                          data-testid={`sql-dataset-restore-${row.datasetKey}`}
                          onClick={() => restoreMutation.mutate(row.datasetKey)}
                        >
                          恢复
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <ConfirmDeleteDialog
        open={pending !== null}
        title="下架数据集"
        label="数据集"
        name={pending?.name ?? ''}
        hint="下架后所有人立刻不能用它做题，正在做题的人会失败。作者仍保留这个数据集，可以之后重新发布。"
        confirmLabel="下架"
        busy={takeDownMutation.isPending}
        onCancel={() => setPending(null)}
        onConfirm={() => {
          if (pending !== null) {
            takeDownMutation.mutate(pending.key)
          }
        }}
      />
    </div>
  )
}
