import {
  circularLayout,
  loadPositions,
  type ConceptEdge,
  type ConceptNode,
  type MasteryTier,
} from '@paideia/knowledge'
import { subscribeToThemeChange } from '@paideia/ui'
import { useEffect, useRef, useState } from 'react'

import { tierColor } from './tier-style'

interface Props {
  nodes: ConceptNode[]
  edges: ConceptEdge[]
  layoutKeyValue: string
  selectedId: number | null
  onSelect: (id: number) => void
}

/**
 * 3D 视角（WORK-021）。
 *
 * <p><b>只是切换视角，不是主入口</b>：3D 的力导向每次收敛都会换位置，空间记忆建立不起来，
 * 小屏上点选也更容易失手。用户已决定 2D 为主，这里留着是为了看遮挡关系更清楚。
 *
 * <p>与 2D **共用同一份平面坐标存档**：切视角时节点不该跳位置，否则两个视角对不上，
 * 用户会以为这是两张图。若 2D 还没来过（没有存档），先按圆环摆一个确定性初值，
 * 之后 2D 重算时会覆盖。
 *
 * <p>整个 three.js 链路由 `import('3d-force-graph')` 按需拉取。
 */
export function GraphCanvas3D({ nodes, edges, layoutKeyValue, selectedId, onSelect }: Props) {
  const container = useRef<HTMLDivElement | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let disposed = false
    let destroy: (() => void) | undefined

    void (async () => {
      try {
        // 3D 同样要求 WebGL；这里不探测就等于把一个空白 canvas 交给用户
        const probe = document.createElement('canvas')
        if (probe.getContext('webgl2') === null && probe.getContext('webgl') === null) {
          setFailed(true)
          return
        }
        const module = await import('3d-force-graph')
        if (disposed || container.current === null) {
          return
        }
        const ForceGraph3D = module.default
        const points = loadPositions(layoutKeyValue) ?? circularLayout(nodes)

        const graphData = {
          nodes: nodes.map((node) => ({
            id: String(node.id),
            name: node.name,
            /**
             * 存 `tier` 而不是存颜色（WORK-079）：颜色要能随换肤重新求值。
             * 存死色值的话，换肤后这张图会留在旧配色里，直到用户刷新页面。
             */
            tier: node.tier,
            val: node.tier === 'unknown' ? 1 : 1 + node.score * 2,
            x: points[node.id]?.x ?? 0,
            y: points[node.id]?.y ?? 0,
            // z 不持久化：2D 里没有这个维度，按 id 给一个确定的散开值即可
            z: (node.id % 7) * 12,
          })),
          links: edges
            .filter((edge) => edge.from !== edge.to)
            .map((edge) => ({ source: String(edge.from), target: String(edge.to) })),
        }

        /** 每次求值都现读令牌：换肤后重新 `nodeColor(...)` 就会拿到新颜色。 */
        const nodeColor = (node: object): string => tierColor((node as { tier: MasteryTier }).tier)

        const element = container.current
        const renderer = new ForceGraph3D(element)
          .graphData(graphData)
          .nodeLabel((node) => (node as { name: string }).name)
          .nodeColor(nodeColor)
          .onNodeClick((node) => onSelect(Number((node as { id: string }).id)))
          .backgroundColor('transparent')

        // 换肤 / 换明暗：重新挂一次颜色访问器，three.js 会重算材质颜色。
        // 不动布局——3D 的力导向每重跑一次就要重新收敛，用户的空间记忆会丢。
        const unsubscribeTheme = subscribeToThemeChange(() => {
          renderer.nodeColor(nodeColor)
        })

        destroy = () => {
          unsubscribeTheme()
          renderer.pauseAnimation()
          element.replaceChildren()
        }
      } catch {
        if (!disposed) {
          setFailed(true)
        }
      }
    })()

    return () => {
      disposed = true
      destroy?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layoutKeyValue])

  if (failed) {
    return (
      <div
        className="text-muted-foreground flex h-full items-center justify-center px-6 text-center text-sm"
        data-testid="graph-fallback-3d"
      >
        3D 视角加载失败，切回 2D 仍可正常查看。
      </div>
    )
  }

  return (
    <div
      ref={container}
      className="h-full w-full"
      data-testid="graph-canvas-3d"
      data-selected={selectedId ?? ''}
    />
  )
}
