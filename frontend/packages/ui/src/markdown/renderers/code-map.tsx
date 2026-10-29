import { useMemo } from 'react'
import type { CustomRendererProps } from 'streamdown'

import type { MapEdge, MapNode } from './schema'
import { parseCodeMap } from './schema'
import { BlockFallback, BlockFrame, Pill } from './blocks'
import { useSourceReading } from './context'

/**
 * 架构地图（WORK-014）。
 *
 * <p><b>手写定位、不引布局库</b>：作者给出 `layer`（从左到右）与 `order`（同一列内自上而下），
 * 我们直接算绝对坐标。这样节点是真正的 `<button>`——可聚焦、可回车、读屏能读，而 SVG 图库
 * 通常要额外补一套可访问性。体积也是零。
 *
 * <p><b>与走读双向联动</b>：节点的 `tour` + `step` 指向同页走读块。点节点 → 走读切到该步并
 * 滚动过去；走读切步 → 这里的 `activeStep` 变化 → 对应节点高亮。两边共用页级上下文。
 *
 * <p>节点多于 24 个通常说明这张图该拆成两张：这里不做自动布局，也不做缩放拖拽。
 */
const NODE_WIDTH = 176
const NODE_HEIGHT = 62
const COLUMN_GAP = 220
const ROW_GAP = 92

export function CodeMap({ code }: CustomRendererProps) {
  const parsed = useMemo(() => parseCodeMap(code), [code])
  const { activeStep, setActiveStep, onOpenEntry } = useSourceReading()

  if (!parsed.ok) {
    return <BlockFallback title="架构地图" error={parsed.error} raw={code} />
  }
  const map = parsed.value
  const positions = new Map(
    map.nodes.map((node) => [node.id, { x: 16 + node.layer * COLUMN_GAP, y: 16 + node.order * ROW_GAP }]),
  )
  const height = Math.max(...map.nodes.map((node) => (positions.get(node.id)?.y ?? 0) + NODE_HEIGHT)) + 24
  const width = Math.max(...map.nodes.map((node) => (positions.get(node.id)?.x ?? 0) + NODE_WIDTH)) + 24

  const isActive = (node: MapNode) =>
    node.tour !== undefined && node.step !== undefined && (activeStep[node.tour] ?? 0) === node.step

  const activate = (node: MapNode) => {
    if (node.entryId !== undefined) {
      onOpenEntry?.(node.entryId)
      return
    }
    if (node.tour !== undefined && node.step !== undefined) {
      setActiveStep(node.tour, node.step)
      document.getElementById(`tour-${node.tour}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' })
    }
  }

  return (
    <BlockFrame
      title={map.title ?? '架构地图'}
      testId="code-map"
      actions={<Pill>{map.nodes.length} 个节点 · 点击跳转</Pill>}
    >
      <div className="overflow-x-auto p-2" data-testid="code-map-canvas">
        <div className="relative" style={{ width, height, minWidth: '100%' }}>
          <svg className="pointer-events-none absolute inset-0" width={width} height={height} aria-hidden="true">
            <defs>
              <marker id="map-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" className="text-border" />
              </marker>
            </defs>
            {map.edges.map((edge, index) => (
              <Edge key={index} edge={edge} from={positions.get(edge.from)} to={positions.get(edge.to)} />
            ))}
          </svg>
          {map.nodes.map((node) => {
            const position = positions.get(node.id)
            if (position === undefined) {
              return null
            }
            const active = isActive(node)
            const interactive = node.tour !== undefined || node.entryId !== undefined
            return (
              <button
                key={node.id}
                type="button"
                data-testid={`code-map-node-${node.id}`}
                data-active={active ? 'true' : 'false'}
                disabled={!interactive}
                onClick={() => activate(node)}
                style={{ left: position.x, top: position.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                aria-label={`${node.label}${node.file === undefined ? '' : `（${node.file}${node.line === undefined ? '' : `:${node.line}`}）`}`}
                className={`absolute flex flex-col justify-center rounded-lg border px-3 text-left transition-colors ${
                  active
                    ? 'border-primary bg-primary/10 text-primary ring-primary/30 ring-2'
                    : 'border-border bg-card hover:bg-muted'
                } ${interactive ? 'cursor-pointer' : 'cursor-default opacity-80'}`}
              >
                {/* 单行截断会把长标签变成一片「…」——节点里必须能读出整句话，最多折两行 */}
                <span className="line-clamp-2 text-sm leading-snug font-medium break-words" title={node.label}>
                  {node.label}
                </span>
                {node.file === undefined ? null : (
                  <span className="text-muted-foreground truncate font-mono text-[10px]">
                    {node.file}
                    {node.line === undefined ? '' : `:${node.line}`}
                  </span>
                )}
              </button>
            )
          })}
        </div>
      </div>
    </BlockFrame>
  )
}

function Edge({
  edge,
  from,
  to,
}: {
  edge: MapEdge
  from: { x: number; y: number } | undefined
  to: { x: number; y: number } | undefined
}) {
  if (from === undefined || to === undefined) {
    return null
  }
  const x1 = from.x + NODE_WIDTH
  const y1 = from.y + NODE_HEIGHT / 2
  const x2 = to.x
  const y2 = to.y + NODE_HEIGHT / 2
  const forward = x2 >= x1
  // 同在左侧（反向/回边）时从右绕出去，避免线压在节点上
  const startX = forward ? x1 : from.x
  const endX = forward ? x2 : to.x + NODE_WIDTH
  const midX = (startX + endX) / 2
  return (
    <g>
      <path
        d={`M ${startX} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${endX} ${y2}`}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        className="text-border"
        markerEnd="url(#map-arrow)"
      />
      {edge.label === undefined ? null : (
        <text x={midX} y={(y1 + y2) / 2 - 6} textAnchor="middle" className="fill-muted-foreground text-[10px]">
          {edge.label}
        </text>
      )}
    </g>
  )
}
