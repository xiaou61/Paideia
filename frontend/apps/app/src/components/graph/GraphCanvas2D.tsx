import {
  circularLayout,
  loadPositions,
  savePositions,
  spreadApart,
  type ConceptEdge,
  type ConceptNode,
  type Point,
} from '@paideia/knowledge'
import { Spinner } from '@paideia/ui'
import { Maximize2, Minus, Plus } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

import { TIER_STYLE, tierStyle } from './tier-style'

interface Props {
  nodes: ConceptNode[]
  edges: ConceptEdge[]
  layoutKeyValue: string
  selectedId: number | null
  onSelect: (id: number) => void
}

/**
 * 坐标系：所有几何都在这个固定的「用户空间」里算（viewBox），再交给 SVG 自动映射到容器。
 * 这样布局与容器实际像素解耦，缩放 / 平移只要改 viewBox 即可。
 */
const VIEW_W = 1000
const VIEW_H = 660
const PAD = 76

const ARROW_ID = 'paideia-graph-arrow'

interface PlacedNode {
  node: ConceptNode
  x: number
  y: number
  r: number
}

interface PlacedEdge {
  id: string
  x1: number
  y1: number
  x2: number
  y2: number
  kind: 'prerequisite' | 'related'
}

interface View {
  x: number
  y: number
  w: number
  h: number
}

const BASE_VIEW: View = { x: 0, y: 0, w: VIEW_W, h: VIEW_H }

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * 2D 图谱画布（WORK-021，WORK-082 重写为 SVG）。
 *
 * <p><b>为什么从 sigma 换成自研 SVG</b>：原实现用 sigma 4（WebGL）。实测线上只有 14 个节点，
 * 但首次进入会出现**约 6 秒的主线程长任务**——sigma 4 的 SDF 字形图集（逐字 EDT 距离场）
 * 与超大标签着色器几乎全耗在这里。图谱数据规模很小，完全不需要 WebGL：用真实 DOM/SVG
 * 渲染，零 WebGL 依赖、无着色器编译、中文标签清晰不糊，交互与样式也完全可控。
 *
 * <p>坐标仍由 `@paideia/knowledge` 的布局工具算（forceAtlas2 + 存档），保证「同一个点一直在那片」。
 * `graphology` 只在**首次没有存档、需要算力导向**时才动态 import；常规回访直接读存档，
 * 连图库都不会加载。
 */
