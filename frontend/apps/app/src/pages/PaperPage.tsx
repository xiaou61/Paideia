import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, difficultyText, type PaperView } from '@paideia/knowledge'
import { AppShell, Button } from '@paideia/ui'
import { CheckCircle2 } from 'lucide-react'
import { useLocation, useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

/** 作答页（WORK-083 TASK-007）。 */
export function PaperPage() {
  const { id } = useParams<{ id: string }>()
  const paperId = Number(id)
  const exam = useMemo(() => createExamApi(api), [])
  const navigate = useNavigate()
  const location = useLocation()
  // 组卷时候选不足，列表页把 shortfall 带过来（GET /papers/{id} 不返回这个值）。
  const shortfall = (location.state as { shortfall?: number; totalCount?: number } | null)?.shortfall ?? 0
  const [paper, setPaper] = useState<PaperView | null>(null)
  const [answers, setAnswers] = useState<Record<number, number>>({})
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    // 路由参数变化会复用同一个组件实例（router 没给 PaperPage 加 key），
    // 旧请求后返回会覆盖新卷、旧选项也会被带到新卷，所以这里既要取消也要清空。
    let cancelled = false
    setPaper(null)
    setAnswers({})
    setError(null)
    void (async () => {
      try {
        const view = await exam.paper(paperId)
        if (!cancelled) setPaper(view)
      } catch (cause) {
        if (!cancelled) setError(errorMessage(cause))
      }
    })()
    return () => {
      cancelled = true
    }
  }, [exam, paperId])

  async function submit() {
    if (!paper) return
    const unanswered = paper.items.length - Object.keys(answers).length
    if (unanswered > 0 && !window.confirm(`还有 ${unanswered} 题未作答，交卷后不可再改。继续？`)) {
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await exam.submitPaper(paper.id, answers)
      navigate(`/papers/${paper.id}/result`)
    } catch (cause) {
      setError(errorMessage(cause))
      setSubmitting(false)
    }
  }

  const answeredCount = Object.keys(answers).length
  const totalItems = paper?.items.length ?? 0
  const progress = totalItems === 0 ? 0 : Math.round((answeredCount / totalItems) * 100)

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <div className="mb-6 flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl" data-testid="paper-title">
            {paper?.title ?? '试卷'}
          </h1>
          {paper ? (
            <p className="text-muted-foreground mt-2 text-sm">
              {totalItems} 题 · 已答 {answeredCount} 题
            </p>
          ) : null}
        </div>
        <Button variant="ghost" size="sm" onClick={() => navigate('/papers')} className="shrink-0">
          返回试卷
        </Button>
      </div>

      {paper && paper.status !== 'submitted' && totalItems > 0 ? (
        <div className="mb-5">
          <div className="bg-muted h-1.5 w-full overflow-hidden rounded-full">
            <div className="bg-primary h-full rounded-full transition-all" style={{ width: `${progress}%` }} />
          </div>
        </div>
      ) : null}

      {shortfall > 0 ? (
        <p className="mb-4 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive" data-testid="paper-shortfall">
          候选不足：这份试卷只组到 {paper?.items.length ?? 0} 题（少 {shortfall} 题）。
        </p>
      ) : null}

      {paper?.status === 'submitted' ? (
        <p className="mb-4 rounded-lg bg-green-500/10 px-3 py-2 text-sm text-green-700">
          这份试卷已交卷，
          <button type="button" className="text-primary font-medium hover:underline" onClick={() => navigate(`/papers/${paper.id}/result`)}>
            查看成绩
          </button>
        </p>
      ) : null}

      {error ? <p className="mb-4 text-sm text-destructive">{error}</p> : null}

      {!paper && !error ? <p className="mt-6 text-sm text-muted-foreground">加载中…</p> : null}

      <ol className="space-y-4" data-testid="paper-items">
        {paper?.items.map((item) => (
          <li key={item.itemId} className="rounded-xl border border-border bg-card p-4">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span className="tabular-nums">第 {item.seq} 题</span>
              {difficultyText(item.difficultyLabel) ? (
                <span className="rounded-full bg-muted px-2 py-0.5">{difficultyText(item.difficultyLabel)}</span>
              ) : null}
            </div>
            <p className="mt-2 text-base font-medium text-foreground">{item.stem}</p>
            <ul className="mt-3 space-y-2">
              {item.options.map((option, optionIndex) => {
                const selected = answers[item.itemId] === optionIndex
                return (
                  <li key={optionIndex}>
                    <label
                      className={
                        'flex cursor-pointer items-start gap-2 rounded-lg border px-3 py-2.5 text-sm transition-colors ' +
                        (selected
                          ? 'border-primary bg-primary/10 text-foreground'
                          : 'border-border bg-background hover:bg-accent/40')
                      }
                    >
                      <input
                        type="radio"
                        className="mt-0.5"
                        name={`item-${item.itemId}`}
                        checked={selected}
                        disabled={paper.status === 'submitted'}
                        onChange={() => setAnswers((prev) => ({ ...prev, [item.itemId]: optionIndex }))}
                      />
                      <span className="min-w-0 flex-1">
                        {String.fromCharCode(65 + optionIndex)}. {option}
                      </span>
                    </label>
                  </li>
                )
              })}
            </ul>
          </li>
        ))}
      </ol>

      {paper && paper.status !== 'submitted' ? (
        <div className="border-border bg-background/95 sticky bottom-0 mt-6 flex items-center justify-between gap-3 border-t py-3">
          <span className="text-muted-foreground text-sm tabular-nums">
            {answeredCount} / {totalItems} 已作答
          </span>
          <Button onClick={() => void submit()} disabled={submitting} data-testid="paper-submit">
            {submitting ? '交卷中…' : '交卷'}
            <CheckCircle2 className="size-4" />
          </Button>
        </div>
      ) : null}
    </AppShell>
  )
}
