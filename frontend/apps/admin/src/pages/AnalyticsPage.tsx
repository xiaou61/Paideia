import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { Activity, ArrowRight, BarChart3, Gauge, RefreshCw, Sparkles } from 'lucide-react'
import { Link } from 'react-router'

import { api } from '../api'

/**
 * 数据大屏（WORK-045）。
 *
 * <p>回答的问题与其它页面不同：`OpsPage` 看"现在怎么样"，`AiRunsOverviewPage` 看"AI 花了多少"，
 * 而这一页看**数据从哪来、经过哪几步、在哪一步掉了**。
 *
 * <p>三条显示纪律：
 * <ol>
 *   <li>**三态必须能分开**：有数据 / 确实是 0 / 根本没采集。把最后一种画成 0 会让空白
 *       看起来像好消息——而"这两项上线后没人用"正是这一页最有价值的信息。</li>
 *   <li>**断点规则由后端判**（本节点为 0 且上游 > 0）。前端不重算，否则同一件事有两个真相。</li>
 *   <li>**小数据量要说出来**：数据点少于 3 天时写"数据不足"，不画一条看起来正常的线。</li>
 * </ol>
 */
type NodeState = 'ok' | 'zero' | 'not-collected'

interface FlowNode {
  label: string
  count: number
  state: NodeState
  unit: string
  broken: boolean
}

interface FlowEdge {
  fromIndex: number
  toIndex: number
  ratio: number | null
}

interface Flow {
  key: string
  title: string
  nodes: FlowNode[]
  edges: FlowEdge[]
  note: string | null
}

interface TableRow {
  name: string
  label: string
  rows: number
  delta7d: number | null
  state: string
}

interface DailyPoint {
  date: string
  series: string
  count: number
}

interface Feature {
  name: string
  state: NodeState
  detail: string
}

interface Overview {
  collectedAt: string
  flows: Flow[]
  volume: { tables: TableRow[]; daily: DailyPoint[]; dailyNote: string | null }
  usage: { accounts: number; features: Feature[] }
  ai: {
    byKind: { kind: string; runs: number; ok: number }[]
    successRate: number | null
    runs: number
    credits: { granted: number; spent: number; balance: number }
    topItems: { id: number; title: string; clicks: number }[]
    clicks: number
    clickRatePercent: number | null
  }
  search: {
    top: { name: string; count: number }[]
    zeroResult: { name: string; count: number }[]
  }
  monitoring: {
    reachable: boolean
    requestRate: number | null
    errorRate: number | null
    p95Seconds: number | null
    aiSuccessRate: number | null
  }
}

/** 节点三态的视觉。`not-collected` 用虚线边框而不是颜色——它不是"坏"，是"没看"。 */
const NODE_STYLE: Record<NodeState, string> = {
  ok: 'tile-surface border-border',
  zero: 'border-amber-500/60 bg-amber-500/10',
  'not-collected': 'border-dashed border-muted-foreground/40 bg-transparent',
}

const STATE_LABEL: Record<NodeState, string> = {
  ok: '',
  zero: '0',
  'not-collected': '未采集',
}

/** 链可下钻到这里；没有对应页面的就不给链接（不要造假入口）。 */
const FLOW_LINK: Record<string, string> = {
  knowledge: '/knowledge/entries',
  ai: '/ai-runs/list',
}

const SERIES_LABEL: Record<string, string> = {
  ai_runs: 'AI 运行',
  digest_items: '速递条目',
  entry_views: '条目浏览',
  digest_clicks: '速递点击',
  resumes: '简历',
}

const SERIES_COLOR: Record<string, string> = {
  ai_runs: 'bg-primary',
  digest_items: 'bg-sky-500',
  entry_views: 'bg-emerald-500',
  digest_clicks: 'bg-amber-500',
  resumes: 'bg-violet-500',
}

