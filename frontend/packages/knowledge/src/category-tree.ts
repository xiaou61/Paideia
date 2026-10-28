import type { CategoryNode } from './types'

/**
 * 按 slug 在**整棵**分类树里找分类（WORK-046）。
 *
 * <p>书籍的子分类挂在 `books` 下面，不在顶层数组里——只用 `categories.find(...)` 会找不到，
 * 页面表现为"这本书还没有收录"。slug 在库里有唯一索引，所以深度优先找到的第一个就是它。
 */
export function findCategoryBySlug(nodes: CategoryNode[], slug: string): CategoryNode | null {
  for (const node of nodes) {
    if (node.slug === slug) {
      return node
    }
    const hit = findCategoryBySlug(node.children, slug)
    if (hit !== null) {
      return hit
    }
  }
  return null
}
