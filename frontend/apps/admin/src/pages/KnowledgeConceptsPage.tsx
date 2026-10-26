import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  PageHeader,
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link2, Plus, Trash2 } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'

import { ConfirmDeleteDialog, Loading, admin, categoryGroups, flatten } from '../knowledge-shared'

/**
 * 概念维护（WORK-021）。
 *
 * <p>这一页是整个知识图谱的入口：**概念表为空，图谱就是空的**。所以它的第一优先级不是
 * "功能齐全"，而是"建概念、挂条目这两件事足够快"——一次能挂多个条目，不必来回切页。
 *
 * <p>没有自动抽取概念的功能，是刻意的：脏概念会毁掉整张图的可信度，而图的全部价值在于
 * 用户相信它说的话。
 */
export function KnowledgeConceptsPage() {
  const queryClient = useQueryClient()
  const [creating, setCreating] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [editing, setEditing] = useState<{ id: number; name: string; summary: string; categoryId: number | null } | null>(null)
  const [linking, setLinking] = useState<{ id: number; name: string } | null>(null)
  const [pendingDelete, setPendingDelete] = useState<{ id: number; name: string } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const concepts = useQuery({ queryKey: ['admin-concepts'], queryFn: () => admin.concepts() })
  const needle = keyword.trim().toLowerCase()
  const visible = (concepts.data ?? []).filter(
    (concept) => needle === '' || concept.name.toLowerCase().includes(needle),
  )
  const categories = useQuery({ queryKey: ['admin-knowledge-categories'], queryFn: () => admin.categories() })
  const entries = useQuery({ queryKey: ['admin-knowledge-entries'], queryFn: () => admin.entries() })

  const flat = useMemo(() => flatten(categories.data ?? []), [categories.data])
  const grouped = useMemo(() => categoryGroups(flat), [flat])

  const report = (cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause))
  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin-concepts'] })
    // 图谱页读的是同一份数据，改完概念要让它重取
    await queryClient.invalidateQueries({ queryKey: ['learning-graph'] })
  }

  const remove = useMutation({
    mutationFn: (id: number) => admin.deleteConcept(id),
    onSuccess: async () => {
      setPendingDelete(null)
      await invalidate()
    },
    onError: report,
  })

  const save = useMutation({
    mutationFn: (input: { id?: number; name: string; summary: string; categoryId: number | null }) =>
      input.id === undefined
        ? admin.createConcept({ name: input.name, summary: input.summary, categoryId: input.categoryId })
        : admin.updateConcept(input.id, { name: input.name, summary: input.summary, categoryId: input.categoryId }),
    onSuccess: async () => {
      setCreating(false)
      setEditing(null)
      await invalidate()
    },
    onError: report,
  })

  const link = useMutation({
    mutationFn: (input: { conceptId: number; entryId: number }) =>
      admin.linkConceptEntry(input.conceptId, input.entryId),
    onSuccess: invalidate,
    onError: report,
  })

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="知识库"
        title="概念"
        description="概念是知识图谱的节点。把条目挂到概念上，学习者就能看到自己每个知识点掌握到什么程度。"
        actions={
          <Button onClick={() => setCreating(true)} data-testid="concept-create">
            <Plus className="size-4" />
            新建概念
          </Button>
        }
      />

      {error !== null ? (
        <Alert variant="destructive">
          <AlertTitle>操作失败</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <Card>
        <CardContent className="px-0 pt-0">
          <div className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-(--card-spacing) py-3">
            <div>
              <h2 className="text-sm font-medium">概念</h2>
              <p className="text-muted-foreground mt-0.5 text-xs" data-testid="admin-concept-total">
                {concepts.isSuccess ? `共 ${concepts.data.length} 个` : '加载中…'}
                {visible.length === (concepts.data?.length ?? 0) ? '' : ` · 筛出 ${visible.length} 个`}
              </p>
            </div>
            <Input
              className="h-9 w-56"
              placeholder="按名称搜索"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              data-testid="admin-concept-search"
            />
          </div>
          <div className="px-(--card-spacing) pt-4">
          {concepts.isPending ? <Loading label="加载概念…" /> : null}
          {concepts.isSuccess && concepts.data.length === 0 ? (
            <p className="text-muted-foreground text-sm" data-testid="concepts-empty">
              还没有概念。点右上角「新建概念」开始——图谱页在概念表为空时只会显示一句提示。
            </p>
          ) : null}
          {concepts.isSuccess && visible.length === 0 && concepts.data.length > 0 ? (
            <p className="text-muted-foreground text-sm" data-testid="concepts-no-match">
              没有匹配的概念。
            </p>
          ) : null}
          {visible.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead>说明</TableHead>
                  <TableHead>所属分类</TableHead>
                  <TableHead className="text-right">条目</TableHead>
                  <TableHead className="text-right">关系</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {visible.map((concept) => (
                  <TableRow key={concept.id}>
                    <TableCell className="font-medium">{concept.name}</TableCell>
                    <TableCell className="text-muted-foreground max-w-[22rem] truncate">
                      {concept.summary ?? '—'}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {flat.find((category) => category.id === concept.categoryId)?.name ?? '—'}
                    </TableCell>
                    <TableCell className="text-right">{concept.entryLinks}</TableCell>
                    <TableCell className="text-right">{concept.edges}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setLinking({ id: concept.id, name: concept.name })}
                          data-testid={`concept-link-${concept.id}`}
                        >
                          <Link2 className="size-4" />
                          挂条目
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() =>
                            setEditing({
                              id: concept.id,
                              name: concept.name,
                              summary: concept.summary ?? '',
                              categoryId: concept.categoryId,
                            })
                          }
                        >
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setPendingDelete({ id: concept.id, name: concept.name })}
                          data-testid={`concept-delete-${concept.id}`}
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : null}
          </div>
        </CardContent>
      </Card>

      <ConceptFormDialog
        // 弹窗常驻、只有 open 控制显隐，useState(initial) 只在首次挂载时读一次——
        // 换一个概念或从编辑切到新建时必须重建，否则表单里留的是上一次的值。
        key={editing?.id ?? 'new'}
        open={creating || editing !== null}
        initial={editing}
        options={grouped}
        submitting={save.isPending}
        onCancel={() => {
          setCreating(false)
          setEditing(null)
        }}
        onSubmit={(value) => save.mutate({ id: editing?.id, ...value })}
      />

      <LinkEntriesDialog
        target={linking}
        entries={entries.data?.items ?? []}
        onClose={() => setLinking(null)}
        onLink={(entryId) => linking !== null && link.mutate({ conceptId: linking.id, entryId })}
      />

      <ConfirmDeleteDialog
        open={pendingDelete !== null}
        title="删除这个概念？"
        label="概念"
        name={pendingDelete?.name ?? ''}
        hint="它的条目关联与概念关系会一并删除；条目本身不受影响。"
        busy={remove.isPending}
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => pendingDelete !== null && remove.mutate(pendingDelete.id)}
      />
    </div>
  )
}

