import { MarkdownField, createKnowledgeApi } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, Link2, Search, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { notes } from '../notes-api'

const knowledge = createKnowledgeApi(api)

/**
 * 新建笔记：**独立的一页**，不是"点一下生成一条空的"。
 *
 * <p>用户 2026-10-15 的要求原话：「新建笔记应该是弹窗的那种，或者说新建一个页面，因为我们
 * 后面肯定是要在这种页面上加很多的功能」。所以这里不是把旧按钮换个壳，而是把"创建一条笔记"
 * 当成一个有内容的过程：写标题、写正文（带预览）、**可选地挂到某条知识库条目上**，
 * 然后才创建。以后要加的东西（模板、标签、AI 摘要、可见性）也都有地方放。
 *
 * <p>创建成功后回到列表页并**选中刚建的那条**（通过路由 state 传 id，不用全局状态）。
 */
export function NoteNewPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  // 速递「存成笔记」带着预填内容跳到这里（WORK-027 I-16）：/notes/new?title=…&content=…
  const [searchParams] = useSearchParams()
  const [title, setTitle] = useState(() => searchParams.get('title') ?? '')
  const [body, setBody] = useState(() => searchParams.get('content') ?? '')
  const [entry, setEntry] = useState<{ id: number; title: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmLeave, setConfirmLeave] = useState(false)

  const create = useMutation({
    mutationFn: () =>
      notes.create({ entryId: entry?.id ?? null, title: title.trim() === '' ? '未命名笔记' : title, body }),
    onSuccess: async (created) => {
      setError(null)
      await queryClient.invalidateQueries({ queryKey: ['notes'] })
      // 带着 id 回列表页，那边会把它选中并加载出来
      void navigate('/notes', { state: { selected: created.id } })
    },
    onError: (cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause)),
  })

  const dirty = title.trim() !== '' || body.trim() !== '' || entry !== null
  const leave = () => {
    if (dirty) {
      setConfirmLeave(true)
      return
    }
    void navigate('/notes')
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <button
        type="button"
        onClick={leave}
        data-testid="new-note-back"
        className="text-muted-foreground hover:text-foreground mb-5 inline-flex items-center gap-1 text-sm transition-colors"
      >
        <ChevronLeft className="size-4" />
        返回我的笔记
      </button>

      <PageHeader
        title="新建笔记"
        description="先写内容，再决定挂不挂到某条知识库条目上。正文支持 markdown，可以随时切到预览看效果。"
      />

      {error === null ? null : (
        <Alert variant="destructive" className="mb-4" data-testid="new-note-error">
          <AlertTitle>没能创建</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="space-y-4 pt-6">
          <Input
            value={title}
            aria-label="标题"
            placeholder="给它起个名字（留空就叫「未命名笔记」）"
            data-testid="new-note-title"
            onChange={(event) => setTitle(event.target.value)}
            className="h-auto rounded-none border-transparent bg-transparent px-0 text-xl font-semibold shadow-none focus-visible:border-transparent focus-visible:ring-0 dark:bg-transparent"
          />

          <MarkdownField value={body} onChange={setBody} testIdPrefix="new-note" rows={14} />

          <EntryPicker value={entry} onChange={setEntry} />
        </CardContent>
      </Card>

      <div className="mt-6 flex flex-wrap items-center gap-3">
        <Button disabled={create.isPending} data-testid="new-note-create" onClick={() => create.mutate()}>
          {create.isPending ? <Spinner /> : null}
          创建笔记
        </Button>
        <Button variant="outline" data-testid="new-note-cancel" onClick={leave}>
          取消
        </Button>
        <span className="text-muted-foreground text-xs">
          {body.trim() === '' ? '正文还空着，也可以先建后写' : `${body.length} 字`}
        </span>
      </div>

      {/* 取消时如果已经写了东西，问一句再走——刚写的字不该因为按错一下就没 */}
      {confirmLeave ? (
        <Alert variant="destructive" className="mt-4" data-testid="new-note-discard">
          <AlertTitle>这些内容还没保存</AlertTitle>
          <AlertDescription className="flex flex-wrap items-center gap-3">
            离开这一页会丢掉刚写的内容。
            <Button
              size="sm"
              variant="destructive"
              data-testid="new-note-discard-confirm"
              onClick={() => void navigate('/notes')}
            >
              丢掉并返回
            </Button>
            <Button size="sm" variant="outline" onClick={() => setConfirmLeave(false)}>
              继续写
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
    </AppShell>
  )
}

/**
 * 挂靠条目选择器（可选）。
 *
 * <p>笔记与知识库条目的关系原本只有助手写笔记时才会建立，用户自己没法指定。既然条目
 * 详情里已经会显示"来自知识库条目"，这条关系就该让用户自己也能建。
 *
 * <p>检索式而不是全量下拉：条目可能有几百条，全量列表在这里没有意义。
 */
function EntryPicker({
  value,
  onChange,
}: {
  value: { id: number; title: string } | null
  onChange: (next: { id: number; title: string } | null) => void
}) {
  const [keyword, setKeyword] = useState('')

  const results = useQuery({
    queryKey: ['entry-picker', keyword],
    queryFn: () => knowledge.entries({ q: keyword.trim(), size: 5 }),
    enabled: keyword.trim() !== '',
  })

  if (value !== null) {
    return (
      <div className="flex flex-wrap items-center gap-2" data-testid="new-note-entry">
        <span className="text-muted-foreground inline-flex items-center gap-1.5 text-xs">
          <Link2 className="size-3.5" />
          挂靠条目
        </span>
        <Badge variant="secondary">
          <Link to={`/knowledge/entries/${value.id}`} className="underline-offset-4 hover:underline">
            {value.title}
          </Link>
        </Badge>
        <Button
          size="sm"
          variant="ghost"
          data-testid="new-note-entry-clear"
          onClick={() => onChange(null)}
        >
          <X className="size-3.5" />
          取消挂靠
        </Button>
      </div>
    )
  }

  return (
    <div className="relative">
      <label className="text-muted-foreground mb-1.5 block text-xs" htmlFor="entry-picker">
        挂到一条知识库条目上（可选）
      </label>
      <div className="relative max-w-md max-sm:max-w-none">
        <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-3.5 -translate-y-1/2" />
        <Input
          id="entry-picker"
          value={keyword}
          placeholder="搜条目标题，选中后这条笔记会出现在那条内容的关联里"
          data-testid="new-note-entry-search"
          onChange={(event) => setKeyword(event.target.value)}
          className="h-9 rounded-full pl-8 text-sm"
        />
      </div>
      {keyword.trim() !== '' ? (
        <div className="border-border bg-popover mt-2 max-h-[50dvh] max-w-md overflow-y-auto rounded-lg border shadow-sm max-sm:max-w-none">
          {results.isPending ? (
            <p className="text-muted-foreground flex items-center gap-2 px-3 py-2 text-xs">
              <Spinner className="size-3.5" />
              找条目…
            </p>
          ) : (results.data?.items ?? []).length === 0 ? (
            <p className="text-muted-foreground px-3 py-2 text-xs">没有匹配的条目。</p>
          ) : (
            <ul data-testid="new-note-entry-results">
              {(results.data?.items ?? []).map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    data-testid={`new-note-entry-option-${item.id}`}
                    onClick={() => {
                      onChange({ id: item.id, title: item.title })
                      setKeyword('')
                    }}
                    className="hover:bg-accent flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm transition-colors"
                  >
                    <span className="truncate">{item.title}</span>
                    {item.categoryName === null ? null : (
                      <span className="text-muted-foreground shrink-0 text-xs">{item.categoryName}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  )
}
