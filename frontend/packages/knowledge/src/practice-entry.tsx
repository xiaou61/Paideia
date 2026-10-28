import { QuizQuestionView, scoreQuiz, type Quiz } from '@paideia/ui'
import { useCallback, useEffect, useMemo, useState } from 'react'

/**
 * 练习册入口（WORK-048）。
 *
 * <p>正文里的 `quiz` 块由 `extractQuizzes` 摘出来，这里把它们收成一张入口卡：
 * **读完可以自己验一下，不测也能往下走**。原来内联渲染时，每道题都在截断一次阅读流。
 *
 * <p>这不新建任何数据：题目仍然长在 markdown 里，作答仍走 `onAttempt`，
 * 成绩仍写进条目的 `quizzes` 进度。"练习册"只是这些题的一次集合视图。
 */
export function PracticeEntry({
  quizzes,
  entryTitle,
  onAttempt,
  onScore,
}: {
  quizzes: Quiz[]
  entryTitle?: string
  /** 逐题上报：与内联小测同一个回调，服务端判定不变。 */
  onAttempt?: (input: { blockKey: string; questionKey: string; choice: number }) => void
  /** 一份题答完时上报总分，写进条目的 quizzes 进度。 */
  onScore?: (quizId: string, correct: number, total: number) => void
}) {
  const [open, setOpen] = useState(false)
  useScrollLock(open)
  const [answers, setAnswers] = useState<Record<string, (number | null)[]>>({})

  const total = useMemo(
    () => quizzes.reduce((sum, quiz) => sum + quiz.questions.length, 0),
    [quizzes],
  )
  const answered = useMemo(
    () => Object.values(answers).reduce((sum, list) => sum + list.filter((item) => item !== null).length, 0),
    [answers],
  )
  const correct = useMemo(
    () => quizzes.reduce((sum, quiz) => {
      const list = answers[quiz.id ?? ''] ?? []
      return sum + quiz.questions.reduce((inner, question, index) => inner + (list[index] === question.answer ? 1 : 0), 0)
    }, 0),
    [answers, quizzes],
  )

  const openPanel = useCallback(() => {
    // 每次打开都从头开始：不做"答到一半"的持久化，重做一遍只要几十秒
    setAnswers({})
    setOpen(true)
  }, [])

  const choose = (quiz: Quiz, questionIndex: number, optionIndex: number) => {
    const key = quiz.id ?? ''
    const current = answers[key] ?? quiz.questions.map(() => null)
    if (current[questionIndex] !== null) {
      return
    }
    const next = [...current]
    next[questionIndex] = optionIndex
    const nextAnswers = { ...answers, [key]: next }
    setAnswers(nextAnswers)
    onAttempt?.({ blockKey: key, questionKey: `${key}#${questionIndex}`, choice: optionIndex })
    if (next.every((item) => item !== null)) {
      const score = scoreQuiz(quiz, next)
      onScore?.(key, score.correct, score.total)
    }
  }

  if (total === 0) {
    return null
  }

  return (
    <>
      <section
        className="border-primary/25 bg-primary/[0.03] mt-8 rounded-xl border p-5"
        data-testid="practice-entry"
      >
        <div className="flex flex-wrap items-center gap-3">
          <span className="bg-primary/10 text-primary flex size-10 shrink-0 items-center justify-center rounded-lg">
            <span aria-hidden className="text-lg leading-none">✎</span>
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-base font-medium">测一测 · {total} 题</p>
            <p className="text-muted-foreground mt-0.5 text-sm">读完可以自己验一下，不测也可以往下走。</p>
          </div>
          <button
            type="button"
            onClick={openPanel}
            data-testid="practice-open"
            className="border-primary bg-primary text-primary-foreground hover:bg-primary/90 rounded-lg border px-4 py-2 text-sm transition-colors"
          >
            开始测一测
          </button>
        </div>
      </section>

      {open ? (
        <div
          className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 sm:items-center sm:p-6"
          role="dialog"
          aria-modal="true"
          aria-label={`测一测${entryTitle === undefined ? '' : `：${entryTitle}`}`}
          data-testid="practice-dialog"
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              setOpen(false)
            }
          }}
          tabIndex={-1}
        >
          <div className="bg-background border-border flex max-h-[92dvh] w-full max-w-2xl flex-col overflow-hidden rounded-t-2xl border sm:rounded-2xl">
            <header className="border-border flex items-center gap-3 border-b px-5 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">测一测</p>
                <p className="text-muted-foreground text-xs">
                  已答 {answered} / {total}
                  {answered === total ? (
                    // 与内联小测同一个 testid：两条路径的"出分"是同一件事，用例不该分叉
                    <span data-testid="quiz-score"> · 答对 {correct} / {total}</span>
                  ) : null}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setOpen(false)}
                data-testid="practice-close"
                aria-label="关闭"
                className="text-muted-foreground hover:text-foreground rounded-md p-1.5"
              >
                <span aria-hidden className="text-lg leading-none">×</span>
              </button>
            </header>

            <div className="min-h-0 flex-1 overflow-y-auto">
              {quizzes.map((quiz) => (
                <section key={quiz.id ?? 'quiz'} className="border-border border-b last:border-b-0">
                  {quiz.title === undefined ? null : (
                    <p className="text-muted-foreground px-4 pt-3 text-xs">{quiz.title}</p>
                  )}
                  <ol className="divide-border divide-y">
                    {quiz.questions.map((question, questionIndex) => (
                      <QuizQuestionView
                        key={questionIndex}
                        question={question}
                        index={questionIndex}
                        choice={(answers[quiz.id ?? ''] ?? [])[questionIndex] ?? null}
                        onChoose={(optionIndex) => choose(quiz, questionIndex, optionIndex)}
                      />
                    ))}
                  </ol>
                </section>
              ))}
            </div>

            <footer className="border-border flex items-center gap-3 border-t px-5 py-3">
              <button
                type="button"
                onClick={() => setAnswers({})}
                data-testid="practice-reset"
                className="text-muted-foreground hover:text-foreground text-sm underline underline-offset-2"
              >
                重做
              </button>
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="border-border hover:bg-muted ml-auto rounded-lg border px-4 py-2 text-sm transition-colors"
              >
                关闭
              </button>
            </footer>
          </div>
        </div>
      ) : null}
    </>
  )
}

/** 面板打开时锁住页面滚动；关闭或卸载立刻还原。 */
function useScrollLock(active: boolean): void {
  useEffect(() => {
    if (!active) {
      return
    }
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [active])
}
