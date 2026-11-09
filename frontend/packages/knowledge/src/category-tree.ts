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

/**
 * 按 id 找**从根到它的整条路径**（含自己）。
 *
 * <p>面包屑要它。注意这不能写成 `categories.find(...)`：那只在顶层数组里找，二级分类
 * （比如「操作系统」挂在「计算机基础」下）会直接找不到——2026-11-09 就是这样一个真实缺陷：
 * 二级分类页的标题退化成「分类」，面包屑也就无从谈起。
 */
export function findCategoryPath(nodes: CategoryNode[], id: number): CategoryNode[] {
  for (const node of nodes) {
    if (node.id === id) {
      return [node]
    }
    const child = findCategoryPath(node.children, id)
    if (child.length > 0) {
      return [node, ...child]
    }
  }
  return []
}
