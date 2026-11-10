import type { SelfAssessmentLevel } from './types'

const OPTIONS: readonly { level: SelfAssessmentLevel; label: string }[] = [
  { level: 'understood', label: '懂了' },
  { level: 'unsure', label: '还不懂' },
]

/**
 * 自评按钮。
 *
 * <p>再点一次同一个按钮就是**取消标记**——不额外放一个"清除"，因为用户想撤销时最自然的
 * 动作就是再点一下它。取消是幂等的，重复请求不会报错。
 *
 * <p>刻意只有两个状态：这是学习者模型的第一块砖，不是掌握度推断。做出"五档熟练度"会让人
 * 以为系统真的算出了什么，而它其实只是听用户说。
 */
export function SelfAssessmentButtons({
  current,
  disabled,
  onChange,
  variant = 'inline',
}: {
  current: SelfAssessmentLevel | null
  disabled?: boolean
  onChange: (next: SelfAssessmentLevel | null) => void
  /**
   * `bar` 是给移动端底部操作条用的（2026-11-10）：去掉外框与说明文字，按钮加大一档。
   * 做成变体而不是另写一份组件，是因为"点一下标记、再点一下取消"的规则只能有一处实现。
   * testid 也带 `-bar` 后缀——同一条内容上两个实例共存时，重复 testid 会让 e2e 直接报
   * strict mode 冲突。
   */
  variant?: 'inline' | 'bar'
}) {
  const bar = variant === 'bar'
  return (
    <div
      className={
        bar ? 'flex items-center gap-2' : 'border-border flex flex-wrap items-center gap-2 rounded-md border p-3'
      }
      data-testid={bar ? 'self-assessment-bar' : 'self-assessment'}
    >
      {bar ? null : <span className="text-muted-foreground text-xs">这条内容你掌握了吗</span>}
      {OPTIONS.map((option) => {
        const active = current === option.level
        return (
          <button
            key={option.level}
            type="button"
            disabled={disabled === true}
            aria-pressed={active}
            data-testid={`self-assessment-${option.level}${bar ? '-bar' : ''}`}
            onClick={() => onChange(active ? null : option.level)}
            className={
              active
                ? `border-foreground bg-muted rounded-md border font-medium ${bar ? 'px-4 py-2 text-sm' : 'px-3 py-1 text-xs'}`
                : `border-border text-muted-foreground hover:text-foreground rounded-md border ${bar ? 'px-4 py-2 text-sm' : 'px-3 py-1 text-xs'}`
            }
          >
            {option.label}
          </button>
        )
      })}
      {current === null || bar ? null : (
        <span className="text-muted-foreground text-xs">（再点一次可取消）</span>
      )}
    </div>
  )
}
