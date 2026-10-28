import type { Sketch, SketchArrow, SketchNode } from './schema'

/**
 * 手绘图的布局（WORK-051）——纯函数，量宽由调用方注入。
 *
 * <p>**为什么量宽是参数而不是直接调 canvas**：布局是这张图唯一有算法的地方（宽度夹取、行居中、
 * 箭头走线），它必须能在没有 canvas 的环境里被单测覆盖。浏览器传 canvas 量宽，
 * 测试传一个按字符数算的桩，两边跑的是同一份代码。
 *
 * <p>坐标**不来自作者**：框宽由文字量出来，行由这里算。作者只写"有哪些框、谁连谁"。
 */

export const SKETCH_FONT = 'Paideia Sketch'

export const SKETCH_METRICS = {
  padX: 22,
  padY: 14,
  gapX: 40,
  gapY: 54,
  minW: 110,
  maxW: 240,
  titleSize: 20,
  subSize: 15,
  titleTop: 28,
  titleGap: 24,
  lineH: 26,
  subLineH: 22,
  marginX: 8,
  marginY: 10,
  backOffset: 28,
} as const

export type SketchMeasure = (text: string, fontSize: number) => number

export interface PlacedNode {
  node: SketchNode
  x: number
  y: number
  w: number
  h: number
}

export interface PlacedArrow {
  arrow: SketchArrow
  points: Array<[number, number]>
}

export interface SketchGeometry {
  width: number
  height: number
  titleY: number | null
  nodes: PlacedNode[]
  arrows: PlacedArrow[]
}

/**
 * 量宽实现。
 *
 * 拿不到 canvas（happy-dom、极老的浏览器）时按"每字约 0.9 个字号"估——这**只**用于测试与降级，
 * 生产走真实度量。估错的后果是框和文字差几个像素，不会裁字（框总会留 padX）。
 */
export function makeMeasurer(): SketchMeasure {
  let context: CanvasRenderingContext2D | null = null
  try {
    context = document.createElement('canvas').getContext('2d')
  } catch {
    context = null
  }
  if (context === null) {
    return (text, fontSize) => [...text].length * fontSize * 0.9
  }
  const ctx = context
  return (text, fontSize) => {
    ctx.font = `${fontSize}px "${SKETCH_FONT}", sans-serif`
    return ctx.measureText(text).width
  }
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

function nodeWidth(node: SketchNode, measure: SketchMeasure): number {
  const title = measure(node.title, SKETCH_METRICS.titleSize)
  const sub = node.sub === undefined ? 0 : measure(node.sub, SKETCH_METRICS.subSize)
  return clamp(Math.max(title, sub) + SKETCH_METRICS.padX * 2, SKETCH_METRICS.minW, SKETCH_METRICS.maxW)
}

function nodeHeight(node: SketchNode): number {
  return SKETCH_METRICS.padY * 2 + SKETCH_METRICS.lineH + (node.sub === undefined ? 0 : SKETCH_METRICS.subLineH)
}

/**
 * 箭头走线。规则只有四条：
 * 同一行从左到右走直线；跨行走"出底/进顶 + 中点横移"的折线；
 * `back` 与"同一行但反向"从下方绕回来；其它情况都退化成竖直线。
 */
function route(from: PlacedNode, to: PlacedNode, style: SketchArrow['style']): Array<[number, number]> {
  const fromX = from.x + from.w / 2
  const fromY = from.y + from.h / 2
  const toX = to.x + to.w / 2
  const toY = to.y + to.h / 2

  if (style === 'back' || (Math.abs(fromY - toY) < 1 && toX < fromX)) {
    const bottom = Math.max(from.y + from.h, to.y + to.h) + SKETCH_METRICS.backOffset
    return [
      [fromX, from.y + from.h],
      [fromX, bottom],
      [toX, bottom],
      [toX, to.y + to.h],
    ]
  }
  if (Math.abs(fromY - toY) < 1) {
    return [
      [from.x + from.w, fromY],
      [to.x, toY],
    ]
  }
  const down = toY > fromY
  const fromEdge = down ? from.y + from.h : from.y
  const toEdge = down ? to.y : to.y + to.h
  if (Math.abs(fromX - toX) < 1) {
    return [
      [fromX, fromEdge],
      [toX, toEdge],
    ]
  }
  const midY = (fromEdge + toEdge) / 2
  return [
    [fromX, fromEdge],
    [fromX, midY],
    [toX, midY],
    [toX, toEdge],
  ]
}

export function layoutSketch(sketch: Sketch, measure: SketchMeasure): SketchGeometry {
  const titleY = sketch.title === undefined ? null : SKETCH_METRICS.titleTop
  let cursor = titleY === null ? SKETCH_METRICS.marginY : titleY + SKETCH_METRICS.titleGap

  // 先按行算宽高，再按最宽行做水平居中——两遍是必要的，居中方向依赖全局最宽值。
  const rows = sketch.rows.map((row) => {
    const nodes = row.map((node) => ({ node, w: nodeWidth(node, measure), h: nodeHeight(node) }))
    const width = nodes.reduce((sum, item) => sum + item.w, 0) + SKETCH_METRICS.gapX * (nodes.length - 1)
    const height = Math.max(...nodes.map((item) => item.h))
    return { nodes, width, height }
  })

  const rowWidth = Math.max(...rows.map((row) => row.width))
  const width = rowWidth + SKETCH_METRICS.marginX * 2

  const placed: PlacedNode[] = []
  for (const row of rows) {
    let x = SKETCH_METRICS.marginX + (rowWidth - row.width) / 2
    for (const item of row.nodes) {
      placed.push({ node: item.node, x, y: cursor + (row.height - item.h) / 2, w: item.w, h: item.h })
      x += item.w + SKETCH_METRICS.gapX
    }
    cursor += row.height + SKETCH_METRICS.gapY
  }

  const byId = new Map(placed.map((item) => [item.node.id, item]))
  const arrows: PlacedArrow[] = sketch.arrows.map((arrow) => ({
    arrow,
    points: route(byId.get(arrow.from)!, byId.get(arrow.to)!, arrow.style),
  }))

  const lowest = Math.max(
    cursor - SKETCH_METRICS.gapY,
    ...arrows.flatMap((item) => item.points.map(([, y]) => y)),
  )
  return { width, height: lowest + SKETCH_METRICS.marginY, titleY, nodes: placed, arrows }
}
