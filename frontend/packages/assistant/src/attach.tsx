import { Button } from '@paideia/ui'
import { Sparkles } from 'lucide-react'

import { attachmentUri, type AttachmentType } from './attachments'
import { useAssistantContext } from './context'

/**
 * 「把这个递给助手」（WORK-058）。
 *
 * <p><b>一个组件覆盖任意对象</b>：任何页面上的任何东西，套上它就成为一个可附着的对象——
 * 这正是"什么都能和助手交互"的实现方式，而不是给每个页面各写一种上下文。
 *
 * <p>点一下 = 附着 + 把面板打开。**必须打开**：只附着不打开的话，用户看不到任何变化，
 * 会以为没生效。也**不发送**——和 `ask()` 一样，"附着"不等于"替用户花钱问一句"。
 *
 * <p>`onMouseDown` 拦掉默认行为是为了**保住正文里的选区**：条目页要在选中一段后点它，
 * 而浏览器在按下按钮时会清掉选区。拦掉之后键盘与触屏都不受影响（它们本来就没有选区问题）。
 */
export function Attach({
  type,
  id,
  label,
  excerpt,
  compact,
  className,
}: {
  type: AttachmentType
  id: string | number
  /** 一行给人看的标签，会同时进提示词。 */
  label: string
  /** 可选摘录：选中段、未保存草稿、题干等。 */
  excerpt?: string
  /**
   * 只显示图标。列表里每一行都挂一个（关卡、到期复习）时用——一列带字的按钮会把内容压掉；
   * 可读名仍在 `aria-label` 上，读屏与 e2e 都按名字找得到。
   */
  compact?: boolean
  className?: string
}) {
  const { attachments, attach, detach, revealAssistant } = useAssistantContext()
  const uri = attachmentUri(type, id)
  const active = attachments.some((item) => item.uri === uri)
  const name = active ? '已递给助手' : '问助手'

  return (
    <Button
      type="button"
      variant="ghost"
      size={compact === true ? 'icon-sm' : 'sm'}
      className={className}
      aria-pressed={active}
      aria-label={name}
      title={active ? '再点一次从上下文里取下' : '把这一条递给助手'}
      data-testid={`attach-${uri.replace(/[/:]/g, '-')}`}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => {
        if (active) {
          detach(uri)
          return
        }
        attach(excerpt === undefined ? { uri, label } : { uri, label, excerpt })
        revealAssistant()
      }}
    >
      <Sparkles className="size-3.5" />
      {compact === true ? null : name}
    </Button>
  )
}