export function GraphCanvas2D({ nodes, edges, layoutKeyValue, selectedId, onSelect }: Props) {
  const svgRef = useRef<SVGSVGElement | null>(null)
  const [scene, setScene] = useState<{ nodes: PlacedNode[]; edges: PlacedEdge[] } | null>(null)
  const [view, setView] = useState<View>(BASE_VIEW)
  const [hoverId, setHoverId] = useState<number | null>(null)

  // 计算布局（异步：先让壳画出来，再做重活）
  useEffect(() => {
    let cancelled = false
    setScene(null)

    void (async () => {
      // 先让出一帧：页面先显示容器与「正在绘制」，避免首帧空白像卡死
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
      if (cancelled) return

      let positions = loadPositions(layoutKeyValue)
      if (positions === null) {
        positions = await computeForceLayout(nodes, edges)
        if (cancelled) return
        positions = spreadApart(positions)
        savePositions(layoutKeyValue, positions)
      }
      if (cancelled) return
      setScene(buildScene(nodes, edges, positions))
    })()

    return () => {
      cancelled = true
    }
  }, [layoutKeyValue, nodes, edges])

  const resetView = useCallback(() => setView(BASE_VIEW), [])

  const zoomBy = useCallback((factor: number) => {
    setView((v) => {
      const nw = clamp(v.w * factor, VIEW_W * 0.2, VIEW_W * 3)
      const k = nw / v.w
      return {
        x: v.x + (v.w - nw) / 2,
        y: v.y + (v.h - v.h * k) / 2,
        w: nw,
        h: v.h * k,
      }
    })
  }, [])

  // 滚轮缩放：必须在原生监听里 preventDefault（React 的 onWheel 是被动监听）
  useEffect(() => {
    const svg = svgRef.current
    if (svg === null) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const toUser = makeUserMapper(svg)
      if (toUser === null) return
      const p = toUser(event.clientX, event.clientY)
      const factor = event.deltaY < 0 ? 0.85 : 1 / 0.85
      setView((v) => {
        const nw = clamp(v.w * factor, VIEW_W * 0.2, VIEW_W * 3)
        const k = nw / v.w
        return {
          x: p.x - (p.x - v.x) * k,
          y: p.y - (p.y - v.y) * k,
          w: nw,
          h: v.h * k,
        }
      })
    }
    svg.addEventListener('wheel', onWheel, { passive: false })
    return () => svg.removeEventListener('wheel', onWheel)
  }, [])

  // 拖动平移 + 空白点击取消选中
  const dragRef = useRef<{
    pointerId: number
    startX: number
    startY: number
    startView: View
    inverse: DOMMatrix
    startUser: { x: number; y: number }
    moved: boolean
  } | null>(null)

  const onPointerDown = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (event.button !== 0) return
    // 点在节点上时不进入平移：节点的 onClick 自己处理选中
    if ((event.target as Element).closest('[data-graph-node]') !== null) return
    const svg = svgRef.current
    if (svg === null) return
    const ctm = svg.getScreenCTM()
    if (ctm === null) return
    const inverse = ctm.inverse()
    dragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startView: view,
      inverse,
      startUser: applyMatrix(inverse, event.clientX, event.clientY),
      moved: false,
    }
    svg.setPointerCapture(event.pointerId)
  }

  const onPointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    const drag = dragRef.current
    if (drag === null || drag.pointerId !== event.pointerId) return
    if (!drag.moved) {
      const dx = event.clientX - drag.startX
      const dy = event.clientY - drag.startY
      if (Math.hypot(dx, dy) < 4) return
      drag.moved = true
    }
    const user = applyMatrix(drag.inverse, event.clientX, event.clientY)
    setView({
      x: drag.startView.x - (user.x - drag.startUser.x),
      y: drag.startView.y - (user.y - drag.startUser.y),
      w: drag.startView.w,
      h: drag.startView.h,
    })
  }

  const onPointerUp = (event: ReactPointerEvent<SVGSVGElement>) => {
    const drag = dragRef.current
    if (drag === null || drag.pointerId !== event.pointerId) return
    svgRef.current?.releasePointerCapture(event.pointerId)
    dragRef.current = null
    if (!drag.moved) {
      // 点空白处：取消选中
      onSelect(-1)
    }
  }

  return (
    <div className="relative h-full w-full">
      <svg
        ref={svgRef}
        className="h-full w-full touch-none select-none"
        viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`}
        preserveAspectRatio="xMidYMid meet"
        role="application"
        aria-label="知识图谱"
        data-testid="graph-canvas-2d"
        data-selected={selectedId ?? ''}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        style={{ cursor: dragRef.current?.moved === true ? 'grabbing' : 'grab' }}
      >
        <defs>
          <marker
            id={ARROW_ID}
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="6"
            markerHeight="6"
            orient="auto-start-reverse"
          >
            <path d="M0,0 L10,5 L0,10 z" style={{ fill: 'var(--muted-foreground)' }} />
          </marker>
        </defs>

        {scene === null ? null : (
          <>
            <g aria-hidden="true">
              {scene.edges.map((edge) =>
                edge.kind === 'prerequisite' ? (
                  <line
                    key={edge.id}
                    data-testid="graph-edge"
                    x1={edge.x1}
                    y1={edge.y1}
                    x2={edge.x2}
                    y2={edge.y2}
                    strokeWidth={1.5}
                    markerEnd={`url(#${ARROW_ID})`}
                    style={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.55 }}
                  />
                ) : (
                  <line
                    key={edge.id}
                    data-testid="graph-edge"
                    x1={edge.x1}
                    y1={edge.y1}
                    x2={edge.x2}
                    y2={edge.y2}
                    strokeWidth={1.2}
                    strokeDasharray="5 5"
                    style={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.32 }}
                  />
                ),
              )}
            </g>

            {scene.nodes.map(({ node, x, y, r }) => {
              const selected = selectedId === node.id
              const dimmed = selectedId !== null && !selected
              const hovered = hoverId === node.id
              const tier = TIER_STYLE[node.tier] ?? TIER_STYLE.unknown
              const lines = splitLabel(node.name)
              const lineHeight = 15
              const baseY = y + r + 14 - ((lines.length - 1) * lineHeight) / 2
              return (
                <g
                  key={node.id}
                  data-graph-node=""
                  data-testid="graph-node"
                  data-id={node.id}
                  role="button"
                  tabIndex={0}
                  aria-label={`${node.name}，${tierStyle(node.tier).label}`}
                  aria-pressed={selected}
                  onPointerEnter={() => setHoverId(node.id)}
                  onPointerLeave={() => setHoverId((id) => (id === node.id ? null : id))}
                  onFocus={() => setHoverId(node.id)}
                  onBlur={() => setHoverId((id) => (id === node.id ? null : id))}
                  onClick={(event) => {
                    event.stopPropagation()
                    onSelect(node.id)
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      onSelect(node.id)
                    }
                  }}
                  style={{
                    cursor: 'pointer',
                    opacity: dimmed ? 0.38 : 1,
                    transition: 'opacity 140ms ease',
                    // 键盘聚焦用自绘的悬停圈表示（见上），去掉浏览器给 <g> 套的方框
                    outline: 'none',
                  }}
                >
                  {selected ? (
                    <circle
                      cx={x}
                      cy={y}
                      r={r + 5}
                      fill="none"
                      strokeWidth={2}
                      style={{ stroke: 'var(--foreground)' }}
                    />
                  ) : hovered ? (
                    <circle
                      cx={x}
                      cy={y}
                      r={r + 3.5}
                      fill="none"
                      strokeWidth={1.5}
                      style={{ stroke: 'var(--foreground)', strokeOpacity: 0.45 }}
                    />
                  ) : null}
                  <circle
                    cx={x}
                    cy={y}
                    r={r}
                    strokeWidth={3}
                    style={{ fill: `var(${tier.cssVar})`, stroke: 'var(--card)' }}
                  />
                  {node.reviewDue ? (
                    <circle
                      cx={x + r * 0.72}
                      cy={y - r * 0.72}
                      r={3.6}
                      strokeWidth={1.5}
                      style={{ fill: 'var(--warning)', stroke: 'var(--card)' }}
                    />
                  ) : null}
                  <text
                    x={x}
                    y={baseY}
                    textAnchor="middle"
                    fontSize={13}
                    fontWeight={500}
                    pointerEvents="none"
                    style={{
                      fill: 'var(--foreground)',
                      paintOrder: 'stroke',
                      stroke: 'var(--card)',
                      strokeWidth: 3.5,
                      strokeLinejoin: 'round',
                    }}
                  >
                    {lines.map((line, index) => (
                      <tspan key={index} x={x} y={baseY + index * lineHeight}>
                        {line}
                      </tspan>
                    ))}
                  </text>
                </g>
              )
            })}
          </>
        )}
      </svg>

      {/* 缩放 / 复位 */}
      <div className="absolute top-3 right-3 flex flex-col overflow-hidden rounded-lg border shadow-sm" role="group" aria-label="图谱缩放">
        <button
          type="button"
          aria-label="放大"
          data-testid="graph-zoom-in"
          className="bg-card hover:bg-muted text-foreground flex size-8 items-center justify-center border-b"
          onClick={() => zoomBy(0.8)}
        >
          <Plus className="size-4" />
        </button>
        <button
          type="button"
          aria-label="缩小"
          data-testid="graph-zoom-out"
          className="bg-card hover:bg-muted text-foreground flex size-8 items-center justify-center border-b"
          onClick={() => zoomBy(1.25)}
        >
          <Minus className="size-4" />
        </button>
        <button
          type="button"
          aria-label="复位"
          data-testid="graph-zoom-fit"
          className="bg-card hover:bg-muted text-foreground flex size-8 items-center justify-center"
          onClick={resetView}
        >
          <Maximize2 className="size-4" />
        </button>
      </div>

      <p className="text-muted-foreground pointer-events-none absolute bottom-2 left-3 text-[11px]">
        滚轮缩放 · 拖动平移 · 点节点看详情
      </p>

      {scene === null ? (
        <p
          className="text-muted-foreground pointer-events-none absolute inset-0 flex items-center justify-center gap-2 text-sm"
          data-testid="graph-drawing"
        >
          <Spinner />
          正在绘制图谱…
        </p>
      ) : null}
    </div>
  )
}

