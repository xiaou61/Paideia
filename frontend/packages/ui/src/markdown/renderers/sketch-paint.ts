import type { Sketch } from './schema'
import { SKETCH_METRICS, type PlacedArrow, type SketchGeometry } from './sketch-layout'

/**
 * 把布局结果画成 SVG（WORK-051）。
 *
 * <p>单独一个文件是为了让"画"不依赖 React：测试可以直接对一棵 `<svg>` 断言 `path` 的 `d`，
 * 不必挂载组件。
 *
 * <p>**抖动必须确定性**：rough.js 不给 seed 就用随机数，同一份内容刷新两次会长得不一样。
 * 这里所有 seed 都由节点 id / 箭头两端派生。
 */

const NS = 'http://www.w3.org/2000/svg'

function seedOf(text: string, salt: number): number {
  let hash = 2166136261
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return ((hash >>> 0) + salt * 7919) % 2147483647
}

function textNode(content: string, x: number, y: number, size: number, fill: string, weight?: number): SVGTextElement {
  const element = document.createElementNS(NS, 'text')
  element.setAttribute('x', String(x))
  element.setAttribute('y', String(y))
  element.setAttribute('font-size', String(size))
  element.setAttribute('fill', fill)
  element.setAttribute('text-anchor', 'middle')
  element.setAttribute('dominant-baseline', 'central')
  if (weight !== undefined) {
    element.setAttribute('font-weight', String(weight))
  }
  element.textContent = content
  return element
}

function pathOf(points: Array<[number, number]>): string {
  return points.map(([x, y], index) => `${index === 0 ? 'M' : 'L'}${x} ${y}`).join(' ')
}

/** 箭头头：从最后一个点往回画两条短线，角度 ±0.42 rad。 */
function arrowHead(points: Array<[number, number]>): Array<Array<[number, number]>> {
  const [tipX, tipY] = points[points.length - 1]!
  const [prevX, prevY] = points[points.length - 2]!
  const angle = Math.atan2(tipY - prevY, tipX - prevX)
  return [0.42, -0.42].map((delta) => [
    [tipX, tipY],
    [tipX - 16 * Math.cos(angle + delta), tipY - 16 * Math.sin(angle + delta)],
  ])
}

/**
 * 标签落在最长的一段**横线**上方：竖线旁边贴着框，字会压到框里。
 * 一律放上方而不放下方，是因为画布高度只算到线段——放到线下面会被裁掉。
 */
function labelSpot(points: Array<[number, number]>): [number, number] {
  let index = 0
  let longest = -1
  for (let cursor = 0; cursor < points.length - 1; cursor += 1) {
    const [x1, y1] = points[cursor]!
    const [x2, y2] = points[cursor + 1]!
    if (Math.abs(y2 - y1) > 1) {
      continue
    }
    const length = Math.abs(x2 - x1)
    if (length > longest) {
      longest = length
      index = cursor
    }
  }
  const [x1, y1] = points[index]!
  const [x2] = points[index + 1]!
  return [(x1 + x2) / 2, y1 - 12]
}

export async function paintSketch(
  svg: SVGSVGElement,
  geometry: SketchGeometry,
  sketch: Sketch,
): Promise<void> {
  const rough = (await import('roughjs')).default
  const painter = rough.svg(svg)

  while (svg.firstChild !== null) {
    svg.removeChild(svg.firstChild)
  }

  // 先画线，框盖在上面：循环箭头绕到下方也不会穿进框里。
  for (const placed of geometry.arrows) {
    const key = `${placed.arrow.from}->${placed.arrow.to}`
    const group = document.createElementNS(NS, 'g')
    group.setAttribute('data-arrow', key)
    group.appendChild(
      painter.path(pathOf(placed.points), {
        stroke: 'var(--sk-ink)',
        strokeWidth: 2,
        roughness: 1.2,
        bowing: 1,
        seed: seedOf(key, 1),
      }),
    )
    for (const [index, head] of arrowHead(placed.points).entries()) {
      group.appendChild(
        painter.line(head[0]![0], head[0]![1], head[1]![0], head[1]![1], {
          stroke: 'var(--sk-ink)',
          strokeWidth: 2,
          roughness: 1,
          seed: seedOf(key, 2 + index),
        }),
      )
    }
    if (placed.arrow.label !== undefined) {
      const [labelX, labelY] = labelSpot(placed.points)
      group.appendChild(textNode(placed.arrow.label, labelX, labelY, 13, 'var(--sk-muted)'))
    }
    svg.appendChild(group)
  }

  for (const placed of geometry.nodes) {
    const group = document.createElementNS(NS, 'g')
    group.setAttribute('data-node', placed.node.id)
    group.appendChild(
      painter.rectangle(placed.x, placed.y, placed.w, placed.h, {
        stroke: `var(--sk-${placed.node.tone}-line)`,
        fill: `var(--sk-${placed.node.tone}-fill)`,
        fillStyle: 'solid',
        strokeWidth: 2,
        roughness: 1.4,
        bowing: 1.1,
        seed: seedOf(placed.node.id, 3),
      }),
    )
    const centerX = placed.x + placed.w / 2
    const titleCenterY = placed.y + SKETCH_METRICS.padY + SKETCH_METRICS.lineH / 2
    group.appendChild(textNode(placed.node.title, centerX, titleCenterY, SKETCH_METRICS.titleSize, 'var(--sk-ink)', 600))
    if (placed.node.sub !== undefined) {
      group.appendChild(
        textNode(
          placed.node.sub,
          centerX,
          titleCenterY + SKETCH_METRICS.lineH / 2 + SKETCH_METRICS.subLineH / 2,
          SKETCH_METRICS.subSize,
          'var(--sk-muted)',
        ),
      )
    }
    svg.appendChild(group)
  }

  if (geometry.titleY !== null && sketch.title !== undefined) {
    svg.appendChild(
      textNode(sketch.title, geometry.width / 2, geometry.titleY, SKETCH_METRICS.titleSize, 'var(--sk-ink)', 600),
    )
  }

  svg.setAttribute('viewBox', `0 0 ${geometry.width} ${geometry.height}`)
}
