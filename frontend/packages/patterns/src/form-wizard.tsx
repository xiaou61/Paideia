import type { ReactNode } from 'react'

import { Button, Card, CardContent, PageHeader } from '@paideia/ui'

export interface FormWizardStep {
  key: string
  title: string
  description?: ReactNode
}

export interface FormWizardProps {
  title?: string
  description?: ReactNode
  steps: FormWizardStep[]
  /** 当前步骤下标，0 起。 */
  current: number
  /** 渲染当前步骤的表单主体。 */
  renderStep: (step: FormWizardStep, index: number) => ReactNode
  onNext?: () => void
  onPrev?: () => void
  onSubmit?: () => void
  nextLabel?: string
  prevLabel?: string
  submitLabel?: string
  /** 提交/切换进行中时禁用按钮。 */
  busy?: boolean
}

/**
 * 表单向导模板：步骤指示 + 单步表单区 + 上一步/下一步/提交。
 *
 * <p>步骤状态（`current`）与提交动作都由调用方持有，模板不保存任何状态——这样表单校验、
 * 草稿保存都能留在业务侧，模板只提供一致的步进外观。
 */
export function FormWizard({
  title,
  description,
  steps,
  current,
  renderStep,
  onNext,
  onPrev,
  onSubmit,
  nextLabel = '下一步',
  prevLabel = '上一步',
  submitLabel = '提交',
  busy = false,
}: FormWizardProps) {
  const activeStep = steps[current]
  const isFirst = current <= 0
  const isLast = current >= steps.length - 1

  return (
    <div>
      {title === undefined ? null : <PageHeader title={title} description={description} />}

      <ol className="mb-6 flex flex-wrap items-center gap-3" data-testid="form-wizard-steps">
        {steps.map((step, index) => {
          const state = index < current ? 'done' : index === current ? 'active' : 'upcoming'
          return (
            <li
              key={step.key}
              data-state={state}
              className={[
                'flex items-center gap-2 rounded-full border px-3 py-1 text-sm',
                state === 'active'
                  ? 'border-foreground/40 bg-muted font-medium text-foreground'
                  : 'text-muted-foreground',
              ].join(' ')}
            >
              <span className="font-mono text-xs">{index + 1}</span>
              <span>{step.title}</span>
            </li>
          )
        })}
      </ol>

      <Card>
        <CardContent className="pt-6">
          {activeStep === undefined ? null : renderStep(activeStep, current)}
        </CardContent>
      </Card>

      <div className="mt-5 flex justify-between">
        <Button type="button" variant="outline" onClick={onPrev} disabled={isFirst || busy}>
          {prevLabel}
        </Button>
        {isLast ? (
          <Button type="button" onClick={onSubmit} disabled={busy}>
            {submitLabel}
          </Button>
        ) : (
          <Button type="button" onClick={onNext} disabled={busy}>
            {nextLabel}
          </Button>
        )}
      </div>
    </div>
  )
}
