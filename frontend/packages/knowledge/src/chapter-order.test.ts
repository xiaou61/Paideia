import { describe, expect, it } from 'vitest'

import { orderChapters } from './chapter-order'

describe('orderChapters（WORK-046）', () => {
  it('按 sortOrder 升序，与接口的返回顺序无关', () => {
    const items = [
      { id: 9, sortOrder: 2 },
      { id: 7, sortOrder: 0 },
      { id: 8, sortOrder: 1 },
    ]
    expect(orderChapters(items).map((item) => item.id)).toEqual([7, 8, 9])
  })

  it('缺 sortOrder 的条目排最后，不插进章节中间', () => {
    const items = [
      { id: 1, sortOrder: null },
      { id: 2, sortOrder: 1 },
      { id: 3, sortOrder: 0 },
    ]
    expect(orderChapters(items).map((item) => item.id)).toEqual([3, 2, 1])
  })

  it('同序时按 id 兜底，同一批数据两次排序结果一致', () => {
    const items = [
      { id: 5, sortOrder: 1 },
      { id: 4, sortOrder: 1 },
    ]
    expect(orderChapters(items).map((item) => item.id)).toEqual([4, 5])
  })

  it('不改原数组：调用方可能还在用接口给的顺序', () => {
    const items = [
      { id: 2, sortOrder: 1 },
      { id: 1, sortOrder: 0 },
    ]
    orderChapters(items)
    expect(items.map((item) => item.id)).toEqual([2, 1])
  })
})
