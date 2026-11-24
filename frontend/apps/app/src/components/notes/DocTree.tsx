import { ChevronDown, ChevronRight, FileText, Folder, Pencil } from 'lucide-react'
import { useState, type DragEvent } from 'react'

import type { NoteNodeRow } from '../../notes-api'

type DropPos = 'before' | 'after' | 'into'

interface DocTreeProps {
  nodes: NoteNodeRow[]
  selectedId: number | null
  onSelect: (id: number) => void
  onDelete: (id: number) => void
  onRename: (id: number, currentTitle: string) => void
  /** 把 dragId 挂到 parentId 下第 index 位（index 已在同级里排好）。 */
  onMove: (dragId: number, parentId: number | null, index: number) => void
  /** 一链补建缺失的「查漏补缺」文档。 */
  onAddGapFill?: (pairKey: string, parentId: number | null) => void
  /** 按分类筛选：为空/不传则显示全部；否则只显示该分类的文档及其祖先目录。 */
  categoryFilter?: string | null
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

/** 按分类筛选：保留匹配分类的文档，以及它们的所有祖先目录；不传筛选时原样返回。 */
function filterByCategory(nodes: NoteNodeRow[], category: string | null): NoteNodeRow[] {
  if (category === null || category === '') {
    return nodes
  }
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const keep = new Set<number>()
  for (const node of nodes) {
    if (node.kind === 'doc' && node.category === category) {
      let cursor: NoteNodeRow | undefined = node
      while (cursor !== undefined && !keep.has(cursor.id)) {
        keep.add(cursor.id)
        cursor = cursor.parentId === null ? undefined : byId.get(cursor.parentId)
      }
    }
  }
  return nodes.filter((node) => keep.has(node.id))
}

function TreeRow({
  item,
  depth,
  selectedId,
  draggedId,
  hint,
  onSelect,
  onDelete,
  onRename,
  onDragStart,
  onDragOverRow,
  onDropRow,
  gapPairs,
  onAddGapFill,
}: {
  item: TreeNode
  depth: number
  selectedId: number | null
  draggedId: number | null
  hint: { id: number; pos: DropPos } | null
  onSelect: (id: number) => void
  onDelete: (id: number) => void
  onRename: (id: number, currentTitle: string) => void
  onDragStart: (id: number) => void
  onDragOverRow: (id: number, isFolder: boolean, event: DragEvent<HTMLDivElement>) => void
  onDropRow: (id: number) => void
  gapPairs: Set<string>
  onAddGapFill?: (pairKey: string, parentId: number | null) => void
}) {
  const [open, setOpen] = useState(true)
  const isFolder = item.node.kind === 'folder'
  const hasChildren = item.children.length > 0
  const selected = item.node.id === selectedId
  const rowHint = hint?.id === item.node.id ? hint.pos : null
  const pairKey = item.node.pairKey
  const isTutorial = item.node.kind === 'doc' && item.node.docType === 'tutorial'
  const pairSlot = `${item.node.parentId ?? 'root'}:${pairKey ?? ''}`
  const missingGap = isTutorial && pairKey !== null && !gapPairs.has(pairSlot)

  return (
    <li>
      <div
        draggable
        onDragStart={(event) => {
          event.dataTransfer.effectAllowed = 'move'
          event.dataTransfer.setData('text/plain', String(item.node.id))
          onDragStart(item.node.id)
        }}
        onDragOver={(event) => onDragOverRow(item.node.id, isFolder, event)}
        onDrop={(event) => {
          event.preventDefault()
          event.stopPropagation()
          onDropRow(item.node.id)
        }}
        data-drop-pos={rowHint ?? undefined}
        className={`group flex items-center gap-1 rounded-md pr-1 ${
          selected ? 'bg-accent text-accent-foreground' : 'hover:bg-muted'
        } ${draggedId === item.node.id ? 'opacity-40' : ''} ${
          rowHint === 'into' ? 'ring-primary ring-1' : ''
        } ${rowHint === 'before' ? 'border-primary border-t-2' : ''} ${
          rowHint === 'after' ? 'border-primary border-b-2' : ''
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
          {item.node.docType === 'tutorial' ? (
            <span className="shrink-0 rounded bg-amber-100 px-1 py-0.5 text-[10px] leading-none text-amber-700">
              教程
            </span>
          ) : null}
          {item.node.docType === 'gap_fill' ? (
            <span className="bg-violet-100 text-violet-700 shrink-0 rounded px-1 py-0.5 text-[10px] leading-none">
              查漏补缺
            </span>
          ) : null}
          {item.node.category !== null && item.node.category !== '' ? (
            <span className="bg-sky-100 text-sky-700 shrink-0 rounded px-1 py-0.5 text-[10px] leading-none">
              {item.node.category}
            </span>
          ) : null}
          {item.node.level !== null && item.node.level !== '' ? (
            <span className="bg-slate-100 text-slate-700 shrink-0 rounded px-1 py-0.5 text-[10px] leading-none">
              {item.node.level}
            </span>
          ) : null}
        </button>
        <button
          type="button"
          aria-label="重命名"
          className="hidden h-6 w-6 items-center justify-center rounded text-muted-foreground hover:text-foreground group-hover:flex"
          onClick={() => onRename(item.node.id, item.node.title)}
        >
          <Pencil className="h-3 w-3" />
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
      {missingGap && onAddGapFill !== undefined && pairKey !== null ? (
        <div style={{ paddingLeft: `${depth * 14 + 26}px` }} className="py-0.5">
          <button
            type="button"
            className="text-primary text-xs hover:underline"
            onClick={() => onAddGapFill(pairKey, item.node.parentId)}
          >
            ＋ 新建查漏补缺
          </button>
        </div>
      ) : null}
      {isFolder && hasChildren && open ? (
        <ul>
          {item.children.map((child) => (
            <TreeRow
              key={child.node.id}
              item={child}
              depth={depth + 1}
              selectedId={selectedId}
              draggedId={draggedId}
              hint={hint}
              onSelect={onSelect}
              onDelete={onDelete}
              onRename={onRename}
              onDragStart={onDragStart}
              onDragOverRow={onDragOverRow}
              onDropRow={onDropRow}
              gapPairs={gapPairs}
              onAddGapFill={onAddGapFill}
            />
          ))}
        </ul>
      ) : null}
    </li>
  )
}

/** 个人知识库的文档目录树：支持拖拽排序/移动与重命名。 */
export function DocTree({
  nodes,
  selectedId,
  onSelect,
  onDelete,
  onRename,
  onMove,
  onAddGapFill,
  categoryFilter,
}: DocTreeProps) {
  const [draggedId, setDraggedId] = useState<number | null>(null)
  const [hint, setHint] = useState<{ id: number; pos: DropPos } | null>(null)
  const visibleNodes = filterByCategory(nodes, categoryFilter ?? null)
  const roots = buildForest(visibleNodes)
  const gapPairs = new Set<string>()
  for (const node of visibleNodes) {
    if (node.kind === 'doc' && node.docType === 'gap_fill' && node.pairKey !== null) {
      gapPairs.add(`${node.parentId ?? 'root'}:${node.pairKey}`)
    }
  }

  function siblingsOf(parentId: number | null): NoteNodeRow[] {
    return nodes
      .filter((node) => node.parentId === parentId && node.id !== draggedId)
      .sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
  }

  function resolveDrop(targetId: number): { parentId: number | null; index: number } | null {
    const target = nodes.find((node) => node.id === targetId)
    if (target === undefined || hint === null || hint.id !== targetId) {
      return null
    }
    if (hint.pos === 'into') {
      return { parentId: target.id, index: siblingsOf(target.id).length }
    }
    const siblings = siblingsOf(target.parentId)
    const at = siblings.findIndex((node) => node.id === targetId)
    const base = at < 0 ? siblings.length : at
    return { parentId: target.parentId, index: hint.pos === 'before' ? base : base + 1 }
  }

  function onDragOverRow(id: number, isFolder: boolean, event: DragEvent<HTMLDivElement>): void {
    if (draggedId === null || draggedId === id) {
      return
    }
    event.preventDefault()
    event.stopPropagation()
    const rect = event.currentTarget.getBoundingClientRect()
    const ratio = (event.clientY - rect.top) / rect.height
    let pos: DropPos
    if (isFolder) {
      pos = ratio < 0.25 ? 'before' : ratio > 0.75 ? 'after' : 'into'
    } else {
      pos = ratio < 0.5 ? 'before' : 'after'
    }
    setHint({ id, pos })
  }

  function onDropRow(id: number): void {
    const target = resolveDrop(id)
    if (target !== null && draggedId !== null) {
      onMove(draggedId, target.parentId, target.index)
    }
    setHint(null)
    setDraggedId(null)
  }

  if (roots.length === 0) {
    return <p className="px-2 py-4 text-sm text-muted-foreground">这个知识库还是空的，新建一篇文档吧。</p>
  }

  return (
    <ul
      className="space-y-0.5"
      onDragOver={(event) => {
        if (draggedId !== null) {
          event.preventDefault()
        }
      }}
      onDrop={(event) => {
        // 落到树的空白处：移到库根末尾。
        event.preventDefault()
        if (draggedId !== null) {
          onMove(draggedId, null, siblingsOf(null).length)
        }
        setHint(null)
        setDraggedId(null)
      }}
    >
      {roots.map((item) => (
        <TreeRow
          key={item.node.id}
          item={item}
          depth={0}
          selectedId={selectedId}
          draggedId={draggedId}
          hint={hint}
          onSelect={onSelect}
          onDelete={onDelete}
          onRename={onRename}
          onDragStart={setDraggedId}
          onDragOverRow={onDragOverRow}
          onDropRow={onDropRow}
          gapPairs={gapPairs}
          onAddGapFill={onAddGapFill}
        />
      ))}
    </ul>
  )
}
