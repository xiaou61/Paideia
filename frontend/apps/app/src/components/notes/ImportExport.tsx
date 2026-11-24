import { useRef, useState } from 'react'

import { Button, Spinner } from '@paideia/ui'

import { notes } from '../../notes-api'

interface ImportExportProps {
  bookId: number
  docId: number | null
  onImported: () => void
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

/** 导入 / 导出：整库 zip 与单篇 markdown。 */
export function ImportExport({ bookId, docId, onImported }: ImportExportProps) {
  const zipInput = useRef<HTMLInputElement>(null)
  const mdInput = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function guard(work: () => Promise<string>): Promise<void> {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      setMessage(await work())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2 text-sm">
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" disabled={docId === null || busy} onClick={() => {
          if (docId === null) return
          void guard(async () => {
            saveBlob(await notes.exportDocBlob(docId), 'note.md')
            return '已导出当前文档'
          })
        }}>
          导出当前文档
        </Button>
        <Button variant="outline" size="sm" disabled={busy} onClick={() => {
          void guard(async () => {
            saveBlob(await notes.exportBookBlob(bookId), 'notes.zip')
            return '已导出整个知识库'
          })
        }}>
          导出整库 .zip
        </Button>
        <Button variant="outline" size="sm" disabled={busy} onClick={() => zipInput.current?.click()}>
          导入 .zip
        </Button>
        <Button variant="outline" size="sm" disabled={busy} onClick={() => mdInput.current?.click()}>
          导入 .md
        </Button>
        {busy ? <Spinner className="h-4 w-4" /> : null}
      </div>
      <input
        ref={zipInput}
        type="file"
        accept=".zip,application/zip"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file === undefined) return
          void guard(async () => {
            const result = await notes.importZip(bookId, file)
            onImported()
            return `导入完成：${result.folders} 个目录、${result.documents} 篇文档`
          })
        }}
      />
      <input
        ref={mdInput}
        type="file"
        accept=".md,.markdown,.txt,text/markdown"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file === undefined) return
          void guard(async () => {
            const text = await file.text()
            const title = file.name.replace(/\.[^.]+$/, '') || '导入的文档'
            const result = await notes.importMarkdown(bookId, title, text)
            onImported()
            return `导入完成：${result.documents} 篇文档`
          })
        }}
      />
      {message !== null ? <p className="text-muted-foreground">{message}</p> : null}
      {error !== null ? <p className="text-destructive">{error}</p> : null}
    </div>
  )
}
