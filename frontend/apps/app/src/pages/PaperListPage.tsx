import { useCallback, useEffect, useMemo, useState } from 'react'
import { errorMessage } from '@paideia/core'
import {
  createExamApi,
  createKnowledgeApi,
  type CategoryNode,
  type PaperSummary,
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
  Section,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { BookOpen, FileText, Plus, Sparkles, Trash2 } from 'lucide-react'
import { Link, useNavigate } from 'react-router'

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

/** 组卷模板：一键按常见的题量与难度配比生成一份试卷。 */
interface PaperTemplate {
  key: string
  name: string
  desc: string
  count: number
  dist: Record<string, number>
}

const TEMPLATES: PaperTemplate[] = [
  { key: 'mix10', name: '混合 10 题', desc: '易 3 · 中 4 · 难 3', count: 10, dist: { easy: 3, medium: 4, hard: 3 } },
  { key: 'mix20', name: '混合 20 题', desc: '易 6 · 中 8 · 难 6', count: 20, dist: { easy: 6, medium: 8, hard: 6 } },
  { key: 'easy10', name: '易题热身', desc: '10 题全为易', count: 10, dist: { easy: 10 } },
  { key: 'medium10', name: '中等巩固', desc: '10 题全为中', count: 10, dist: { medium: 10 } },
  { key: 'hard10', name: '难题冲刺', desc: '10 题全为难', count: 10, dist: { hard: 10 } },
  { key: 'mix30', name: '综合 30 题', desc: '易 9 · 中 12 · 难 9', count: 30, dist: { easy: 9, medium: 12, hard: 9 } },
]

const DIFFICULTIES: { key: 'easy' | 'medium' | 'hard'; label: string }[] = [
  { key: 'easy', label: '易' },
  { key: 'medium', label: '中' },
  { key: 'hard', label: '难' },
]

/** 试卷列表 + 组卷（WORK-083 TASK-007，2026-11-24 扩充模板与分类组卷、重做列表）。 */
export function PaperListPage() {
  const exam = useMemo(() => createExamApi(api), [])
  const navigate = useNavigate()
  const [papers, setPapers] = useState<PaperSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState<string | null>(null)

  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [title, setTitle] = useState('我的试卷')
  const [mix, setMix] = useState<Record<'easy' | 'medium' | 'hard', number>>({ easy: 3, medium: 4, hard: 3 })

  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
    staleTime: 5 * 60 * 1000,
  })
  const flat = useMemo(() => flattenCategories(categories.data ?? []), [categories.data])

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

  const customTotal = mix.easy + mix.medium + mix.hard

  async function createPaper(ruleTitle: string, count: number, dist: Record<string, number>, key: string) {
    setCreating(key)
    setError(null)
    try {
      const paper = await exam.createPaper({
        title: ruleTitle,
        count,
        categoryId: categoryId ?? undefined,
        difficultyDistribution: dist,
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
      setCreating(null)
    }
  }

  function createFromTemplate(template: PaperTemplate) {
    void createPaper(template.name, template.count, template.dist, `tpl:${template.key}`)
  }

  function createCustom() {
    const clean: Record<string, number> = {}
    for (const { key } of DIFFICULTIES) {
      if (mix[key] > 0) clean[key] = mix[key]
    }
    if (customTotal <= 0) {
      setError('题量至少为 1 题')
      return
    }
    void createPaper(title.trim() || '我的试卷', customTotal, clean, 'custom')
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

  const categoryName = categoryId === null ? '全部分类' : (flat.find((item) => item.node.id === categoryId)?.node.name ?? '全部分类')

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <div className="mb-8">
        <h1 className="text-3xl" data-testid="papers-title">
          试卷
        </h1>
        <p className="text-muted-foreground mt-3 max-w-2xl text-base leading-7">
          按分类与难度组卷，作答后由服务端判分并给出成绩与薄弱点。可以一键套用模板，也能自定义难度配比。
        </p>
      </div>

      {/* 分类选择：模板与自定义组卷共用 */}
      <div className="mb-6 flex flex-wrap items-center gap-2 rounded-xl border border-border bg-card px-4 py-3">
        <span className="text-sm text-muted-foreground">出题范围</span>
        <select
          className="rounded-lg border border-border bg-background px-2.5 py-1.5 text-sm"
          value={categoryId ?? ''}
          onChange={(e) => setCategoryId(e.target.value === '' ? null : Number(e.target.value))}
          data-testid="paper-category"
        >
          <option value="">全部分类</option>
          {flat.map(({ node, depth }) => (
            <option key={node.id} value={node.id}>
              {'　'.repeat(depth)}
              {node.name}
            </option>
          ))}
        </select>
        <span className="text-muted-foreground text-xs">当前：{categoryName}</span>
      </div>

      <Section title="快速组卷" description="点一下按预设题量与难度配比立即生成一份试卷。">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="paper-templates">
          {TEMPLATES.map((template) => (
            <button
              key={template.key}
              type="button"
              onClick={() => createFromTemplate(template)}
              disabled={creating !== null}
              data-testid={`paper-template-${template.key}`}
              className="group flex items-center gap-3 rounded-xl border border-border bg-card p-4 text-left transition-colors hover:border-primary/50 hover:bg-accent/40 disabled:opacity-60"
            >
              <span className="bg-primary/10 text-primary flex size-10 shrink-0 items-center justify-center rounded-lg">
                <Sparkles className="size-5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-foreground">{template.name}</span>
                <span className="text-muted-foreground mt-0.5 block text-xs">{template.desc}</span>
              </span>
              {creating === `tpl:${template.key}` ? (
                <span className="text-muted-foreground text-xs">生成中…</span>
              ) : (
                <Plus className="text-muted-foreground group-hover:text-foreground size-4 shrink-0 transition-colors" />
              )}
            </button>
          ))}
        </div>
      </Section>

      <Section title="自定义组卷" description="自己定标题与易/中/难各放多少题，总题量为三者之和。">
        <div className="rounded-xl border border-border bg-card p-4">
          <div className="flex flex-wrap items-end gap-4">
            <label className="flex flex-col gap-1 text-sm">
              标题
              <input
                className="w-52 rounded-lg border border-border bg-background px-2.5 py-1.5"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                data-testid="paper-title-input"
              />
            </label>
            {DIFFICULTIES.map(({ key, label }) => (
              <label key={key} className="flex flex-col gap-1 text-sm">
                {label} 题数
                <input
                  type="number"
                  min={0}
                  max={50}
                  className="w-20 rounded-lg border border-border bg-background px-2.5 py-1.5"
                  value={mix[key]}
                  onChange={(e) =>
                    setMix((prev) => ({ ...prev, [key]: Math.max(0, Math.min(50, Number(e.target.value) || 0)) }))
                  }
                  data-testid={`paper-mix-${key}`}
                />
              </label>
            ))}
            <div className="text-sm">
              <span className="text-muted-foreground">共 </span>
              <span className="font-semibold tabular-nums">{customTotal}</span>
              <span className="text-muted-foreground"> 题</span>
            </div>
            <Button
              size="sm"
              onClick={() => createCustom()}
              disabled={creating !== null || customTotal === 0}
              data-testid="papers-create"
            >
              {creating === 'custom' ? '组卷中…' : '组卷'}
            </Button>
          </div>
        </div>
      </Section>

      {error ? <p className="mb-4 text-sm text-destructive">{error}</p> : null}

      <Section
        title="我的试卷"
        description={papers.length === 0 ? undefined : `共 ${papers.length} 份，点标题进入作答。`}
      >
        {papers.length === 0 ? (
          <Empty data-testid="papers-empty">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <FileText />
              </EmptyMedia>
              <EmptyTitle>还没有试卷</EmptyTitle>
              <EmptyDescription>用上面的模板一键生成，或自定义一份难度配比。</EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              <Button variant="outline" onClick={() => createFromTemplate(TEMPLATES[0]!)} disabled={creating !== null}>
                <Sparkles className="size-4" /> 来一份混合 10 题
              </Button>
            </EmptyContent>
          </Empty>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="papers-list">
            {papers.map((paper) => (
              <div
                key={paper.id}
                className="flex flex-col rounded-xl border border-border bg-card p-4 transition-colors hover:border-primary/40"
              >
                <div className="flex items-start justify-between gap-2">
                  <Link
                    to={`/papers/${paper.id}`}
                    className="min-w-0 flex-1 text-sm font-medium text-foreground hover:underline"
                  >
                    {paper.title}
                  </Link>
                  <span
                    className={
                      'shrink-0 rounded-full px-2 py-0.5 text-xs ' +
                      (paper.status === 'submitted'
                        ? 'bg-green-500/10 text-green-700'
                        : 'bg-muted text-muted-foreground')
                    }
                  >
                    {paper.status === 'submitted' ? '已交卷' : '待作答'}
                  </span>
                </div>
                <p className="text-muted-foreground mt-1 text-xs tabular-nums">
                  {paper.totalCount ?? 0} 题
                  {paper.createdAt ? ` · ${new Date(paper.createdAt).toLocaleDateString('zh-CN')}` : ''}
                </p>
                <div className="mt-3 flex items-center gap-2 border-t border-border pt-3">
                  {paper.status === 'submitted' ? (
                    <Button variant="outline" size="sm" render={<Link to={`/papers/${paper.id}/result`} />}>
                      <BookOpen className="size-4" /> 看成绩
                    </Button>
                  ) : (
                    <Button variant="outline" size="sm" render={<Link to={`/papers/${paper.id}`} />}>
                      <BookOpen className="size-4" /> 继续作答
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    size="sm"
                    className="ml-auto text-muted-foreground hover:text-destructive"
                    onClick={() => void remove(paper.id)}
                    data-testid={`paper-delete-${paper.id}`}
                  >
                    <Trash2 className="size-4" /> 删除
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Section>
    </AppShell>
  )
}
