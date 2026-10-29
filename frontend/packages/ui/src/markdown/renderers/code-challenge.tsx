import { useMemo, useState } from 'react'
import type { CustomRendererProps } from 'streamdown'

import { Button } from '../../components/button'
import { BlockFallback, BlockFrame, Pill } from './blocks'
import { RunBody } from './run-challenge'
import { ShellBody } from './shell-challenge'
import { SqlBody } from './sql-challenge'
import { useSourceReading } from './context'
import {
  blankCount,
  judgeBug,
  judgeFill,
  judgeOrder,
  parseCodeChallenge,
  type BugChallenge,
  type CodeChallenge,
  type FillChallenge,
  type OrderChallenge,
} from './schema'

/**
 * 动手练习（WORK-014）：填空 / 排序 / 找 bug。
 *
 * <p>三种题型都是**确定性判定**，全部在客户端完成——不执行用户代码。真正的"写代码并运行"
 * 是 WORK-015（沙箱），那需要服务端隔离，量级完全不同。
 *
 * <p>排序题刻意**不做拖拽**：上下按钮对键盘与触屏都可靠，拖拽在窄屏上很容易变成"想滚页面
 * 结果拖动了一行"。
 */
export function CodeChallenge({ code }: CustomRendererProps) {
  const parsed = useMemo(() => parseCodeChallenge(code), [code])
  const { progress } = useSourceReading()

  if (!parsed.ok) {
    return <BlockFallback title="动手练习" error={parsed.error} raw={code} />
  }
  const challenge = parsed.value
  return (
    <ChallengeView
      key={challenge.id}
      challenge={challenge}
      onGraded={(correct, total) => progress?.update({ challenges: { [challenge.id]: { score: correct, total } } })}
    />
  )
}

function ChallengeView({
  challenge,
  onGraded,
}: {
  challenge: CodeChallenge
  onGraded: (correct: number, total: number) => void
}) {
  return (
    <BlockFrame
      title={challenge.title}
      testId="challenge"
      actions={<Pill>{{ fill: '填空', order: '排序', bug: '找 bug', run: '写代码',
        'sql-write': '写 SQL', 'sql-fix': '改 SQL', shell: '判题' }[challenge.kind]}</Pill>}
    >
      <div className="space-y-4 p-4">
        {challenge.kind === 'fill' ? <FillBody challenge={challenge} onGraded={onGraded} /> : null}
        {challenge.kind === 'order' ? <OrderBody challenge={challenge} onGraded={onGraded} /> : null}
        {challenge.kind === 'bug' ? <BugBody challenge={challenge} onGraded={onGraded} /> : null}
        {challenge.kind === 'run' ? <RunBody challenge={challenge} onGraded={onGraded} /> : null}
        {challenge.kind === 'shell' ? <ShellBody challenge={challenge} onGraded={onGraded} /> : null}
        {challenge.kind === 'sql-write' || challenge.kind === 'sql-fix'
          ? <SqlBody challenge={challenge} onGraded={onGraded} />
          : null}
      </div>
    </BlockFrame>
  )
}

function FillBody({ challenge, onGraded }: { challenge: FillChallenge; onGraded: (c: number, t: number) => void }) {
  const [selected, setSelected] = useState<Record<number, number>>({})
  const [checked, setChecked] = useState(false)
  const blanks = blankCount(challenge.template)
  const segments = challenge.template.split(/\{\{\d+\}\}/)
  const correct = Array.from({ length: blanks }, (_, index) => selected[index] === challenge.answers[index]).filter(Boolean).length

  return (
    <>
      <div className="border-border bg-muted/30 overflow-x-auto rounded-lg border p-3 font-mono text-[12.5px] leading-7">
        <div className="min-w-max">
          {segments.map((segment, index) => (
            <span key={index}>
              <span className="whitespace-pre">{segment}</span>
              {index >= blanks ? null : (
                <select
                  aria-label={`第 ${index + 1} 个空`}
                  data-testid={`challenge-blank-${index}`}
                  value={selected[index] ?? ''}
                  disabled={checked}
                  onChange={(event) => {
                    const raw = event.target.value
                    setSelected((previous) => {
                      const next = { ...previous }
                      if (raw === '') {
                        // 选回「选择…」必须真的清空：写 0 会被当成"选了第 0 个"，
                        // 既无法撤销，也会让"已作答"与判对判错都算错。
                        delete next[index]
                      } else {
                        next[index] = Number(raw)
                      }
                      return next
                    })
                  }}
                  className={`mx-1 rounded border px-1 py-0.5 font-mono text-xs ${
                    checked
                      ? selected[index] === challenge.answers[index]
                        ? 'border-success text-success'
                        : 'border-destructive text-destructive'
                      : 'border-border bg-background'
                  }`}
                >
                  <option value="">选择…</option>
                  {(challenge.options[index] ?? []).map((option, optionIndex) => (
                    <option key={optionIndex} value={optionIndex}>
                      {option}
                    </option>
                  ))}
                </select>
              )}
            </span>
          ))}
        </div>
      </div>
      <GradedActions
        checked={checked}
        ready={Object.keys(selected).length === blanks}
        correct={correct}
        total={blanks}
        explain={challenge.explain}
        onCheck={() => {
          setChecked(true)
          onGraded(correct, blanks)
        }}
        onReset={() => {
          setChecked(false)
          setSelected({})
        }}
      />
    </>
  )
}

