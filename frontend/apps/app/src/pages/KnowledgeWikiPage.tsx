import { MarkdownBody, createKnowledgeApi, fetchAllEntries, plainText } from '@paideia/knowledge'
import type { CategoryNode, EntrySummary } from '@paideia/knowledge'
import { AppShell, Empty, EmptyDescription, EmptyHeader, EmptyTitle, ErrorAlert, LoadingRow, PageHeader } from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { BookOpen, FolderOpen } from 'lucide-react'
import { useMemo, useState } from 'react'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { API_BASE_URL } from '../env'

const knowledge = createKnowledgeApi(api)

/**
 * 按目录阅读（WORK-076 TASK-014）。
 *
 * <p>三栏的文档站式浏览：左栏分类树 → 中栏该分类下的条目 → 右栏正文（复用 `MarkdownBody`，
 * 与条目详情页同一渲染）。这是“像读一本手册一样逐页翻阅”的入口，不新增任何分类/图谱模型。
 */
export function KnowledgeWikiPage() {
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [entryId, setEntryId] = useState<number | null>(null)

  const categories = useQuery({ queryKey: ['knowledge-categories'], queryFn: () => knowledge.categories() })
  // 目录必须**完整**：原来写死 size: 200，线上 571 条时后面三百多条直接不出现（2026-11-10）
  const entries = useQuery({
    queryKey: ['wiki-entries', categoryId],
    queryFn: () => fetchAllEntries(knowledge, { categoryId: categoryId ?? undefined }),
  })
  const detail = useQuery({
    queryKey: ['wiki-entry', entryId],
    queryFn: () => knowledge.entry(entryId as number),
    enabled: entryId !== null,
  })

  const flat = useMemo(() => flatten(categories.data ?? []), [categories.data])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader title="按目录阅读" description="沿左侧目录选分类、再选条目，右侧连续阅读正文。" />

      {categories.isError ? <ErrorAlert title="目录加载失败" error={categories.error} testId="wiki-state" /> : null}
      {categories.isPending ? <LoadingRow label="加载目录…" testId="wiki-state" /> : null}

      <div className="grid gap-6 lg:grid-cols-[16rem_18rem_minmax(0,1fr)]">
        <nav className="border-border bg-card h-fit rounded-xl border p-2" data-testid="wiki-categories">
          <button
            type="button"
            onClick={() => {
              setCategoryId(null)
              setEntryId(null)
            }}
            data-testid="wiki-category-all"
            className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm ${
              categoryId === null ? 'bg-accent font-medium' : 'hover:bg-accent/60'
            }`}
          >
            <BookOpen className="size-4" />
            全部条目
          </button>
          {flat.map((node) => (
            <button
              key={node.id}
              type="button"
              onClick={() => {
                setCategoryId(node.id)
                setEntryId(null)
              }}
              data-testid={`wiki-category-${node.id}`}
              style={{ paddingLeft: `${8 + node.depth * 14}px` }}
              className={`flex w-full items-center gap-2 rounded-md py-1.5 pr-2 text-left text-sm ${
                categoryId === node.id ? 'bg-accent font-medium' : 'hover:bg-accent/60'
              }`}
            >
              <FolderOpen className="size-3.5 shrink-0" />
              <span className="truncate">{node.name}</span>
            </button>
          ))}
        </nav>

        <div className="border-border bg-card h-fit rounded-xl border p-2" data-testid="wiki-entries">
          {(entries.data ?? []).map((item) => (
            <EntryRow key={item.id} item={item} active={entryId === item.id} onSelect={() => setEntryId(item.id)} />
          ))}
          {entries.isPending ? <p className="text-muted-foreground p-2 text-sm">加载中…</p> : null}
          {entries.isSuccess && entries.data.length === 0 ? (
            <p className="text-muted-foreground p-2 text-sm">该分类下还没有条目</p>
          ) : null}
        </div>

        <article className="border-border bg-card min-h-[24rem] rounded-xl border p-6" data-testid="wiki-reader">
          {entryId === null ? (
            <Empty>
              <EmptyHeader>
                <EmptyTitle>选一条开始读</EmptyTitle>
                <EmptyDescription>从左侧目录选择分类与条目。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : null}
          {detail.isPending && entryId !== null ? <LoadingRow label="加载正文…" testId="wiki-reader-state" /> : null}
          {detail.isError && entryId !== null ? (
            <ErrorAlert title="正文加载失败" error={detail.error} testId="wiki-reader-state" />
          ) : null}
          {detail.isSuccess && entryId !== null ? (
            <>
              <h2 className="mb-4 text-xl font-semibold">{detail.data.title}</h2>
              <MarkdownBody markdown={detail.data.body} mediaBaseUrl={API_BASE_URL} entryId={detail.data.id} entryTitle={detail.data.title} />
            </>
          ) : null}
        </article>
      </div>
    </AppShell>
  )
}

interface FlatNode {
  id: number
  name: string
  depth: number
}

function flatten(nodes: CategoryNode[], depth = 0): FlatNode[] {
  return nodes.flatMap((node) => [{ id: node.id, name: node.name, depth }, ...flatten(node.children, depth + 1)])
}

function EntryRow({ item, active, onSelect }: { item: EntrySummary; active: boolean; onSelect: () => void }) {
  return (
    <button
      type="button"
      onClick={onSelect}
      data-testid={`wiki-entry-${item.id}`}
      className={`block w-full rounded-md px-2 py-2 text-left text-sm ${active ? 'bg-accent font-medium' : 'hover:bg-accent/60'}`}
    >
      <span className="block truncate">{item.title}</span>
      {item.excerpt === null || plainText(item.excerpt) === '' ? null : (
        <span className="text-muted-foreground mt-0.5 line-clamp-1 block text-xs">{plainText(item.excerpt)}</span>
      )}
    </button>
  )
}
