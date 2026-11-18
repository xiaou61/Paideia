import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, difficultyText, type PaperResult } from '@paideia/knowledge'
import { Link, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

/** 成绩页（WORK-083 TASK-007）：只给计数与概念/难度分布，不出 0–100 能力总分。 */
export function PaperResultPage() {
  const { id } = useParams<{ id: string }>()
  const paperId = Number(id)
  const exam = useMemo(() => createExamApi(api), [])
  const [result, setResult] = useState<PaperResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const view = await exam.paperResult(paperId)
        if (!cancelled) setResult(view)
      } catch (cause) {
        if (!cancelled) setError(errorMessage(cause))
      }
    })()
    return () => {
      cancelled = true
    }
  }, [exam, paperId])

  return (
    <div className="min-h-dvh bg-background">
      <LearnerHeader />
      <main className="mx-auto w-full max-w-3xl px-4 py-6">
        <div className="flex items-center justify-between">
          <h1 className="text-xl font-semibold text-foreground">{result?.title ?? '成绩'}</h1>
          <Link to="/papers" className="text-sm text-primary hover:underline">
            返回试卷
          </Link>
        </div>

        {error ? <p className="mt-4 text-sm text-destructive">{error}</p> : null}

        {result ? (
          <>
            <p className="mt-2 text-sm text-muted-foreground" data-testid="paper-result-summary">
              答对 {result.correct} / 共 {result.total} 题
            </p>

            <section className="mt-5 rounded-lg border border-border bg-card p-4">
              <h2 className="text-sm font-medium text-foreground">按概念</h2>
              <ul className="mt-2 divide-y divide-border text-sm">
                {result.concepts.map((concept) => (
                  <li key={concept.conceptId} className="flex items-center justify-between py-2">
                    <span className="text-foreground">
                      概念 #{concept.conceptId}
                      {concept.weak ? <span className="ml-2 rounded bg-destructive/10 px-2 py-0.5 text-xs text-destructive">薄弱</span> : null}
                    </span>
                    <span className="text-muted-foreground">
                      对 {concept.correct} / {concept.total}
                    </span>
                  </li>
                ))}
                {result.concepts.length === 0 ? <li className="py-2 text-muted-foreground">这些题未关联概念。</li> : null}
              </ul>
            </section>

            <section className="mt-4 rounded-lg border border-border bg-card p-4">
              <h2 className="text-sm font-medium text-foreground">按难度</h2>
              <ul className="mt-2 divide-y divide-border text-sm">
                {result.difficulties.map((item) => (
                  <li key={item.label} className="flex items-center justify-between py-2">
                    <span className="text-foreground">{difficultyText(item.label) || item.label}</span>
                    <span className="text-muted-foreground">
                      对 {item.correct} / {item.total}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          </>
        ) : null}
      </main>
    </div>
  )
}
