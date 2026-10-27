import { formatRelative } from '@paideia/core'
import { MarkdownField, plainText } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Copy, NotebookPen, Plus, Search, Trash2 } from 'lucide-react'
import { useLocation, useNavigate } from 'react-router'
import { useEffect, useRef, useState } from 'react'

import { LearnerHeader } from '../components/LearnerHeader'
import { notes, type NoteSummary } from '../notes-api'

/**
 * 我的笔记：左侧列表、右侧编辑。
 *
 * <p>三条取舍（原有）：
 * <ul>
 *   <li><b>列表不带正文全文</b>，只带一段服务端截好的摘要。</li>
 *   <li><b>保存是显式的</b>：不做自动保存。自动保存要在"什么时候算一次编辑"上做一堆猜测，
 *       而写错一个字就被自动存下去，比多按一次按钮更让人不安。代价是必须**把状态说清楚**：
 *       顶上有"有未保存的改动"、底下有"与服务端一致"，切走时还会拦一下。</li>
 *   <li><b>删除要二次确认</b>：笔记是用户自己写的东西，误删没有撤销。</li>
 * </ul>
 *
 * <p>2026-10-15 按用户反馈重做（原话："这里还是太简单了……你要站在用户的视角去想"）。
 * 参考了语雀与 Notion 的"列表 + 编辑器"形态，补的是**每个位置本该有的东西**：列表每行要能
 * 一眼分清（标题 + 摘要 + 相对时间），编辑器要知道"我刚改的存了没有"，正文是 markdown 就该
 * 能预览，破坏性动作该弹窗确认而不是 `window.confirm`。
 */
