import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, difficultyText, type PaperView } from '@paideia/knowledge'
import { Button } from '@paideia/ui'
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

  return (
    <div className="min-h-dvh bg-background">
      <LearnerHeader />
      <main className="mx-auto w-full max-w-3xl px-4 py-6">
        <h1 className="text-xl font-semibold text-foreground" data-testid="paper-title">{paper?.title ?? '试卷'}</h1>
        {paper ? (
          <p className="mt-1 text-sm text-muted-foreground">
            {paper.items.length} 题
          </p>
        ) : null}
        {shortfall > 0 ? (
          <p className="mt-2 text-sm text-destructive" data-testid="paper-shortfall">
            候选不足：这份试卷只组到 {paper?.items.length ?? 0} 题（少 {shortfall} 题）。
          </p>
        ) : null}

        {paper?.status === 'submitted' ? (
          <p className="mt-4 text-sm text-muted-foreground">
            这份试卷已交卷，
            <button type="button" className="text-primary hover:underline" onClick={() => navigate(`/papers/${paper.id}/result`)}>
              查看成绩
            </button>
          </p>
        ) : null}

        {error ? <p className="mt-4 text-sm text-destructive">{error}</p> : null}

        <ol className="mt-4 space-y-4" data-testid="paper-items">
          {paper?.items.map((item) => (
            <li key={item.itemId} className="rounded-lg border border-border bg-card p-4">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>第 {item.seq} 题</span>
                {difficultyText(item.difficultyLabel) ? (
                  <span className="rounded bg-muted px-2 py-0.5">{difficultyText(item.difficultyLabel)}</span>
                ) : null}
              </div>
              <p className="mt-2 text-base font-medium text-foreground">{item.stem}</p>
              <ul className="mt-3 space-y-2">
                {item.options.map((option, optionIndex) => (
                  <li key={optionIndex}>
                    <label className="flex cursor-pointer items-center gap-2 rounded border border-border bg-background px-3 py-2 text-sm">
                      <input
                        type="radio"
                        name={`item-${item.itemId}`}
                        checked={answers[item.itemId] === optionIndex}
                        disabled={paper.status === 'submitted'}
                        onChange={() => setAnswers((prev) => ({ ...prev, [item.itemId]: optionIndex }))}
                      />
                      {String.fromCharCode(65 + optionIndex)}. {option}
                    </label>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>

        {paper && paper.status !== 'submitted' ? (
          <div className="mt-6 flex justify-end">
            <Button onClick={() => void submit()} disabled={submitting} data-testid="paper-submit">
              {submitting ? '交卷中…' : '交卷'}
            </Button>
          </div>
        ) : null}
      </main>
    </div>
  )
}