function OrderBody({ challenge, onGraded }: { challenge: OrderChallenge; onGraded: (c: number, t: number) => void }) {
  const [order, setOrder] = useState<number[]>(() => challenge.lines.map((_, index) => index))
  const [checked, setChecked] = useState(false)
  const correct = judgeOrder(challenge, order)

  const swap = (position: number, delta: number) => {
    const target = position + delta
    if (target < 0 || target >= order.length) {
      return
    }
    setOrder((previous) => {
      const next = [...previous]
      const current = next[position]
      const other = next[target]
      if (current === undefined || other === undefined) {
        return previous
      }
      next[position] = other
      next[target] = current
      return next
    })
  }

  return (
    <>
      <p className="text-muted-foreground text-xs">把行排成正确的执行顺序（用 ↑ ↓ 调整，不用拖拽）。</p>
      <ol className="space-y-1.5">
        {order.map((lineIndex, position) => {
          const line = challenge.lines[lineIndex] ?? ''
          return (
            <li key={lineIndex} className="flex items-start gap-2">
              <span className="text-muted-foreground w-5 shrink-0 text-right font-mono text-xs">{position + 1}</span>
              <span className="border-border bg-muted/30 min-w-0 flex-1 rounded border px-2 py-1 font-mono text-xs">
                {line}
              </span>
              <span className="flex shrink-0 gap-1">
                <Button type="button" variant="outline" size="xs" className="max-sm:size-11" aria-label="上移" disabled={checked || position === 0} onClick={() => swap(position, -1)}>
                  ↑
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="xs"
                  className="max-sm:size-11"
                  aria-label="下移"
                  disabled={checked || position === order.length - 1}
                  onClick={() => swap(position, 1)}
                >
                  ↓
                </Button>
              </span>
            </li>
          )
        })}
      </ol>
      <GradedActions
        checked={checked}
        ready
        correct={correct ? 1 : 0}
        total={1}
        explain={challenge.explain}
        onCheck={() => {
          setChecked(true)
          onGraded(correct ? 1 : 0, 1)
        }}
        onReset={() => {
          setChecked(false)
          setOrder(challenge.lines.map((_, index) => index))
        }}
      />
    </>
  )
}

function BugBody({ challenge, onGraded }: { challenge: BugChallenge; onGraded: (c: number, t: number) => void }) {
  const [line, setLine] = useState<number | null>(null)
  const [checked, setChecked] = useState(false)
  const lines = challenge.code.split('\n')
  const correct = line === null ? false : judgeBug(challenge, line)

  return (
    <>
      <p className="text-muted-foreground text-xs">点出这一行里错在哪一行。</p>
      <div className="border-border bg-muted/30 overflow-x-auto rounded-lg border py-2 font-mono text-[12.5px] leading-6">
        <div className="min-w-max">
          {lines.map((text, index) => {
            const number = index + 1
            const chosen = line === number
            const isBug = challenge.bugLine === number
            return (
              <button
                key={number}
                type="button"
                data-testid={`challenge-bug-line-${number}`}
                disabled={checked}
                onClick={() => setLine(number)}
                className={`flex w-full gap-3 px-3 text-left ${
                  checked && isBug
                    ? 'bg-success/15 text-success'
                    : checked && chosen
                      ? 'bg-destructive/15 text-destructive'
                      : chosen
                        ? 'bg-primary/10'
                        : 'hover:bg-muted'
                }`}
              >
                <span className="text-muted-foreground w-8 shrink-0 text-right">{number}</span>
                <span className="whitespace-pre">{text === '' ? ' ' : text}</span>
              </button>
            )
          })}
        </div>
      </div>
      <GradedActions
        checked={checked}
        ready={line !== null}
        correct={correct ? 1 : 0}
        total={1}
        explain={challenge.explain}
        onCheck={() => {
          setChecked(true)
          onGraded(correct ? 1 : 0, 1)
        }}
        onReset={() => {
          setChecked(false)
          setLine(null)
        }}
      />
    </>
  )
}

function GradedActions({
  checked,
  ready,
  correct,
  total,
  explain,
  onCheck,
  onReset,
}: {
  checked: boolean
  ready: boolean
  correct: number
  total: number
  explain?: string
  onCheck: () => void
  onReset: () => void
}) {
  const passed = correct === total
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" size="sm" data-testid="challenge-check" disabled={!ready || checked} onClick={onCheck}>
          检查
        </Button>
        {checked ? (
          <Button type="button" variant="outline" size="sm" onClick={onReset}>
            重做
          </Button>
        ) : null}
        {checked ? (
          <span className={`text-sm ${passed ? 'text-success' : 'text-destructive'}`} data-testid="challenge-result">
            {passed ? '✓ 全对' : `✗ 还对不上（${correct} / ${total}）`}
          </span>
        ) : null}
      </div>
      {checked && explain !== undefined ? (
        <p className="text-muted-foreground text-sm leading-7" data-testid="challenge-explain">
          {explain}
        </p>
      ) : null}
      {!checked && !ready ? <p className="text-muted-foreground text-xs">先做出选择，再点检查。</p> : null}
    </div>
  )
}

/** 供测试与作者指南复用：判定函数不依赖视图。 */
export { judgeBug, judgeFill, judgeOrder }
