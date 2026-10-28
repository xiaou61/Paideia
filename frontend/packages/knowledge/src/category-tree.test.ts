import { describe, expect, it } from 'vitest'

import { findCategoryBySlug } from './category-tree'
import type { CategoryNode } from './types'

function node(slug: string, children: CategoryNode[] = []): CategoryNode {
  return { id: slug.length, name: slug, slug, children }
}

describe('findCategoryBySlug（WORK-046）', () => {
  it('能找到顶层分类', () => {
    const tree = [node('books'), node('jvm')]
    expect(findCategoryBySlug(tree, 'jvm')?.slug).toBe('jvm')
  })

  it('能找到挂在父分类下的子分类——书籍就是这一层', () => {
    const tree = [node('books', [node('books-man-month')]), node('jvm')]
    expect(findCategoryBySlug(tree, 'books-man-month')?.slug).toBe('books-man-month')
  })

  it('多层嵌套也能找到', () => {
    const tree = [node('books', [node('2026', [node('books-deep')])])]
    expect(findCategoryBySlug(tree, 'books-deep')?.slug).toBe('books-deep')
  })

  it('找不到时返回 null，不抛错', () => {
    expect(findCategoryBySlug([node('books')], 'nope')).toBeNull()
    expect(findCategoryBySlug([], 'nope')).toBeNull()
  })
})
