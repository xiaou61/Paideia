import { describe, expect, it } from 'vitest'

import { parseSketch } from './schema'

/**
 * 手绘图的契约（WORK-051）。
 *
 * <p>重点是**可定位的拒绝**：作者写错时得到的是一条指明字段的中文错误，而不是白屏。
 * "画出来好不好看"不在这里测——那是 e2e 与人工看图的事。
 */

const MINIMAL = { rows: [[{ id: 'a', title: '起点' }]] }

describe('手绘图的契约', () => {
  it('最简图：一行一个框，tone 缺省为 gray、arrows 缺省为空', () => {
    const result = parseSketch(JSON.stringify(MINIMAL))
    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }
    expect(result.value.rows).toHaveLength(1)
    expect(result.value.rows[0]?.[0]).toMatchObject({ id: 'a', title: '起点', tone: 'gray' })
    expect(result.value.arrows).toEqual([])
    expect(result.value.title).toBeUndefined()
  })

  it('JSON 语法错给出可定位的提示', () => {
    const result = parseSketch('{ "rows": [ [ {"id":"a","title":"起点"} ] }')
    expect(result.ok).toBe(false)
    if (result.ok) {
      return
    }
    expect(result.error).toContain('不是合法 JSON')
  })

  it('tone 只接受五种，写错时指出允许值', () => {
    const result = parseSketch(JSON.stringify({ rows: [[{ id: 'a', title: '起点', tone: 'red' }]] }))
    expect(result.ok).toBe(false)
    if (result.ok) {
      return
    }
    expect(result.error).toContain('gray / blue / amber / green / violet')
  })

  it('箭头指向不存在的节点时，错误里带那个 id', () => {
    const result = parseSketch(
      JSON.stringify({
        rows: [[{ id: 'a', title: '起点' }], [{ id: 'b', title: '终点' }]],
        arrows: [{ from: 'a', to: 'nope' }],
      }),
    )
    expect(result.ok).toBe(false)
    if (result.ok) {
      return
    }
    expect(result.error).toContain('nope')
  })

  it('节点 id 重复被拒绝', () => {
    const result = parseSketch(
      JSON.stringify({ rows: [[{ id: 'a', title: '甲' }, { id: 'a', title: '乙' }]] }),
    )
    expect(result.ok).toBe(false)
    if (result.ok) {
      return
    }
    expect(result.error).toContain('重复')
  })

  it('空行与空图被拒绝', () => {
    expect(parseSketch(JSON.stringify({ rows: [] })).ok).toBe(false)
    expect(parseSketch(JSON.stringify({ rows: [[]] })).ok).toBe(false)
  })

  it('超过总框数上限被拒绝，并报出当前数量', () => {
    const rows = Array.from({ length: 4 }, (_, row) =>
      Array.from({ length: 4 }, (_, index) => ({ id: `n${row}${index}`, title: '框' })),
    )
    const result = parseSketch(JSON.stringify({ rows }))
    expect(result.ok).toBe(false)
    if (result.ok) {
      return
    }
    expect(result.error).toContain('16')
  })
})
