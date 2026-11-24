import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import {
  createExamApi,
  createKnowledgeApi,
  difficultyText,
  type CategoryNode,
  type WrongQuestion,
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
  PageHeader,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { NotebookPen } from 'lucide-react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

interface FlatCategory {
  node: CategoryNode
  depth: number
}

function flattenCategories(nodes: CategoryNode[], depth = 0, out: FlatCategory[] = []): FlatCategory[] {
  for (const node of nodes) {
    out.push({ node, depth })
    if (node.children.length > 0) flattenCategories(node.children, depth + 1, out)
  }
  return out
}

/** 错题本（WORK-083 TASK-007）：每道身份最近一次作答为错的题；可按分类收窄。 */
export function WrongBookPage() {
  const exam = useMemo(() => createExamApi(api), [])
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [questions, setQuestions] = useState<WrongQuestion[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
    staleTime: 5 * 60 * 1000,
  })
  const flat = useMemo(() => flattenCategories(categories.data ?? []), [categories.data])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    void (async () => {
      try {
        const list = await exam.wrongQuestions({ limit: 100, categoryId: categoryId ?? undefined })
        if (!cancelled) setQuestions(list)
      } catch (cause) {
        if (!cancelled) setError(errorMessage(cause))
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [exam, categoryId])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="错题本"
        description="每道题最近一次作答为错的会出现在这里；按分类收窄，去「刷题」重做。"
        actions={
          <Button variant="outline" size="sm" render={<Link to="/practice" />}>
            <NotebookPen className="size-4" /> 去刷题
          </Button>
        }
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <span className="text-sm text-muted-foreground">分类</span>
        <select
          className="rounded-lg border border-border bg-background px-2.5 py-1.5 text-sm"
          value={categoryId ?? ''}
          onChange={(e) => setCategoryId(e.target.value === '' ? null : Number(e.target.value))}
          data-testid="wrong-category"
        >
          <option value="">全部分类</option>
          {flat.map(({ node, depth }) => (
            <option key={node.id} value={node.id}>
              {'　'.repeat(depth)}
              {node.name}
            </option>
          ))}
        </select>
        {!loading && questions.length > 0 ? (
          <span className="text-muted-foreground ml-auto text-xs tabular-nums">共 {questions.length} 道错题</span>
        ) : null}
      </div>

      {error ? <p className="mb-4 text-sm text-destructive">{error}</p> : null}
      {loading ? <p className="mt-6 text-sm text-muted-foreground">加载中…</p> : null}

      {!loading && questions.length === 0 && !error ? (
        <Empty data-testid="wrong-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <NotebookPen />
            </EmptyMedia>
            <EmptyTitle>还没有错题</EmptyTitle>
            <EmptyDescription>继续保持。去刷题练一轮，答错的题会自动收进这里。</EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <Button variant="outline" render={<Link to="/practice" />}>
              去刷题
            </Button>
          </EmptyContent>
        </Empty>
      ) : null}

      <ul className="grid gap-3 sm:grid-cols-2" data-testid="wrong-list">
        {questions.map((question) => (
          // questionKey 只在条目内唯一（如 quiz1#0），错题本会跨条目聚合，必须用复合 key
          <li
            key={`${question.entryId}/${question.blockKey}/${question.questionKey}`}
            className="rounded-xl border border-border bg-card p-4"
          >
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span>条目 #{question.entryId}</span>
              {difficultyText(question.difficultyLabel) ? (
                <span className="rounded-full bg-muted px-2 py-0.5">{difficultyText(question.difficultyLabel)}</span>
              ) : null}
            </div>
            <p className="mt-2 text-base font-medium text-foreground">{question.stem}</p>
            {question.conceptNames.length > 0 ? (
              <p className="mt-2 flex flex-wrap gap-1">
                {question.conceptNames.map((name) => (
                  <span key={name} className="rounded-full bg-primary/10 px-2 py-0.5 text-xs text-primary">
                    {name}
                  </span>
                ))}
              </p>
            ) : null}
            <div className="mt-3 border-t border-border pt-3">
              <Link to="/practice" className="text-primary text-xs hover:underline">
                去刷题重做 →
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </AppShell>
  )
}
