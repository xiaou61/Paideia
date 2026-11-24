import { MarkdownField } from '@paideia/knowledge'
import { Button, Input } from '@paideia/ui'

interface DocEditorProps {
  title: string
  body: string
  dirty: boolean
  saving: boolean
  onTitle: (value: string) => void
  onBody: (value: string) => void
  onSave: () => void
  onExport: () => void
}

/** 右侧文档编辑器：标题 + markdown 正文 + 显式保存。 */
export function DocEditor({
  title,
  body,
  dirty,
  saving,
  onTitle,
  onBody,
  onSave,
  onExport,
}: DocEditorProps) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b border-border px-4 py-3">
        <Input
          aria-label="文档标题"
          className="h-9 flex-1 border-0 text-lg font-semibold shadow-none focus-visible:ring-0"
          value={title}
          onChange={(event) => onTitle(event.target.value)}
          placeholder="无标题文档"
        />
        <Button variant="ghost" size="sm" onClick={onExport}>
          导出 .md
        </Button>
        <Button size="sm" onClick={onSave} disabled={saving || !dirty}>
          {saving ? '保存中…' : dirty ? '保存' : '已保存'}
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
        <MarkdownField
          testIdPrefix="note-doc"
          value={body}
          onChange={onBody}
          placeholder="用 Markdown 写正文，支持 [[标题]] 双链"
        />
      </div>
    </div>
  )
}
