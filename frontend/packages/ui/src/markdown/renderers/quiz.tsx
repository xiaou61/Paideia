import { useMemo, useState, type ReactNode } from 'react'
import type { CustomRendererProps } from 'streamdown'

import { BlockFallback, BlockFrame, Pill } from './blocks'
import { useSourceReading } from './context'
import { parseQuiz, scoreQuiz, type QuizQuestion } from './schema'

/**
 * 小测（WORK-014）：逐题即时反馈 + 块末得分。
 *
 * <p>成绩只写本地进度，不上传。对错**不只靠颜色**：文字里带 ✓ / ✗，读屏与色觉障碍都能判断。
 *
 * <p><b>从 WORK-048 起，学习者阅读页不再内联渲染它</b>：题目被 `extractQuizzes` 摘出来，
 * 由正文末尾的「测一测」入口打开面板集中作答。这个内联版本保留给**管理端预览**——
 * 作者要能看见自己写的题。
 *
 * <p>单题渲染抽成了 {@link QuizQuestionView}，内联版与面板版共用同一份，避免两套 UI 各自演进。
 */
export function Quiz({ code }: CustomRendererProps) {
  const parsed = useMemo(() => parseQuiz(code), [code])
  const { progress, renderText, recordAttempt } = useSourceReading()
  const [answers, setAnswers] = useState<(number | null)[]>([])

  if (!parsed.ok) {
    return <BlockFallback title="小测" error={parsed.error} raw={code} />
  }
  const quiz = parsed.value
  const answered = answers.filter((answer) => answer !== null).length
  const result = scoreQuiz(quiz, answers)
  const complete = answered === quiz.questions.length

  const choose = (questionIndex: number, optionIndex: number) => {
    // 已经选过就不重复上报（与原先 updater 里的守卫等价）
    if (answers[questionIndex] !== null && answers[questionIndex] !== undefined) {
      return
    }
    // 在 updater **外面**算好再提交：updater 必须是纯函数（StrictMode 会调两次），
    // 而副作用也不能依赖 updater 里的赋值——React 可能延后执行它。
    const next = [...answers]
    next[questionIndex] = optionIndex
    setAnswers(next)
    if (quiz.id === undefined) {
      return
    }
    recordAttempt?.({
      blockKey: quiz.id,
      questionKey: `${quiz.id}#${questionIndex}`,
      choice: optionIndex,
    })
    if (next.filter((a) => a !== null && a !== undefined).length === quiz.questions.length) {
      const final = scoreQuiz(quiz, next)
      progress?.update({ quizzes: { [quiz.id]: { score: final.correct, total: final.total } } })
    }
  }

  const reset = () => setAnswers([])

  return (
    <BlockFrame
      title={quiz.title ?? '小测'}
      testId="quiz"
      actions={
        <Pill tone={complete ? 'success' : 'muted'}>
          {complete ? `${result.correct} / ${result.total} 答对` : `已答 ${answered} / ${quiz.questions.length}`}
        </Pill>
      }
    >
      <ol className="divide-border divide-y">
        {quiz.questions.map((question, questionIndex) => (
          <QuizQuestionView
            key={questionIndex}
            question={question}
            index={questionIndex}
            choice={answers[questionIndex] ?? null}
            onChoose={(optionIndex) => choose(questionIndex, optionIndex)}
            renderText={renderText}
          />
        ))}
      </ol>
      {complete ? (
        <div className="border-border flex items-center gap-3 border-t px-4 py-2.5 text-sm">
          <span data-testid="quiz-score">
            答对 {result.correct} / {result.total}
          </span>
          <button type="button" onClick={reset} className="text-muted-foreground ml-auto inline-flex min-h-11 items-center text-xs underline underline-offset-2">
            重做
          </button>
        </div>
      ) : null}
    </BlockFrame>
  )
}

/**
 * 单题的渲染与即时反馈。内联 `Quiz` 与练习册面板共用。
 *
 * <p>testid 保持 `quiz-option-*` / `quiz-explain-*`：既有 e2e 按它们定位，
 * 抽组件不能把这些观察点改掉。
 */
export function QuizQuestionView({
  question,
  index,
  choice,
  onChoose,
  renderText,
}: {
  question: QuizQuestion
  index: number
  choice: number | null
  onChoose: (optionIndex: number) => void
  renderText?: (markdown: string) => ReactNode
}) {
  const locked = choice !== null
  return (
    <li className="p-4">
      <p className="mb-2 text-sm font-medium">
        {index + 1}. {question.question}
      </p>
      <div className="grid gap-1.5">
        {question.options.map((option, optionIndex) => {
          const chosen = choice === optionIndex
          const isAnswer = question.answer === optionIndex
          return (
            <button
              key={optionIndex}
              type="button"
              data-testid={`quiz-option-${index}-${optionIndex}`}
              disabled={locked}
              onClick={() => onChoose(optionIndex)}
              className={`min-h-11 rounded-md border px-3 py-2 text-left text-sm transition-colors ${
                locked && isAnswer
                  ? 'border-success bg-success/10 text-success'
                  : locked && chosen
                    ? 'border-destructive bg-destructive/10 text-destructive'
                    : 'border-border bg-background hover:bg-muted'
              }`}
            >
              {locked && isAnswer ? '✓ ' : ''}
              {locked && chosen && !isAnswer ? '✗ ' : ''}
              {option}
            </button>
          )
        })}
      </div>
      {locked ? (
        <div className="text-muted-foreground mt-2 text-xs leading-6" data-testid={`quiz-explain-${index}`}>
          {choice === question.answer ? '答对了。' : '这题不对。'}
          {question.explain === undefined ? (
            // 解析缺失是内容缺口，不是渲染问题：说清楚，别留一片空白让人以为是加载失败
            <span className="ml-1">这道题还没有解析。</span>
          ) : (
            <span className="ml-1">
              {renderText === undefined ? question.explain : renderText(question.explain)}
            </span>
          )}
        </div>
      ) : null}
    </li>
  )
}
