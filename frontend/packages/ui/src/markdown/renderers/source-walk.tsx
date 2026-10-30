import { ChevronLeft, ChevronRight, CornerDownRight, RotateCcw } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { CustomRendererProps } from 'streamdown'

import { Button } from '../../components/button'
import { AskButton, BlockFallback, BlockFrame, Pill } from './blocks'
import { useSourceReading } from './context'
import {
  parseSourceWalk,
  visibleSteps,
  walkDefaults,
  walkPermalink,
  type SourceWalk,
  type WalkControlValues,
  type WalkStep,
} from './schema'

/**
 * 整文件源码走读（WORK-057）。
 *
 * <p>与 `code-tour` 的根本区别：步骤只给「文件 + 行区间」，代码从整份快照里取。
 * 读者能看到方法前后的上下文，而不是被裁剪过的片段。
 *
 * <p>三条硬边界：
 * 1. **只取同源相对路径**——`base` 与 `file` 已在 schema 里校验过；这里再验一次解析后的 origin，
 *    不通过就降级成块内错误，绝不发请求。
 * 2. **控件只重排可见步骤**——过滤是纯函数（`visibleSteps`），组件不写任何章节逻辑。
 * 3. **文件取不到不影响讲解**——代码区显示错误，步骤、状态与练习仍可读。
 */
export function SourceWalk({ code }: CustomRendererProps) {
  const parsed = useMemo(() => parseSourceWalk(code), [code])
  const walk = parsed.ok ? parsed.value : null
  const { entryTitle, progress, renderText } = useSourceReading()

  if (!parsed.ok) {
    return <BlockFallback title="源码精读" error={parsed.error} raw={code} />
  }
  if (walk === null) {
    return null
  }
  return <WalkBody walk={walk} entryTitle={entryTitle} progress={progress} renderText={renderText} />
}

