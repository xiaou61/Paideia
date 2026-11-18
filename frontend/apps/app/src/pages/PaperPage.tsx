import { useCallback, useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, type PaperView } from '@paideia/knowledge'
import { Button } from '@paideia/ui'
import { useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

function difficultyText(label: string | null): string {
  if (label === 'EASY') return '易'
  if (label === 'MEDIUM') return '中'
  if (label === 'HARD') return '难'
  return ''
}

/** 作答页（WORK-083 TASK-007）。 */
export function PaperPage() {
  const { id } = useParams<{ id: string }>()
  const paperId = Number(id)
  const exam = useMemo(() => createExamApi(api), [])
  const navigate = useNavigate()
  const [paper, setPaper] = useState<PaperView | null>(null)
  const [answers, setAnswers] = useState<Record<number, number>>({})
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const view = await exam.paper(paperId)
      setPaper(view)
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }, [exam, paperId])

  useEffect(() => {
    void load()
  }, [load])

  async function submit() {
    if (!paper) return
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
            {paper.shortfall > 0 ? ` · 候选不足，少 ${paper.shortfall} 题` : ''}
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
