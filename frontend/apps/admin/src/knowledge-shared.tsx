import { createKnowledgeAdminApi, type CategoryNode } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Spinner,
} from '@paideia/ui'
import { TriangleAlert } from 'lucide-react'

import { api } from './api'

/**
 * 分类管理与条目管理共用的东西。
 *
 * <p>两个页面各占一个菜单项，但仍然共用同一份分类树：**条目要挂到分类上**，所以拆分界面
 * 不等于拆开数据。共享的是"怎么把树摊平、怎么列成下拉、删除怎么确认"这三件事，
 * 页面各自的表格、工具栏与弹窗仍留在各自的文件里。
 */
export const admin = createKnowledgeAdminApi(api)

export interface FlatCategory {
  id: number
  name: string
  slug: string
  /** `course` / `reading` 是学习线，null 是手工分类。 */
  lineKind: string | null
  depth: number
  /** 上级分类名：表格里显式列出来，光靠缩进说不清"缩进到哪一层算谁的孩子"。 */
  parentName: string | null
}

export function flatten(nodes: CategoryNode[], depth = 0, parentName: string | null = null): FlatCategory[] {
  return nodes.flatMap((node) => [
    { id: node.id, name: node.name, slug: node.slug, lineKind: node.lineKind ?? null, depth, parentName },
    ...flatten(node.children, depth + 1, node.name),
  ])
}

/**
 * 分类下拉的分组：`null` 是"未分类/根分类"，用 `null` 而不是空串——空串是一个合法值，当哨兵会混淆。
 *
 * <p>分类树只有两层，可 21 条学习线全是并列的一级分类、还混着手工分类，平铺下来要滚十几屏。
 * 按学习线自己的类型分两档（实战课 / 阅读线），其余归「其他分类」；层级仍用缩进表示。
 */
export function categoryGroups(flat: FlatCategory[]) {
  return [
    { label: '实战课', kind: 'course' as string | null },
    { label: '阅读线', kind: 'reading' as string | null },
    { label: '其他分类', kind: null as string | null },
  ]
    .map(({ label, kind }) => ({
      label,
      options: flat
        .filter((category) => category.lineKind === kind)
        .map((category) => ({
          value: category.id as number | null,
          label: `${'\u3000'.repeat(category.depth)}${category.name}`,
        })),
    }))
    .filter((group) => group.options.length > 0)
}

export function Loading({ label }: { label: string }) {
  return (
    <p className="text-muted-foreground flex items-center gap-2 px-(--card-spacing) pb-3 text-sm">
      <Spinner />
      {label}
    </p>
  )
}

/**
 * 删除前的二次确认。
 *
 * <p>两个列表共用：删除不可撤销，一次误点就该被拦下，不该指望用户"下次小心"。
 */
export function ConfirmDeleteDialog({
  open,
  title = '确认删除',
  label,
  name,
  hint,
  confirmLabel = '删除',
  busy,
  onCancel,
  onConfirm,
}: {
  open: boolean
  /** 标题与确认按钮可定制："下架一条分享"与"删除一条条目"不是同一件事，文案不该硬编。 */
  title?: string
  label: string
  name: string
  hint?: string
  confirmLabel?: string
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  if (!open) {
    return null
  }
  return (
    <Dialog open onOpenChange={() => onCancel()}>
      <DialogContent className="sm:max-w-md" data-testid="admin-delete-dialog">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <TriangleAlert className="text-destructive size-4" />
            {title}
          </DialogTitle>
          <DialogDescription>
            将删除{label}「{name}」。这一步没有撤销，请确认。
          </DialogDescription>
        </DialogHeader>
        {hint === undefined ? null : (
          <Alert variant="destructive">
            <AlertTitle>注意</AlertTitle>
            <AlertDescription>{hint}</AlertDescription>
          </Alert>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={onCancel} data-testid="admin-delete-cancel">
            取消
          </Button>
          <Button variant="destructive" disabled={busy} onClick={onConfirm} data-testid="admin-delete-confirm">
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
