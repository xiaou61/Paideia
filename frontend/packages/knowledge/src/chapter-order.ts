import type { EntrySummary } from './types'

/**
 * 按作者声明的站序排章节（WORK-046）。
 *
 * <p>列表接口固定按发布时间倒序返回，对"按书里的顺序读"是反的；这里在客户端重排。
 *
 * <p>`sortOrder` 缺失（手工建的条目）一律排最后，不让它插进章节中间；同序时按 id 兜底，
 * 保证同一批数据两次渲染的顺序一致。
 */
export function orderChapters<T extends Pick<EntrySummary, 'id' | 'sortOrder'>>(items: T[]): T[] {
  const rank = (item: T) => (typeof item.sortOrder === 'number' ? item.sortOrder : Number.MAX_SAFE_INTEGER)
  return [...items].sort((left, right) => rank(left) - rank(right) || left.id - right.id)
}