export function AnalyticsPage() {
  const overview = useQuery({
    queryKey: ['admin-analytics-overview'],
    queryFn: () => api.requestEnvelope<Overview>('/api/v1/ops/admin/analytics/overview'),
    // 60 秒：聚合要打 10+ 张表，大屏的决策粒度本来就是分钟
    refetchInterval: 60_000,
  })

  if (overview.isPending) {
    return (
      <>
        <PageHeader title="数据大屏" description="数据从哪来、经过哪几步、在哪一步掉了。" />
        <div className="text-muted-foreground flex items-center gap-2 text-sm">
          <Spinner /> 正在汇总…
        </div>
      </>
    )
  }

  if (overview.isError) {
    return (
      <>
        <PageHeader title="数据大屏" description="数据从哪来、经过哪几步、在哪一步掉了。" />
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <BarChart3 />
            </EmptyMedia>
            <EmptyTitle>汇总失败</EmptyTitle>
            <EmptyDescription>
              接口没有返回数据。这不代表没有数据，只代表这次没读到。
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      </>
    )
  }

  const data = overview.data

  return (
    <>
      <PageHeader
        title="数据大屏"
        description="数据从哪来、经过哪几步、在哪一步掉了。所有「每用户」指标的分母就是账号数。"
        actions={
          <div className="flex items-center gap-2">
            <Badge variant="outline" className="tabular-nums">
              采集于 {formatTime(data.collectedAt)}
            </Badge>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void overview.refetch()}
              disabled={overview.isFetching}
              data-testid="analytics-refresh"
            >
              <RefreshCw className={overview.isFetching ? 'size-4 animate-spin' : 'size-4'} />
              刷新
            </Button>
          </div>
        }
      />

      <div className="space-y-6">
        <FlowsSection flows={data.flows} />
        <div className="grid gap-6 xl:grid-cols-2">
          <VolumeSection volume={data.volume} />
          <UsageSection usage={data.usage} />
        </div>
        <div className="grid gap-6 xl:grid-cols-2">
          <AiSection ai={data.ai} />
          <SearchSection search={data.search} />
        </div>
        <MonitoringSection monitoring={data.monitoring} />
      </div>
    </>
  )
}

/** 六条链的泳道。每行：标题 → 四个节点（中间三个箭头）→ 可选下钻。 */
function FlowsSection({ flows }: { flows: Flow[] }) {
  return (
    <Card data-testid="analytics-flows">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <span className="tile-surface border-border text-primary grid size-8 shrink-0 place-items-center rounded-lg border">
            <BarChart3 className="size-4" />
          </span>
          数据流转
        </CardTitle>
        <CardDescription>
          六条链，每条四个阶段。节点上是真实计数；<span className="text-amber-600">琥珀色</span>
          表示上一步有数据、这一步为 0（断点）；虚线边框表示这项没有采集，不是 0。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {flows.map((flow) => {
          // `tsconfig` 开了 noUncheckedIndexedAccess：索引结果可能是 undefined，
          // 必须先把值取出来再判空，否则 JSX 里传 `to={可能 undefined}` 编译不过
          const link = FLOW_LINK[flow.key]
          return (
            <div
              key={flow.key}
              className="border-border rounded-xl border p-3"
              data-testid={`analytics-flow-${flow.key}`}
            >
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="text-sm font-medium">{flow.title}</span>
                {link === undefined ? null : (
                  <Link
                    to={link}
                    className="text-muted-foreground hover:text-primary flex items-center gap-1 text-xs"
                  >
                    明细 <ArrowRight className="size-3" />
                  </Link>
                )}
              </div>
              <div className="flex flex-wrap items-stretch gap-1.5">
                {flow.nodes.map((node, index) => (
                  <div key={node.label} className="flex flex-1 items-center gap-1.5">
                    <div
                      className={`min-w-0 flex-1 rounded-lg border px-2.5 py-2 ${NODE_STYLE[node.state]}`}
                      data-testid={`analytics-node-${flow.key}-${index}`}
                      data-state={node.state}
                      title={node.broken ? '上一步有数据，这一步是 0' : undefined}
                    >
                      <div className="text-muted-foreground truncate text-[0.7rem]">{node.label}</div>
                      <div className="tabular-nums text-base font-medium">
                        {node.count.toLocaleString('zh-CN')}
                        <span className="text-muted-foreground ml-0.5 text-[0.7rem] font-normal">
                          {node.unit}
                        </span>
                      </div>
                      {node.state !== 'ok' && STATE_LABEL[node.state] ? (
                        <div className="text-[0.7rem] text-amber-600">{STATE_LABEL[node.state]}</div>
                      ) : null}
                    </div>
                    {index < flow.nodes.length - 1 ? (
                      <ArrowRight className="text-muted-foreground/50 size-3.5 shrink-0" />
                    ) : null}
                  </div>
                ))}
              </div>
              {flow.note ? (
                <p className="text-muted-foreground mt-2 text-[0.7rem] leading-relaxed">{flow.note}</p>
              ) : null}
            </div>
          )
        })}
      </CardContent>
    </Card>
  )
}

