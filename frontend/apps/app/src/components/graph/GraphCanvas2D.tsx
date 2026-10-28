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
import { useEffect, useRef, useState } from 'react'

import { resolveVar, tierColor } from './tier-style'

interface Props {
  nodes: ConceptNode[]
  edges: ConceptEdge[]
  layoutKeyValue: string
  selectedId: number | null
  onSelect: (id: number) => void
}

/**
 * 2D 图谱画布（WORK-021）。
 *
 * <p>sigma + graphology 都是**动态加载**的：图库本身不小，不该让其他页面付这份首屏成本。
 *
 * <p>坐标持久化是这个组件存在的主要理由。forceAtlas2 每次收敛都会挪动节点，而人对图谱的
 * 定位感依赖"这个点一直在那片"。所以：有存档直接用，没存档算一次并存下来；
 * 「重新布局」由页面清掉存档再重挂本组件。
 */
export function GraphCanvas2D({ nodes, edges, layoutKeyValue, selectedId, onSelect }: Props) {
  const container = useRef<HTMLDivElement | null>(null)
  const [failed, setFailed] = useState(false)
  /** 渲染器建好之前显示"正在绘制"：这段时间在软件 WebGL 下是 100ms 以上的主线程阻塞，空白看起来像卡死。 */
  const [ready, setReady] = useState(false)

  useEffect(() => {
    let disposed = false
    let destroy: (() => void) | undefined

    void (async () => {
      try {
        // **必须自己探测 WebGL**：sigma 拿不到上下文时不会抛，它只是画出一片空白。
        // 那正是用户所说的"白屏"，也是唯一能在没有 GPU 的机器上提前给出提示的时机。
        if (!supportsWebGl()) {
          setFailed(true)
          return
        }

        const [graphology, sigmaModule, layoutModule] = await Promise.all([
          import('graphology'),
          import('sigma'),
          import('graphology-layout-forceatlas2'),
        ])
        if (disposed || container.current === null) {
          return
        }
        const Graph = graphology.default
        const Sigma = sigmaModule.default
        const forceAtlas2 = layoutModule.default

        const graph = new Graph({ multi: false, type: 'directed' })
        // 初值必须**散开**：forceAtlas2 靠节点间的斥力推位置，全部落在原点时斥力方向
        // 没有定义，它不会抛错，只是把所有点原样留在 (0,0)。sigma 面对一个零尺寸的图
        // 会把相机推到极限，整块画布变成一颗巨大的节点色圆——看起来就是"图谱坏了"。
        const seed = circularLayout(nodes)
        nodes.forEach((node) => {
          const point = seed[node.id] ?? { x: 0, y: 0 }
          graph.addNode(String(node.id), {
            label: node.name,
            size: node.tier === 'unknown' ? 6 : 8 + node.score * 6,
            color: tierColor(node.tier),
            x: point.x,
            y: point.y,
          })
        })
        /**
         * 让所有标签都画出来。
         *
         * <p>sigma 默认按网格抽样决定画哪些标签，公式是**每格 `ceil(labelDensity / ratio²)` 个**。
         * `ratio` 大于 1（视图缩小）时无论怎么调 `labelDensity` 都只剩 1 个，同格其余节点就没有标签
         * ——这就是"图上有一堆没名字的点"，也是怎么调 density 都没用的原因。
         *
         * <p>强制渲染读的是 `labelVisibility === 'visible'`。**必须走 nodeReducer**：
         * 直接写在 `addNode` 的属性里没用——没有样式声明时 sigma 只从属性取 x/y/size/color/label，
         * 其余字段一律回到默认值（`labelVisibility` 的默认是 `auto`），属性会被静默覆盖。
         *
         * <p>只在节点数不多时强制：几千个节点全标会糊成一片，那时抽样才是对的。
         */
        const forceLabels = nodes.length <= 40
        // 同一对概念可以同时有 prerequisite 与 related 两条记录（后端唯一键含 relation_type），
        // 而 graphology 在 multi:false 下对同一对 source/target 只会保留一条、第二条直接抛。
        // 按无向对去重：保留先出现的那条（后端按关系类型排序，前置优先）。
        const seenPairs = new Set<string>()
        edges.forEach((edge, index) => {
          const from = String(edge.from)
          const to = String(edge.to)
          if (from === to || !graph.hasNode(from) || !graph.hasNode(to)) {
            return
          }
          const pair = from < to ? `${from}|${to}` : `${to}|${from}`
          if (seenPairs.has(pair)) {
            return
          }
          seenPairs.add(pair)
          graph.addEdgeWithKey(`${edge.type}-${index}`, from, to, {
            // 边的浓淡也走令牌：前置关系要看得见（border），关联关系要退到背景里（tier-unknown）
            color: resolveVar(edge.type === 'prerequisite' ? '--border' : '--tier-unknown'),
            size: edge.type === 'prerequisite' ? 1.4 : 1,
          })
        })

        const saved = loadPositions(layoutKeyValue)
        // 标签重叠的根因是节点挤在一起，不是标签密度：sigma 不做碰撞检测，
        // 只按网格限制"每格画几个标签"，画出来的照样叠。先推开，再归一化。
        const positions = spreadApart(saved ?? computeLayout(graph, forceAtlas2, nodes))
        if (saved === null) {
          savePositions(layoutKeyValue, positions)
        }
        // sigma 会按包围盒自动适配视口，而节点 size 是世界单位：布局越紧凑，自动缩放越猛，
        // 节点在屏幕上就越大。力导向在十几个节点时会把坐标压到几十个单位，一颗节点能铺满
        // 半个画布。缩放到固定跨度后，屏幕上的大小与布局紧凑程度脱钩。
        const scale = normalizeScale(positions)
        nodes.forEach((node) => {
          const point = positions[node.id] ?? { x: 0, y: 0 }
          if (graph.hasNode(String(node.id))) {
            graph.setNodeAttribute(String(node.id), 'x', point.x * scale)
            graph.setNodeAttribute(String(node.id), 'y', point.y * scale)
          }
        })

        // 这三个选项在 sigma 4 的运行期设置里存在，但没进导出的类型声明；
        // 断言一次比把默认值改成手写渲染层便宜得多。
        //
        // 建渲染器会编译 WebGL 着色器：有 GPU 加速时是一两毫秒，退回软件渲染（SwiftShader）时
        // **实测阻塞主线程约 180ms**（2026-10-27 实测：同一台机器走 D3D11 时长任务 0 个，
        // 走 SwiftShader 时 1 个 181ms 的长任务）。先让出一帧，让页面先画出来并显示"正在绘制"，
        // 否则这段阻塞落在点击的同一次任务里，用户看到的是"点了没反应"。
        await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
        if (disposed || container.current === null) {
          return
        }

        const renderer = new Sigma(graph, container.current, {
          labelDensity: 2,
          // 节点半径最小 6（未接触档），阈值取 6 会把它们全部隐去标签——
          // 而"有哪些还没碰过"恰恰是图谱最该回答的问题。
          labelRenderedSizeThreshold: 0,
          // 必须放这里：写在 addNode 的属性里会被 sigma 的默认值覆盖（见上面那段说明）
          ...(forceLabels
            ? { nodeReducer: (_node: string, data: Record<string, unknown>) =>
                ({ ...data, labelVisibility: 'visible' }) }
            : {}),
        } as ConstructorParameters<typeof Sigma>[2])
        if (disposed) {
          // 让出的这一帧里用户可能已经离开；不 kill 就漏一个 WebGL 上下文
          renderer.kill()
          return
        }
        setReady(true)

        renderer.on('clickNode', ({ node }) => onSelect(Number(node)))
        renderer.on('clickStage', () => onSelect(-1))

        destroy = () => {
          renderer.kill()
        }
      } catch {
        if (!disposed) {
          // 没有 WebGL / 图库加载失败：给一句人话，不给白屏
          setFailed(true)
        }
      }
    })()

    return () => {
      disposed = true
      destroy?.()
    }
    // 选中态不参与重建：点一下节点就重画整张图会闪。onSelect 由父组件用 setState 传入，引用稳定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layoutKeyValue])

  if (failed) {
    return (
      <div
        className="text-muted-foreground flex h-full items-center justify-center px-6 text-center text-sm"
        data-testid="graph-fallback"
      >
        这个浏览器无法绘制图谱（需要 WebGL）。你仍然可以在学习线里查看每一个知识点。
      </div>
    )
  }

  return (
    <div className="relative h-full w-full">
      <div
        ref={container}
        className="h-full w-full"
        data-testid="graph-canvas-2d"
        data-selected={selectedId ?? ''}
      />
      {ready ? null : (
        <p
          className="text-muted-foreground pointer-events-none absolute inset-0 flex items-center justify-center gap-2 text-sm"
          data-testid="graph-drawing"
        >
          <Spinner />
          正在绘制图谱…
        </p>
      )}
    </div>
  )
}

/**
 * 把布局缩放到一个固定的坐标跨度（最长边 1000 单位）。
 *
 * <p>sigma 用节点坐标做世界单位、按包围盒自动适配视口，所以**布局有多紧凑，屏幕上就有多放大**。
 * 力导向在十几个节点时会把坐标压到几十个单位，一颗半径 14 的节点就能盖住半个画布；
 * 节点上百个时又反过来只剩几个像素。固定跨度让"屏幕上看起来多大"不再取决于布局。
 *
 * <p>1000 这个量级是拿"节点半径 8~14、画布约 1000px"反推的：节点直径占画布 3% 左右，
 * 连线之间留得下标签。跨度再小标签就会互相压住。
 *
 * <p>单点或全重合时返回 1：没有跨度可归一，乘什么都一样。
 */
function normalizeScale(positions: Record<number, Point>): number {
  const points = Object.values(positions)
  if (points.length < 2) {
    return 1
  }
  const xs = points.map((point) => point.x)
  const ys = points.map((point) => point.y)
  const span = Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys))
  return span > 0 ? 1000 / span : 1
}