/** 把节点坐标铺满固定 viewBox，节点半径随掌握度分级，边裁到圆边界并给箭头留位。 */
function buildScene(
  nodes: ConceptNode[],
  edges: ConceptEdge[],
  positions: Record<number, Point>,
): { nodes: PlacedNode[]; edges: PlacedEdge[] } {
  const raw = nodes.map((node) => ({ node, point: positions[node.id] ?? { x: 0, y: 0 } }))

  const xs = raw.map((item) => item.point.x)
  const ys = raw.map((item) => item.point.y)
  const minX = xs.length > 0 ? Math.min(...xs) : 0
  const maxX = xs.length > 0 ? Math.max(...xs) : 0
  const minY = ys.length > 0 ? Math.min(...ys) : 0
  const maxY = ys.length > 0 ? Math.max(...ys) : 0
  const spanX = maxX - minX
  const spanY = maxY - minY
  // 两个方向各自铺满（非等比）：图谱常见「大部分节点挤在一角、两个孤立点把包围盒拉长」，
  // 等比缩放会留出大片空白。坐标分开缩放不影响节点圆（半径是最终用户单位），只是把位置拉开。
  const scaleX = spanX > 0 ? (VIEW_W - 2 * PAD) / spanX : 1
  const scaleY = spanY > 0 ? (VIEW_H - 2 * PAD) / spanY : 1
  const offX = (VIEW_W - spanX * scaleX) / 2 - minX * scaleX
  const offY = (VIEW_H - spanY * scaleY) / 2 - minY * scaleY

  const placedNodes: PlacedNode[] = raw.map(({ node, point }) => ({
    node,
    x: point.x * scaleX + offX,
    y: point.y * scaleY + offY,
    r: node.tier === 'unknown' ? 9 : 11 + clamp(node.score, 0, 1) * 9,
  }))

  const byId = new Map(placedNodes.map((item) => [item.node.id, item]))
  const placedEdges: PlacedEdge[] = []
  const seen = new Set<string>()
  // 无向对只画一条：同一对概念可能既有 prerequisite 又有 related，后端 SQL 没有 ORDER BY，
  // 谁先到谁留下。先按「prerequisite 优先」排一遍，语义更强的那条边才不会被虚线顶掉。
  const orderedEdges = [...edges].sort((left, right) => {
    const rank = (type: string) => (type === 'prerequisite' ? 0 : 1)
    return rank(left.type) - rank(right.type)
  })
  orderedEdges.forEach((edge, index) => {
    if (edge.from === edge.to) return
    const a = byId.get(edge.from)
    const b = byId.get(edge.to)
    if (a === undefined || b === undefined) return
    const pair = edge.from < edge.to ? `${edge.from}|${edge.to}` : `${edge.to}|${edge.from}`
    if (seen.has(pair)) return
    seen.add(pair)
    const dx = b.x - a.x
    const dy = b.y - a.y
    const distance = Math.hypot(dx, dy) || 1
    const ux = dx / distance
    const uy = dy / distance
    const startGap = a.r + 2
    const endGap = b.r + (edge.type === 'prerequisite' ? 10 : 2)
    placedEdges.push({
      id: `${edge.type}-${index}`,
      x1: a.x + ux * startGap,
      y1: a.y + uy * startGap,
      x2: b.x - ux * endGap,
      y2: b.y - uy * endGap,
      kind: edge.type,
    })
  })

  return { nodes: placedNodes, edges: placedEdges }
}

