import { Search } from 'lucide-react'
import { useState } from 'react'

import { Input, Spinner } from '@paideia/ui'

import { notes, type NoteSummary } from '../../notes-api'

interface SearchPanelProps {
  onOpen: (noteId: number) => void
}

/** 全文检索面板：输入关键词，列出命中的文档。 */
export function SearchPanel({ onOpen }: SearchPanelProps) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<NoteSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)

  async function run(): Promise<void> {
    const trimmed = keyword.trim()
    if (trimmed === '') {
      setResults([])
      setSearched(false)
      return
    }
    setLoading(true)
    try {
      const found = await notes.search(trimmed)
      setResults(found)
      setSearched(true)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-3">
      <form
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          void run()
        }}
      >
        <Input
          aria-label="检索笔记"
          placeholder="搜索标题与正文…"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <button
          type="submit"
          className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-input"
          aria-label="搜索"
        >
          {loading ? <Spinner className="h-4 w-4" /> : <Search className="h-4 w-4" />}
        </button>
      </form>
      {searched && results.length === 0 ? (
        <p className="text-sm text-muted-foreground">没有找到匹配的文档。</p>
      ) : (
        <ul className="space-y-1">
          {results.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                className="w-full rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted"
                onClick={() => onOpen(item.id)}
              >
                <span className="block truncate font-medium">{item.title}</span>
                {item.excerpt !== null && item.excerpt !== '' ? (
                  <span className="block truncate text-xs text-muted-foreground">{item.excerpt}</span>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
