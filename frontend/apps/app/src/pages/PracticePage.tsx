import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { errorMessage } from '@paideia/core'
import {
  createExamApi,
  createKnowledgeApi,
  difficultyText,
  type CategoryNode,
  type PracticeQuestion,
} from '@paideia/knowledge'
import {
  AppShell,
  Button,
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import {
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Layers,
  ListChecks,
  XCircle,
} from 'lucide-react'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

/**
 * 题目的全局身份：`questionKey` 形如 `quiz1#0`，只在条目内唯一（后端唯一约束是
 * entry_id + block_key + question_key），跨条目同名块必须靠三者组合区分。
 */
function questionIdentity(question: PracticeQuestion): string {
  return `${question.entryId}/${question.blockKey}/${question.questionKey}`
}

interface FlatCategory {
  node: CategoryNode
  depth: number
}

/** 把分类树压平成带缩进层级的一维列表，供左侧目录与窄屏标签共用。 */
function flattenCategories(nodes: CategoryNode[], depth = 0, out: FlatCategory[] = []): FlatCategory[] {
  for (const node of nodes) {
    out.push({ node, depth })
    if (node.children.length > 0) flattenCategories(node.children, depth + 1, out)
  }
  return out
}

/**
 * 刷题页（WORK-083 TASK-006，2026-11-24 重做分类与作答体验）：
 * 左侧按**知识分类**筛题（分类来自 /api/v1/knowledge/categories，
 * 命中 question_index.category_id），右侧逐题作答、即时判对错、带进度与成绩小结；不生成试卷。
 */
export function PracticePage() {
  const exam = useMemo(() => createExamApi(api), [])
  const [categoryId, setCategoryId] = useState<number | null>(null)
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

  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
    staleTime: 5 * 60 * 1000,
  })
  const flat = useMemo(() => flattenCategories(categories.data ?? []), [categories.data])

  const load = useCallback(async () => {
    const seq = requestSeq.current + 1
    requestSeq.current = seq
    setLoading(true)
    setError(null)
    try {
      const list = await exam.practice({
        categoryId: categoryId ?? undefined,
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
  }, [exam, categoryId, onlyWrong, onlyUndone, difficulty])

  useEffect(() => {
    void load()
  }, [load])

  const current = questions[index]
  const currentIdentity = current ? questionIdentity(current) : ''
  const answered = current ? picked[currentIdentity] !== undefined : false

  const answeredCount = Object.keys(verdict).length
  const correctCount = Object.values(verdict).filter(Boolean).length
  const progress = questions.length === 0 ? 0 : Math.round((answeredCount / questions.length) * 100)

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

  const goPrev = useCallback(() => {
    setIndex((value) => Math.max(0, value - 1))
  }, [])
  const goNext = useCallback(() => {
    setIndex((value) => Math.min(value + 1, Math.max(0, questions.length - 1)))
  }, [questions.length])

  // 键盘作答：A/B/C 选选项，←/→ 翻题。输入框聚焦时不接管，避免和打字冲突。
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      if (event.key === 'ArrowLeft') {
        goPrev()
        return
      }
      if (event.key === 'ArrowRight') {
        goNext()
        return
      }
      if (!current || answered) return
      const key = event.key.toLowerCase()
      const optionIndex = key === 'a' ? 0 : key === 'b' ? 1 : key === 'c' ? 2 : -1
      if (optionIndex >= 0 && optionIndex < current.options.length) {
        event.preventDefault()
        void choose(optionIndex)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // choose 依赖 current/answered，随它们一起重绑
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, answered, goPrev, goNext, submitting])

  const activeCategoryName =
    categoryId === null ? '全部分类' : (flat.find((item) => item.node.id === categoryId)?.node.name ?? '全部分类')

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <div className="mb-8">
        <h1 className="text-3xl" data-testid="practice-title">
          刷题
        </h1>
        <p className="text-muted-foreground mt-3 max-w-2xl text-base leading-7">
          按知识分类筛题、逐题作答即时反馈；不交卷、不生成试卷。答完一轮会给出本轮成绩。
        </p>
      </div>

      <div className="grid gap-6 md:grid-cols-[13.5rem_minmax(0,1fr)]">
        {/* ── 左栏：知识分类目录（桌面常驻） ─────────────────────────────── */}
        <aside className="hidden md:block">
          <div className="md:sticky md:top-20 md:max-h-[calc(100vh-6rem)] md:overflow-y-auto md:pr-1">
            <p className="text-muted-foreground mb-2 px-2 text-xs font-medium">按分类刷题</p>
            <ul className="space-y-0.5" data-testid="practice-category-rail">
              <li>
                <button
                  type="button"
                  onClick={() => setCategoryId(null)}
                  data-testid="practice-category-all"
                  className={
                    'flex w-full items-center gap-1.5 rounded-lg px-2.5 py-2 text-left text-sm transition-colors ' +
                    (categoryId === null
                      ? 'bg-primary/10 text-primary font-medium'
                      : 'text-foreground hover:bg-accent/50')
                  }
                >
                  <Layers className="size-3.5 shrink-0" />
                  全部分类
                </button>
              </li>
              {flat.map(({ node, depth }) => (
                <li key={node.id}>
                  <button
                    type="button"
                    onClick={() => setCategoryId(node.id)}
                    data-testid={`practice-category-${node.id}`}
                    title={node.name}
                    style={{ paddingLeft: `${0.625 + depth * 0.9}rem` }}
                    className={
                      'flex w-full items-center rounded-lg py-1.5 pr-2.5 text-left text-sm transition-colors ' +
                      (categoryId === node.id
                        ? 'bg-primary/10 text-primary font-medium'
                        : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground')
                    }
                  >
                    <span className="min-w-0 truncate">{node.name}</span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </aside>

        {/* ── 右栏：筛选 + 作答 ─────────────────────────────────────────── */}
        <div className="min-w-0">
          {/* 窄屏分类标签（横向滑动，与左栏同一份数据） */}
          <div className="-mx-1 mb-4 flex gap-1.5 overflow-x-auto px-1 pb-1 md:hidden" data-testid="practice-category-chips">
            <button
              type="button"
              onClick={() => setCategoryId(null)}
              className={
                'inline-flex min-h-9 shrink-0 items-center rounded-full border px-3 text-xs ' +
                (categoryId === null
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border text-muted-foreground')
              }
            >
              全部
            </button>
            {flat.map(({ node }) => (
              <button
                key={node.id}
                type="button"
                onClick={() => setCategoryId(node.id)}
                className={
                  'inline-flex min-h-9 shrink-0 items-center rounded-full border px-3 text-xs ' +
                  (categoryId === node.id
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border text-muted-foreground')
                }
              >
                {node.name}
              </button>
            ))}
          </div>

          <div className="mb-4 flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground rounded-full border border-border px-2.5 py-1 text-xs">
              分类：{activeCategoryName}
            </span>
            <label className="flex cursor-pointer items-center gap-1.5 rounded-full border border-border px-2.5 py-1 text-xs">
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
            <label className="flex cursor-pointer items-center gap-1.5 rounded-full border border-border px-2.5 py-1 text-xs">
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
            <label className="flex items-center gap-1.5 rounded-full border border-border px-2.5 py-1 text-xs">
              难度
              <select
                className="rounded bg-background px-1 py-0.5"
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

          {loading ? (
            <div className="mt-6 rounded-xl border border-border bg-card p-6">
              <div className="bg-muted h-4 w-2/3 animate-pulse rounded" />
              <div className="bg-muted mt-4 h-10 w-full animate-pulse rounded" />
              <div className="bg-muted mt-2 h-10 w-full animate-pulse rounded" />
              <div className="bg-muted mt-2 h-10 w-5/6 animate-pulse rounded" />
            </div>
          ) : null}

          {!loading && !error && questions.length === 0 ? (
            <Empty data-testid="practice-empty" className="mt-6">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <ListChecks />
                </EmptyMedia>
                <EmptyTitle>这个范围下还没有题目</EmptyTitle>
                <EmptyDescription>换个分类，或去掉「只刷未做 / 只刷错题 / 难度」的筛选试试。</EmptyDescription>
              </EmptyHeader>
              <EmptyContent>
                <Button
                  variant="outline"
                  onClick={() => {
                    setCategoryId(null)
                    setOnlyWrong(false)
                    setOnlyUndone(false)
                    setDifficulty('')
                  }}
                >
                  清除全部筛选
                </Button>
              </EmptyContent>
            </Empty>
          ) : null}

          {current ? (
            <section className="mt-2 rounded-xl border border-border bg-card p-5" data-testid="practice-question">
              {/* 进度 */}
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span className="tabular-nums">
                  第 {index + 1} / {questions.length} 题
                </span>
                <span className="tabular-nums">
                  已答 {answeredCount} · 答对 {correctCount}
                </span>
              </div>
              <div className="bg-muted mt-2 h-1.5 w-full overflow-hidden rounded-full">
                <div
                  className="bg-primary h-full rounded-full transition-all"
                  style={{ width: `${progress}%` }}
                  data-testid="practice-progress"
                />
              </div>

              <div className="mt-4 flex items-start justify-between gap-3">
                <p className="text-base font-medium text-foreground">{current.stem}</p>
                {difficultyText(current.difficultyLabel) ? (
                  <span className="mt-0.5 shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                    {difficultyText(current.difficultyLabel)}
                  </span>
                ) : null}
              </div>

              <ul className="mt-4 space-y-2">
                {current.options.map((option, optionIndex) => {
                  const chosen = picked[currentIdentity] === optionIndex
                  const isCorrectPick = answered && chosen && verdict[currentIdentity]
                  const isWrongPick = answered && chosen && !verdict[currentIdentity]
                  const base =
                    'flex w-full items-start gap-2 rounded-lg border px-3 py-2.5 text-left text-sm transition '
                  const state = !answered
                    ? 'border-border bg-background hover:border-primary/50 hover:bg-accent/40'
                    : isCorrectPick
                      ? 'border-green-500 bg-green-500/10 text-foreground'
                      : isWrongPick
                        ? 'border-destructive bg-destructive/10 text-foreground'
                        : 'border-border bg-background opacity-70'
                  return (
                    <li key={optionIndex}>
                      <button
                        type="button"
                        disabled={answered || submitting}
                        onClick={() => void choose(optionIndex)}
                        className={base + state}
                      >
                        <span className="flex size-5 shrink-0 items-center justify-center rounded-full border border-current text-[11px] font-medium">
                          {String.fromCharCode(65 + optionIndex)}
                        </span>
                        <span className="min-w-0 flex-1">{option}</span>
                        {isCorrectPick ? <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-green-600" /> : null}
                        {isWrongPick ? <XCircle className="mt-0.5 size-4 shrink-0 text-destructive" /> : null}
                      </button>
                    </li>
                  )
                })}
              </ul>

              {answered ? (
                <div
                  className={
                    'mt-4 flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium ' +
                    (verdict[currentIdentity]
                      ? 'bg-green-500/10 text-green-700'
                      : 'bg-destructive/10 text-destructive')
                  }
                  data-testid="practice-verdict"
                >
                  {verdict[currentIdentity] ? (
                    <>
                      <CheckCircle2 className="size-4" /> 答对了
                    </>
                  ) : (
                    <>
                      <XCircle className="size-4" /> 答错了
                    </>
                  )}
                </div>
              ) : null}

              <div className="mt-5 flex items-center justify-between">
                <Button variant="outline" size="sm" disabled={index === 0} onClick={goPrev} data-testid="practice-prev">
                  <ChevronLeft className="size-4" /> 上一题
                </Button>
                <span className="text-muted-foreground hidden text-xs sm:inline">键盘 A/B/C 作答，← → 翻题</span>
                <Button
                  size="sm"
                  disabled={index >= questions.length - 1}
                  onClick={goNext}
                  data-testid="practice-next"
                >
                  下一题 <ChevronRight className="size-4" />
                </Button>
              </div>
            </section>
          ) : null}
        </div>
      </div>
    </AppShell>
  )
}
