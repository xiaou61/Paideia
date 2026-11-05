import { ChevronLeft, ChevronRight, Pause, Play } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { CustomRendererProps } from 'streamdown'

import { Button } from '../../components/button'
import { BlockFallback, BlockFrame, Pill } from './blocks'
import { useSourceReading } from './context'
import {
  flowPermalink,
  parseCodeFlow,
  type CodeFlow,
  type FlowStep,
} from './schema'
import { PredictBlock } from './source-walk'

/**
 * 流程播放器（code-flow）。
 *
 * <p>把「左架构图 + 右真实代码 + 顶部步进 + 底部会话日志」合成一个同屏的步进块：
 * 每走一步，图上对应节点/连线亮起、右侧代码精确高亮到行、底部日志追加一条。
 * 这是 `code-map` 与 `code-tour` 的合体，用于把「一次请求怎么流过整个项目」讲成一段可播放的走查。
 *
 * <p>边界：代码用块内 `code`（示意片段即可），不强制真实仓库；有 `repo/commit/file` 时页脚给原文链接。
 * 图布局与 `code-map` 一致（手写 layer/order，不引布局库），节点是真正的 `<button>`。
 */

const NODE_WIDTH = 176
const NODE_HEIGHT = 62
const COLUMN_GAP = 210
const ROW_GAP = 86

export function CodeFlow({ code }: CustomRendererProps) {
  const parsed = useMemo(() => parseCodeFlow(code), [code])
  if (!parsed.ok) {
    return <BlockFallback title="流程播放器" error={parsed.error} raw={code} />
  }
  return <FlowBody flow={parsed.value} />
}