/**
 * 算一次力导向布局。只有「没有存档」时才会走到这里（首次 / 重新布局），
 * 因此 `graphology` 与 `forceatlas2` 都是**此时才动态 import**——常规回访完全不加载。
 */
async function computeForceLayout(
  nodes: ConceptNode[],
  edges: ConceptEdge[],
): Promise<Record<number, Point>> {
  if (nodes.length <= 2) {
    return circularLayout(nodes)
  }
  try {
    const [graphology, layoutModule] = await Promise.all([
      import('graphology'),
      import('graphology-layout-forceatlas2'),
    ])
    const Graph = graphology.default
    const forceAtlas2 = layoutModule.default
    const graph = new Graph({ multi: false, type: 'directed' })

    const seed = circularLayout(nodes)
    nodes.forEach((node) => {
      const point = seed[node.id] ?? { x: 0, y: 0 }
      graph.addNode(String(node.id), { x: point.x, y: point.y })
    })
    const seen = new Set<string>()
    edges.forEach((edge, index) => {
      const from = String(edge.from)
      const to = String(edge.to)
      if (from === to || !graph.hasNode(from) || !graph.hasNode(to)) return
      const pair = from < to ? `${from}|${to}` : `${to}|${from}`
      if (seen.has(pair)) return
      seen.add(pair)
      graph.addEdgeWithKey(`edge-${index}`, from, to)
    })

    forceAtlas2.assign(graph as never, {
      iterations: 250,
      settings: {
        // 引力放大、斥力收敛：小图里孤立节点（如只挂了两条 unrelated 边的概念）会被推到很远，
        // 把包围盒拉长、其它节点挤成一团。加大 gravity 让整体更聚拢、分布更均匀。
        gravity: 1.4,
        scalingRatio: 12,
        barnesHutOptimize: true,
      },
    })
    const positions: Record<number, Point> = {}
    graph.forEachNode((id) => {
      positions[Number(id)] = {
        x: Number(graph.getNodeAttribute(id, 'x')),
        y: Number(graph.getNodeAttribute(id, 'y')),
      }
    })

    const complete = nodes.every((node) => Number.isFinite(positions[node.id]?.x))
    const distinct = new Set(
      Object.values(positions).map((point) => `${point.x.toFixed(2)},${point.y.toFixed(2)}`),
    ).size
    return complete && distinct > 1 ? positions : circularLayout(nodes)
  } catch {
    return circularLayout(nodes)
  }
}

