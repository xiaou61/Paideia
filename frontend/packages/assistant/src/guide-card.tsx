import { Button } from '@paideia/ui'
import { useState } from 'react'

import type { AssistantGuide } from './context'

/**
 * 发送前的向导卡（WORK-063）。
 *
 * <p>选项由页面给、默认各选第一项：用户点开只需按「生成」。卡片只负责拼一条消息，
 * **不碰发送**——发送留给面板（它才知道会话与忙碌状态），也不进会话历史。
 */
export function composeGuideMessage(guide: AssistantGuide, choices: Record<string, string>): string {
  const lines = guide.groups
    .map((group) => (choices[group.key] === undefined ? null : `- ${group.label}：${choices[group.key]}`))
    .filter((line): line is string => line !== null)
  return [guide.prompt, ...lines].join('\n')
}

export function GuideCard({
  guide,
  disabled,
  onCancel,
  onSubmit,
}: {
  guide: AssistantGuide
  disabled: boolean
  onCancel: () => void
  onSubmit: (text: string) => void
}) {
  const [choices, setChoices] = useState<Record<string, string>>(() =>
    Object.fromEntries(guide.groups.map((group) => [group.key, group.options[0] ?? ''])),
  )

  return (
    <section
      className="border-border bg-muted/30 space-y-3 rounded-lg border p-3"
      data-testid="assistant-guide"
      aria-label={guide.title}
    >
      <p className="text-xs font-medium">{guide.title}</p>
      {guide.groups.map((group) => (
        <fieldset key={group.key} data-testid={`guide-group-${group.key}`}>
          <legend className="text-muted-foreground mb-1 text-[11px]">{group.label}</legend>
          <div className="flex flex-wrap gap-1.5">
            {group.options.map((option, index) => (
              <Button
                key={option}
                type="button"
                size="sm"
                variant={choices[group.key] === option ? 'default' : 'outline'}
                aria-pressed={choices[group.key] === option}
                data-testid={`guide-${group.key}-${index}`}
                onClick={() => setChoices((current) => ({ ...current, [group.key]: option }))}
              >
                {option}
              </Button>
            ))}
          </div>
        </fieldset>
      ))}
      <div className="flex items-center gap-2">
        <Button type="button" size="sm" disabled={disabled} data-testid="guide-submit" onClick={() => onSubmit(composeGuideMessage(guide, choices))}>
          生成
        </Button>
        <Button type="button" size="sm" variant="ghost" data-testid="guide-cancel" onClick={onCancel}>
          先不用
        </Button>
      </div>
    </section>
  )
}
