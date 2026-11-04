import { Tabs, TabsContent, TabsList, TabsTrigger } from '@paideia/ui'
import { useRef, useState, type ClipboardEvent, type DragEvent } from 'react'

import { MarkdownBody } from './markdown-body'

/**
 * markdown 正文编辑器：**编辑 / 预览**两个标签共用一份内容。
 *
 * <p>**放在共享包里而不是某个应用里**：学习者端的笔记（新建页与详情页）与管理端的条目编辑
 * 用的是同一块东西——"预览等于最终效果"这条承诺，两边必须由同一份实现来兑现。
 *
 * <p>抽出原因很实际：新建页与详情页都要这一块，而"编辑"与"预览"的对应关系
 * （同一份文本、同一套渲染）是这个页面里最不能走样的部分——各写一份，两边迟早在
 * "预览里看到的"和"保存下来的"之间出现差异。
 *
 * <p>预览直接复用知识库的 `MarkdownBody`：学习者的笔记与知识库条目用的是同一套渲染，
 * 所以编辑器里的预览就是最终的样子，不需要第二套渲染实现。
 *
 * <p>`testIdPrefix` 让两个页面各有稳定的测试钩子（`note-body` / `new-note-body`…），
 * 而不是让测试去猜 DOM 结构。
 *
 * <p>WORK-072：加了轻量 Markdown 工具栏与可选上传回调。**仍然只存 Markdown**——按钮只
 * 在 textarea 的光标处插入文本，不做 HTML WYSIWYG，也不改变既有正文格式。
 */
export function MarkdownField({
  value,
  onChange,
  testIdPrefix,
  rows = 16,
  placeholder = '用 markdown 写点什么…',
  mediaBaseUrl,
  onUpload,
}: {
  value: string
  onChange: (next: string) => void
  testIdPrefix: string
  rows?: number
  placeholder?: string
  /** 正文里相对附件地址（`/api/v1/knowledge/files/...`）要拼上的后端基址。 */
  mediaBaseUrl?: string
  /** WORK-072：粘贴/拖拽上传；不传时编辑器仍可用，只是没有上传能力。 */
  onUpload?: (file: File) => Promise<{ url: string; name: string }>
}) {
  const [tab, setTab] = useState('edit')
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const insertAtCursor = (before: string, after = '') => {
    const element = textareaRef.current
    if (element === null) {
      onChange(`${value}${before}${after}`)
      return
    }
    const start = element.selectionStart
    const end = element.selectionEnd
    const selected = value.slice(start, end)
    const next = `${value.slice(0, start)}${before}${selected}${after}${value.slice(end)}`
    onChange(next)
    requestAnimationFrame(() => {
      element.focus()
      const cursor = start + before.length + selected.length + after.length
      element.setSelectionRange(cursor, cursor)
    })
  }

  const uploadFile = async (file: File) => {
    if (onUpload === undefined) {
      return
    }
    setUploadError(null)
    setUploading(true)
    try {
      const uploaded = await onUpload(file)
      const alt = uploaded.name.replace(/[[\]]/g, '')
      insertAtCursor(`![${alt}](${uploaded.url})\n`)
    } catch {
      setUploadError('上传失败，正文没有被修改。')
    } finally {
      setUploading(false)
    }
  }

  const onPaste = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const file = event.clipboardData.files[0]
    if (onUpload !== undefined && file !== undefined) {
      event.preventDefault()
      void uploadFile(file)
    }
  }

  const onDrop = (event: DragEvent<HTMLTextAreaElement>) => {
    const file = event.dataTransfer.files[0]
    if (onUpload !== undefined && file !== undefined) {
      event.preventDefault()
      void uploadFile(file)
    }
  }

  const toolbar = [
    { label: 'H2', testId: 'heading', before: '## ', after: '' },
    { label: 'B', testId: 'bold', before: '**', after: '**' },
    { label: 'I', testId: 'italic', before: '*', after: '*' },
    { label: '•', testId: 'list', before: '- ', after: '' },
    { label: '“', testId: 'quote', before: '> ', after: '' },
    { label: '</>', testId: 'code', before: '```\n', after: '\n```' },
    { label: '链接', testId: 'link', before: '[', after: '](https://)' },
  ]

  return (
    <Tabs value={tab} onValueChange={(next) => setTab(String(next))} className="flex flex-col gap-3">
      <TabsList variant="line">
        <TabsTrigger value="edit" data-testid={`${testIdPrefix}-tab-edit`}>
          编辑
        </TabsTrigger>
        <TabsTrigger value="preview" data-testid={`${testIdPrefix}-tab-preview`}>
          预览
        </TabsTrigger>
      </TabsList>

      <TabsContent value="edit">
        <div className="border-border bg-muted/30 flex flex-wrap items-center gap-1 rounded-t-lg border border-b-0 px-2 py-1.5">
          {toolbar.map((item) => (
            <button
              key={item.testId}
              type="button"
              aria-label={item.testId}
              data-testid={`${testIdPrefix}-toolbar-${item.testId}`}
              onClick={() => insertAtCursor(item.before, item.after)}
              className="hover:bg-background rounded px-2 py-1 font-mono text-xs"
            >
              {item.label}
            </button>
          ))}
          {onUpload === undefined ? null : (
            <span className="text-muted-foreground ml-auto text-xs">
              {uploading ? '上传中…' : '可粘贴/拖入图片或 PDF'}
            </span>
          )}
        </div>
        <textarea
          ref={textareaRef}
          value={value}
          rows={rows}
          aria-label="正文（markdown）"
          placeholder={placeholder}
          data-testid={`${testIdPrefix}-body`}
          onChange={(event) => onChange(event.target.value)}
          onPaste={onPaste}
          onDrop={onDrop}
          onDragOver={(event) => event.preventDefault()}
          className="border-border bg-background focus:border-ring w-full resize-y rounded-b-lg border p-4 font-mono text-sm leading-7 outline-none"
        />
        {uploadError === null ? null : (
          <p className="text-destructive mt-1 text-xs" data-testid={`${testIdPrefix}-upload-error`}>
            {uploadError}
          </p>
        )}
      </TabsContent>

      <TabsContent value="preview">
        {value.trim() === '' ? (
          <p className="text-muted-foreground py-6 text-sm" data-testid={`${testIdPrefix}-preview-empty`}>
            还没有正文。
          </p>
        ) : (
          <div className="border-border rounded-lg border p-4" data-testid={`${testIdPrefix}-preview`}>
            <MarkdownBody markdown={value} mediaBaseUrl={mediaBaseUrl} />
          </div>
        )}
      </TabsContent>
    </Tabs>
  )
}
