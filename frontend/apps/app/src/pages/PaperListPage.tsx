import { useCallback, useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import { createExamApi, type PaperSummary } from '@paideia/knowledge'
import { Button } from '@paideia/ui'
import { Link, useNavigate } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

/** 试卷列表 + 组卷（WORK-083 TASK-007）。 */
export function PaperListPage() {
  const exam = useMemo(() => createExamApi(api), [])
  const navigate = useNavigate()
  const [papers, setPapers] = useState<PaperSummary[]>([])
  const [title, setTitle] = useState('我的试卷')
  const [count, setCount] = useState(10)
  const [difficulty, setDifficulty] = useState('medium')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setPapers(await exam.papers())
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }, [exam])

  useEffect(() => {
    void load()
  }, [load])

  async function create() {
    setLoading(true)
    setError(null)
    try {
      const paper = await exam.createPaper({
        title: title.trim() || '我的试卷',
        count,
        difficultyDistribution: { [difficulty]: count },
      })
      // 组卷配额不足时后端会少放题；shortfall 只在创建响应里（GET /papers/{id} 恒返回 0），
      // 所以带着它跳到作答页由作答页提示，而不是停在本页（停在本页会让用户以为没组卷成功）。
      await load()
      navigate(
        `/papers/${paper.id}`,
        paper.shortfall > 0 ? { state: { shortfall: paper.shortfall, totalCount: paper.totalCount ?? 0 } } : undefined,
      )
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setLoading(false)
    }
  }

  async function remove(id: number) {
    // 后端是硬删除（连同题目快照与作答记录），必须二次确认。
    if (!window.confirm('删除这份试卷？作答记录会一并删除，无法恢复。')) {
      return
    }
    try {
      await exam.deletePaper(id)
      setError(null)
      await load()
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  return (
    <div className="min-h-dvh bg-background">
      <LearnerHeader />
      <main className="mx-auto w-full max-w-3xl px-4 py-6">
        <h1 className="text-xl font-semibold text-foreground" data-testid="papers-title">试卷</h1>
        <p className="mt-1 text-sm text-muted-foreground">按难度规则组卷，作答后由服务端判分给出成绩与薄弱点。</p>

        <section className="mt-4 rounded-lg border border-border bg-card p-4">
          <h2 className="text-sm font-medium text-foreground">新建试卷</h2>
          <div className="mt-3 flex flex-wrap items-end gap-3 text-sm">
            <label className="flex flex-col gap-1">
              标题
              <input
                className="rounded border border-border bg-background px-2 py-1"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            </label>
            <label className="flex flex-col gap-1">
              题量
              <input
                type="number"
                min={1}
                max={50}
                className="w-24 rounded border border-border bg-background px-2 py-1"
                value={count}
                onChange={(e) => setCount(Math.max(1, Math.min(50, Number(e.target.value) || 1)))}
              />
            </label>
            <label className="flex flex-col gap-1">
              难度
              <select
                className="rounded border border-border bg-background px-2 py-1"
                value={difficulty}
                onChange={(e) => setDifficulty(e.target.value)}
              >
                <option value="easy">易</option>
                <option value="medium">中</option>
                <option value="hard">难</option>
              </select>
            </label>
            <Button size="sm" onClick={() => void create()} disabled={loading} data-testid="papers-create">
              组卷
            </Button>
          </div>
        </section>

        {error ? <p className="mt-4 text-sm text-destructive">{error}</p> : null}

        <ul className="mt-6 space-y-2" data-testid="papers-list">
          {papers.map((paper) => (
            <li key={paper.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-card px-4 py-3">
              <div>
                <Link to={`/papers/${paper.id}`} className="text-sm font-medium text-foreground hover:underline">
                  {paper.title}
                </Link>
                <p className="text-xs text-muted-foreground">
                  {paper.status === 'submitted' ? '已交卷' : '待作答'} · {paper.totalCount ?? 0} 题
                </p>
              </div>
              <div className="flex items-center gap-2">
                {paper.status === 'submitted' ? (
                  <Link to={`/papers/${paper.id}/result`} className="text-sm text-primary hover:underline">
                    看成绩
                  </Link>
                ) : null}
                <Button size="sm" variant="ghost" onClick={() => void remove(paper.id)}>
                  删除
                </Button>
              </div>
            </li>
          ))}
          {papers.length === 0 ? <li className="text-sm text-muted-foreground" data-testid="papers-empty">还没有试卷，先组一份。</li> : null}
        </ul>
      </main>
    </div>
  )
}
