import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Card,
  CardContent,
  PageHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, FolderUp, RefreshCw } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { errorMessage } from '@paideia/core'

import { admin } from '../knowledge-shared'

/**
 * 文档导入（WORK-076 TASK-012）。
 *
 * <p>选一个文件夹（保留目录树）→ 逐个上传到附件端点 → 登记为导入任务 → 后台异步解析/蒸馏成
 * 条目草稿。列表轮询任务状态；失败可重试。生成的是**草稿**，要发布仍需去「条目管理」审核。
 */
export function KnowledgeImportPage() {
  const queryClient = useQueryClient()
  const folderRef = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [picked, setPicked] = useState<{ file: File; relativePath: string }[]>([])

  // 原生目录选择没有 React 属性，只能落成 DOM 属性
  useEffect(() => {
    const el = folderRef.current
    if (el !== null) {
      el.setAttribute('webkitdirectory', '')
      el.setAttribute('directory', '')
    }
  }, [])

  const imports = useQuery({
    queryKey: ['admin-knowledge-imports'],
    queryFn: () => admin.knowledgeImports.list(),
    // 有在途任务时轮询；全部完成后停
    refetchInterval: (query) => {
      const rows = query.state.data ?? []
      return rows.some((row) => row.status === 'pending' || row.status === 'processing') ? 3000 : false
    },
  })

  const startImport = useMutation({
    mutationFn: async () => {
      const items: { fileId: string; relativePath: string | null }[] = []
      for (const entry of picked) {
        const uploaded = await admin.uploadFile(entry.file)
        items.push({ fileId: uploaded.id, relativePath: entry.relativePath })
      }
      return admin.knowledgeImports.create(null, items)
    },
    onSuccess: async () => {
      setPicked([])
      if (folderRef.current !== null) {
        folderRef.current.value = ''
      }
      await queryClient.invalidateQueries({ queryKey: ['admin-knowledge-imports'] })
    },
    onError: (cause) => setError(errorMessage(cause)),
  })

  const retry = useMutation({
    mutationFn: (id: number) => admin.knowledgeImports.retry(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-knowledge-imports'] })
    },
    onError: (cause) => setError(errorMessage(cause)),
  })

  return (
    <>
      <PageHeader
        title="文档导入"
        description="选一个文件夹（保留目录结构），系统解析文档并蒸馏成知识条目草稿；草稿需在「条目管理」审核后发布。首期支持 PDF。"
      />

      <Card className="mb-6">
        <CardContent className="space-y-3 pt-6">
          <div className="flex flex-wrap items-center gap-3">
            <label className="border-border bg-background hover:bg-muted inline-flex h-9 cursor-pointer items-center gap-2 rounded-lg border px-3 text-sm font-medium">
              <FolderUp className="size-4" />
              选择文件夹
              <input
                ref={folderRef}
                type="file"
                multiple
                className="sr-only"
                data-testid="import-folder-input"
                onChange={(event) => {
                  const files = Array.from(event.target.files ?? [])
                  setPicked(
                    files.map((file) => ({
                      file,
                      relativePath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
                    })),
                  )
                }}
              />
            </label>
            <Button
              disabled={picked.length === 0 || startImport.isPending}
              onClick={() => startImport.mutate()}
              data-testid="import-start"
            >
              {startImport.isPending ? '上传并登记中…' : `导入所选（${picked.length}）`}
            </Button>
            {picked.length > 0 ? (
              <span className="text-muted-foreground text-sm">已选 {picked.length} 个文件</span>
            ) : null}
          </div>
          {error === null ? null : (
            <Alert variant="destructive" data-testid="import-error">
              <AlertTriangle className="size-4" />
              <AlertTitle>导入失败</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
        </CardContent>
      </Card>

      <Table data-testid="import-table">
        <TableHeader>
          <TableRow>
            <TableHead>#</TableHead>
            <TableHead>文件</TableHead>
            <TableHead>状态</TableHead>
            <TableHead>生成条目</TableHead>
            <TableHead>说明</TableHead>
            <TableHead className="text-right">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {(imports.data ?? []).map((row) => (
            <TableRow key={row.id} data-testid={`import-row-${row.id}`}>
              <TableCell>{row.id}</TableCell>
              <TableCell className="max-w-[22rem] truncate">{row.relativePath ?? row.fileId}</TableCell>
              <TableCell>
                <Badge variant={row.status === 'failed' ? 'destructive' : row.status === 'succeeded' ? 'default' : 'secondary'}>
                  {statusLabel(row.status)}
                </Badge>
              </TableCell>
              <TableCell>{row.entryId ?? '—'}</TableCell>
              <TableCell className="text-muted-foreground max-w-[20rem] truncate text-xs">
                {row.error ?? ''}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost"
                  size="xs"
                  disabled={row.status !== 'failed' || retry.isPending}
                  onClick={() => retry.mutate(row.id)}
                  data-testid={`import-retry-${row.id}`}
                >
                  <RefreshCw className="size-3.5" />
                  重试
                </Button>
              </TableCell>
            </TableRow>
          ))}
          {(imports.data ?? []).length === 0 ? (
            <TableRow>
              <TableCell colSpan={6} className="text-muted-foreground text-center">
                {imports.isPending ? '加载中…' : '还没有导入任务'}
              </TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>
    </>
  )
}

function statusLabel(status: string): string {
  switch (status) {
    case 'pending':
      return '排队中'
    case 'processing':
      return '处理中'
    case 'succeeded':
      return '成功'
    case 'failed':
      return '失败'
    default:
      return status
  }
}
