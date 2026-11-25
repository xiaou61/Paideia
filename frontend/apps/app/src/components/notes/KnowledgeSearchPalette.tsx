import { useQuery } from '@tanstack/react-query'
import { FileText, Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
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

/**
 * VitePress 风格的全局文档搜索：点击搜索框（或 Ctrl/Cmd+K）弹出居中浮层，
 * 输入即检索本人全部知识库里的文档，↑↓ 选择、↵ 打开、esc 关闭。
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

  const searching = searchQuery.isFetching && results.length === 0

  return (
    <div
      className="fixed inset-0 z-[60] flex items-start justify-center bg-black/40 px-4 pt-[8vh] backdrop-blur-sm"
      onMouseDown={() => onOpenChange(false)}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="搜索文档"
        className="bg-card border-border w-full max-w-2xl overflow-hidden rounded-xl border shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="border-border flex items-center gap-3 border-b px-4 py-3">
          <Search className="text-muted-foreground h-5 w-5 shrink-0" />
          <input
            ref={inputRef}
            value={text}
            onChange={(event) => setText(event.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="搜索全部知识库文档…"
            aria-label="搜索文档"
            className="placeholder:text-muted-foreground w-full bg-transparent text-base outline-none"
          />
          <button
            type="button"
            onClick={() => onOpenChange(false)}
            className="border-border text-muted-foreground hover:text-foreground shrink-0 rounded border px-1.5 py-0.5 text-[10px] leading-none"
          >
            esc
          </button>
        </div>

        <div ref={listRef} className="max-h-[55vh] overflow-y-auto p-2">
          {query === '' ? (
            <p className="text-muted-foreground px-3 py-10 text-center text-sm">
              输入关键词，搜索你所有知识库里的文档
            </p>
          ) : searching ? (
            <p className="text-muted-foreground px-3 py-10 text-center text-sm">搜索中…</p>
          ) : results.length === 0 ? (
            <p className="text-muted-foreground px-3 py-10 text-center text-sm">
              没有找到「{query}」相关的文档
            </p>
          ) : (
            <>
              <div className="text-muted-foreground px-3 pt-1 pb-2 text-xs font-medium">
                文档 · {results.length}
              </div>
              {results.map((item, index) => (
                <button
                  key={item.id}
                  type="button"
                  data-idx={index}
                  onMouseEnter={() => setActive(index)}
                  onClick={() => void openResult(item.id)}
                  className={`flex w-full items-start gap-3 rounded-lg px-3 py-2 text-left transition-colors ${
                    index === active ? 'bg-primary/10' : 'hover:bg-muted'
                  }`}
                >
                  <FileText className="text-muted-foreground mt-0.5 h-4 w-4 shrink-0" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">
                      {item.title !== '' ? item.title : '未命名文档'}
                    </span>
                    {item.excerpt !== null && stripMarkdown(item.excerpt) !== '' ? (
                      <span className="text-muted-foreground mt-0.5 block truncate text-xs">
                        {stripMarkdown(item.excerpt)}
                      </span>
                    ) : null}
                  </span>
                </button>
              ))}
            </>
          )}
        </div>

        <div className="border-border text-muted-foreground flex items-center gap-3 border-t px-4 py-2 text-[11px]">
          <span>↑↓ 选择</span>
          <span>↵ 打开</span>
          <span>esc 关闭</span>
        </div>
      </div>
    </div>
  )
}
