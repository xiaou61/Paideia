import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, difficultyText, type WrongQuestion } from '@paideia/knowledge'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

/** 错题本（WORK-083 TASK-007）：每道身份最近一次作答为错的题。 */
export function WrongBookPage() {
  const exam = useMemo(() => createExamApi(api), [])
  const [questions, setQuestions] = useState<WrongQuestion[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const list = await exam.wrongQuestions({ limit: 100 })
        if (!cancelled) setQuestions(list)
      } catch (cause) {
        if (!cancelled) setError(errorMessage(cause))
      }
    })()
    return () => {
      cancelled = true
    }
  }, [exam])

  return (
    <div className="min-h-dvh bg-background">
      <LearnerHeader />
      <main className="mx-auto w-full max-w-3xl px-4 py-6">
        <div className="flex items-center justify-between">
          <h1 className="text-xl font-semibold text-foreground">错题本</h1>
          <Link to="/practice" className="text-sm text-primary hover:underline">
            去刷题
          </Link>
        </div>

        {error ? <p className="mt-4 text-sm text-destructive">{error}</p> : null}
        {questions.length === 0 && !error ? <p className="mt-6 text-sm text-muted-foreground" data-testid="wrong-empty">还没有错题，继续保持。</p> : null}

        <ul className="mt-4 space-y-3" data-testid="wrong-list">
          {questions.map((question) => (
            <li key={question.questionKey} className="rounded-lg border border-border bg-card p-4">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>条目 #{question.entryId}</span>
                {difficultyText(question.difficultyLabel) ? (
                  <span className="rounded bg-muted px-2 py-0.5">{difficultyText(question.difficultyLabel)}</span>
                ) : null}
              </div>
              <p className="mt-2 text-base font-medium text-foreground">{question.stem}</p>
              {question.conceptNames.length > 0 ? (
                <p className="mt-2 text-xs text-muted-foreground">概念：{question.conceptNames.join('、')}</p>
              ) : null}
            </li>
          ))}
        </ul>
      </main>
    </div>
  )
}
