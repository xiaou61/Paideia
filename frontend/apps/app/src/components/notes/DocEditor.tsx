import { MarkdownField } from '@paideia/knowledge'
import { Button, Input } from '@paideia/ui'
import { Maximize2, Minimize2 } from 'lucide-react'

interface DocEditorProps {
  title: string
  body: string
  dirty: boolean
  saving: boolean
  onTitle: (value: string) => void
  onBody: (value: string) => void
  onSave: () => void
  /** 粘贴/拖入图片时把图片转成 data URL（由调用方决定实现）。 */
  onUpload: (file: File) => Promise<{ url: string; name: string }>
  /** 纯净预览：隐藏目录与侧栏，只留正文。 */
  zen?: boolean
  onToggleZen?: () => void
  /** 配对文档标题（教程 ↔ 查漏补缺）；有值时显示跳转按钮。 */
  pairedTitle?: string
  onOpenPair?: () => void
}

/**
 * 文档编辑器：标题 + markdown 正文。
 *
 * <p>默认打开就是「预览」——知识库是给自己复习用的，先看到排版结果比先看到源码更自然；
 * 要动笔再切到「编辑」。右上角的「纯净预览」把目录和侧栏都收起来，只留正文。
 */
export function DocEditor({
  title,
  body,
  dirty,
  saving,
  onTitle,
  onBody,
  onSave,
  onUpload,
  zen = false,
  onToggleZen,
  pairedTitle,
  onOpenPair,
}: DocEditorProps) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-border px-5 py-3">
        <Input
          aria-label="文档标题"
          data-testid="doc-title"
          className="h-9 flex-1 border-0 px-0 text-xl font-semibold shadow-none focus-visible:ring-0"
          value={title}
          onChange={(event) => onTitle(event.target.value)}
          placeholder="无标题文档"
        />
        {pairedTitle !== undefined && onOpenPair !== undefined ? (
          <Button variant="ghost" size="sm" aria-label="跳到配对文档" onClick={onOpenPair}>
            ↔ {pairedTitle}
          </Button>
        ) : null}
        {onToggleZen === undefined ? null : (
          <Button
            variant="ghost"
            size="sm"
            aria-label={zen ? '退出纯净预览' : '纯净预览'}
            onClick={onToggleZen}
          >
            {zen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
            <span className="hidden sm:inline">{zen ? '退出纯净预览' : '纯净预览'}</span>
          </Button>
        )}
        <Button size="sm" data-testid="doc-save" onClick={onSave} disabled={saving}>
          {saving ? '保存中…' : dirty ? '保存' : '已保存'}
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        <div className={zen ? 'w-full px-8 py-8' : 'mx-auto w-full max-w-4xl px-5 py-6'}>
          <MarkdownField
            testIdPrefix="note-doc"
            value={body}
            onChange={onBody}
            placeholder="用 Markdown 写正文，支持 [[标题]] 双链"
            onUpload={onUpload}
            slashCommands
            defaultTab="preview"
            previewOnly={zen}
            rows={24}
          />
        </div>
      </div>
    </div>
  )
}
