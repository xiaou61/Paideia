import { describe, expect, it } from 'vitest'

import type { Sketch } from './schema'
import { layoutSketch, type SketchMeasure } from './sketch-layout'
import { paintSketch } from './sketch-paint'

/**
 * 绘制（WORK-051）。这里最重要的是**确定性**：rough.js 不给 seed 就用随机数，
 * 同一份内容刷新两次会长得不一样；图是静态内容，那不可接受。
 *
 * 用真的 rough.js 画两遍再比 `path` 的 `d`，比断言"传了 seed"更能说明问题。
 */

const measure: SketchMeasure = (text, size) => [...text].length * size

const SKETCH: Sketch = {
  title: '一张小图',
  rows: [
    [
      { id: 'a', title: '起点', sub: '先看一眼', tone: 'blue' },
      { id: 'b', title: '终点', tone: 'amber' },
    ],
  ],
  arrows: [{ from: 'a', to: 'b', style: 'straight' }],
}

async function paintOnce(): Promise<SVGSVGElement> {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg') as SVGSVGElement
  await paintSketch(svg, layoutSketch(SKETCH, measure), SKETCH)
  return svg
}

const pathsOf = (svg: SVGSVGElement) =>
  [...svg.querySelectorAll('path')].map((path) => path.getAttribute('d'))

describe('手绘图绘制', () => {
  it('同一份内容画两次，每一条 path 的 d 完全一致', async () => {
    const first = pathsOf(await paintOnce())
    const second = pathsOf(await paintOnce())
    expect(first.length).toBeGreaterThan(0)
    expect(second).toEqual(first)
  })

  it('每个节点留一个 data-node 标记（框是两条 path，不能靠数 path 判断数量）', async () => {
    const svg = await paintOnce()
    expect(svg.querySelectorAll('[data-node]')).toHaveLength(2)
    expect(svg.querySelectorAll('path').length).toBeGreaterThan(2)
  })

  it('画完写上 viewBox，宽度来自布局而不是画布', async () => {
    const svg = await paintOnce()
    const geometry = layoutSketch(SKETCH, measure)
    expect(svg.getAttribute('viewBox')).toBe(`0 0 ${geometry.width} ${geometry.height}`)
  })
})
