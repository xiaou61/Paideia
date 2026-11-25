import { useQuery } from '@tanstack/react-query'
import { Command } from 'cmdk'
import { BookOpen, FileText, Loader2, Search } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { useNavigate } from 'react-router'

import { notes, type NoteSummary } from '../../notes-api'

/** 把正文摘录里的 Markdown 记号清掉，只留可读的文字片段。 */
function stripMarkdown(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_~>#|]+/g, ' ')
    .replace(/-{2,}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/** 用 <mark> 高亮标题/摘录里命中的关键词（支持空格分隔的多个词）。 */
function highlight(text: string, query: string): ReactNode {
  const terms = query
    .trim()
    .split(/\s+/)
    .filter((term) => term !== '')
  if (terms.length === 0) return text
  const escaped = terms.map((term) => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  const parts = text.split(new RegExp(`(${escaped.join('|')})`, 'gi'))
  const lower = new Set(terms.map((term) => term.toLowerCase()))
  return parts.map((part, index) =>
    lower.has(part.toLowerCase()) ? (
      <mark
        key={index}
        className="bg-primary/15 text-primary rounded-[3px] px-0.5 font-medium [box-decoration-break:clone]"
      >
        {part}
      </mark>
    ) : (
      part
    ),
  )
}

const DOC_TYPE_LABEL: Record<string, string> = { tutorial: '教程', gap_fill: '查漏补缺' }

function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="border-border bg-muted/70 text-muted-foreground inline-flex h-5 min-w-5 items-center justify-center rounded border px-1 text-[10px] leading-none font-medium">
      {children}
    </kbd>
  )
}

/** 一条结果的多枚小徽章：文档类型 / 分类 / 难度。 */
function MetaChips({ item }: { item: NoteSummary }) {
  const chips: { key: string; label: string; cls: string }[] = []
  if (item.docType === 'tutorial') {
    chips.push({ key: 'dt', label: '教程', cls: 'bg-amber-100 text-amber-700' })
  } else if (item.docType === 'gap_fill') {
    chips.push({ key: 'dt', label: '查漏补缺', cls: 'bg-violet-100 text-violet-700' })
  }
  if (item.category !== null && item.category !== undefined && item.category !== '') {
    chips.push({ key: 'cat', label: item.category, cls: 'bg-sky-100 text-sky-700' })
  }
  if (item.level !== null && item.level !== undefined && item.level !== '') {
    chips.push({ key: 'lv', label: item.level, cls: 'bg-slate-100 text-slate-700' })
  }
  if (chips.length === 0) return null
  return (
    <span className="mt-0.5 flex flex-wrap items-center gap-1">
      {chips.map((chip) => (
        <span key={chip.key} className={`shrink-0 rounded px-1 py-0.5 text-[10px] leading-none ${chip.cls}`}>
          {chip.label}
        </span>
      ))}
    </span>
  )
}

/**
 * VitePress / Algolia DocSearch 风格的全局文档搜索（底座用 cmdk）：
 * 点击搜索框或 Ctrl/Cmd+K 弹出居中浮层，输入即检索本人全部知识库里的文档，
 * 结果按知识库分组、命中词高亮，↑↓ 选择、↵ 打开、esc 关闭。
 */
export function KnowledgeSearchPalette({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (value: boolean) => void
}) {
  const navigate = useNavigate()
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState('')

  // 打开时清空
  useEffect(() => {
    if (!open) {
      setText('')
      setQuery('')
      setSelected('')
    }
  }, [open])

  // 输入防抖
  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(text.trim()), 180)
    return () => window.clearTimeout(timer)
  }, [text])

  const searchQuery = useQuery({
    queryKey: ['note-search', query],
    queryFn: () => notes.search(query),
    enabled: open && query !== '',
    staleTime: 30_000,
  })
  const results = searchQuery.data ?? EMPTY

  // 结果变化时把选中项复位到第一条（cmdk 受控选中）
  useEffect(() => {
    setSelected(searchQuery.data !== undefined && searchQuery.data.length > 0 ? String(searchQuery.data[0]!.id) : '')
  }, [searchQuery.data])

  // 按所属知识库分组，保持后端排序（最近更新在前）
  const groups = useMemo(() => {
    const map = new Map<string, NoteSummary[]>()
    for (const item of results) {
      const key = item.bookName !== null && item.bookName !== undefined && item.bookName !== ''
        ? item.bookName
        : '未命名知识库'
      const bucket = map.get(key)
      if (bucket === undefined) {
        map.set(key, [item])
      } else {
        bucket.push(item)
      }
    }
    return [...map.entries()]
  }, [results])

  async function openResult(noteId: number): Promise<void> {
    try {
      const doc = await notes.read(noteId)
      onOpenChange(false)
      if (typeof doc.bookId === 'number') {
        navigate(`/notes/${doc.bookId}`, { state: { selected: noteId } })
      }
    } catch {
      // 打不开就保持浮层，让用户换一条
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      onOpenChange(false)
    }
  }

  if (!open) return null

  const loading = searchQuery.isFetching && results.length === 0

  return (
    <div
      className="fixed inset-0 z-[60] flex items-start justify-center bg-black/40 px-4 pt-[9vh] backdrop-blur-[3px]"
      onMouseDown={() => onOpenChange(false)}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="搜索文档"
        className="bg-card text-card-foreground border-border w-full max-w-[640px] overflow-hidden rounded-2xl border shadow-[0_24px_60px_-12px_rgba(0,0,0,0.35)] ring-1 ring-black/5"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <Command shouldFilter={false} loop value={selected} onValueChange={setSelected} label="搜索文档">
          {/* 搜索头 */}
          <div className="border-border flex items-center gap-3 border-b px-4 py-3.5">
            <Search className="text-muted-foreground h-5 w-5 shrink-0" />
            <Command.Input
              autoFocus
              value={text}
              onValueChange={setText}
              onKeyDown={handleKeyDown}
              placeholder="搜索全部知识库文档…"
              aria-label="搜索文档"
              className="placeholder:text-muted-foreground/80 w-full bg-transparent text-[15px] outline-none"
            />
            {loading ? <Loader2 className="text-muted-foreground h-4 w-4 shrink-0 animate-spin" /> : null}
            <button type="button" onClick={() => onOpenChange(false)} aria-label="关闭搜索" className="shrink-0">
              <Kbd>esc</Kbd>
            </button>
          </div>

          {/* 结果区 */}
          <Command.List className="max-h-[58vh] overflow-y-auto overscroll-contain p-2">
            {query === '' ? (
              <div className="flex flex-col items-center gap-1.5 px-3 py-14 text-center">
                <Search className="text-muted-foreground/40 mb-1 h-8 w-8" />
                <p className="text-foreground/80 text-sm font-medium">搜索你的全部知识库</p>
                <p className="text-muted-foreground text-xs">支持文档标题与正文全文检索</p>
              </div>
            ) : loading ? (
              <div className="text-muted-foreground flex items-center justify-center gap-2 px-3 py-14 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 搜索中…
              </div>
            ) : results.length === 0 ? (
              <div className="flex flex-col items-center gap-1.5 px-3 py-14 text-center">
                <Search className="text-muted-foreground/40 mb-1 h-8 w-8" />
                <p className="text-foreground/80 text-sm font-medium">没有找到「{query}」</p>
                <p className="text-muted-foreground text-xs">换个关键词试试，或检查是否拼写有误</p>
              </div>
            ) : (
              <>
                <div className="text-muted-foreground flex items-center justify-between px-3 pt-1 pb-2 text-xs font-medium">
                  <span>搜索结果</span>
                  <span className="text-muted-foreground/70">{results.length} 条</span>
                </div>
                {groups.map(([bookName, items]) => (
                  <Command.Group key={bookName}>
                    <div className="text-muted-foreground flex items-center gap-1.5 px-3 pt-2 pb-1 text-[11px] font-medium">
                      <BookOpen className="h-3.5 w-3.5 shrink-0" />
                      <span className="truncate">{bookName}</span>
                      <span className="text-muted-foreground/50">· {items.length}</span>
                    </div>
                    {items.map((item) => {
                      const title = item.title !== '' ? item.title : '未命名文档'
                      const excerpt = item.excerpt !== null ? stripMarkdown(item.excerpt) : ''
                      return (
                        <Command.Item
                          key={item.id}
                          value={String(item.id)}
                          onSelect={() => void openResult(item.id)}
                          className="group flex cursor-pointer items-start gap-3 rounded-lg px-3 py-2.5 text-left text-sm outline-none data-[selected=true]:bg-primary/10"
                        >
                          <span className="bg-muted text-muted-foreground mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md group-data-[selected=true]:bg-primary/15 group-data-[selected=true]:text-primary">
                            <FileText className="h-3.5 w-3.5" />
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className="flex items-center gap-2">
                              <span className="truncate font-medium group-data-[selected=true]:text-primary">
                                {highlight(title, query)}
                              </span>
                              <span className="text-primary ml-auto hidden shrink-0 group-data-[selected=true]:inline-flex">
                                <Kbd>↵</Kbd>
                              </span>
                            </span>
                            <MetaChips item={item} />
                            {excerpt !== '' ? (
                              <span className="text-muted-foreground mt-0.5 line-clamp-2 block text-xs leading-relaxed">
                                {highlight(excerpt, query)}
                              </span>
                            ) : null}
                          </span>
                        </Command.Item>
                      )
                    })}
                  </Command.Group>
                ))}
              </>
            )}
          </Command.List>

          {/* 底部键位提示 */}
          <div className="border-border text-muted-foreground flex items-center gap-4 border-t px-4 py-2 text-[11px]">
            <span className="flex items-center gap-1">
              <Kbd>↑</Kbd>
              <Kbd>↓</Kbd>
              <span className="ml-0.5">选择</span>
            </span>
            <span className="flex items-center gap-1">
              <Kbd>↵</Kbd>
              <span className="ml-0.5">打开</span>
            </span>
            <span className="flex items-center gap-1">
              <Kbd>esc</Kbd>
              <span className="ml-0.5">关闭</span>
            </span>
            <span className="text-muted-foreground/60 ml-auto">Paideia 全文检索 · cmdk</span>
          </div>
        </Command>
      </div>
    </div>
  )
}

const EMPTY: NoteSummary[] = []