/** 业务量：近 14 天按天柱状 + 各表行数。 */
function VolumeSection({ volume }: { volume: Overview['volume'] }) {
  const dates = [...new Set(volume.daily.map((point) => point.date))].sort()
  const series = [...new Set(volume.daily.map((point) => point.series))]
  const maxOf = (name: string) =>
    Math.max(1, ...volume.daily.filter((point) => point.series === name).map((point) => point.count))

  return (
    <Card data-testid="analytics-volume">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <span className="tile-surface border-border text-primary grid size-8 shrink-0 place-items-center rounded-lg border">
            <Activity className="size-4" />
          </span>
          业务量
        </CardTitle>
        <CardDescription>
          按北京自然日切桶（与速递的 edition_date 同源）。
          {volume.dailyNote ? (
            <span className="ml-1 text-amber-600" data-testid="analytics-daily-note">
              {volume.dailyNote}
            </span>
          ) : null}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {/* 纯 CSS 柱状：每条 series 一行，每根柱的高度按该 series 的最大值归一 */}
        <div className="mb-5 space-y-2" data-testid="analytics-daily">
          {series.map((name) => {
            const max = maxOf(name)
            return (
              <div key={name} className="flex items-center gap-2">
                <span className="text-muted-foreground w-20 shrink-0 text-[0.7rem]">
                  {SERIES_LABEL[name] ?? name}
                </span>
                <div className="flex h-6 flex-1 items-end gap-px">
                  {dates.map((date) => {
                    const point = volume.daily.find(
                      (row) => row.date === date && row.series === name,
                    )
                    const count = point?.count ?? 0
                    const height = count === 0 ? 2 : Math.max(8, (count / max) * 100)
                    return (
                      <div
                        key={date}
                        className={`flex-1 rounded-sm ${count === 0 ? 'bg-muted' : (SERIES_COLOR[name] ?? 'bg-primary')}`}
                        style={{ height: `${height}%` }}
                        title={`${date} ${SERIES_LABEL[name] ?? name}：${count}`}
                      />
                    )
                  })}
                </div>
                <span className="text-muted-foreground w-8 shrink-0 text-right text-[0.7rem] tabular-nums">
                  {max}
                </span>
              </div>
            )
          })}
        </div>

        <table className="w-full text-xs" data-testid="analytics-tables">
          <thead>
            <tr className="text-muted-foreground border-border border-b">
              <th className="py-1.5 text-left font-normal">表</th>
              <th className="py-1.5 text-right font-normal">行数</th>
              <th className="py-1.5 text-right font-normal">近 7 天</th>
            </tr>
          </thead>
          <tbody>
            {[...volume.tables]
              .sort((left, right) => right.rows - left.rows)
              .map((row) => (
                <tr key={row.name} className="border-border/50 border-b last:border-b-0">
                  <td className="py-1.5">
                    <span>{row.label}</span>
                    <code className="text-muted-foreground ml-1.5 text-[0.65rem]">{row.name}</code>
                    {row.state === 'append-only' ? (
                      <span className="text-muted-foreground ml-1.5 text-[0.65rem]">只增</span>
                    ) : null}
                  </td>
                  <td className="py-1.5 text-right tabular-nums">
                    {row.rows.toLocaleString('zh-CN')}
                  </td>
                  <td className="text-muted-foreground py-1.5 text-right tabular-nums">
                    {row.delta7d === null ? '—' : `+${row.delta7d.toLocaleString('zh-CN')}`}
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </CardContent>
    </Card>
  )
}

/** 功能使用度：回答"上线了但没人用"。 */
function UsageSection({ usage }: { usage: Overview['usage'] }) {
  return (
    <Card data-testid="analytics-usage">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <span className="tile-surface border-border text-primary grid size-8 shrink-0 place-items-center rounded-lg border">
            <Gauge className="size-4" />
          </span>
          功能使用度
        </CardTitle>
        <CardDescription>
          当前 {usage.accounts} 个账号。<span className="text-amber-600">琥珀色</span>
          表示已采集但为 0（功能存在、没人用）；虚线表示没有采集。
        </CardDescription>
      </CardHeader>
      <CardContent>
        <ul className="grid gap-1.5 sm:grid-cols-2">
          {usage.features.map((feature) => (
            <li
              key={feature.name}
              className={`flex items-center justify-between gap-2 rounded-lg border px-2.5 py-1.5 ${NODE_STYLE[feature.state]}`}
              data-testid={`analytics-feature-${feature.name}`}
              data-state={feature.state}
            >
              <span className="text-xs">{feature.name}</span>
              <span className="text-muted-foreground text-[0.7rem] tabular-nums">
                {feature.detail}
              </span>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

function AiSection({ ai }: { ai: Overview['ai'] }) {
  return (
    <Card data-testid="analytics-ai">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <span className="tile-surface border-border text-primary grid size-8 shrink-0 place-items-center rounded-lg border">
            <Sparkles className="size-4" />
          </span>
          AI 与成本
        </CardTitle>
        <CardDescription>
          <Link to="/ai-runs/overview" className="hover:text-primary underline-offset-2 hover:underline">
            更细的趋势与成本看 AI 调用概览
          </Link>
          。这里的口径与它一致。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-3 gap-2 text-center">
          <Stat label="运行" value={ai.runs.toLocaleString('zh-CN')} />
          <Stat
            label="成功率"
            value={ai.successRate === null ? '—' : `${(ai.successRate * 100).toFixed(1)}%`}
          />
          <Stat label="余额合计" value={ai.credits.balance.toLocaleString('zh-CN')} />
        </div>

        {ai.byKind.length > 0 ? (
          <div>
            <div className="text-muted-foreground mb-1.5 text-[0.7rem]">按场景</div>
            <ul className="space-y-1">
              {ai.byKind.map((row) => (
                <li key={row.kind} className="flex items-center gap-2 text-xs">
                  <span className="w-32 shrink-0 truncate">{row.kind}</span>
                  <div className="bg-muted h-1.5 flex-1 overflow-hidden rounded-full">
                    <div
                      className="bg-primary h-full rounded-full"
                      style={{ width: `${(row.runs / Math.max(1, ai.runs)) * 100}%` }}
                    />
                  </div>
                  <span className="text-muted-foreground w-16 shrink-0 text-right tabular-nums">
                    {row.runs} / ok {row.ok}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        <div className="text-muted-foreground grid grid-cols-2 gap-x-4 gap-y-1 text-[0.7rem] tabular-nums">
          <span>积分发放 {ai.credits.granted.toLocaleString('zh-CN')}</span>
          <span>积分消耗 {ai.credits.spent.toLocaleString('zh-CN')}</span>
        </div>

        <div>
          <div className="text-muted-foreground mb-1 text-[0.7rem]">
            速递点击 · 近 7 天 {ai.clicks} 次
            {ai.clickRatePercent === null ? '' : ` · 覆盖 ${ai.clickRatePercent}% 的条目`}
          </div>
          {ai.topItems.length > 0 ? (
            <ul className="space-y-0.5">
              {ai.topItems.map((item) => (
                <li key={item.id} className="flex items-center justify-between gap-2 text-[0.7rem]">
                  <span className="truncate">{item.title}</span>
                  <span className="text-muted-foreground shrink-0 tabular-nums">
                    {item.clicks}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-muted-foreground text-[0.7rem]">
              还没有站内点击。埋点是新增的，数据要慢慢长出来。
            </p>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function SearchSection({ search }: { search: Overview['search'] }) {
  return (
    <Card data-testid="analytics-search">
      <CardHeader>
        <CardTitle className="text-sm font-medium">搜索洞察</CardTitle>
        <CardDescription>
          「搜不到」那列最有价值：它直接告诉你内容缺什么。数据来自新增的埋点（近 7 天）。
        </CardDescription>
      </CardHeader>
      <CardContent>
        {search.top.length === 0 && search.zeroResult.length === 0 ? (
          <p className="text-muted-foreground text-xs" data-testid="analytics-search-empty">
            还没有搜索记录。埋点是新增的，用户搜索后这里会有内容。
          </p>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <div className="text-muted-foreground mb-1.5 text-[0.7rem]">搜得最多</div>
              {search.top.length === 0 ? (
                <p className="text-muted-foreground text-[0.7rem]">无</p>
              ) : (
                <ul className="space-y-0.5">
                  {search.top.map((row) => (
                    <li key={row.name} className="flex justify-between gap-2 text-[0.7rem]">
                      <span className="truncate">{row.name}</span>
                      <span className="text-muted-foreground tabular-nums">{row.count}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <div className="mb-1.5 text-[0.7rem] text-amber-600">搜不到（内容缺口）</div>
              {search.zeroResult.length === 0 ? (
                <p className="text-muted-foreground text-[0.7rem]">无</p>
              ) : (
                <ul className="space-y-0.5">
                  {search.zeroResult.map((row) => (
                    <li key={row.name} className="flex justify-between gap-2 text-[0.7rem]">
                      <span className="truncate">{row.name}</span>
                      <span className="text-muted-foreground tabular-nums">{row.count}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function MonitoringSection({ monitoring }: { monitoring: Overview['monitoring'] }) {
  return (
    <Card data-testid="analytics-monitoring">
      <CardHeader>
        <CardTitle className="flex items-center justify-between gap-2 text-sm font-medium">
          <span>实时监控</span>
          <Link to="/ops/panel" className="text-muted-foreground hover:text-primary text-xs">
            运维面板 <ArrowRight className="inline size-3" />
          </Link>
        </CardTitle>
        <CardDescription>
          来自 Prometheus 的实时值；业务数字来自数据库，两层互不影响。
        </CardDescription>
      </CardHeader>
      <CardContent>
        {!monitoring.reachable ? (
          <p className="text-sm text-amber-600" data-testid="analytics-monitoring-down">
            Prometheus 不可达。上面四个区块来自数据库，不受影响。
          </p>
        ) : (
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Stat
              label="请求/秒"
              value={monitoring.requestRate === null ? '—' : monitoring.requestRate.toFixed(2)}
            />
            <Stat
              label="5xx 比例"
              value={
                monitoring.errorRate === null ? '—' : `${(monitoring.errorRate * 100).toFixed(2)}%`
              }
            />
            <Stat
              label="P95"
              value={monitoring.p95Seconds === null ? '—' : `${monitoring.p95Seconds.toFixed(2)}s`}
            />
            <Stat
              label="AI 成功率"
              value={
                monitoring.aiSuccessRate === null
                  ? '无流量'
                  : `${(monitoring.aiSuccessRate * 100).toFixed(1)}%`
              }
            />
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="border-border rounded-lg border px-2.5 py-2">
      <div className="text-muted-foreground text-[0.65rem]">{label}</div>
      <div className="tabular-nums text-sm font-medium">{value}</div>
    </div>
  )
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString('zh-CN', { hour12: false })
}