interface ConceptFormValue {
  name: string
  summary: string
  categoryId: number | null
}

function ConceptFormDialog({
  open,
  initial,
  options,
  submitting,
  onCancel,
  onSubmit,
}: {
  open: boolean
  initial: { id: number; name: string; summary: string; categoryId: number | null } | null
  options: { label: string; options: { value: number | null; label: string }[] }[]
  submitting: boolean
  onCancel: () => void
  onSubmit: (value: ConceptFormValue) => void
}) {
  const [name, setName] = useState(initial?.name ?? '')
  const [summary, setSummary] = useState(initial?.summary ?? '')
  const [categoryId, setCategoryId] = useState<number | null>(initial?.categoryId ?? null)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    onSubmit({ name: name.trim(), summary: summary.trim(), categoryId })
  }

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? undefined : onCancel())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{initial === null ? '新建概念' : '编辑概念'}</DialogTitle>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="concept-name">名称</Label>
            <Input
              id="concept-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="比如：哈希表、三次握手、MVCC"
              maxLength={64}
              required
            />
            <p className="text-muted-foreground text-xs">
              一个概念应该对应**一次能独立测验的知识点**。太细会有上百个点，太粗就没有信息量。
            </p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="concept-summary">说明（可选）</Label>
            <Input
              id="concept-summary"
              value={summary}
              onChange={(event) => setSummary(event.target.value)}
              placeholder="一句话说清它是什么"
              maxLength={500}
            />
          </div>
          <div className="space-y-2">
            <Label>所属分类（可选）</Label>
            <Select
              value={categoryId === null ? 'none' : String(categoryId)}
              onValueChange={(value) => setCategoryId(value === 'none' ? null : Number(value))}
            >
              <SelectTrigger>
                <SelectValue placeholder="不指定分类" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">不指定分类</SelectItem>
                {options.map((group) => (
                  <SelectGroup key={group.label}>
                    <SelectLabel>{group.label}</SelectLabel>
                    {group.options.map((option) => (
                      <SelectItem key={String(option.value)} value={String(option.value)}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onCancel}>
              取消
            </Button>
            <Button type="submit" disabled={submitting || name.trim() === ''} data-testid="concept-save">
              {submitting ? '保存中…' : '保存'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function LinkEntriesDialog({
  target,
  entries,
  onClose,
  onLink,
}: {
  target: { id: number; name: string } | null
  entries: { id: number; title: string; status: string }[]
  onClose: () => void
  onLink: (entryId: number) => void
}) {
  return (
    <Dialog open={target !== null} onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>给「{target?.name ?? ''}」挂条目</DialogTitle>
        </DialogHeader>
        <p className="text-muted-foreground text-sm">
          一个概念可以挂在多篇内容上，同一个知识点分散在不同课里也没关系。点一次挂一条。
        </p>
        <div className="max-h-80 space-y-1 overflow-y-auto">
          {entries.map((entry) => (
            <div key={entry.id} className="border-border/50 flex items-center gap-2 rounded-md border px-3 py-2">
              <span className="flex-1 truncate text-sm">{entry.title}</span>
              {entry.status === 'draft' ? (
                <span className="text-muted-foreground text-xs">草稿</span>
              ) : null}
              <Button
                size="sm"
                variant="outline"
                onClick={() => onLink(entry.id)}
                data-testid={`link-entry-${entry.id}`}
              >
                挂上
              </Button>
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>
            完成
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