/** 生成「客户端坐标 → 当前 viewBox 用户坐标」的映射函数。 */
function makeUserMapper(svg: SVGSVGElement): ((clientX: number, clientY: number) => Point) | null {
  const ctm = svg.getScreenCTM()
  if (ctm === null) return null
  const inverse = ctm.inverse()
  return (clientX, clientY) => applyMatrix(inverse, clientX, clientY)
}

function applyMatrix(matrix: DOMMatrix, clientX: number, clientY: number): Point {
  const point = scratchSvg().createSVGPoint()
  point.x = clientX
  point.y = clientY
  const result = point.matrixTransform(matrix)
  return { x: result.x, y: result.y }
}

/** 复用一个离屏 SVG 只为了拿 createSVGPoint：pointermove 每帧都调用，别每次新建元素。 */
let scratch: SVGSVGElement | null = null
function scratchSvg(): SVGSVGElement {
  if (scratch === null) {
    scratch = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  }
  return scratch
}

/**
 * 标签换行：中文标签过长时会横向压住邻居。超过 9 个字符就折成两行——
 * 优先在空格处断（"Lambda 与函数式接口"），没有空格就按中点断。
 */
function splitLabel(name: string): string[] {
  if (name.length <= 9) return [name]
  const middle = Math.floor(name.length / 2)
  // 优先在空格处断（"Stream 流水线" / "Lambda 与函数式接口"），空格落在中点附近才用
  let space = name.indexOf(' ', Math.max(2, middle - 2))
  if (space === -1) space = name.lastIndexOf(' ', middle)
  if (space > 2 && space < name.length - 2) {
    return [name.slice(0, space), name.slice(space + 1)]
  }
  return [name.slice(0, middle), name.slice(middle)]
}
