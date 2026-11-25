import { useQuery } from '@tanstack/react-query'
import { FileText, Loader2, Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { useNavigate } from 'react-router'

import { notes } from '../../notes-api'

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

function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="border-border bg-muted/70 text-muted-foreground inline-flex h-5 min-w-5 items-center justify-center rounded border px-1 text-[10px] leading-none font-medium">
      {children}
    </kbd>
  )
}

/**
 * VitePress / Algolia DocSearch 风格的全局文档搜索：点击搜索框（或 Ctrl/Cmd+K）弹出居中浮层，
 * 输入即检索本人全部知识库里的文档，命中词高亮，↑↓ 选择、↵ 打开、esc 关闭。
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
  const [active, setActive] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)

  // 打开时聚焦、关闭时清空
  useEffect(() => {
    if (!open) {
      setText('')
      setQuery('')
      setActive(0)
      return
    }
    const timer = window.setTimeout(() => inputRef.current?.focus(), 30)
    return () => window.clearTimeout(timer)
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
  const results = searchQuery.data ?? []

  useEffect(() => {
    setActive(0)
  }, [query, results.length])

  // 让选中项始终在可视区域内
  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(`[data-idx="${active}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  }, [active])

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
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActive((index) => Math.min(index + 1, Math.max(results.length - 1, 0)))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActive((index) => Math.max(index - 1, 0))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const target = results[active]
      if (target !== undefined) void openResult(target.id)
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
        className="bg-card text-card-foreground border-border ring-border/60 w-full max-w-[640px] overflow-hidden rounded-2xl border shadow-[0_24px_60px_-12px_rgba(0,0,0,0.35)] ring-1"
        onMouseDown={(event) => event.stopPropagation()}
      >
        {/* 搜索头 */}
        <div className="border-border flex items-center gap-3 border-b px-4 py-3.5">
          <Search className="text-muted-foreground h-5 w-5 shrink-0" />
          <input
            ref={inputRef}
            value={text}
            onChange={(event) => setText(event.target.value)}
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
        <div ref={listRef} className="max-h-[58vh] overflow-y-auto p-2">
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
                <span>文档</span>
                <span className="text-muted-foreground/70">{results.length} 条结果</span>
              </div>
              {results.map((item, index) => {
                const selected = index === active
                const title = item.title !== '' ? item.title : '未命名文档'
                const excerpt = item.excerpt !== null ? stripMarkdown(item.excerpt) : ''
                return (
                  <button
                    key={item.id}
                    type="button"
                    data-idx={index}
                    onMouseEnter={() => setActive(index)}
                    onClick={() => void openResult(item.id)}
                    aria-selected={selected}
                    className={`flex w-full items-start gap-3 rounded-lg px-3 py-2.5 text-left transition-colors ${
                      selected ? 'bg-primary/10' : 'hover:bg-muted/60'
                    }`}
                  >
                    <span
                      className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md ${
                        selected ? 'bg-primary/15 text-primary' : 'bg-muted text-muted-foreground'
                      }`}
                    >
                      <FileText className="h-3.5 w-3.5" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <span className={`truncate text-sm font-medium ${selected ? 'text-primary' : ''}`}>
                          {highlight(title, query)}
                        </span>
                        {selected ? (
                          <span className="text-primary ml-auto flex shrink-0 items-center gap-1 text-[10px]">
                            <Kbd>↵</Kbd>
                          </span>
                        ) : null}
                      </span>
                      {excerpt !== '' ? (
                        <span className="text-muted-foreground mt-0.5 line-clamp-2 block text-xs leading-relaxed">
                          {highlight(excerpt, query)}
                        </span>
                      ) : null}
                    </span>
                  </button>
                )
              })}
            </>
          )}
        </div>

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
          <span className="text-muted-foreground/60 ml-auto">Paideia 全文检索</span>
        </div>
      </div>
    </div>
  )
}
