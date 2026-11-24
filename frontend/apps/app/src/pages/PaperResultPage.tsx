import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, difficultyText, type PaperResult } from '@paideia/knowledge'
import { AppShell, Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@paideia/ui'
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

  const percent = result && result.total > 0 ? Math.round((result.correct / result.total) * 100) : 0

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <div className="mb-6 flex items-center justify-between gap-4">
        <h1 className="text-2xl">{result?.title ?? '成绩'}</h1>
        <Link to="/papers" className="text-primary shrink-0 text-sm hover:underline">
          返回试卷
        </Link>
      </div>

      {error ? <p className="mt-4 text-sm text-destructive">{error}</p> : null}
      {!result && !error ? <p className="mt-6 text-sm text-muted-foreground">加载中…</p> : null}

      {result ? (
        <>
          {/* 成绩总览 */}
          <div className="rounded-2xl border border-border bg-card p-6" data-testid="paper-result-summary">
            <div className="flex items-end justify-between gap-4">
              <div>
                <p className="text-muted-foreground text-sm">答对</p>
                <p className="mt-1 text-4xl font-semibold tabular-nums">
                  {result.correct}
                  <span className="text-muted-foreground text-xl font-normal"> / {result.total}</span>
                </p>
              </div>
              <span
                className={
                  'rounded-full px-3 py-1 text-sm font-medium ' +
                  (percent >= 80
                    ? 'bg-green-500/10 text-green-700'
                    : percent >= 60
                      ? 'bg-amber-500/10 text-amber-700'
                      : 'bg-destructive/10 text-destructive')
                }
              >
                正确率 {percent}%
              </span>
            </div>
            <div className="bg-muted mt-4 h-2.5 w-full overflow-hidden rounded-full">
              <div
                className={
                  'h-full rounded-full transition-all ' +
                  (percent >= 80 ? 'bg-green-500' : percent >= 60 ? 'bg-amber-500' : 'bg-destructive')
                }
                style={{ width: `${percent}%` }}
              />
            </div>
          </div>

          <section className="mt-6 rounded-xl border border-border bg-card p-4">
            <h2 className="text-sm font-semibold text-foreground">按概念</h2>
            <ul className="mt-2 divide-y divide-border text-sm">
              {result.concepts.map((concept) => {
                const p = concept.total > 0 ? Math.round((concept.correct / concept.total) * 100) : 0
                return (
                  <li key={concept.conceptId} className="flex items-center justify-between gap-3 py-2.5">
                    <span className="flex min-w-0 items-center gap-2 text-foreground">
                      概念 #{concept.conceptId}
                      {concept.weak ? (
                        <span className="rounded bg-destructive/10 px-2 py-0.5 text-xs text-destructive">薄弱</span>
                      ) : null}
                    </span>
                    <span className="flex shrink-0 items-center gap-2">
                      <span className="bg-muted h-1.5 w-24 overflow-hidden rounded-full">
                        <span className="bg-primary block h-full rounded-full" style={{ width: `${p}%` }} />
                      </span>
                      <span className="text-muted-foreground w-14 text-right tabular-nums">
                        {concept.correct} / {concept.total}
                      </span>
                    </span>
                  </li>
                )
              })}
              {result.concepts.length === 0 ? (
                <li className="py-2 text-muted-foreground">这些题未关联概念。</li>
              ) : null}
            </ul>
          </section>

          <section className="mt-4 rounded-xl border border-border bg-card p-4">
            <h2 className="text-sm font-semibold text-foreground">按难度</h2>
            <ul className="mt-2 divide-y divide-border text-sm">
              {result.difficulties.map((item) => (
                <li key={item.label} className="flex items-center justify-between py-2.5">
                  <span className="text-foreground">{difficultyText(item.label) || item.label}</span>
                  <span className="text-muted-foreground tabular-nums">
                    对 {item.correct} / {item.total}
                  </span>
                </li>
              ))}
              {result.difficulties.length === 0 ? (
                <li className="py-2 text-muted-foreground">没有难度分布数据。</li>
              ) : null}
            </ul>
          </section>

          {result.weakConceptIds.length > 0 ? (
            <p className="mt-4 text-sm text-muted-foreground">
              有 {result.weakConceptIds.length} 个概念比较薄弱，建议去「错题本」或按分类「刷题」再练一轮。
            </p>
          ) : null}
        </>
      ) : null}

      {result && result.total === 0 ? (
        <Empty className="mt-6">
          <EmptyHeader>
            <EmptyTitle>这份试卷没有题目</EmptyTitle>
            <EmptyDescription>组卷时候选不足，换一份试卷或按分类重新组卷。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}
    </AppShell>
  )
}