export function NotesPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [selected, setSelected] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [filter, setFilter] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [shareOpen, setShareOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  /** 待确认的破坏性动作：删除，或"带着未保存的改动切走"。 */
  const [pending, setPending] = useState<{ kind: 'delete' } | { kind: 'switch'; id: number } | null>(null)

  /**
   * 从"新建笔记"页回来时带上刚创建那条的 id（路由 state），这里把它选中。
   *
   * <p>用路由 state 而不是全局状态：它天然只作用于"这一次跳转"，刷新页面不会莫名其妙地
   * 选中某条笔记，也不需要把"当前选中"提到全局。
   */
  useEffect(() => {
    const created = (location.state as { selected?: number } | null)?.selected
    if (created !== undefined) {
      setSelected(created)
      void navigate('.', { replace: true, state: null })
    }
  }, [location.state, navigate])

  const list = useQuery({ queryKey: ['notes'], queryFn: () => notes.list() })
  const detail = useQuery({
    queryKey: ['note', selected],
    queryFn: () => notes.read(selected as number),
    enabled: selected !== null,
  })

  /**
   * 草稿与"当前打开的那条笔记"的关系，全在这一个 effect 里判定——**顺序很要紧**。
   *
   * <p>两条纪律，各对应一个真踩过的坑：
   * <ol>
   *   <li><b>已经采纳过的数据绝不覆盖草稿</b>。原先是"`detail.data` 一变就重置"，于是任何一次
   *       后台重新取详情（invalidate、缓存更新）都会把用户正在打的字换成服务端的旧值——
   *       "打字打到一半内容自己变回去了"。</li>
   *   <li><b>换一条时先清空</b>。不清空的话，上一条的内容会留在输入框里直到新数据回来，
   *       而在这个窗口里打的字会被随后到达的服务端值盖掉（实测：新建笔记后立刻改名，
   *       名字落到了上一条的输入框上，等新笔记加载完又被清成「未命名笔记」）。</li>
   * </ol>
   *
   * <p>**写成两个 effect 会出错**：React 按声明顺序执行，先"采纳"后"清空"的话，
   * 切到一条缓存里已有的笔记时会被立刻清掉（实测就是这个表现）。所以判定放在一处、按顺序走。
   */
  const adoptedId = useRef<number | null>(null)
  useEffect(() => {
    if (selected === null) {
      adoptedId.current = null
      setTitle('')
      setBody('')
      return
    }
    if (detail.data?.id === selected) {
      if (adoptedId.current === selected) {
        // 已经采纳过这条：后台再怎么重新取，都不许动草稿
        return
      }
      adoptedId.current = selected
      setTitle(detail.data.title)
      setBody(detail.data.body)
      setCopied(false)
      return
    }
    // 新选中的那条详情还没到：先清空，别让上一条的内容留在框里
    adoptedId.current = null
    setTitle('')
    setBody('')
  }, [selected, detail.data])

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['notes'] })
    void queryClient.invalidateQueries({ queryKey: ['note', selected] })
  }

  const fail = (cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause))

  const save = useMutation({
    mutationFn: () => notes.update(selected as number, { entryId: detail.data?.entryId ?? null, title, body }),
    onSuccess: () => {
      setError(null)
      invalidate()
    },
    onError: fail,
  })

  // 这条笔记当前有效的分享。查它而不是只记"刚生成的 id"：用户离开页面再回来也要能撤回
  const shares = useQuery({
    queryKey: ['note-shares', selected],
    queryFn: () => notes.activeShares(selected as number),
    enabled: selected !== null,
  })

  const createShare = useMutation({
    mutationFn: () => notes.share(selected as number),
    onSuccess: () => {
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['note-shares', selected] })
    },
    onError: fail,
  })

  const revokeShare = useMutation({
    mutationFn: (shareId: string) => notes.revoke(shareId),
    onSuccess: () => {
      setError(null)
      setCopied(false)
      void queryClient.invalidateQueries({ queryKey: ['note-shares', selected] })
    },
    onError: fail,
  })

  const remove = useMutation({
    mutationFn: (id: number) => notes.remove(id),
    onSuccess: () => {
      setError(null)
      setSelected(null)
      invalidate()
    },
    onError: fail,
  })

  /** 草稿与服务端不一致 = 有东西没存。状态文案与保存按钮都看它。 */
  const dirty = detail.data !== undefined && (title !== detail.data.title || body !== detail.data.body)
  const rows = (list.data ?? []).filter((item: NoteSummary) =>
    filter.trim() === ''
      ? true
      : `${item.title} ${item.excerpt ?? ''}`.toLowerCase().includes(filter.trim().toLowerCase()),
  )
  const activeShare = (shares.data ?? [])[0] ?? null
  const shareLink = activeShare === null ? '' : notes.shareLink(activeShare)

  /** 点列表：有没保存的改动就先问一句，别把它静默丢掉。 */
  const openNote = (id: number) => {
    if (id === selected) {
      return
    }
    if (dirty) {
      setPending({ kind: 'switch', id })
      return
    }
    setSelected(id)
  }

  /**
   * 复制分享链接。
   *
   * <p>线上是**明文 HTTP**，而 `navigator.clipboard` 只在安全上下文里存在，所以在部署实例上
   * 它多半是 undefined——必须留退路，否则按钮点了毫无反应（一次静默失败）。退路是选中输入框里的
   * 文字并说明手动复制；链接本身一直是可选中的文本。
   */
  const copyLink = async () => {
    try {
      if (navigator.clipboard !== undefined) {
        await navigator.clipboard.writeText(shareLink)
        setCopied(true)
        return
      }
    } catch {
      // 落到下面的退路
    }
    document.querySelector<HTMLInputElement>('[data-testid="note-share-link"]')?.select()
    setCopied(false)
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="我的笔记"
        description="随手记点什么，或让助手把答案存进来；也能生成只读链接发给别人。"
      />

      {error === null ? null : (
        <Alert variant="destructive" data-testid="notes-error" className="mb-4">
          <AlertTitle>没能完成</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <div className="grid items-start gap-5 lg:grid-cols-[20rem_minmax(0,1fr)]">
        {/* ── 列表 ── */}
        <div className="border-border bg-card flex flex-col overflow-hidden rounded-xl border">
          <div className="border-border space-y-2.5 border-b p-3.5">
            {/* 新建走**独立的一页**（/notes/new）：标题、正文、挂靠条目都在那边写，
                列表页只负责浏览与整理。见 NoteNewPage 的说明 */}
            <Button className="w-full" data-testid="notes-create" onClick={() => void navigate('/notes/new')}>
              <Plus className="size-4" />
              新建笔记
            </Button>
            <div className="relative">
              <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2" />
              <Input
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
                placeholder="在标题与摘要里找…"
                aria-label="筛选笔记"
                data-testid="notes-filter"
                className="h-9 rounded-full pl-8 text-sm"
              />
            </div>
          </div>

          <div className="lg:max-h-[30rem] lg:overflow-y-auto">
            {list.isLoading ? (
              <p className="text-muted-foreground flex items-center gap-2 p-4 text-sm">
                <Spinner />
                加载笔记…
              </p>
            ) : (list.data ?? []).length === 0 ? (
              <p className="text-muted-foreground p-4 text-sm" data-testid="notes-empty">
                还没有笔记。点上面的「新建笔记」，或让助手把答案存进来。
              </p>
            ) : rows.length === 0 ? (
              <p className="text-muted-foreground p-4 text-sm" data-testid="notes-filter-empty">
                没有匹配「{filter}」的笔记。
              </p>
            ) : (
              <ul className="divide-border divide-y" data-testid="notes-list">
                {rows.map((item: NoteSummary) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      data-testid={`note-item-${item.id}`}
                      onClick={() => openNote(item.id)}
                      // 选中态用左侧一条品牌色竖线 + 淡底：一眼看出"正在编辑的是这条"
                      className={`w-full border-l-2 px-3.5 py-3 text-left transition-colors ${
                        selected === item.id
                          ? 'border-primary bg-accent/50'
                          : 'hover:bg-accent/30 border-transparent'
                      }`}
                    >
                      <span className="block truncate text-sm font-medium">{item.title}</span>
                      {item.excerpt === null || plainText(item.excerpt) === '' ? null : (
                        <span className="text-muted-foreground mt-0.5 line-clamp-2 block text-xs leading-5">
                          {plainText(item.excerpt)}
                        </span>
                      )}
                      <span className="text-muted-foreground mt-1.5 flex items-center gap-2 text-xs">
                        {formatRelative(item.updatedAt)}
                        {item.entryId === null ? null : <span>· 来自知识库</span>}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {rows.length === 0 ? null : (
            <div
              className="border-border text-muted-foreground border-t px-3.5 py-2 text-xs"
              data-testid="notes-total"
            >
              共 {rows.length} 条
            </div>
          )}
        </div>

        {/* ── 编辑器 ── */}
        {/* Tabs 的 Root 包住整个编辑器：编辑/预览两个面板与上面的标签必须同属一个 Root，
            分成两个 Root 靠外部状态对齐是能跑但很脆的写法 */}
        <div className="border-border bg-card flex min-h-[26rem] flex-col overflow-hidden rounded-xl border">
          {selected === null ? (
            <Empty data-testid="notes-placeholder" className="border-0">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <NotebookPen />
                </EmptyMedia>
                <EmptyTitle>左边选一条，或新建一条</EmptyTitle>
                <EmptyDescription>笔记支持 markdown，写完可以切到「预览」看渲染结果。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : detail.isLoading ? (
            <p className="text-muted-foreground flex items-center gap-2 p-5 text-sm">
              <Spinner />
              打开中…
            </p>
          ) : detail.isError ? (
            <Alert variant="destructive" className="m-5" data-testid="note-state">
              <AlertTitle>打不开这条笔记</AlertTitle>
              <AlertDescription>{(detail.error as Error).message}</AlertDescription>
            </Alert>
          ) : (
            <>
              <div className="border-border flex flex-wrap items-center gap-2 border-b px-5 py-2.5">
                <span className="text-muted-foreground text-xs">正在编辑</span>
                <div className="ml-auto flex items-center gap-2">
                  {dirty ? (
                    <Badge variant="warning" data-testid="note-dirty">
                      有未保存的改动
                    </Badge>
                  ) : null}
                  <Button size="sm" variant="outline" data-testid="note-share" onClick={() => setShareOpen(true)}>
                    分享
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    data-testid="note-delete"
                    onClick={() => setPending({ kind: 'delete' })}
                  >
                    <Trash2 className="size-4" />
                    删除
                  </Button>
                </div>
              </div>

              <div className="flex-1 space-y-3 px-5 py-4">
                <Input
                  value={title}
                  aria-label="标题"
                  data-testid="note-title"
                  placeholder="标题"
                  onChange={(event) => setTitle(event.target.value)}
                  className="h-auto rounded-none border-transparent bg-transparent px-0 text-xl font-semibold shadow-none focus-visible:border-transparent focus-visible:ring-0 dark:bg-transparent"
                />
                <p className="text-muted-foreground flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                  <span data-testid="note-meta">
                    {dirty ? '尚未保存' : `最后编辑 ${formatRelative(detail.data?.updatedAt)}`}
                  </span>
                  <span>{body.length} 字</span>
                  {detail.data?.entryId === null || detail.data === undefined ? null : (
                    <span data-testid="note-entry">来自知识库条目 #{detail.data.entryId}</span>
                  )}
                </p>

                {/* key 让换一条笔记时编辑/预览回到"编辑"（否则上一条停在预览，下一条一进来就是预览） */}
                <MarkdownField key={selected ?? 'none'} value={body} onChange={setBody} testIdPrefix="note" />
              </div>

              <div className="border-border flex items-center gap-3 border-t px-5 py-3">
                <Button disabled={save.isPending || !dirty} data-testid="note-save" onClick={() => save.mutate()}>
                  {save.isPending ? <Spinner /> : null}
                  保存
                </Button>
                <span className="text-muted-foreground text-xs" data-testid="note-save-state">
                  {dirty ? '改了还没保存' : '与服务端一致'}
                </span>
              </div>
            </>
          )}
        </div>
      </div>

      {/* 分享：链接免登录可读，30 天到期，可提前撤回（用户裁决 12） */}
      <Dialog open={shareOpen} onOpenChange={setShareOpen}>
        <DialogContent data-testid="note-share-dialog">
          <DialogHeader>
            <DialogTitle>只读分享</DialogTitle>
            <DialogDescription>
              拿到链接的人不需要登录就能读这条笔记。链接 30 天后自动失效，也可以随时撤回。
            </DialogDescription>
          </DialogHeader>

          {activeShare === null ? (
            <Button
              disabled={createShare.isPending}
              data-testid="note-share-create"
              onClick={() => createShare.mutate()}
            >
              {createShare.isPending ? <Spinner /> : null}
              生成只读链接
            </Button>
          ) : (
            <div className="space-y-3">
              <Input
                readOnly
                value={shareLink}
                aria-label="分享链接"
                data-testid="note-share-link"
                onFocus={(event) => event.target.select()}
                className="font-mono text-xs"
              />
              <div className="flex items-center gap-2">
                <Button size="sm" variant="outline" data-testid="note-share-copy" onClick={() => void copyLink()}>
                  {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
                  {copied ? '已复制' : '复制链接'}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={revokeShare.isPending}
                  data-testid="note-revoke"
                  onClick={() => revokeShare.mutate(activeShare)}
                >
                  撤回
                </Button>
              </div>
              {copied ? null : (
                <p className="text-muted-foreground text-xs">
                  浏览器不允许直接写剪贴板时（明文 HTTP 下就是这样），链接已经选中，按 Ctrl+C 复制。
                </p>
              )}
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* 破坏性动作的确认：删除、或带着未保存的改动切走 */}
      <Dialog open={pending !== null} onOpenChange={(open) => (open ? undefined : setPending(null))}>
        <DialogContent data-testid="note-confirm-dialog">
          <DialogHeader>
            <DialogTitle>{pending?.kind === 'delete' ? '删除这条笔记？' : '改动还没保存'}</DialogTitle>
            <DialogDescription>
              {pending?.kind === 'delete'
                ? '删掉之后没有撤销。已经发出去的分享链接也会一起失效。'
                : '切到另一条会丢掉当前这些改动。'}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPending(null)}>
              取消
            </Button>
            <Button
              variant={pending?.kind === 'delete' ? 'destructive' : 'default'}
              data-testid="note-confirm"
              onClick={() => {
                if (pending?.kind === 'delete' && selected !== null) {
                  remove.mutate(selected)
                }
                if (pending?.kind === 'switch') {
                  setSelected(pending.id)
                }
                setPending(null)
              }}
            >
              {pending?.kind === 'delete' ? '删除' : '丢掉改动'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AppShell>
  )
}
