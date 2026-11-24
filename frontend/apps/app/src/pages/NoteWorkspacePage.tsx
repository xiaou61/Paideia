import { errorMessage } from '@paideia/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, FilePlus, FolderPlus } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'

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
  EmptyTitle,
  Input,
  PageHeader,
  Spinner,
} from '@paideia/ui'

import { BacklinksPanel } from '../components/notes/BacklinksPanel'
import { DocEditor } from '../components/notes/DocEditor'
import { DocTree } from '../components/notes/DocTree'
import { ImportExport } from '../components/notes/ImportExport'
import { OutlinePanel } from '../components/notes/OutlinePanel'
import { SearchPanel } from '../components/notes/SearchPanel'
import { LearnerHeader } from '../components/LearnerHeader'
import { notes } from '../notes-api'

/** 单个知识库的工作区：左侧目录树、中间编辑器、右侧大纲与双链。 */
export function NoteWorkspacePage() {
  const params = useParams()
  const bookId = Number(params.bookId)
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()

  const [selected, setSelected] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [createKind, setCreateKind] = useState<'doc' | 'folder' | null>(null)
  const [newTitle, setNewTitle] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<number | null>(null)
  const [shareOpen, setShareOpen] = useState(false)
  const [copiedShare, setCopiedShare] = useState<string | null>(null)

  const booksQuery = useQuery({ queryKey: ['note-books'], queryFn: () => notes.listBooks() })
  const book = booksQuery.data?.find((item) => item.id === bookId)

  const treeQuery = useQuery({
    queryKey: ['note-tree', bookId],
    queryFn: () => notes.tree(bookId),
    enabled: Number.isFinite(bookId),
  })

  const docQuery = useQuery({
    queryKey: ['note-doc', selected],
    queryFn: () => (selected === null ? Promise.resolve(null) : notes.read(selected)),
    enabled: selected !== null,
  })

  const linksQuery = useQuery({
    queryKey: ['note-links', selected],
    queryFn: () => (selected === null ? Promise.resolve(null) : notes.links(selected)),
    enabled: selected !== null,
  })

  const sharesQuery = useQuery({
    queryKey: ['note-book-shares', bookId],
    queryFn: () => notes.activeBookShares(bookId),
    enabled: shareOpen && Number.isFinite(bookId),
  })

  // 从检索结果 / 双链跳进来时带 selected（跨库会换路由，这里接住）。
  useEffect(() => {
    const state = location.state as { selected?: number } | null
    if (state !== null && typeof state.selected === 'number') {
      setSelected(state.selected)
    }
  }, [location.state])

  useEffect(() => {
    const doc = docQuery.data
    if (doc !== undefined && doc !== null) {
      setTitle(doc.title)
      setBody(doc.body)
      setDirty(false)
    }
  }, [docQuery.data])

  const selectedNode = (treeQuery.data ?? []).find((node) => node.id === selected)

  function refreshDoc(): void {
    void queryClient.invalidateQueries({ queryKey: ['note-tree', bookId] })
    void queryClient.invalidateQueries({ queryKey: ['note-doc', selected] })
    void queryClient.invalidateQueries({ queryKey: ['note-links', selected] })
  }

  async function openDoc(id: number): Promise<void> {
    const doc = await notes.read(id)
    if (typeof doc.bookId === 'number' && doc.bookId !== bookId) {
      navigate(`/notes/${doc.bookId}`, { state: { selected: id } })
      return
    }
    setSelected(id)
  }

  function chooseDoc(id: number): void {
    if (dirty && !window.confirm('有未保存的改动，确定切换吗？')) {
      return
    }
    setSelected(id)
  }

  async function save(): Promise<void> {
    if (selected === null) return
    setSaving(true)
    setError(null)
    try {
      await notes.updateDoc(selected, {
        entryId: docQuery.data?.entryId ?? null,
        title,
        body,
      })
      setDirty(false)
      refreshDoc()
    } catch (reason) {
      setError(errorMessage(reason))
    } finally {
      setSaving(false)
    }
  }

  const createNode = useMutation({
    mutationFn: (input: { kind: 'doc' | 'folder'; title: string }) =>
      notes.createNode({
        bookId,
        parentId: selectedNode?.kind === 'folder' ? selectedNode.id : null,
        kind: input.kind,
        title: input.title,
        body: '',
      }),
    onSuccess: (created, input) => {
      setCreateKind(null)
      setNewTitle('')
      void queryClient.invalidateQueries({ queryKey: ['note-tree', bookId] })
      if (input.kind === 'doc') {
        setSelected(created.id)
      }
    },
    onError: (reason) => setError(errorMessage(reason)),
  })

  const removeNode = useMutation({
    mutationFn: (id: number) => notes.removeNode(id),
    onSuccess: (_result, id) => {
      setDeleteTarget(null)
      if (selected === id) {
        setSelected(null)
      }
      void queryClient.invalidateQueries({ queryKey: ['note-tree', bookId] })
    },
    onError: (reason) => setError(errorMessage(reason)),
  })

  const createShare = useMutation({
    mutationFn: () => notes.shareBook(bookId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['note-book-shares', bookId] })
    },
    onError: (reason) => setError(errorMessage(reason)),
  })

  function jumpTo(heading: string): void {
    const headings = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6'))
    const match = headings.find((element) => element.textContent?.trim() === heading)
    match?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  function exportDoc(): void {
    if (selected === null) return
    void notes
      .exportDocBlob(selected)
      .then((blob) => {
        const url = URL.createObjectURL(blob)
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download = 'note.md'
        anchor.click()
        URL.revokeObjectURL(url)
      })
      .catch((reason: unknown) => setError(errorMessage(reason)))
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title={book?.name ?? '知识库'}
        description={book?.description ?? '整理你的文档与目录，正文用 Markdown 保存。'}
        actions={
          <Button variant="ghost" render={<Link to="/notes" />}>
            返回知识库列表
          </Button>
        }
      />

      {error !== null ? (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-[260px_minmax(0,1fr)_230px]">
        <aside className="space-y-4">
          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0 py-3">
              <CardTitle className="text-sm">目录</CardTitle>
              <div className="flex gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label="新建文档"
                  onClick={() => {
                    setNewTitle('')
                    setCreateKind('doc')
                  }}
                >
                  <FilePlus className="h-4 w-4" />
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label="新建目录"
                  onClick={() => {
                    setNewTitle('')
                    setCreateKind('folder')
                  }}
                >
                  <FolderPlus className="h-4 w-4" />
                </Button>
              </div>
            </CardHeader>
            <CardContent className="max-h-[50vh] overflow-auto py-2">
              {treeQuery.isLoading ? (
                <div className="flex justify-center py-6">
                  <Spinner />
                </div>
              ) : (
                <DocTree
                  nodes={treeQuery.data ?? []}
                  selectedId={selected}
                  onSelect={chooseDoc}
                  onDelete={(id) => setDeleteTarget(id)}
                />
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="py-3">
              <CardTitle className="text-sm">导入 / 导出</CardTitle>
            </CardHeader>
            <CardContent className="py-2">
              <ImportExport
                bookId={bookId}
                docId={selected}
                onImported={() => {
                  void queryClient.invalidateQueries({ queryKey: ['note-tree', bookId] })
                }}
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0 py-3">
              <CardTitle className="text-sm">分享整个知识库</CardTitle>
              <Button variant="ghost" size="sm" onClick={() => setShareOpen(true)}>
                分享
              </Button>
            </CardHeader>
          </Card>
        </aside>

        <section className="min-h-[70vh] overflow-hidden rounded-lg border border-border bg-card">
          {selected === null ? (
            <Empty className="h-full">
              <EmptyHeader>
                <EmptyTitle>选择左侧的一篇文档</EmptyTitle>
                <EmptyDescription>或点右上角的新建按钮，开始写第一篇。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : docQuery.isLoading ? (
            <div className="flex h-full items-center justify-center py-20">
              <Spinner />
            </div>
          ) : (
            <DocEditor
              title={title}
              body={body}
              dirty={dirty}
              saving={saving}
              onTitle={(value) => {
                setTitle(value)
                setDirty(true)
              }}
              onBody={(value) => {
                setBody(value)
                setDirty(true)
              }}
              onSave={() => {
                void save()
              }}
              onExport={exportDoc}
            />
          )}
        </section>

        <aside className="space-y-4">
          <Card>
            <CardHeader className="py-3">
              <CardTitle className="text-sm">检索</CardTitle>
            </CardHeader>
            <CardContent className="py-2">
              <SearchPanel onOpen={(id) => void openDoc(id)} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="py-3">
              <CardTitle className="text-sm">大纲</CardTitle>
            </CardHeader>
            <CardContent className="py-2">
              <OutlinePanel body={body} onJump={jumpTo} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="py-3">
              <CardTitle className="text-sm">双链</CardTitle>
            </CardHeader>
            <CardContent className="py-2">
              <BacklinksPanel links={linksQuery.data ?? undefined} onOpen={(id) => void openDoc(id)} />
            </CardContent>
          </Card>
        </aside>
      </div>

      <Dialog open={createKind !== null} onOpenChange={(open) => !open && setCreateKind(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{createKind === 'folder' ? '新建目录' : '新建文档'}</DialogTitle>
            <DialogDescription>
              {selectedNode?.kind === 'folder'
                ? `将放在目录「${selectedNode.title}」下面。`
                : '将放在知识库根目录。'}
            </DialogDescription>
          </DialogHeader>
          <Input
            aria-label={createKind === 'folder' ? '目录名称' : '文档标题'}
            placeholder={createKind === 'folder' ? '目录名称' : '文档标题'}
            value={newTitle}
            onChange={(event) => setNewTitle(event.target.value)}
          />
          <DialogFooter>
            <Button variant="ghost" onClick={() => setCreateKind(null)}>
              取消
            </Button>
            <Button
              disabled={createNode.isPending || newTitle.trim() === ''}
              onClick={() => {
                if (createKind !== null && newTitle.trim() !== '') {
                  createNode.mutate({ kind: createKind, title: newTitle.trim() })
                }
              }}
            >
              创建
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除这个节点？</DialogTitle>
            <DialogDescription>目录连带其中的子文档会一起删除，且无法恢复。</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setDeleteTarget(null)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={removeNode.isPending}
              onClick={() => {
                if (deleteTarget !== null) removeNode.mutate(deleteTarget)
              }}
            >
              确认删除
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={shareOpen} onOpenChange={(open) => !open && setShareOpen(false)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>分享知识库「{book?.name ?? ''}」</DialogTitle>
            <DialogDescription>获得链接的人可免登录只读浏览整库，仅包含你自己的文档。链接 30 天有效。</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Button size="sm" disabled={createShare.isPending} onClick={() => createShare.mutate()}>
              生成新的分享链接
            </Button>
            {sharesQuery.isLoading ? (
              <Spinner className="h-4 w-4" />
            ) : (
              <ul className="space-y-1 text-sm">
                {(sharesQuery.data ?? []).map((shareId) => (
                  <li key={shareId} className="flex items-center gap-2">
                    <code className="truncate text-xs">{notes.shareLink(shareId)}</code>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label="复制链接"
                      onClick={() => {
                        void navigator.clipboard.writeText(notes.shareLink(shareId)).then(() => {
                          setCopiedShare(shareId)
                        })
                      }}
                    >
                      <Copy className="h-3.5 w-3.5" />
                    </Button>
                    {copiedShare === shareId ? <span className="text-xs text-muted-foreground">已复制</span> : null}
                  </li>
                ))}
              </ul>
            )}
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShareOpen(false)}>
              关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AppShell>
  )
}
