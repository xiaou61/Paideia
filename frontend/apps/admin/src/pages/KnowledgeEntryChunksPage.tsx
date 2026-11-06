import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  PageHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, History, Pencil } from 'lucide-react'
import { useState } from 'react'
import { useParams } from 'react-router'
import { errorMessage, formatLocalDateTime } from '@paideia/core'

import { admin } from '../knowledge-shared'

/**
 * 检索切片编辑（WORK-076 TASK-013）。
 *
 * <p>展示某条目的自动切片与手工覆盖；可编辑单块（只重建该块向量）、查看历史、回滚。
 * 冲突（正文变更导致块错位）与孤儿（自动块已不存在）都会明确标注，不静默覆盖。
 */
export function KnowledgeEntryChunksPage() {
  const params = useParams()
  const entryId = Number(params.entryId)
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<{ index: number; content: string } | null>(null)
  const [historyFor, setHistoryFor] = useState<number | null>(null)

  const chunks = useQuery({
    queryKey: ['admin-chunks', entryId],
    queryFn: () => admin.chunkEdits.list(entryId),
    enabled: Number.isFinite(entryId),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['admin-chunks', entryId] })

  const save = useMutation({
    mutationFn: (input: { index: number; content: string }) =>
      admin.chunkEdits.edit(entryId, input.index, input.content),
    onSuccess: async () => {
      setEditing(null)
      await invalidate()
    },
    onError: (cause) => setError(errorMessage(cause)),
  })

  const history = useQuery({
    queryKey: ['admin-chunk-revisions', entryId, historyFor],
    queryFn: () => admin.chunkEdits.revisions(entryId, historyFor as number),
    enabled: historyFor !== null,
  })

  const rollback = useMutation({
    mutationFn: (input: { index: number; version: number }) =>
      admin.chunkEdits.rollback(entryId, input.index, input.version),
    onSuccess: async () => {
      setHistoryFor(null)
      await invalidate()
    },
    onError: (cause) => setError(errorMessage(cause)),
  })

  return (
    <>
      <PageHeader
        title="检索切片"
        description={`条目 #${entryId} 的 RAG 切片。编辑单块只重建该块向量，不被正文重建覆盖；可回滚到历史版本。`}
      />

      {error === null ? null : (
        <Alert variant="destructive" className="mb-4" data-testid="chunks-error">
          <AlertTriangle className="size-4" />
          <AlertTitle>操作失败</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="pt-6">
          <Table data-testid="chunks-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-14">#</TableHead>
                <TableHead className="w-24">来源</TableHead>
                <TableHead>内容</TableHead>
                <TableHead className="w-40 text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(chunks.data ?? []).map((chunk) => (
                <TableRow key={chunk.chunkIndex} data-testid={`chunk-row-${chunk.chunkIndex}`}>
                  <TableCell>{chunk.chunkIndex}</TableCell>
                  <TableCell>
                    <Badge variant={chunk.origin === 'manual' ? 'default' : 'secondary'}>
                      {chunk.origin === 'manual' ? '手工' : '自动'}
                    </Badge>
                    {chunk.conflict ? (
                      <Badge variant="destructive" className="ml-1" title="正文变更导致该块与自动切分不一致">
                        冲突
                      </Badge>
                    ) : null}
                    {chunk.orphaned ? (
                      <Badge variant="outline" className="ml-1" title="自动块已不存在">
                        孤儿
                      </Badge>
                    ) : null}
                  </TableCell>
                  <TableCell>
                    {editing !== null && editing.index === chunk.chunkIndex ? (
                      <textarea
                        className="border-border bg-background min-h-32 w-full rounded-md border p-2 font-mono text-xs"
                        value={editing.content}
                        data-testid={`chunk-edit-${chunk.chunkIndex}`}
                        onChange={(event) => setEditing({ index: chunk.chunkIndex, content: event.target.value })}
                      />
                    ) : (
                      <span className="line-clamp-3 text-xs whitespace-pre-wrap">{chunk.content}</span>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    {editing !== null && editing.index === chunk.chunkIndex ? (
                      <span className="flex justify-end gap-1">
                        <Button variant="ghost" size="xs" onClick={() => setEditing(null)}>
                          取消
                        </Button>
                        <Button
                          size="xs"
                          disabled={save.isPending || editing.content.trim() === ''}
                          onClick={() => save.mutate({ index: chunk.chunkIndex, content: editing.content })}
                          data-testid={`chunk-save-${chunk.chunkIndex}`}
                        >
                          保存
                        </Button>
                      </span>
                    ) : (
                      <span className="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="xs"
                          onClick={() => setEditing({ index: chunk.chunkIndex, content: chunk.content })}
                          data-testid={`chunk-edit-btn-${chunk.chunkIndex}`}
                        >
                          <Pencil className="size-3.5" />
                          编辑
                        </Button>
                        {chunk.origin === 'manual' ? (
                          <Button
                            variant="ghost"
                            size="xs"
                            onClick={() => setHistoryFor(chunk.chunkIndex)}
                            data-testid={`chunk-history-${chunk.chunkIndex}`}
                          >
                            <History className="size-3.5" />
                            历史
                          </Button>
                        ) : null}
                      </span>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {(chunks.data ?? []).length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4} className="text-muted-foreground text-center">
                    {chunks.isPending ? '加载中…' : '该条目还没有切片（可能未发布或尚未索引）'}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={historyFor !== null} onOpenChange={() => setHistoryFor(null)}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>切片历史（#{historyFor}）</DialogTitle>
            <DialogDescription>每次编辑都留快照；回滚会追加一个新版本（内容取自所选历史）。</DialogDescription>
          </DialogHeader>
          <div className="max-h-[60vh] space-y-2 overflow-y-auto">
            {(history.data ?? []).map((revision) => (
              <div key={revision.version} className="border-border rounded-md border p-3" data-testid={`revision-${revision.version}`}>
                <div className="mb-1 flex items-center justify-between">
                  <span className="text-sm font-medium">版本 {revision.version}</span>
                  <span className="flex items-center gap-2">
                    <span className="text-muted-foreground text-xs">{formatLocalDateTime(revision.createdAt)}</span>
                    <Button
                      size="xs"
                      variant="outline"
                      disabled={rollback.isPending || historyFor === null}
                      onClick={() =>
                        historyFor !== null && rollback.mutate({ index: historyFor, version: revision.version })
                      }
                      data-testid={`revision-rollback-${revision.version}`}
                    >
                      回滚到此版本
                    </Button>
                  </span>
                </div>
                <pre className="text-muted-foreground max-h-40 overflow-y-auto text-xs whitespace-pre-wrap">
                  {revision.content}
                </pre>
              </div>
            ))}
            {history.isPending ? <p className="text-muted-foreground text-sm">加载中…</p> : null}
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}
