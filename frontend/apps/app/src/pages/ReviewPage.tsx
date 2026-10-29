import { createLearningApi, type ReviewSession, type ReviewSubmitResult } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useMutation, useQuery } from '@tanstack/react-query'
import { CalendarClock, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const learning = createLearningApi(api)

/**
 * 复习（WORK-034）。
 *
 * <p>S1 是到期列表；S2 加会话态：一次一题、服务端判分、交卷后看解析与下次到期日。
 * 出题失败时显示承载条目（`fallback`），不把用户卡在空白页。
 */
export function ReviewPage() {
  const reviews = useQuery({ queryKey: ['learning-reviews'], queryFn: () => learning.reviews() })
  const [session, setSession] = useState<ReviewSession | null>(null)
  const [index, setIndex] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)
  const [answers, setAnswers] = useState<{ itemId: number; choice: number }[]>([])
  const [result, setResult] = useState<ReviewSubmitResult | null>(null)

  const start = useMutation({
    mutationFn: () => learning.startReview(),
    onSuccess: (data) => {
      setSession(data)
      setIndex(0)
      setSelected(null)
      setAnswers([])
      setResult(null)
    },
  })
  const submit = useMutation({
    // 取显式传入的答案：交卷那一题刚 setAnswers 还没生效，闭包里的 answers 少一题
    mutationFn: (payload: { itemId: number; choice: number }[]) =>
      learning.submitReview(session?.sessionId ?? 0, payload),
    onSuccess: (data) => setResult(data),
  })

  const items = reviews.data?.items ?? []
  const reset = () => {
    setSession(null)
    setResult(null)
    setAnswers([])
    setSelected(null)
    setIndex(0)
    void reviews.refetch()
  }

  const current = session?.items[index]

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <PageHeader
        title="今天该回头看的"
        description="按到期日排好。答错顺延到明天、档位不变；全对才前进一档。"
      />

      {reviews.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="review-state">
          <Spinner />
          加载中…
        </p>
      ) : null}

      {reviews.isError ? (
        <Alert variant="destructive" className="mt-6" data-testid="review-state">
          <AlertTitle>复习列表加载失败</AlertTitle>
          <AlertDescription>稍后重试一下，这不影响其它功能。</AlertDescription>
        </Alert>
      ) : null}

      {result !== null ? (
        <section className="mt-6 space-y-3" data-testid="review-result">
          <h2 className="text-lg font-semibold">这次的结果</h2>
          {result.results.map((item, i) => (
            <Card key={item.itemId} className={item.correct ? 'border-success/40' : 'border-destructive/40'}>
              <CardContent className="pt-5 text-sm">
                <p className="font-medium">
                  第 {i + 1} 题 · {item.correct ? '答对了' : '答错了'}
                </p>
                <p className="text-muted-foreground mt-1 leading-6">{item.explanation}</p>
              </CardContent>
            </Card>
          ))}
          {result.schedule[0] === undefined ? null : (
            <p className="text-muted-foreground text-sm">
              下一次复习：
              {new Date(result.schedule[0].reviewDueAt).toLocaleDateString('zh-CN')}（第{' '}
              {result.schedule[0].stage} 档）
            </p>
          )}
          <Button variant="outline" onClick={reset} data-testid="review-result-back">
            回到复习列表
          </Button>
        </section>
      ) : session !== null && session.items.length > 0 && current !== undefined ? (
        <section className="mt-6" data-testid="review-session">
          <p className="text-muted-foreground mb-3 text-sm">
            第 {index + 1} / {session.items.length} 题
          </p>
          <Card>
            <CardContent className="pt-5">
              <p className="mb-4 font-medium">{current.stem}</p>
              <div className="grid gap-2">
                {current.options.map((option, optionIndex) => (
                  <button
                    key={optionIndex}
                    type="button"
                    data-testid={`review-option-${optionIndex}`}
                    onClick={() => setSelected(optionIndex)}
                    className={`min-h-11 rounded-md border px-3 py-2 text-left text-sm transition-colors ${
                      selected === optionIndex
                        ? 'border-primary bg-primary/10'
                        : 'border-border hover:bg-muted'
                    }`}
                  >
                    {option}
                  </button>
                ))}
              </div>
              <div className="mt-4 flex justify-end">
                <Button
                  disabled={selected === null || submit.isPending}
                  data-testid="review-next"
                  onClick={() => {
                    if (selected === null) {
                      return
                    }
                    const next = [...answers, { itemId: current.itemId, choice: selected }]
                    setAnswers(next)
                    setSelected(null)
                    if (index + 1 < session.items.length) {
                      setIndex(index + 1)
                    } else {
                      submit.mutate(next)
                    }
                  }}
                >
                  {index + 1 < session.items.length ? '下一题' : '交卷'}
                </Button>
              </div>
              {/* 交卷失败必须看得见：否则用户停在最后一题、按钮又恢复可点，只会反复重试 */}
              {submit.isError ? (
                <Alert variant="destructive" className="mt-4" data-testid="review-submit-error">
                  <AlertTitle>没能交卷</AlertTitle>
                  <AlertDescription>{(submit.error as Error).message}</AlertDescription>
                </Alert>
              ) : null}
            </CardContent>
          </Card>
        </section>
      ) : session !== null ? (
        <Alert className="mt-6" data-testid="review-fallback">
          <AlertTitle>这次没能出题</AlertTitle>
          <AlertDescription>
            先回到原来的条目看一遍：
            <span className="mt-2 flex flex-wrap gap-2">
              {session.fallback.map((item) => (
                <Link
                  key={item.entryId}
                  to={`/knowledge/entries/${item.entryId}`}
                  className="border-border hover:border-primary/50 inline-flex min-h-11 items-center rounded-md border px-3 text-sm"
                >
                  {item.title}
                </Link>
              ))}
            </span>
          </AlertDescription>
          <div className="mt-3">
            <Button variant="outline" onClick={reset}>
              返回
            </Button>
          </div>
        </Alert>
      ) : items.length === 0 ? (
        <Empty className="mt-6 border" data-testid="review-empty">
          <EmptyHeader>
            <EmptyTitle>今天没有到期的复习</EmptyTitle>
            <EmptyDescription>学完一关并通过小测后，这里会按 1 / 3 / 7 / 21 天提醒你。</EmptyDescription>
          </EmptyHeader>
          <div className="mt-4 flex flex-wrap gap-2">
            <Link
              to="/learn"
              className="border-border hover:border-primary/50 inline-flex min-h-11 items-center rounded-md border px-4 text-sm"
            >
              去从零开始
            </Link>
            <Link
              to="/knowledge"
              className="border-border hover:border-primary/50 inline-flex min-h-11 items-center rounded-md border px-4 text-sm"
            >
              去知识库
            </Link>
          </div>
        </Empty>
      ) : (
        <section className="mt-6" data-testid="review-due">
          {/* 竖排居中：按钮与「共 N 个到期」同处一行时，按钮会偏左半个计数文字 */}
          <div className="mb-4 flex flex-col items-center gap-2">
            <Button onClick={() => start.mutate()} disabled={start.isPending} data-testid="review-start">
              开始复习（最多 5 题）
            </Button>
            <span className="text-muted-foreground text-sm">共 {items.length} 个到期</span>
          </div>
          {start.isError ? (
            <Alert variant="destructive" className="mb-4">
              <AlertTitle>没能开始</AlertTitle>
              <AlertDescription>{(start.error as Error).message}</AlertDescription>
            </Alert>
          ) : null}
          <ul className="grid gap-3" data-testid="review-list">
            {items.map((item) => (
              <li key={item.entryId}>
                <Link to={`/knowledge/entries/${item.entryId}`} data-testid="review-item">
                  <Card className="hover:border-primary/40 transition-colors">
                    <CardContent className="flex flex-wrap items-center gap-3 pt-5">
                      <RotateCcw className="text-primary size-4 shrink-0" />
                      <span className="min-w-0 flex-1 font-medium">{item.title}</span>
                      {item.conceptName === null ? null : (
                        <Badge variant="secondary">{item.conceptName}</Badge>
                      )}
                      <span className="text-muted-foreground flex items-center gap-1.5 text-xs">
                        <CalendarClock className="size-3.5" />第 {item.stage} 档 ·{' '}
                        {new Date(item.reviewDueAt).toLocaleDateString('zh-CN')}
                      </span>
                    </CardContent>
                  </Card>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </AppShell>
  )
}