function FlowBody({ flow }: { flow: CodeFlow }) {
  const { progress, entryTitle, renderText } = useSourceReading()
  const [current, setCurrent] = useState(() => {
    const saved = progress?.read()?.tours[flow.id]?.step ?? 0
    return Math.min(Math.max(saved, 0), Math.max(flow.steps.length - 1, 0))
  })
  const [playing, setPlaying] = useState(false)
  const [predicted, setPredicted] = useState<Record<number, number>>({})
  const [revealed, setRevealed] = useState<Record<number, boolean>>({})
  const timer = useRef<number | null>(null)
  const total = flow.steps.length

  useEffect(() => {
    if (!playing) {
      return
    }
    timer.current = window.setInterval(() => {
      setCurrent((value) => {
        if (value + 1 >= total) {
          setPlaying(false)
          return value
        }
        return value + 1
      })
    }, 1500)
    return () => {
      if (timer.current !== null) {
        window.clearInterval(timer.current)
        timer.current = null
      }
    }
  }, [playing, total])

  useEffect(() => {
    if (progress === undefined) {
      return
    }
    progress.update({ tours: { [flow.id]: { step: current, total, done: current >= total - 1 } } })
  }, [flow.id, current, total, progress])

  const step = flow.steps[Math.min(current, total - 1)]
  if (step === undefined) {
    return null
  }

  const positions = new Map(
    flow.nodes.map((node) => [node.id, { x: 16 + node.layer * COLUMN_GAP, y: 16 + node.order * ROW_GAP }]),
  )
  const width = Math.max(...flow.nodes.map((node) => (positions.get(node.id)?.x ?? 0) + NODE_WIDTH)) + 24
  const height = Math.max(...flow.nodes.map((node) => (positions.get(node.id)?.y ?? 0) + NODE_HEIGHT)) + 24

  const activeNodes = new Set(step.nodes ?? [])
  const activeEdges = new Set(step.edges ?? [])

  const move = (next: number) => {
    setPlaying(false)
    setCurrent(Math.max(0, Math.min(next, total - 1)))
  }
  const jumpToNode = (id: string) => {
    const node = flow.nodes.find((item) => item.id === id)
    if (node?.step !== undefined) {
      move(node.step)
    }
  }

  const permalink = flowPermalink(flow, step)
  const shownFile = step.file ?? flow.file
  const startLine = step.line ?? 1

  return (
    <BlockFrame
      title={flow.title ?? `流程播放 · ${flow.id}`}
      testId="code-flow"
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
        onKeyDown={(event) => {
          if (event.key === 'ArrowRight') {
            event.preventDefault()
            move(current + 1)
          } else if (event.key === 'ArrowLeft') {
            event.preventDefault()
            move(current - 1)
          }
        }}
        tabIndex={-1}
        role="group"
        aria-label={`流程播放：${flow.title ?? flow.id}`}
      >
        {/* 顶部步进工具条 */}
        <div className="border-border bg-muted/20 border-b px-4 py-2.5">
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" variant="outline" data-testid="code-flow-prev" disabled={current === 0} onClick={() => move(current - 1)}>
              <ChevronLeft className="size-3.5" />
              上一步
            </Button>
            <Button size="sm" data-testid="code-flow-next" disabled={current >= total - 1} onClick={() => move(current + 1)}>
              下一步
              <ChevronRight className="size-3.5" />
            </Button>
            <Button size="sm" variant="outline" data-testid="code-flow-play" aria-label={playing ? '暂停' : '自动播放'} onClick={() => setPlaying((value) => !value)}>
              {playing ? <Pause className="size-3.5" /> : <Play className="size-3.5" />}
              {playing ? '暂停' : '播放'}
            </Button>
            <span className="text-muted-foreground text-xs tabular-nums" aria-live="polite" data-testid="code-flow-step-indicator">
              第 {current + 1} / {total} 步
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium">{step.title}</div>
              {step.desc === undefined ? null : (
                <div className="text-muted-foreground truncate text-xs">{step.desc}</div>
              )}
            </div>
          </div>
          <div className="mt-2 flex gap-1" aria-hidden="true">
            {flow.steps.map((item, index) => (
              <span
                key={`${item.title}-${index}`}
                className={`h-1 flex-1 rounded-full transition-colors ${
                  index === current ? 'bg-primary' : index < current ? 'bg-primary/40' : 'bg-border'
                }`}
              />
            ))}
          </div>
        </div>

        <div className="grid lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
          {/* 左：架构图 */}
          <div className="border-border min-w-0 overflow-x-auto border-b p-3 lg:border-r lg:border-b-0">
            <div className="relative" style={{ width, height, minWidth: '100%' }}>
              <svg className="pointer-events-none absolute inset-0" width={width} height={height} aria-hidden="true">
                <defs>
                  <marker id="flow-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                    <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" />
                  </marker>
                </defs>
                {flow.edges.map((edge, index) => {
                  const from = positions.get(edge.from)
                  const to = positions.get(edge.to)
                  if (from === undefined || to === undefined) {
                    return null
                  }
                  const key = `${edge.from}>${edge.to}`
                  const active = activeEdges.has(key)
                  const forward = to.x >= from.x + NODE_WIDTH
                  const x1 = forward ? from.x + NODE_WIDTH : from.x
                  const y1 = from.y + NODE_HEIGHT / 2
                  const x2 = forward ? to.x : to.x + NODE_WIDTH
                  const y2 = to.y + NODE_HEIGHT / 2
                  const midX = (x1 + x2) / 2
                  return (
                    <g key={index} className={active ? 'text-primary' : 'text-border'}>
                      <path
                        d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`}
                        fill="none"
                        stroke="currentColor"
                        strokeWidth={active ? 2.4 : 1.4}
                        strokeDasharray={active ? '6 5' : undefined}
                        markerEnd="url(#flow-arrow)"
                      />
                      {edge.label === undefined ? null : (
                        <text x={midX} y={(y1 + y2) / 2 - 6} textAnchor="middle" className="fill-muted-foreground text-[9.5px]">
                          {edge.label}
                        </text>
                      )}
                    </g>
                  )
                })}
              </svg>
              {flow.nodes.map((node) => {
                const position = positions.get(node.id)
                if (position === undefined) {
                  return null
                }
                const active = activeNodes.has(node.id)
                return (
                  <button
                    key={node.id}
                    type="button"
                    data-testid={`code-flow-node-${node.id}`}
                    data-active={active ? 'true' : 'false'}
                    onClick={() => jumpToNode(node.id)}
                    disabled={node.step === undefined}
                    style={{ left: position.x, top: position.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                    className={`absolute flex flex-col justify-center rounded-lg border px-3 text-left transition-colors ${
                      active
                        ? 'border-primary bg-primary/10 text-primary ring-primary/30 ring-2'
                        : 'border-border bg-card hover:bg-muted'
                    } ${node.step === undefined ? 'cursor-default' : 'cursor-pointer'}`}
                  >
                    <span className="line-clamp-2 text-sm leading-snug font-medium break-words">{node.label}</span>
                    {node.sub === undefined ? null : (
                      <span className="text-muted-foreground truncate font-mono text-[10px]">{node.sub}</span>
                    )}
                  </button>
                )
              })}
            </div>
          </div>

          {/* 右：代码 + 日志 + 讲解 */}
          <div className="flex min-w-0 flex-col">
            <div className="border-border border-b">
              <div className="bg-muted/40 flex items-center justify-between gap-2 px-3 py-1.5">
                <span className="truncate font-mono text-[11px] text-muted-foreground">{shownFile ?? '示意片段'}</span>
                {permalink === undefined ? (
                  <span className="text-muted-foreground shrink-0 text-[10px]">示意片段</span>
                ) : (
                  <a href={permalink} target="_blank" rel="noreferrer noopener" className="text-primary shrink-0 text-[11px] underline underline-offset-2">
                    在 GitHub 打开 ↗
                  </a>
                )}
              </div>
              <div className="max-h-[42dvh] overflow-auto py-2 font-mono text-[12.5px] leading-6" data-testid="code-flow-code">
                {step.code === undefined ? (
                  <p className="text-muted-foreground px-3 py-2 text-xs">这一步没有代码，只有图与讲解。</p>
                ) : (
                  step.code.split('\n').map((line, index) => {
                    const number = startLine + index
                    const highlighted = (step.focus ?? []).includes(index + 1)
                    return (
                      <div
                        key={number}
                        data-testid={highlighted ? 'code-flow-focus-line' : undefined}
                        className={`flex gap-3 px-3 ${highlighted ? 'bg-primary/10 border-primary border-l-2' : 'border-l border-transparent'}`}
                      >
                        <span className={`w-9 shrink-0 text-right select-none ${highlighted ? 'text-primary font-medium' : 'text-muted-foreground'}`}>
                          {number}
                        </span>
                        <span className="whitespace-pre">{line === '' ? ' ' : line}</span>
                      </div>
                    )
                  })
                )}
              </div>
            </div>

            <div className="border-border border-b bg-muted/20 px-3 py-2" data-testid="code-flow-log">
              <div className="text-muted-foreground mb-1 text-[10.5px]">会话日志</div>
              {step.log === undefined || step.log.length === 0 ? (
                <div className="text-muted-foreground text-[11px]">（还没有内容）</div>
              ) : (
                <div className="space-y-0.5">
                  {step.log.map((row, index) => (
                    <div key={index} className="flex gap-2 text-[11.5px]">
                      <span className="text-muted-foreground min-w-14 shrink-0">{row.k}</span>
                      <span className="break-all font-mono">{row.v}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="flex min-w-0 flex-col gap-3 p-4">
              {step.explain === undefined ? null : (
                <div className="text-sm leading-7" data-testid="code-flow-explain">
                  {render(step.explain, renderText)}
                </div>
              )}

              <PredictBlock
                predict={step.predict}
                answered={predicted[current]}
                revealed={revealed[current] === true}
                onAnswer={(choice) => setPredicted((previous) => ({ ...previous, [current]: choice }))}
                onReveal={() => setRevealed((previous) => ({ ...previous, [current]: true }))}
                renderText={renderText}
              />

              <FlowState step={step} />

              <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
                <Button size="sm" variant="outline" disabled={current === 0} onClick={() => move(current - 1)}>
                  <ChevronLeft className="size-3.5" />
                  上一步
                </Button>
                <Button size="sm" disabled={current >= total - 1} onClick={() => move(current + 1)}>
                  下一步
                  <ChevronRight className="size-3.5" />
                </Button>
                {step.explain === undefined ? null : (
                  <span className="text-muted-foreground ml-auto text-[11px]">
                    第 {current + 1} 步 · {entryTitle ?? flow.title ?? flow.id}
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>

      <footer className="border-border text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 border-t px-4 py-2 text-[11px]">
        {flow.repo === undefined ? (
          <span>示意片段（非真实仓库）</span>
        ) : (
          <span className="font-mono">
            {flow.repo}
            {flow.commit === undefined ? '' : `@${flow.commit}`}
          </span>
        )}
        {flow.license === undefined ? null : (
          <>
            <span>·</span>
            <span>{flow.license}</span>
          </>
        )}
        <span className="ml-auto">流程播放器 · 左图右码步进</span>
      </footer>
    </BlockFrame>
  )
}

function render(text: string, renderText?: (markdown: string) => ReactNode): ReactNode {
  return renderText === undefined ? <p className="whitespace-pre-wrap">{text}</p> : renderText(text)
}

/** 状态快照（变量 / 调用栈 / 堆 / 输出），作者不写就不渲染。 */
function FlowState({ step }: { step: FlowStep }) {
  const state = step.state
  if (state === undefined) {
    return null
  }
  const empty =
    (state.vars?.length ?? 0) + (state.stack?.length ?? 0) + (state.heap?.length ?? 0) + (state.output?.length ?? 0) === 0
  if (empty) {
    return null
  }
  return (
    <div className="border-border rounded-lg border" data-testid="code-flow-state">
      <div className="border-border text-muted-foreground border-b px-3 py-1.5 text-[11px]">此刻的状态</div>
      <div className="grid gap-3 p-3 text-xs sm:grid-cols-2">
        {state.vars === undefined || state.vars.length === 0 ? null : (
          <FlowGroup title="变量">
            {state.vars.map((variable) => (
              <div key={variable.name} className="flex gap-2 font-mono">
                <span className="text-primary">{variable.name}</span>
                <span className="text-muted-foreground">=</span>
                <span className="break-all">{variable.value}</span>
              </div>
            ))}
          </FlowGroup>
        )}
        {state.stack === undefined || state.stack.length === 0 ? null : (
          <FlowGroup title="调用栈（顶在上）">
            {state.stack.map((frame, index) => (
              <div key={index} className="font-mono">
                {index === 0 ? '▸ ' : '  '}
                {frame}
              </div>
            ))}
          </FlowGroup>
        )}
        {state.output === undefined || state.output.length === 0 ? null : (
          <FlowGroup title="输出">
            <pre className="whitespace-pre-wrap">{state.output.join('\n')}</pre>
          </FlowGroup>
        )}
      </div>
    </div>
  )
}

function FlowGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <div className="text-muted-foreground mb-1">{title}</div>
      <div className="space-y-0.5">{children}</div>
    </div>
  )
}