function WalkBody({
  walk,
  entryTitle,
  progress,
  renderText,
}: {
  walk: SourceWalk
  entryTitle?: string
  progress: ReturnType<typeof useSourceReading>['progress']
  renderText?: (markdown: string) => ReactNode
}) {
  const [values, setValues] = useState<WalkControlValues>(() => walkDefaults(walk))
  const visible = useMemo(() => visibleSteps(walk, values), [walk, values])
  const [current, setCurrent] = useState(() => {
    const saved = progress?.read()?.tours[walk.id]?.step ?? 0
    return Math.min(Math.max(saved, 0), Math.max(visible.length - 1, 0))
  })
  const [predicted, setPredicted] = useState<Record<number, number>>({})
  const [revealed, setRevealed] = useState<Record<number, boolean>>({})
  /** 读者手动点的文件页签；换步骤时清空，回到该步声明的文件。 */
  const [manualFile, setManualFile] = useState<string | null>(null)
  const [pin, setPin] = useState<{ file: string; line: number } | null>(null)
  const [texts, setTexts] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const codeRef = useRef<HTMLDivElement | null>(null)

  // 控件一变就回到第一步：分支换了，下标含义就变了，硬停在原步只会让人错位
  const setControl = (id: string, value: boolean | string) => {
    setValues((previous) => ({ ...previous, [id]: value }))
    setCurrent(0)
    setPin(null)
  }

  const step = visible[Math.min(current, Math.max(visible.length - 1, 0))]

  // 取整份文件：并发一次取完，失败只影响代码区
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      const loaded: Record<string, string> = {}
      for (const file of walk.files) {
        let url: URL
        try {
          url = new URL(`${walk.base}/${file.path}`, document.baseURI)
        } catch {
          setError(`文件路径不合法：${file.path}`)
          return
        }
        if (url.origin !== window.location.origin) {
          setError(`拒绝读取非同源文件：${file.path}`)
          return
        }
        try {
          const response = await fetch(url)
          if (!response.ok) {
            setError(`读取源码失败（HTTP ${response.status}）：${file.path}`)
            return
          }
          loaded[file.path] = await response.text()
        } catch (cause) {
          setError(`读取源码失败：${file.path}（${cause instanceof Error ? cause.message : String(cause)}）`)
          return
        }
      }
      if (!cancelled) {
        setTexts(loaded)
        setError(null)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [walk])

  // 切步/切文件后把目标行滚进视野（只滚代码面板）
  useEffect(() => {
    if (step === undefined) {
      return
    }
    setManualFile(null)
    const target = pin?.line ?? step.line
    const container = codeRef.current
    const line = container?.querySelector<HTMLElement>(`[data-line="${target}"]`)
    line?.scrollIntoView({ block: 'center' })
    // pin 只在被设置时影响，不能进依赖——否则点「返回」也会重新定位
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step?.file, step?.line, pin])

  // 进度：tours 是既有字段，服务端不认它的语义，localStorage 与页面合并都走原路
  useEffect(() => {
    if (progress === undefined || step === undefined) {
      return
    }
    progress.update({
      tours: { [walk.id]: { step: current, total: visible.length, done: current >= visible.length - 1 } },
    })
  }, [walk.id, current, visible.length, progress, step])

  if (step === undefined) {
    return null
  }

  const shownFile = pin?.file ?? manualFile ?? step.file
  const shownText = texts[shownFile]
  const answered = predicted[current]
  const total = visible.length
  const move = (next: number) => {
    setPin(null)
    setCurrent(Math.max(0, Math.min(next, total - 1)))
  }
  // 手动切到别的文件时，那一步的高亮不该出现在另一个文件里
  const onStepFile = shownFile === step.file
  const highlightFrom = pin === null ? (onStepFile ? step.line : 0) : pin.line
  const highlightTo = pin === null ? (onStepFile ? step.line + step.lines - 1 : 0) : pin.line

  return (
    <BlockFrame
      title={walk.title ?? `源码精读 · ${walk.repo}`}
      testId="source-walk"
      actions={
        <>
          <Pill>
            {current + 1} / {total}
          </Pill>
          {progress === undefined ? null : <Pill tone="primary">已记录进度</Pill>}
        </>
      }
    >
      <div
        className="grid lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]"
        onKeyDown={(event) => {
          if (event.key === 'ArrowRight') {
            event.preventDefault()
            move(current + 1)
          } else if (event.key === 'ArrowLeft') {
            event.preventDefault()
            move(current - 1)
          }
        }}
        role="group"
        aria-label={`源码精读：${walk.title ?? walk.repo}`}
      >
        <div className="border-border flex min-w-0 flex-col border-b lg:border-r lg:border-b-0">
          <div className="border-border flex gap-1 overflow-x-auto border-b px-2 py-1.5" role="tablist" aria-label="源码文件">
            {walk.files.map((file) => {
              const active = file.path === shownFile
              return (
                <button
                  key={file.path}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  data-testid="source-walk-file"
                  onClick={() => {
                    setPin(null)
                    setManualFile(file.path)
                  }}
                  className={`shrink-0 rounded-md px-2 py-1 font-mono text-[11px] transition-colors ${
                    active ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {file.label ?? file.path.split('/').pop()}
                </button>
              )
            })}
          </div>

          <div
            ref={codeRef}
            tabIndex={-1}
            role="region"
            aria-label={`源码：${shownFile}`}
            className="max-h-[60dvh] min-h-40 overflow-x-auto overflow-y-auto"
          >
            {error !== null ? (
              <p className="text-destructive p-4 text-sm" data-testid="source-walk-error">
                {error}
              </p>
            ) : shownText === undefined ? (
              <p className="text-muted-foreground p-4 text-sm" data-testid="source-walk-loading">
                加载源码…
              </p>
            ) : (
              <CodeLines text={shownText} from={highlightFrom} to={highlightTo} pinned={pin !== null} />
            )}
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-4 p-4">
          {walk.controls.length === 0 ? null : (
            <div className="border-border bg-muted/30 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border px-3 py-2">
              <span className="text-muted-foreground text-[11px]">实验</span>
              {walk.controls.map((control) =>
                control.type === 'toggle' ? (
                  <label key={control.id} className="flex items-center gap-1.5 text-xs">
                    <input
                      type="checkbox"
                      data-testid={`source-walk-control-${control.id}`}
                      checked={values[control.id] === true}
                      onChange={(event) => setControl(control.id, event.target.checked)}
                    />
                    {control.label}
                  </label>
                ) : (
                  <label key={control.id} className="flex items-center gap-1.5 text-xs">
                    {control.label}
                    <select
                      data-testid={`source-walk-control-${control.id}`}
                      className="border-border bg-background rounded-md border px-1.5 py-0.5 text-xs"
                      value={String(values[control.id] ?? control.default)}
                      onChange={(event) => setControl(control.id, event.target.value)}
                    >
                      {control.options.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                ),
              )}
            </div>
          )}

          <div>
            <div className="text-muted-foreground mb-1 text-[11px]">
              第 {current + 1} 步 · {step.file.split('/').pop()}:{step.line}
            </div>
            <h4 className="text-base font-medium">{step.title}</h4>
          </div>

          <ol className="border-border max-h-36 space-y-0.5 overflow-y-auto pr-1" data-testid="source-walk-steps">
            {visible.map((item, index) => (
              <li key={`${item.file}:${item.line}:${item.title}`}>
                <button
                  type="button"
                  aria-current={index === current ? 'step' : undefined}
                  data-testid="source-walk-step"
                  onClick={() => move(index)}
                  className={`w-full rounded px-2 py-1 text-left text-xs transition-colors ${
                    index === current ? 'bg-primary/10 text-primary font-medium' : 'text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {index + 1}. {item.title}
                </button>
              </li>
            ))}
          </ol>

          <div className="text-sm leading-7" data-testid="source-walk-explain">
            {renderText === undefined ? <p className="whitespace-pre-wrap">{step.explain}</p> : renderText(step.explain)}
          </div>

          {step.refs === undefined || step.refs.length === 0 ? null : (
            <div className="flex flex-wrap gap-1.5" data-testid="source-walk-refs">
              {step.refs.map((ref) => (
                <Button
                  key={`${ref.file}:${ref.line}`}
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setPin({ file: ref.file, line: ref.line })}
                >
                  <CornerDownRight className="size-3" />
                  {ref.label}
                </Button>
              ))}
              {pin === null ? null : (
                <Button type="button" variant="ghost" size="sm" onClick={() => setPin(null)}>
                  <RotateCcw className="size-3" />
                  返回
                </Button>
              )}
            </div>
          )}

          <PredictBlock
            predict={step.predict}
            answered={answered}
            revealed={revealed[current] === true}
            onAnswer={(choice) => setPredicted((previous) => ({ ...previous, [current]: choice }))}
            onReveal={() => setRevealed((previous) => ({ ...previous, [current]: true }))}
            renderText={renderText}
          />

          <StatePanel step={step} />

          <div className="mt-auto flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              data-testid="source-walk-prev"
              disabled={current === 0}
              onClick={() => move(current - 1)}
            >
              <ChevronLeft className="size-3.5" />
              上一步
            </Button>
            <Button
              type="button"
              size="sm"
              data-testid="source-walk-next"
              disabled={current >= total - 1}
              onClick={() => move(current + 1)}
            >
              下一步
              <ChevronRight className="size-3.5" />
            </Button>
            <AskButton
              label="问 AI"
              prompt={`结合《${entryTitle ?? walk.title ?? '这章'}》第 ${current + 1} 步（${walk.repo} ${step.file}:${step.line}），解释「${step.title}」这一步为什么这样做。`}
            />
          </div>
        </div>
      </div>

      <footer className="border-border text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 border-t px-4 py-2.5 text-[11px]">
        <span className="font-mono">
          {walk.repo}@{walk.ref}
        </span>
        <span>·</span>
        <span className="font-mono break-all">
          {step.file}:{step.line}-{step.line + step.lines - 1}
        </span>
        <span>·</span>
        <span>{walk.license}</span>
        <a
          href={walkPermalink(walk, step.file, step.line, step.lines)}
          target="_blank"
          rel="noreferrer noopener"
          className="text-primary ml-auto underline underline-offset-2"
        >
          查看 GitHub 原文 ↗
        </a>
      </footer>
    </BlockFrame>
  )
}

function CodeLines({ text, from, to, pinned }: { text: string; from: number; to: number; pinned: boolean }) {
  const lines = text.split('\n')
  return (
    <div className="min-w-max py-3 font-mono text-[12.5px] leading-6">
      {lines.map((line, index) => {
        const number = index + 1
        const active = number >= from && number <= to
        return (
          <div
            key={number}
            data-line={number}
            data-testid={active ? (pinned ? 'source-walk-pin-line' : 'source-walk-focus-line') : undefined}
            className={`flex gap-3 px-4 ${
              active ? 'bg-primary/10 border-primary border-l-2' : 'border-l border-transparent'
            }`}
          >
            <span className={`w-10 shrink-0 text-right select-none ${active ? 'text-primary font-medium' : 'text-muted-foreground'}`}>
              {number}
            </span>
            <span className="whitespace-pre">{line === '' ? ' ' : line}</span>
          </div>
        )
      })}
    </div>
  )
}

function StatePanel({ step }: { step: WalkStep }) {
  const groups = [
    { label: '调用栈', items: step.state?.stack?.map((frame) => ({ name: frame, value: '' })) },
    { label: '关键变量', items: step.state?.vars },
    { label: '注册表', items: step.state?.tables },
  ].filter((group) => group.items !== undefined && group.items.length > 0)
  if (groups.length === 0) {
    return null
  }
  return (
    <div className="border-border rounded-lg border" data-testid="source-walk-state">
      {groups.map((group) => (
        <div key={group.label} className="border-border border-b px-3 py-2 last:border-b-0">
          <div className="text-muted-foreground mb-1 text-[11px]">{group.label}</div>
          <dl className="space-y-0.5 font-mono text-[11.5px]">
            {group.items?.map((item, index) => (
              <div key={`${item.name}-${index}`} className="flex gap-2">
                <dt className="text-muted-foreground shrink-0">{item.name}</dt>
                {item.value === '' ? null : <dd className="break-all">{item.value}</dd>}
              </div>
            ))}
          </dl>
        </div>
      ))}
    </div>
  )
}

/** 预测题：答完才揭晓；答错可以重试，也有"直接看解释"的逃生口。 */
export function PredictBlock({
  predict,
  answered,
  revealed,
  onAnswer,
  onReveal,
  renderText,
}: {
  predict: WalkStep['predict']
  answered: number | undefined
  revealed: boolean
  onAnswer: (choice: number) => void
  onReveal: () => void
  renderText?: (markdown: string) => ReactNode
}) {
  if (predict === undefined) {
    return null
  }
  const correct = answered === predict.answer
  return (
    <div className="border-border bg-muted/30 rounded-lg border p-3" data-testid="source-walk-predict">
      <p className="mb-2 text-sm font-medium">{predict.question}</p>
      {answered === undefined && !revealed ? (
        <div className="flex flex-wrap gap-1.5">
          {predict.options.map((option, index) => (
            <Button key={option} type="button" variant="outline" size="sm" onClick={() => onAnswer(index)}>
              {option}
            </Button>
          ))}
          <Button type="button" variant="ghost" size="sm" onClick={onReveal}>
            直接看解释
          </Button>
        </div>
      ) : (
        <div className="space-y-2 text-sm">
          <p className={correct ? 'text-success' : 'text-destructive'}>
            {revealed ? '已揭晓：' : correct ? '答对了。' : '不对，再看一眼。'}
            {answered !== undefined && !correct ? ` 正确答案：${predict.options[predict.answer]}` : ''}
          </p>
          <div className="text-muted-foreground text-sm leading-6">
            {renderText === undefined ? <p className="whitespace-pre-wrap">{predict.reveal}</p> : renderText(predict.reveal)}
          </div>
          {answered !== undefined && !correct ? (
            <Button type="button" variant="outline" size="sm" onClick={() => onAnswer(predict.answer)}>
              再试一次
            </Button>
          ) : null}
        </div>
      )}
    </div>
  )
}
