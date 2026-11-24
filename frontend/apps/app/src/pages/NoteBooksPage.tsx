import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BookOpen,
  Clock,
  FileText,
  Globe,
  Lock,
  Plus,
  Search,
  Trash2,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'

import {
  Alert,
  AlertDescription,
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Empty,
  EmptyContent,
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

const FALLBACK_ACCENTS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6']

function accentOf(book: BookView): string {
  return book.accentColor ?? FALLBACK_ACCENTS[book.id % FALLBACK_ACCENTS.length] ?? '#6366f1'
}

function formatDate(value: string | null): string {
  if (value === null) return '—'
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) return '—'
  const days = Math.floor((Date.now() - time) / 86_400_000)
  if (days <= 0) return '今天更新'
  if (days === 1) return '昨天更新'
  if (days < 30) return `${days} 天前更新`
  const d = new Date(time)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function matches(book: BookView, keyword: string): boolean {
  const q = keyword.trim().toLowerCase()
  if (q === '') return true
  const haystack = [book.name, book.description ?? '', ...book.tags].join(' ').toLowerCase()
  return haystack.includes(q)
}

/** 我的知识库：多知识库总览，支持搜索、排序与新建入口。 */
export function NoteBooksPage() {
  const queryClient = useQueryClient()
  const booksQuery = useQuery({ queryKey: ['note-books'], queryFn: () => notes.listBooks() })
  const [keyword, setKeyword] = useState('')
  const [sort, setSort] = useState<'updated' | 'name' | 'created'>('updated')
  const [error, setError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState<BookView | null>(null)

  const remove = useMutation({
    mutationFn: (id: number) => notes.removeBook(id),
    onSuccess: () => {
      setPendingDelete(null)
      void queryClient.invalidateQueries({ queryKey: ['note-books'] })
    },
    onError: (reason) => setError(reason instanceof Error ? reason.message : '删除失败'),
  })

  const all = useMemo(
    () =>
      (booksQuery.data ?? []).map((book) => ({
        ...book,
        visibility: book.visibility ?? 'private',
        tags: book.tags ?? [],
        documentCount: book.documentCount ?? 0,
      })),
    [booksQuery.data],
  )
  const visible = useMemo(() => {
    const filtered = all.filter((book) => matches(book, keyword))
    return [...filtered].sort((a, b) => {
      if (sort === 'name') return a.name.localeCompare(b.name, 'zh-Hans-CN')
      if (sort === 'created') {
        return (new Date(b.createdAt ?? 0).getTime() || 0) - (new Date(a.createdAt ?? 0).getTime() || 0)
      }
      return (new Date(b.updatedAt ?? 0).getTime() || 0) - (new Date(a.updatedAt ?? 0).getTime() || 0)
    })
  }, [all, keyword, sort])

  const totalDocs = all.reduce((sum, book) => sum + book.documentCount, 0)
  const publicCount = all.filter((book) => book.visibility === 'public').length

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="我的知识库"
        description="把学习资料整理成一个个知识库，每个库都是一棵可自由编排的文档树，正文以 Markdown 保存。"
        actions={
          <Button render={<Link to="/notes/new-book" />}>
            <Plus className="mr-1 h-4 w-4" />
            新建知识库
          </Button>
        }
      />

      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        {[
          { label: '知识库', value: all.length },
          { label: '文档', value: totalDocs },
          { label: '公开', value: publicCount },
          { label: '私有', value: all.length - publicCount },
        ].map((item) => (
          <div key={item.label} className="bg-card border-border rounded-xl border px-4 py-3">
            <div className="text-2xl font-semibold tabular-nums">{item.value}</div>
            <div className="text-muted-foreground mt-1 text-xs">{item.label}</div>
          </div>
        ))}
      </div>

      <div className="bg-card border-border mb-6 flex flex-col gap-3 rounded-xl border p-3 sm:flex-row sm:items-center">
        <div className="relative min-w-0 flex-1">
          <Search className="text-muted-foreground absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
          <Input
            aria-label="搜索知识库"
            className="pl-9"
            placeholder="搜索知识库名称、描述或标签…"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
        </div>
        <select
          aria-label="排序方式"
          className="border-input bg-background focus-visible:border-ring focus-visible:ring-ring/50 h-9 rounded-lg border px-3 text-sm outline-none focus-visible:ring-3"
          value={sort}
          onChange={(event) => setSort(event.target.value as 'updated' | 'name' | 'created')}
        >
          <option value="updated">最近更新</option>
          <option value="name">名称</option>
          <option value="created">创建时间</option>
        </select>
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
        <div className="flex justify-center py-16">
          <Spinner />
        </div>
      ) : booksQuery.isError ? (
        <Alert variant="destructive">
          <AlertDescription>知识库加载失败，请稍后重试。</AlertDescription>
        </Alert>
      ) : all.length === 0 ? (
        <Empty className="border-border rounded-xl border border-dashed py-16">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <BookOpen />
            </EmptyMedia>
            <EmptyTitle>还没有知识库</EmptyTitle>
            <EmptyDescription>创建第一个知识库，开始把你的笔记与学习资料整理成树。</EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <Button render={<Link to="/notes/new-book" />}>
              <Plus className="mr-1 h-4 w-4" />
              新建知识库
            </Button>
          </EmptyContent>
        </Empty>
      ) : visible.length === 0 ? (
        <div className="border-border rounded-xl border border-dashed py-16 text-center">
          <Search className="text-muted-foreground mx-auto mb-3 h-8 w-8" />
          <p className="text-sm font-medium">没有找到「{keyword}」相关的知识库</p>
          <p className="text-muted-foreground mt-1 text-xs">换个关键词，或清空搜索看看全部。</p>
          <Button variant="ghost" className="mt-3" onClick={() => setKeyword('')}>
            清空搜索
          </Button>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {visible.map((book) => {
            const accent = accentOf(book)
            const isPublic = book.visibility === 'public'
            return (
              <div key={book.id} className="group relative">
                <Card className="hover:border-border h-full overflow-hidden transition duration-200 hover:-translate-y-0.5 hover:shadow-lg">
                  <div
                    className="h-1.5 w-full"
                    style={{ background: `linear-gradient(90deg, ${accent}, ${accent}55)` }}
                  />
                  <CardHeader className="gap-3 pb-3">
                    <div className="flex items-start justify-between gap-2">
                      <span
                        className="flex h-10 w-10 items-center justify-center rounded-lg"
                        style={{ backgroundColor: `${accent}1f`, color: accent }}
                      >
                        <BookOpen className="h-5 w-5" />
                      </span>
                      <Badge variant={isPublic ? 'default' : 'secondary'} className="gap-1">
                        {isPublic ? <Globe className="h-3 w-3" /> : <Lock className="h-3 w-3" />}
                        {isPublic ? '公开' : '私有'}
                      </Badge>
                    </div>
                    <CardTitle className="truncate pr-1 text-base">
                      <Link className="hover:underline" to={`/notes/${book.id}`}>
                        {book.name}
                      </Link>
                    </CardTitle>
                    <CardDescription className="line-clamp-2 min-h-10 leading-5">
                      {book.description !== null && book.description !== ''
                        ? book.description
                        : '暂无描述'}
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="pt-0">
                    {book.tags.length > 0 ? (
                      <div className="mb-3 flex flex-wrap gap-1.5">
                        {book.tags.slice(0, 4).map((tag) => (
                          <span
                            key={tag}
                            className="bg-muted text-muted-foreground rounded-full px-2 py-0.5 text-[11px]"
                          >
                            {tag}
                          </span>
                        ))}
                        {book.tags.length > 4 ? (
                          <span className="text-muted-foreground text-[11px]">
                            +{book.tags.length - 4}
                          </span>
                        ) : null}
                      </div>
                    ) : (
                      <div className="mb-3 h-[18px]" />
                    )}
                    <div className="border-border text-muted-foreground flex items-center justify-between border-t pt-3 text-xs">
                      <span className="inline-flex items-center gap-1">
                        <FileText className="h-3.5 w-3.5" />
                        {book.documentCount} 篇文档
                      </span>
                      <span className="inline-flex items-center gap-1">
                        <Clock className="h-3.5 w-3.5" />
                        {formatDate(book.updatedAt)}
                      </span>
                      <Button
                        variant="ghost"
                        size="sm"
                        aria-label="删除知识库"
                        className="text-muted-foreground hover:text-destructive -mr-2 h-7 w-7 p-0 opacity-0 transition group-hover:opacity-100"
                        onClick={() => setPendingDelete(book)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              </div>
            )
          })}
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
