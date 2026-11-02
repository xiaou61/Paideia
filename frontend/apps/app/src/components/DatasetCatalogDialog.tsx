import { createKnowledgeApi, type DatasetCatalogItem } from '@paideia/knowledge'
import {
  Badge,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  Spinner,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { api } from '../api'

const knowledge = createKnowledgeApi(api)

/**
 * 数据集目录（WORK-060）。
 *
 * <p>只展示「能选什么」：公开集所有人可用，我的私有集只有自己可见。上传者只显示用户名，
 * 不显示邮箱或 id。切换公开/私有是作者动作，切回私有时其他人会立刻看不到。
 */
export function DatasetCatalogDialog({
  open,
  onOpenChange,
  selectedKey,
  onUse,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  selectedKey: string | null
  onUse: (key: string) => void
}) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const catalog = useQuery({
    queryKey: ['sql-dataset-catalog'],
    queryFn: () => knowledge.datasetCatalog(),
    enabled: open,
  })

  const toggle = useMutation({
    mutationFn: ({ key, visibility }: { key: string; visibility: 'private' | 'public' }) =>
      knowledge.setDatasetVisibility(key, visibility),
    onSuccess: () => {
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['sql-dataset-catalog'] })
      void queryClient.invalidateQueries({ queryKey: ['sandbox-status'] })
    },
    onError: (cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause)),
  })

  const items = catalog.data ?? []
  const publicItems = items.filter((item) => item.visibility === 'public')
  const mine = items.filter((item) => item.mine)

  const use = (item: DatasetCatalogItem) => {
    onUse(item.key)
    onOpenChange(false)
  }

  const changeVisibility = (item: DatasetCatalogItem) => {
    const next = item.visibility === 'public' ? 'private' : 'public'
    if (
      next === 'private' &&
      !window.confirm(`把「${item.name}」切回私有后，其他人将不能再使用它。继续？`)
    ) {
      return
    }
    toggle.mutate({ key: item.key, visibility: next })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl" data-testid="dataset-catalog-dialog">
        <DialogHeader>
          <DialogTitle>数据集</DialogTitle>
          <DialogDescription>
            公开数据集所有人都能用；私有数据集只有你自己能看到。切换只影响可见性，不删内容。
          </DialogDescription>
        </DialogHeader>

        {error === null ? null : <p className="text-destructive text-sm">{error}</p>}
        {catalog.isPending ? (
          <div className="flex items-center gap-2 py-6 text-sm">
            <Spinner />
            正在加载…
          </div>
        ) : catalog.isError ? (
          <p className="text-destructive text-sm">
            加载失败：{catalog.error instanceof Error ? catalog.error.message : '请稍后重试'}
          </p>
        ) : (
          <div className="max-h-[60vh] space-y-5 overflow-y-auto pr-1">
            <Section title="公开" items={publicItems} selectedKey={selectedKey} onUse={use} />
            <Section
              title="我的"
              items={mine}
              selectedKey={selectedKey}
              onUse={use}
              onToggle={changeVisibility}
              toggling={toggle.isPending}
            />
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function Section({
  title,
  items,
  selectedKey,
  onUse,
  onToggle,
  toggling = false,
}: {
  title: string
  items: DatasetCatalogItem[]
  selectedKey: string | null
  onUse: (item: DatasetCatalogItem) => void
  onToggle?: (item: DatasetCatalogItem) => void
  toggling?: boolean
}) {
  return (
    <section className="space-y-2">
      <h3 className="text-sm font-medium">{title}</h3>
      {items.length === 0 ? (
        <p className="text-muted-foreground text-xs">这里还没有数据集。</p>
      ) : (
        <ul className="space-y-1.5">
          {items.map((item) => (
            <li
              key={item.key}
              className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
              data-testid={`dataset-catalog-item-${item.key}`}
            >
              <span className="text-sm font-medium">{item.name}</span>
              <Badge variant={item.visibility === 'public' ? 'secondary' : 'outline'}>
                {item.visibility === 'public' ? '公开' : '私有'}
              </Badge>
              {item.source === 'builtin' ? (
                <Badge variant="ghost">内置</Badge>
              ) : null}
              <span className="text-muted-foreground text-xs">
                {item.tableCount} 张表
                {item.source === 'builtin' ? '' : ` · ${item.rowCount} 行`}
              </span>
              <span className="text-muted-foreground text-xs">上传者：{item.ownerName}</span>
              <span className="ml-auto flex items-center gap-1">
                {onToggle === undefined ? null : (
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    disabled={toggling}
                    onClick={() => onToggle(item)}
                  >
                    {item.visibility === 'public' ? '设为私有' : '设为公开'}
                  </Button>
                )}
                <Button
                  type="button"
                  size="sm"
                  variant={item.key === selectedKey ? 'secondary' : 'outline'}
                  onClick={() => onUse(item)}
                >
                  {item.key === selectedKey ? '当前' : '使用'}
                </Button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
