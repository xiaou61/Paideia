import { Tabs, TabsContent, TabsList, TabsTrigger } from '@paideia/ui'
import {
  useRef,
  useState,
  type ClipboardEvent,
  type DragEvent,
  type KeyboardEvent,
} from 'react'

import { MarkdownBody } from './markdown-body'

/** 斜杠命令：标签 + 插入的 Markdown 片段。 */
const SLASH_COMMANDS: { label: string; snippet: string }[] = [
  { label: '一级标题', snippet: '# ' },
  { label: '二级标题', snippet: '## ' },
  { label: '无序列表', snippet: '- ' },
  { label: '有序列表', snippet: '1. ' },
  { label: '待办事项', snippet: '- [ ] ' },
  { label: '引用', snippet: '> ' },
  { label: '代码块', snippet: '```\n\n```' },
  { label: '表格', snippet: '| 列 1 | 列 2 |\n| --- | --- |\n|  |  |\n' },
  { label: '分割线', snippet: '\n---\n' },
]

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
  slashCommands = false,
  defaultTab = 'edit',
  previewOnly = false,
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
  /** 打开行首 `/` 斜杠命令菜单（默认关闭，避免影响既有消费者）。 */
  slashCommands?: boolean
  /** 初始标签页，默认"编辑"；阅读为主的场景可传 "preview" 默认展示预览。 */
  defaultTab?: 'edit' | 'preview'
  /** 纯预览：不渲染标签栏与工具栏，只显示排版结果（用于纯净预览/阅读模式）。 */
  previewOnly?: boolean
}) {
  const [tab, setTab] = useState(defaultTab)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [slash, setSlash] = useState<{ at: number; index: number } | null>(null)
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

  /** 用一段片段替换掉触发菜单的那个 `/`。 */
  const applyCommand = (at: number, snippet: string) => {
    const next = `${value.slice(0, at)}${snippet}${value.slice(at + 1)}`
    onChange(next)
    setSlash(null)
    requestAnimationFrame(() => {
      const element = textareaRef.current
      if (element !== null) {
        element.focus()
        const cursor = at + snippet.length
        element.setSelectionRange(cursor, cursor)
      }
    })
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!slashCommands) {
      return
    }
    if (slash === null) {
      if (event.key === '/') {
        event.preventDefault()
        const at = textareaRef.current?.selectionStart ?? value.length
        insertAtCursor('/')
        setSlash({ at, index: 0 })
      }
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setSlash((current) =>
        current === null ? current : { ...current, index: (current.index + 1) % SLASH_COMMANDS.length },
      )
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSlash((current) =>
        current === null
          ? current
          : { ...current, index: (current.index - 1 + SLASH_COMMANDS.length) % SLASH_COMMANDS.length },
      )
    } else if (event.key === 'Enter') {
      event.preventDefault()
      applyCommand(slash.at, SLASH_COMMANDS[slash.index]?.snippet ?? '')
    } else if (event.key === 'Escape') {
      event.preventDefault()
      setSlash(null)
    } else if (event.key.length === 1) {
      setSlash(null)
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

  if (previewOnly) {
    return (
      <div className="w-full" data-testid={`${testIdPrefix}-preview`}>
        {value.trim() === '' ? (
          <p className="text-muted-foreground py-6 text-sm" data-testid={`${testIdPrefix}-preview-empty`}>
            还没有正文。
          </p>
        ) : (
          <MarkdownBody markdown={value} mediaBaseUrl={mediaBaseUrl} />
        )}
      </div>
    )
  }

  return (
    <Tabs
      value={tab}
      onValueChange={(next) => setTab(String(next) === 'preview' ? 'preview' : 'edit')}
      className="flex flex-col gap-3"
    >
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
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          onDrop={onDrop}
          onDragOver={(event) => event.preventDefault()}
          className="border-border bg-background focus:border-ring w-full resize-y rounded-b-lg border p-4 font-mono text-sm leading-7 outline-none"
        />
        {slash === null ? null : (
          <ul
            role="listbox"
            aria-label="插入命令"
            data-testid={`${testIdPrefix}-slash-menu`}
            className="border-border bg-popover mt-1 w-48 overflow-hidden rounded-md border shadow-md"
          >
            {SLASH_COMMANDS.map((command, index) => (
              <li key={command.label}>
                <button
                  type="button"
                  role="option"
                  aria-selected={index === slash.index}
                  className={`flex w-full items-center px-3 py-1.5 text-left text-sm ${
                    index === slash.index ? 'bg-accent text-accent-foreground' : 'hover:bg-muted'
                  }`}
                  onClick={() => applyCommand(slash.at, command.snippet)}
                >
                  {command.label}
                </button>
              </li>
            ))}
          </ul>
        )}
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
