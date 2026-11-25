import { MarkdownField } from '@paideia/knowledge'
import {
  Button,
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@paideia/ui'
import { Check, ChevronDown, Maximize2, Minimize2, Plus, Tag } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

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
  /** 分类标签（自由文本，可为空）。 */
  category?: string
  onCategory?: (value: string) => void
  /** 难度评级：入门 / 进阶 / 高级 / 空。 */
  level?: string
  onLevel?: (value: string) => void
  /** 已有分类，供输入联想。 */
  categoryOptions?: string[]
}

const LEVEL_OPTIONS = [
  { value: '', label: '难度：不限' },
  { value: '入门', label: '入门' },
  { value: '进阶', label: '进阶' },
  { value: '高级', label: '高级' },
] as const

interface CategoryComboboxProps {
  value: string
  onChange: (value: string) => void
  /** 已有分类，作下拉建议。 */
  options?: string[]
  /** 整体宽度类，默认 w-52。 */
  className?: string
  ariaLabel?: string
  placeholder?: string
}

/**
 * 分类选择器：可以自由输入新分类，也可以从已有分类里挑一个。
 *
 * <p>用自定义下拉面板替掉原生 {@code <datalist>}：原生弹层样式跟随操作系统，
 * 会盖住编辑器上方的标签、也对不齐站内的圆角与阴影。这里统一成「左侧图标 + 输入 + 展开箭头」，
 * 面板是一个圆角浮层，可点选、可回车确认，也能即时「使用当前输入」新建。
 */
export function CategoryCombobox({
  value,
  onChange,
  options = [],
  className = 'w-52',
  ariaLabel = '分类',
  placeholder = '分类，如 Agent基础概念',
}: CategoryComboboxProps) {
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    const onDocumentMouseDown = (event: MouseEvent) => {
      if (boxRef.current !== null && !boxRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onDocumentMouseDown)
    return () => document.removeEventListener('mousedown', onDocumentMouseDown)
  }, [open])

  const query = value.trim()
  const lowered = query.toLowerCase()
  const matched = options.filter(
    (option) => lowered === '' || option.toLowerCase().includes(lowered),
  )
  const creatable = query !== '' && !options.some((option) => option === query)

  return (
    <div ref={boxRef} className={`relative ${className}`}>
      <div className="flex h-8 items-center gap-1.5 rounded-lg border border-input bg-background pl-2.5 pr-1 transition-colors focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/40">
        <Tag className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        <input
          aria-label={ariaLabel}
          className="h-full min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
          placeholder={placeholder}
          value={value}
          onChange={(event) => {
            onChange(event.target.value)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === 'Escape') {
              setOpen(false)
            }
          }}
        />
        <button
          type="button"
          aria-label="展开分类选项"
          onClick={() => setOpen((prev) => !prev)}
          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <ChevronDown
            className={`h-3.5 w-3.5 transition-transform ${open ? 'rotate-180' : ''}`}
          />
        </button>
      </div>
      {open && (matched.length > 0 || creatable) ? (
        <div className="absolute left-0 top-9 z-30 max-h-60 w-64 overflow-auto rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
          {matched.map((option) => (
            <button
              key={option}
              type="button"
              onClick={() => {
                onChange(option)
                setOpen(false)
              }}
              className="flex w-full items-center justify-between gap-2 rounded-md px-2.5 py-1.5 text-left text-sm transition-colors hover:bg-muted"
            >
              <span className="truncate">{option}</span>
              {option === value ? <Check className="h-3.5 w-3.5 shrink-0 text-primary" /> : null}
            </button>
          ))}
          {creatable ? (
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="flex w-full items-center gap-1.5 rounded-md px-2.5 py-1.5 text-left text-sm text-primary transition-colors hover:bg-muted"
            >
              <Plus className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">使用「{query}」</span>
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

interface LevelSelectProps {
  value: string
  onChange?: (value: string) => void
  /** 宽度类，默认 w-32。 */
  className?: string
  ariaLabel?: string
}

/**
 * 难度选择器：用设计系统的 Select，触发器与下拉面板跟站内其它选择框保持一致。
 */
export function LevelSelect({
  value,
  onChange,
  className = 'w-32',
  ariaLabel = '难度',
}: LevelSelectProps) {
  return (
    <Select
      items={LEVEL_OPTIONS.map((option) => ({ value: option.value, label: option.label }))}
      value={value}
      onValueChange={(next) => onChange?.(next as string)}
    >
      <SelectTrigger size="sm" aria-label={ariaLabel} className={`h-8 ${className}`}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {LEVEL_OPTIONS.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
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
  category = '',
  onCategory,
  level = '',
  onLevel,
  categoryOptions,
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
      {zen || onCategory === undefined ? null : (
        <div className="flex flex-wrap items-center gap-2 border-b border-border bg-muted/30 px-5 py-2">
          <CategoryCombobox
            value={category}
            onChange={(next) => onCategory(next)}
            options={categoryOptions}
          />
          <LevelSelect value={level} onChange={(next) => onLevel?.(next)} />
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-auto">
        <div className={zen ? 'w-full px-8 py-8' : 'w-full px-6 py-6'}>
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
