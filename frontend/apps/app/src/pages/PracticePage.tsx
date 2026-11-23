import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, difficultyText, type PracticeQuestion } from '@paideia/knowledge'
import { Button } from '@paideia/ui'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

/**
 * 题目的全局身份：`questionKey` 形如 `quiz1#0`，只在条目内唯一（后端唯一约束是
 * entry_id + block_key + question_key），跨条目同名块必须靠三者组合区分。
 */
function questionIdentity(question: PracticeQuestion): string {
  return `${question.entryId}/${question.blockKey}/${question.questionKey}`
}

/** 刷题页（WORK-083 TASK-006）：按标签筛题、逐题作答即时反馈，不生成试卷。 */
export function PracticePage() {
  const exam = useMemo(() => createExamApi(api), [])
  const [onlyWrong, setOnlyWrong] = useState(false)
  const [onlyUndone, setOnlyUndone] = useState(false)
  const [difficulty, setDifficulty] = useState('')
  const [questions, setQuestions] = useState<PracticeQuestion[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [index, setIndex] = useState(0)
  const [picked, setPicked] = useState<Record<string, number>>({})
  const [verdict, setVerdict] = useState<Record<string, boolean>>({})
  // 请求进行中标记：作答状态要等响应回来才写入，期间连点会重复提交（后端无幂等键）
  const [submitting, setSubmitting] = useState(false)
  // 连续切换筛选时先发后到的旧响应会覆盖新结果，用递增序号只认最新一次请求
  const requestSeq = useRef(0)

  const load = useCallback(async () => {
    const seq = requestSeq.current + 1
    requestSeq.current = seq
    setLoading(true)
    setError(null)
    try {
      const list = await exam.practice({
        onlyWrong,
        onlyUndone,
        difficultyLabel: difficulty || undefined,
        limit: 20,
      })
      if (seq !== requestSeq.current) return
      setQuestions(list)
      setIndex(0)
      setPicked({})
      setVerdict({})
    } catch (cause) {
      if (seq !== requestSeq.current) return
      setError(errorMessage(cause))
    } finally {
      if (seq === requestSeq.current) setLoading(false)
    }
  }, [exam, onlyWrong, onlyUndone, difficulty])

  useEffect(() => {
    void load()
  }, [load])

  const current = questions[index]
  const currentIdentity = current ? questionIdentity(current) : ''
  const answered = current ? picked[currentIdentity] !== undefined : false

  async function choose(optionIndex: number) {
    if (!current || answered || submitting) return
    setSubmitting(true)
    try {
      const result = await exam.attempt({
        entryId: current.entryId,
        blockKey: current.blockKey,
        questionKey: current.questionKey,
        choice: optionIndex,
      })
      setPicked((prev) => ({ ...prev, [currentIdentity]: optionIndex }))
      setVerdict((prev) => ({ ...prev, [currentIdentity]: result.correct }))
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="min-h-dvh bg-background">
      <LearnerHeader />
      <main className="mx-auto w-full max-w-3xl px-4 py-6">
        <h1 className="text-xl font-semibold text-foreground" data-testid="practice-title">刷题</h1>
        <p className="mt-1 text-sm text-muted-foreground">按标签筛题，逐题作答即时反馈；不交卷、不生成试卷。</p>

        <div className="mt-4 flex flex-wrap items-center gap-4 rounded-lg border border-border bg-card p-3 text-sm">
          <label className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={onlyUndone}
              onChange={(e) => {
                // 后端两个条件取交集：未做过与做错过不可能同时成立，同时勾上永远是空列表
                setOnlyUndone(e.target.checked)
                if (e.target.checked) setOnlyWrong(false)
              }}
            />
            只刷未做
          </label>
          <label className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={onlyWrong}
              onChange={(e) => {
                setOnlyWrong(e.target.checked)
                if (e.target.checked) setOnlyUndone(false)
              }}
            />
            只刷错题
          </label>
          <label className="flex items-center gap-1">
            难度
            <select
              className="rounded border border-border bg-background px-2 py-1"
              value={difficulty}
              onChange={(e) => setDifficulty(e.target.value)}
            >
              <option value="">全部</option>
              <option value="easy">易</option>
              <option value="medium">中</option>
              <option value="hard">难</option>
            </select>
          </label>
        </div>

        {error ? <p className="mt-4 text-sm text-destructive">{error}</p> : null}
        {loading ? <p className="mt-6 text-sm text-muted-foreground">加载中…</p> : null}

        {!loading && !error && questions.length === 0 ? (
          <p className="mt-6 text-sm text-muted-foreground" data-testid="practice-empty">没有符合条件的题目，换个筛选试试。</p>
        ) : null}

        {current ? (
          <section className="mt-6 rounded-lg border border-border bg-card p-4" data-testid="practice-question">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span>
                第 {index + 1} / {questions.length} 题
              </span>
              {difficultyText(current.difficultyLabel) ? (
                <span className="rounded bg-muted px-2 py-0.5">{difficultyText(current.difficultyLabel)}</span>
              ) : null}
            </div>
            <p className="mt-3 text-base font-medium text-foreground">{current.stem}</p>
            <ul className="mt-3 space-y-2">
              {current.options.map((option, optionIndex) => {
                const chosen = picked[currentIdentity] === optionIndex
                return (
                  <li key={optionIndex}>
                    <button
                      type="button"
                      disabled={answered || submitting}
                      onClick={() => void choose(optionIndex)}
                      className={
                        'w-full rounded border px-3 py-2 text-left text-sm transition ' +
                        (chosen
                          ? 'border-primary bg-primary/10 text-foreground'
                          : 'border-border bg-background hover:bg-muted')
                      }
                    >
                      {String.fromCharCode(65 + optionIndex)}. {option}
                    </button>
                  </li>
                )
              })}
            </ul>
            {answered ? (
              <p className={'mt-3 text-sm font-medium ' + (verdict[currentIdentity] ? 'text-green-600' : 'text-destructive')} data-testid="practice-verdict">
                {verdict[currentIdentity] ? '答对了' : '答错了'}
              </p>
            ) : null}
            <div className="mt-4 flex justify-end">
              <Button
                size="sm"
                disabled={index >= questions.length - 1}
                onClick={() => setIndex((value) => Math.min(value + 1, questions.length - 1))}
              >
                下一题
              </Button>
            </div>
          </section>
        ) : null}
      </main>
    </div>
  )
}
