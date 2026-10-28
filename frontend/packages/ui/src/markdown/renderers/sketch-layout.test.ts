import { describe, expect, it } from 'vitest'

import type { Sketch, SketchNode } from './schema'
import { layoutSketch, SKETCH_METRICS, type SketchMeasure } from './sketch-layout'

/**
 * 布局（WORK-051）：宽度夹取、行居中、箭头走线。
 *
 * <p>量宽用桩（每字 = 字号），这样断言的是**算法**而不是某台机器的字体度量。
 */

const measure: SketchMeasure = (text, size) => [...text].length * size

const node = (id: string, title: string, sub?: string): SketchNode => ({ id, title, sub, tone: 'gray' })
const sketchOf = (rows: SketchNode[][], arrows: Sketch['arrows'] = []): Sketch => ({ rows, arrows })

describe('手绘图布局', () => {
  it('同一行的框等距，整行在最宽行里居中', () => {
    const geometry = layoutSketch(
      sketchOf([[node('solo', '一个')], [node('a', '甲甲'), node('b', '乙乙'), node('c', '丙丙')]]),
      measure,
    )
    const [solo, a, b, c] = geometry.nodes
    const center = (item: { x: number; w: number }) => item.x + item.w / 2

    // 行内等距
    expect(center(b!) - center(a!)).toBeCloseTo(center(c!) - center(b!), 5)
    // 只有两个框的行居中于整幅画布
    expect(center(solo!)).toBeCloseTo(geometry.width / 2, 5)
    // 最宽行的左右留白相等
    expect(a!.x - SKETCH_METRICS.marginX).toBeCloseTo(
      geometry.width - (c!.x + c!.w) - SKETCH_METRICS.marginX,
      5,
    )
  })

  it('宽度被夹在上下限之间', () => {
    const geometry = layoutSketch(
      sketchOf([[node('tiny', '一'), node('huge', '很长很长很长很长很长的标题')]]),
      measure,
    )
    expect(geometry.nodes[0]!.w).toBe(SKETCH_METRICS.minW)
    expect(geometry.nodes[1]!.w).toBe(SKETCH_METRICS.maxW)
  })

  it('跨行箭头出底进顶', () => {
    const geometry = layoutSketch(sketchOf([[node('a', '甲')], [node('b', '乙')]], [{ from: 'a', to: 'b', style: 'straight' }]), measure)
    const [a, b] = geometry.nodes
    const points = geometry.arrows[0]!.points
    expect(points[0]![1]).toBeCloseTo(a!.y + a!.h, 5)
    expect(points[points.length - 1]![1]).toBeCloseTo(b!.y, 5)
  })

  it('back 箭头从图下方绕回来，不穿过框', () => {
    const geometry = layoutSketch(
      sketchOf([[node('a', '甲'), node('b', '乙')]], [{ from: 'b', to: 'a', style: 'back' }]),
      measure,
    )
    const rowBottom = Math.max(...geometry.nodes.map((item) => item.y + item.h))
    const lowest = Math.max(...geometry.arrows[0]!.points.map(([, y]) => y))
    expect(lowest).toBeGreaterThan(rowBottom)
    // 画布高度要把绕行那段包进来，否则箭头被裁掉
    expect(geometry.height).toBeGreaterThan(lowest)
  })

  it('同一行但箭头反向时也走下方，不从字上穿过去', () => {
    const geometry = layoutSketch(
      sketchOf([[node('a', '甲'), node('b', '乙')]], [{ from: 'b', to: 'a', style: 'straight' }]),
      measure,
    )
    const rowCenter = geometry.nodes[0]!.y + geometry.nodes[0]!.h / 2
    expect(geometry.arrows[0]!.points.every(([, y]) => y !== rowCenter)).toBe(true)
  })
})
