import { ChevronDown, ChevronRight, FileText, Folder } from 'lucide-react'
import { useState } from 'react'

import type { NoteNodeRow } from '../../notes-api'

interface DocTreeProps {
  nodes: NoteNodeRow[]
  selectedId: number | null
  onSelect: (id: number) => void
  onDelete: (id: number) => void
}

interface TreeNode {
  node: NoteNodeRow
  children: TreeNode[]
}

/** 把后端返回的扁平列表（parentId 指父）拼成森林，并按 sortOrder 排好。 */
function buildForest(nodes: NoteNodeRow[]): TreeNode[] {
  const map = new Map<number, TreeNode>()
  for (const node of nodes) {
    map.set(node.id, { node, children: [] })
  }
  const roots: TreeNode[] = []
  for (const entry of map.values()) {
    const parent = entry.node.parentId
    const holder = parent === null ? undefined : map.get(parent)
    if (holder === undefined) {
      roots.push(entry)
    } else {
      holder.children.push(entry)
    }
  }
  const sortRec = (list: TreeNode[]): void => {
    list.sort((a, b) => a.node.sortOrder - b.node.sortOrder || a.node.id - b.node.id)
    for (const item of list) {
      sortRec(item.children)
    }
  }
  sortRec(roots)
  return roots
}

function TreeRow({
  item,
  depth,
  selectedId,
  onSelect,
  onDelete,
}: {
  item: TreeNode
  depth: number
  selectedId: number | null
  onSelect: (id: number) => void
  onDelete: (id: number) => void
}) {
  const [open, setOpen] = useState(true)
  const isFolder = item.node.kind === 'folder'
  const hasChildren = item.children.length > 0
  const selected = item.node.id === selectedId

  return (
    <li>
      <div
        className={`group flex items-center gap-1 rounded-md pr-1 ${
          selected ? 'bg-accent text-accent-foreground' : 'hover:bg-muted'
        }`}
        style={{ paddingLeft: `${depth * 14}px` }}
      >
        {isFolder ? (
          <button
            type="button"
            aria-label={open ? '折叠' : '展开'}
            className="flex h-6 w-5 items-center justify-center text-muted-foreground"
            onClick={() => setOpen((value) => !value)}
            disabled={!hasChildren}
          >
            {hasChildren ? (
              open ? (
                <ChevronDown className="h-3.5 w-3.5" />
              ) : (
                <ChevronRight className="h-3.5 w-3.5" />
              )
            ) : null}
          </button>
        ) : (
          <span className="h-6 w-5" />
        )}
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-1.5 py-1 text-left text-sm"
          onClick={() => onSelect(item.node.id)}
        >
          {isFolder ? (
            <Folder className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          ) : (
            <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          )}
          <span className="truncate">{item.node.title}</span>
        </button>
        <button
          type="button"
          aria-label="删除"
          className="hidden h-6 w-6 items-center justify-center rounded text-muted-foreground hover:text-destructive group-hover:flex"
          onClick={() => onDelete(item.node.id)}
        >
          ×
        </button>
      </div>
      {isFolder && hasChildren && open ? (
        <ul>
          {item.children.map((child) => (
            <TreeRow
              key={child.node.id}
              item={child}
              depth={depth + 1}
              selectedId={selectedId}
              onSelect={onSelect}
              onDelete={onDelete}
            />
          ))}
        </ul>
      ) : null}
    </li>
  )
}

/** 个人知识库的文档目录树。 */
export function DocTree({ nodes, selectedId, onSelect, onDelete }: DocTreeProps) {
  const roots = buildForest(nodes)
  if (roots.length === 0) {
    return <p className="px-2 py-4 text-sm text-muted-foreground">这个知识库还是空的，新建一篇文档吧。</p>
  }
  return (
    <ul className="space-y-0.5">
      {roots.map((item) => (
        <TreeRow
          key={item.node.id}
          item={item}
          depth={0}
          selectedId={selectedId}
          onSelect={onSelect}
          onDelete={onDelete}
        />
      ))}
    </ul>
  )
}
