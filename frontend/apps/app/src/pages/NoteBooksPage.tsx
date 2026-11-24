import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpen, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'

import {
  Alert,
  AlertDescription,
  AppShell,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Input,
  PageHeader,
  Spinner,
} from '@paideia/ui'

import { LearnerHeader } from '../components/LearnerHeader'
import { notes, type BookView } from '../notes-api'

/** 我的知识库：一个用户可以有多个个人知识库，每个库内有文档树。 */
export function NoteBooksPage() {
  const queryClient = useQueryClient()
  const booksQuery = useQuery({ queryKey: ['note-books'], queryFn: () => notes.listBooks() })
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState<BookView | null>(null)

  const create = useMutation({
    mutationFn: (value: string) => notes.createBook({ name: value }),
    onSuccess: () => {
      setName('')
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['note-books'] })
    },
    onError: (reason) => setError(reason instanceof Error ? reason.message : '创建失败'),
  })

  const remove = useMutation({
    mutationFn: (id: number) => notes.removeBook(id),
    onSuccess: () => {
      setPendingDelete(null)
      void queryClient.invalidateQueries({ queryKey: ['note-books'] })
    },
    onError: (reason) => setError(reason instanceof Error ? reason.message : '删除失败'),
  })

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="我的知识库"
        description="每个知识库都是一棵可自由整理的文档树，正文以 Markdown 保存，可整库导入导出。"
      />

      <div className="mb-6 flex flex-wrap gap-2">
        <Input
          aria-label="知识库名称"
          className="max-w-xs"
          placeholder="新知识库名称，例如「考研数学」"
          value={name}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && name.trim() !== '') {
              create.mutate(name.trim())
            }
          }}
        />
        <Button
          onClick={() => {
            if (name.trim() !== '') create.mutate(name.trim())
          }}
          disabled={create.isPending || name.trim() === ''}
        >
          <Plus className="mr-1 h-4 w-4" />
          新建知识库
        </Button>
        <Button variant="ghost" render={<Link to="/notes/all" />}>
          旧版全部笔记
        </Button>
      </div>

      {error !== null ? (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      {booksQuery.isLoading ? (
        <div className="flex justify-center py-10">
          <Spinner />
        </div>
      ) : booksQuery.isError ? (
        <Alert variant="destructive">
          <AlertDescription>知识库加载失败，请稍后重试。</AlertDescription>
        </Alert>
      ) : (booksQuery.data ?? []).length === 0 ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <BookOpen />
            </EmptyMedia>
            <EmptyTitle>还没有知识库</EmptyTitle>
            <EmptyDescription>在上面输入名称，创建你的第一个个人知识库。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {(booksQuery.data ?? []).map((book) => (
            <Card key={book.id} className="group relative">
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <BookOpen className="h-4 w-4 text-muted-foreground" />
                  <Link className="truncate hover:underline" to={`/notes/${book.id}`}>
                    {book.name}
                  </Link>
                </CardTitle>
              </CardHeader>
              <CardContent className="flex items-center justify-between text-sm text-muted-foreground">
                <span className="truncate">{book.description ?? '暂无描述'}</span>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label="删除知识库"
                  onClick={() => setPendingDelete(book)}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <Dialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除知识库「{pendingDelete?.name}」？</DialogTitle>
            <DialogDescription>
              库内所有文档与目录都会一起删除，且无法恢复。分享出去的链接也会失效。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setPendingDelete(null)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={remove.isPending}
              onClick={() => {
                if (pendingDelete !== null) remove.mutate(pendingDelete.id)
              }}
            >
              确认删除
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AppShell>
  )
}
