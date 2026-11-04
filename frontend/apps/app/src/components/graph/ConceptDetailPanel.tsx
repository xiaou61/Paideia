import { createAssistantApi } from '@paideia/assistant'
import type { ConceptNode } from '@paideia/knowledge'
import { Button } from '@paideia/ui'
import { useMutation } from '@tanstack/react-query'
import { BookOpen, Clock } from 'lucide-react'
import { Link } from 'react-router'

import { api } from '../../api'
import { tierColor, tierStyle } from './tier-style'

const assistant = createAssistantApi(api)

interface Props {
  concept: ConceptNode | null
  onNavigate?: (entryId: number) => void
}

/**
 * 点选节点后的详情面板（WORK-021）。
 *
 * <p>三块信息，顺序就是用户关心的顺序：这个概念是什么 → 我掌握到什么程度 →
 * 哪几篇内容承载它（能点进去）。没有载体时明确说出来，而不是留一片空白。
 */
export function ConceptDetailPanel({ concept }: Props) {
  if (concept === null) {
    return (
      <aside className="text-muted-foreground border-border/60 rounded-xl border p-5 text-sm">
        点一个节点看详情。
        <div className="mt-4 space-y-1.5 text-xs">
          {(['unknown', 'learning', 'familiar', 'mastered'] as const).map((tier) => (
            <p key={tier} className="flex items-center gap-2">
              <span
                className="inline-block size-2.5 rounded-full"
                style={{ backgroundColor: tierColor(tier) }}
              />
              {tierStyle(tier).label}
              <span className="opacity-70">— {tierStyle(tier).hint}</span>
            </p>
          ))}
        </div>
      </aside>
    )
  }

  const style = tierStyle(concept.tier)

  return (
    <aside className="border-border/60 rounded-xl border p-5" data-testid="concept-detail">
      <h2 className="text-lg font-semibold">{concept.name}</h2>
      <p className="text-muted-foreground mt-1 text-sm">
        <span className="inline-block size-2.5 rounded-full" style={{ backgroundColor: tierColor(concept.tier) }} />{' '}
        {style.label} · {Math.round(concept.score * 100)}%
        {concept.categoryName !== null ? ` · ${concept.categoryName}` : ''}
      </p>

      {concept.reviewDue ? (
        <p className="text-warning mt-2 flex items-center gap-1.5 text-xs">
          <Clock className="size-3.5" />
          到期该复习了
        </p>
      ) : null}

      {concept.summary !== null && concept.summary !== '' ? (
        <p className="mt-3 text-sm leading-relaxed">{concept.summary}</p>
      ) : null}

      <AiConceptExplain name={concept.name} summary={concept.summary ?? ''} />

      <p className="text-muted-foreground mt-3 text-xs">
        看过 {concept.evidence.viewed} 次
        {concept.evidence.assessed === null
          ? ''
          : ` · 自评${concept.evidence.assessed === 'understood' ? '懂' : '不确定'}`}
        {concept.evidence.scoredEntries > 0 ? ` · ${concept.evidence.scoredEntries} 篇有练习成绩` : ''}
      </p>

      <h3 className="mt-4 text-sm font-medium">承载它的内容</h3>
      {concept.entries.length === 0 ? (
        <p className="text-muted-foreground mt-1 text-xs">还没有挂上任何条目。</p>
      ) : (
        <ul className="mt-2 space-y-1.5">
          {concept.entries.map((entry) => (
            <li key={entry.entryId}>
              <Link
                to={`/knowledge/entries/${entry.entryId}`}
                className="hover:text-foreground flex items-start gap-1.5 text-sm text-muted-foreground transition-colors"
                data-testid="concept-entry-link"
              >
                <BookOpen className="mt-0.5 size-3.5 shrink-0" />
                <span>
                  {entry.title}
                  {entry.scoreRatio !== null ? (
                    <span className="opacity-70"> · {Math.round(entry.scoreRatio * 100)}%</span>
                  ) : null}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </aside>
  )
}

/** 「AI 用大白话讲讲」（WORK-067）：只把概念名与摘要发上去，不落库。 */
function AiConceptExplain({ name, summary }: { name: string; summary: string }) {
  const ask = useMutation({
    mutationFn: () => assistant.explainConcept({ name, ...(summary === '' ? {} : { summary }) }),
  })
  return (
    <div className="mt-3">
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={ask.isPending}
        data-testid="concept-ai-explain"
        onClick={() => ask.mutate()}
      >
        {ask.isPending ? 'AI 正在解释…' : 'AI 用大白话讲讲'}
      </Button>
      {ask.data === undefined ? null : (
        <p className="text-muted-foreground mt-2 text-sm leading-6" data-testid="concept-ai-text">
          {ask.data.text}
        </p>
      )}
      {ask.isError ? <p className="text-destructive mt-2 text-xs">AI 暂时用不了，稍后再试。</p> : null}
    </div>
  )
}
