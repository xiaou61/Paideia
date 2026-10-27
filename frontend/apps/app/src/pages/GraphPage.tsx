import { createLearningApi, layoutKey, resetPositions, type ConceptGraph } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Button,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { Boxes, RotateCcw } from 'lucide-react'
import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { ConceptDetailPanel } from '../components/graph/ConceptDetailPanel'
import { GraphCanvas2D } from '../components/graph/GraphCanvas2D'
import { GraphCanvas3D } from '../components/graph/GraphCanvas3D'

const learning = createLearningApi(api)

/**
 * 个人知识图谱（WORK-021）。
 *
 * <p><b>2D 是主入口，3D 是切换视角</b>。原因不是 3D 不好看，而是它的方位感不稳定：
 * 每次力导向重新收敛，节点就换位置，人建立不起空间记忆。2D 在这些方面都更可靠。
 *
 * <p>两个画布都是**动态加载**的：图渲染库（sigma / three）体积不小，不该让其他页面
 * 为它付首屏成本。
 */
export function GraphPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [mode, setMode] = useState<'2d' | '3d'>('2d')
  const [layoutEpoch, setLayoutEpoch] = useState(0)

  const graph = useQuery({
    queryKey: ['learning-graph'],
    queryFn: () => learning.graph(),
  })

  const data: ConceptGraph | undefined = graph.data
  const key = useMemo(
    () => (data === undefined ? '' : layoutKey(data.concepts, data.edges)),
    [data],
  )

  const selected = data?.concepts.find((concept) => concept.id === selectedId) ?? null

  const rebuild = useCallback(() => {
    resetPositions(key)
    setLayoutEpoch((epoch) => epoch + 1)
  }, [key])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="你已经碰到什么、掌握到什么程度"
        description="节点是知识点，不是文章。颜色越深表示掌握得越牢；带标记的是到期该复习的。"
      />

      {graph.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="graph-state">
          <Spinner />
          正在计算你的掌握度…
        </p>
      ) : null}

      {graph.isError ? (
        <Alert variant="destructive" data-testid="graph-state">
          <AlertTitle>图谱加载失败</AlertTitle>
          <AlertDescription>{(graph.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {data !== undefined && data.concepts.length === 0 ? (
        <div data-testid="graph-empty">
          <Alert>
            <AlertTitle>还没有建立知识概念</AlertTitle>
            <AlertDescription>
              概念由管理员在管理端「概念」页建立，再把条目挂上去。建立之后，这里会自动显示你的掌握情况。
            </AlertDescription>
          </Alert>
          {/* 空状态不能只是一句解释：一段"这里将来会有什么"比一个死胡同有用得多 */}
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            {[
              { title: '看出断层', body: '哪些知识点你读过，但它们之间的前置你还没碰过。' },
              { title: '知道该复习谁', body: '标记过「还不懂」或小测没过的，会在这里被标成待复习。' },
              { title: '连回学习线', body: '点一个节点，直接跳到承载它的那一课。' },
            ].map((item) => (
              <div key={item.title} className="border-border bg-card rounded-xl border p-4">
                <p className="text-sm font-medium">{item.title}</p>
                <p className="text-muted-foreground mt-1 text-sm leading-6">{item.body}</p>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {data !== undefined && data.concepts.length > 0 ? (
        <>
          <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
            <span className="text-muted-foreground" data-testid="graph-stats">
              {data.stats.concepts} 个概念 · 接触过 {data.stats.touched} 个 · 已掌握{' '}
              {data.stats.mastered} 个 · 待复习 {data.stats.dueForReview} 个
            </span>
            {data.stats.truncated ? (
              <span className="text-muted-foreground">（节点过多，仅显示接触过的部分）</span>
            ) : null}
            <div className="ml-auto flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setMode(mode === '2d' ? '3d' : '2d')}
                data-testid="graph-mode-toggle"
              >
                <Boxes className="size-4" />
                {mode === '2d' ? '看 3D' : '回到 2D'}
              </Button>
              <Button variant="ghost" size="sm" onClick={rebuild} data-testid="graph-relayout">
                <RotateCcw className="size-4" />
                重新布局
              </Button>
            </div>
          </div>

          <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
            <div className="bg-card relative h-[32rem] overflow-hidden rounded-xl border lg:h-[calc(100vh-18rem)] lg:min-h-[32rem]">
              {mode === '2d' ? (
                <GraphCanvas2D
                  key={`2d-${key}-${layoutEpoch}`}
                  nodes={data.concepts}
                  edges={data.edges}
                  layoutKeyValue={key}
                  selectedId={selectedId}
                  onSelect={setSelectedId}
                />
              ) : (
                <GraphCanvas3D
                  key={`3d-${key}-${layoutEpoch}`}
                  nodes={data.concepts}
                  edges={data.edges}
                  layoutKeyValue={key}
                  selectedId={selectedId}
                  onSelect={setSelectedId}
                />
              )}
            </div>

            <ConceptDetailPanel concept={selected} />
          </div>

          <p className="text-muted-foreground mt-4 text-xs">
            想看某一课的具体内容？回{' '}
            <Link to="/learn" className="underline underline-offset-2">
              学习线
            </Link>
            。
          </p>
        </>
      ) : null}
    </AppShell>
  )
}