/**
 * 能不能画 WebGL。
 *
 * <p>单独探测而不是等 sigma 报错：实测 sigma 在拿不到上下文时**不抛异常**，
 * 只是留一片空白画布——那与"图里没有节点"在界面上无法区分。
 */
function supportsWebGl(): boolean {
  try {
    const canvas = document.createElement('canvas')
    return canvas.getContext('webgl2') !== null || canvas.getContext('webgl') !== null
  } catch {
    return false
  }
}

/**
 * 优先用力导向；节点太少或算法抛错时退回圆环。
 *
 * <p>两种情况下都必须返回**每个节点都有坐标**的映射——坐标会被存下来，
 * 缺一个节点就等于那个点下次会掉在原点。
 */
function computeLayout(
  graph: { order: number; forEachNode: (callback: (id: string) => void) => void; getNodeAttribute: (id: string, key: string) => unknown },
  forceAtlas2: { assign: (graph: never, options: { iterations: number }) => void },
  nodes: ConceptNode[],
): Record<number, Point> {
  if (nodes.length <= 2) {
    return circularLayout(nodes)
  }
  try {
    forceAtlas2.assign(graph as never, { iterations: 120 })
    const positions: Record<number, Point> = {}
    graph.forEachNode((id) => {
      positions[Number(id)] = {
        x: Number(graph.getNodeAttribute(id, 'x')),
        y: Number(graph.getNodeAttribute(id, 'y')),
      }
    })
    const complete = nodes.every((node) => Number.isFinite(positions[node.id]?.x))
    // 有限还不够：全部重合的布局同样"有限"，而它画出的是一个大圆而不是一张图。
    // 力导向偶尔会退化成这样（尤其节点初始位置相同时），所以额外要求至少有两个点分开。
    const spread = new Set(
      Object.values(positions).map((point) => `${point.x.toFixed(2)},${point.y.toFixed(2)}`),
    ).size
    return complete && spread > 1 ? positions : circularLayout(nodes)
  } catch {
    return circularLayout(nodes)
  }
}
