import {
  Badge,
  Button,
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Input,
  Markdown,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, Database, HardDrive, RefreshCw, Server, ShieldAlert, Sparkles, Timer } from 'lucide-react'
import { useState } from 'react'

import { api } from '../api'
import { JSON_HEADERS, errorMessage, formatLocalDateTime } from '@paideia/core'

/**
 * 运维面板（WORK-028）。
 *
 * <p>七张卡片各答一个问题：主机还剩多少、服务活没活、中间件通不通、外部依赖在不在、
 * 定时任务跑没跑、开关开没开、备份新不新。**深挖看下面的整块面板**——趋势只能靠时序图，
 * 卡片只回答"现在"。
 *
 * <p>两条显示纪律：
 * <ol>
 *   <li>**「未知」是独立的一档**，不能画成 0、也不能画成绿色。取不到数据时显示"未知"
 *       并给出原因，因为"监控自己挂了"与"一切正常"看起来必须不一样。</li>
 *   <li>**状态与原因都由后端判定**，前端不复算阈值——判据只有一份（`OpsSummaryService`）。</li>
 * </ol>
 */
type OpsStatus = 'OK' | 'WARN' | 'ERROR' | 'UNKNOWN'

interface Metric {
  name: string
  value: number | null
  unit: string
  text: string | null
}

interface Group {
  key: string
  title: string
  status: OpsStatus
  reason: string
  metrics: Metric[]
}

interface Summary {
  collectedAt: string
  prometheusReachable: boolean
  groups: Group[]
}

type DiagnosisStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'

/** 诊断记录（WORK-042）。列表接口不带报告全文，详情接口才带。 */
interface Diagnosis {
  id: number
  triggerType: 'manual' | 'alert'
  alertName: string | null
  question: string | null
  status: DiagnosisStatus
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  reportMd: string | null
  errorMessage: string | null
  rating: number | null
  feedbackNote: string | null
  createdAt: string
  finishedAt: string | null
}

/** 好评率（WORK-043）。rated = 0 时 helpfulRate 为 null：还没人评不等于全都不准。 */
interface DiagnosisStats {
  rated: number
  good: number
  partial: number
  bad: number
  helpfulRate: number | null
}

/** 日志模板与模板异常（WORK-085）。Top 模板按当前窗口频次排序。 */
interface PatternTop {
  unit: string
  templateHash: string
  pattern: string
  count: number
}

type PatternKind = 'NEW_TEMPLATE' | 'FREQ_SPIKE' | 'DISAPPEARED'

interface PatternAnomaly {
  id: number
  kind: PatternKind
  templateHash: string
  unit: string
  ratio: number | null
  z: number | null
  baselineCount: number | null
  currentCount: number | null
  status: string
  diagnosisId: number | null
  detail: string | null
  createdAt: string
}

interface PatternOverview {
  top: PatternTop[]
  anomalies: PatternAnomaly[]
}

const PATTERN_KIND: Record<PatternKind, { label: string; variant: 'warning' | 'destructive' | 'outline' }> = {
  NEW_TEMPLATE: { label: '新模板', variant: 'warning' },
  FREQ_SPIKE: { label: '频次突增', variant: 'destructive' },
  DISAPPEARED: { label: '模板消失', variant: 'outline' },
}

/** 三档评分的显示文案；值与后端约定的 -1/0/1 一一对应。 */
const RATINGS: { value: number; label: string }[] = [
  { value: 1, label: '准' },
  { value: 0, label: '部分准' },
  { value: -1, label: '不准' },
]

const DIAGNOSIS_STATUS: Record<
  DiagnosisStatus,
  { label: string; variant: 'success' | 'warning' | 'destructive' | 'outline' }
> = {
  PENDING: { label: '排队中', variant: 'warning' },
  RUNNING: { label: '诊断中', variant: 'warning' },
  DONE: { label: '已完成', variant: 'success' },
  FAILED: { label: '失败', variant: 'destructive' },
}

/** 卡片与图标一一对应；出现未知的 group 时用兜底图标，不能因为缺图标就不渲染。 */
const ICONS: Record<string, typeof Server> = {
  host: HardDrive,
  services: Server,
  middleware: Database,
  external: Activity,
  scheduled: Timer,
  switches: ShieldAlert,
  backup: Database,
}

// 四态各有自己的色调：警告用 warning 而不是 outline，否则"警告"与"未知"在视觉上
// 分辨不出来——而这两者的处置动作完全不同
const STATUS_STYLE: Record<
  OpsStatus,
  { label: string; variant: 'success' | 'warning' | 'destructive' | 'outline' }
> = {
  OK: { label: '正常', variant: 'success' },
  WARN: { label: '警告', variant: 'warning' },
  ERROR: { label: '异常', variant: 'destructive' },
  UNKNOWN: { label: '未知', variant: 'outline' },
}

/**
 * 面板地址（`?kiosk` 去掉 Grafana 自己的侧栏与顶栏，只留面板内容）。
 *
 * **不需要带任何平台令牌**：iframe 的子请求带不上 `Authorization` 头，也带不上查询串，
 * 所以这套鉴权交给 Grafana 自己的会话 cookie（详见后端 `SecurityConfiguration` 的规则注释）。
 * 代价是管理员第一次在 iframe 里要用 Grafana 口令登录一次——这是实测后唯一能用的做法。
 */
const PANEL_URL = '/api/v1/ops/grafana/d/paideia-ops?kiosk'

export function OpsPage() {
  const summary = useQuery({
    queryKey: ['admin-ops-summary'],
    // requestEnvelope 取的是统一结构的 data 字段；request 返回整包（含 code/message/traceId）。
    // 用错它会在渲染时抛 "Cannot read properties of undefined (reading 'length')"——
    // 实测踩过：页面只显示 "Unexpected Application Error!"，接口本身是 200。
    queryFn: () => api.requestEnvelope<Summary>('/api/v1/ops/admin/summary'),
    // 面板本身 30 秒刷新一次；卡片跟同一个节奏，免得两处数字对不上
    refetchInterval: 30_000,
  })

  if (summary.isPending) {
    return (
      <>
        <PageHeader title="运维" description="服务、中间件、外部依赖与备份的当前状态。" />
        <div className="text-muted-foreground flex items-center gap-2 text-sm">
          <Spinner /> 正在采集…
        </div>
      </>
    )
  }

  if (summary.isError) {
    return (
      <>
        <PageHeader title="运维" description="服务、中间件、外部依赖与备份的当前状态。" />
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <ShieldAlert />
            </EmptyMedia>
            <EmptyTitle>采集失败</EmptyTitle>
            {/* 这里说"取不到"，不说"一切正常"——运维页最不该做的就是猜 */}
            <EmptyDescription>摘要接口没有返回数据。这不代表服务正常，只代表看不了。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      </>
    )
  }

  const data = summary.data

  return (
    <>
      <PageHeader
        title="运维"
        description="服务、中间件、外部依赖与备份的当前状态。趋势与下钻在下面的面板里。"
        actions={
          <Badge variant={data.prometheusReachable ? 'success' : 'destructive'}>
            {data.prometheusReachable ? '采集正常' : 'Prometheus 不可达'}
          </Badge>
        }
      />

      {data.groups.length === 0 ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <RefreshCw />
            </EmptyMedia>
            <EmptyTitle>还没有数据</EmptyTitle>
            <EmptyDescription>Prometheus 刚起来时可能还没有抓到第一轮。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {data.groups.map((group) => {
            const Icon = ICONS[group.key] ?? Server
            const style = STATUS_STYLE[group.status]
            return (
              <Card key={group.key} data-testid={`ops-group-${group.key}`}>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <span className="tile-surface border-border text-primary grid size-8 shrink-0 place-items-center rounded-lg border">
                      <Icon className="size-4" />
                    </span>
                    {group.title}
                  </CardTitle>
                  <CardAction>
                    <Badge variant={style.variant} data-testid={`ops-status-${group.key}`}>
                      {style.label}
                    </Badge>
                  </CardAction>
                </CardHeader>
                <CardContent className="text-muted-foreground text-xs leading-relaxed">
                  {group.reason}
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}

      <DiagnosisSection />

      <PatternSection />

      {/*
        面板用 Grafana 自己的登录：iframe 的子请求带不上平台令牌（本项目无 Cookie、纯 Bearer），
        鉴权只能落在 Grafana 的会话上。**第一次打开会要求 Grafana 口令**，之后由浏览器记住。
        这句话要写在界面上——否则管理员会以为是 bug。
      */}
      <p className="text-muted-foreground mb-3 text-xs">
        面板第一次打开需要 Grafana 口令（它有自己的会话），之后浏览器会记住。
      </p>
      <Card className="overflow-hidden py-0">
        <iframe
          title="Paideia 运维面板"
          src={PANEL_URL}
          className="h-[calc(100dvh-15rem)] min-h-[480px] w-full border-0"
        />
      </Card>
    </>
  )
}

/**
 * AI 诊断区（WORK-042）。
 *
 * <p>报告全文只在展开时按需取：列表一次 20 条，带全文会把响应撑大，而且大多数时候没人看。
 * 有诊断在跑时列表每 3 秒刷新一次；全部结束后停止轮询——没人看的页面不该一直打接口。
 */
function DiagnosisSection() {
  const [question, setQuestion] = useState('')
  const [openId, setOpenId] = useState<number | null>(null)
  const queryClient = useQueryClient()

  const diagnoses = useQuery({
    queryKey: ['admin-ops-diagnoses'],
    queryFn: () => api.requestEnvelope<Diagnosis[]>('/api/v1/ops/admin/diagnoses'),
    refetchInterval: (query) => {
      const rows = query.state.data
      return rows?.some((row) => row.status === 'PENDING' || row.status === 'RUNNING') ? 3_000 : false
    },
  })

  const start = useMutation({
    mutationFn: (text: string) =>
      api.requestEnvelope<{ id: number }>('/api/v1/ops/admin/diagnoses', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ question: text.trim() === '' ? undefined : text.trim() }),
      }),
    onSuccess: (data) => {
      setQuestion('')
      setOpenId(data.id)
      void queryClient.invalidateQueries({ queryKey: ['admin-ops-diagnoses'] })
    },
  })

  // 好评率只随评分变化，跟着列表一起刷就够了（不额外加定时器）
  const stats = useQuery({
    queryKey: ['admin-ops-diagnosis-stats'],
    queryFn: () => api.requestEnvelope<DiagnosisStats>('/api/v1/ops/admin/diagnoses/stats'),
  })

  return (
    <Card className="mb-6" data-testid="ops-diagnosis-card">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <span className="tile-surface border-border text-primary grid size-8 shrink-0 place-items-center rounded-lg border">
            <Sparkles className="size-4" />
          </span>
          AI 诊断
        </CardTitle>
        <CardDescription>
          采集当前指标、告警、最近错误日志与发版时间，交给模型写一份根因假设。只读，不会自动修复。
          {/* 好评率不驱动任何自动化——它只用来回答"这套诊断值不值得继续用" */}
          {stats.data && stats.data.rated > 0 ? (
            <span data-testid="ops-helpful-rate">
              {' '}
              已评 {stats.data.rated} 份，好评率{' '}
              {stats.data.helpfulRate === null
                ? '—'
                : `${Math.round(stats.data.helpfulRate * 100)}%`}
              （准 {stats.data.good} / 部分准 {stats.data.partial} / 不准 {stats.data.bad}）
            </span>
          ) : null}
        </CardDescription>
        <CardAction>
          <Button
            onClick={() => start.mutate(question)}
            disabled={start.isPending}
            data-testid="ops-diagnose"
          >
            {start.isPending ? '提交中…' : '开始诊断'}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        <div className="mb-4">
          <Input
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            maxLength={500}
            placeholder="可选：这次想重点看什么？例如「为什么昨晚变慢」"
            aria-label="诊断关注点"
          />
        </div>

        {start.isError ? (
          <p className="text-destructive mb-3 text-sm" role="alert">
            提交失败：{errorMessage(start.error, '未知错误')}
          </p>
        ) : null}

        {diagnoses.isPending ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Spinner /> 正在读取诊断记录…
          </p>
        ) : diagnoses.isError ? (
          <p className="text-muted-foreground text-sm">
            诊断记录读取失败：{errorMessage(diagnoses.error, '未知错误')}
          </p>
        ) : diagnoses.data.length === 0 ? (
          <p className="text-muted-foreground text-sm" data-testid="ops-diagnosis-empty">
            还没有诊断记录。告警触发或点上面的按钮后会出现在这里。
          </p>
        ) : (
          <ul data-testid="ops-diagnosis-list">
            {diagnoses.data.map((item) => (
              <DiagnosisItem
                key={item.id}
                item={item}
                open={openId === item.id}
                onToggle={() => setOpenId(openId === item.id ? null : item.id)}
              />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function DiagnosisItem({
  item,
  open,
  onToggle,
}: {
  item: Diagnosis
  open: boolean
  onToggle: () => void
}) {
  const style = DIAGNOSIS_STATUS[item.status]
  const queryClient = useQueryClient()
  const [note, setNote] = useState('')
  const detail = useQuery({
    queryKey: ['admin-ops-diagnosis', item.id],
    queryFn: () => api.requestEnvelope<Diagnosis>(`/api/v1/ops/admin/diagnoses/${item.id}`),
    enabled: open && item.status === 'DONE',
  })

  // 评分是覆盖式更新：改判要能改，且统计里只算一次（后端按行更新，不追加）
  const rate = useMutation({
    mutationFn: (rating: number) =>
      api.requestEnvelope<Diagnosis>(`/api/v1/ops/admin/diagnoses/${item.id}/feedback`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ rating, note: note.trim() === '' ? undefined : note.trim() }),
      }),
    onSuccess: () => {
      setNote('')
      void queryClient.invalidateQueries({ queryKey: ['admin-ops-diagnoses'] })
      void queryClient.invalidateQueries({ queryKey: ['admin-ops-diagnosis-stats'] })
      void queryClient.invalidateQueries({ queryKey: ['admin-ops-diagnosis', item.id] })
    },
  })

  const label = item.triggerType === 'alert' ? `告警：${item.alertName ?? '未知'}` : '手动诊断'

  // 起草复盘（WORK-086）：只起草、不确认——闸门在复盘页，免得一键把模型产出变成"经验"。
  const draft = useMutation({
    mutationFn: () =>
      api.requestEnvelope<{ id: number }>('/api/v1/ops/admin/postmortems', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ diagnosisId: item.id }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['ops', 'postmortems'] })
      window.location.hash = '#/ops/postmortems'
    },
  })

  return (
    <li className="border-border border-t py-3 first:border-t-0">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-3 text-left"
      >
        <span className="flex flex-wrap items-center gap-2 text-sm">
          <Badge variant={style.variant}>{style.label}</Badge>
          <span>{label}</span>
          <span className="text-muted-foreground text-xs">{formatTime(item.createdAt)}</span>
          {item.status === 'DONE' && item.inputTokens !== null ? (
            <span className="text-muted-foreground text-xs">
              {(item.inputTokens ?? 0) + (item.outputTokens ?? 0)} tokens
            </span>
          ) : null}
        </span>
        <span className="text-muted-foreground shrink-0 text-xs">
          {item.status === 'DONE'
            ? open
              ? '收起'
              : '查看报告'
            : item.status === 'FAILED'
              ? '查看原因'
              : '处理中…'}
        </span>
      </button>

      {open ? (
        item.status === 'FAILED' ? (
          <p className="text-destructive mt-2 text-sm">
            {item.errorMessage ?? '诊断失败，但没有记录原因。'}
          </p>
        ) : item.status === 'DONE' ? (
          detail.isPending ? (
            <p className="text-muted-foreground mt-2 flex items-center gap-2 text-sm">
              <Spinner /> 读取报告…
            </p>
          ) : detail.isError ? (
            <p className="text-muted-foreground mt-2 text-sm">
              报告读取失败：{errorMessage(detail.error, '未知错误')}
            </p>
          ) : detail.data?.reportMd ? (
            <div className="mt-3">
              <Markdown markdown={detail.data.reportMd} className="text-sm" />
              {/*
                评分（WORK-043）：只对已完成的报告出现。三档而不是五颗星——
                要回答的是"能不能照着做"，星级会把这个问题拆成没法汇总的东西。
              */}
              <div
                className="border-border mt-4 flex flex-wrap items-center gap-2 border-t pt-3"
                data-testid={`ops-feedback-${item.id}`}
              >
                <span className="text-muted-foreground text-xs">这份诊断准不准？</span>
                {RATINGS.map((option) => (
                  <Button
                    key={option.value}
                    size="sm"
                    variant={item.rating === option.value ? 'default' : 'outline'}
                    onClick={() => rate.mutate(option.value)}
                    disabled={rate.isPending}
                    data-testid={`ops-rate-${option.value}`}
                  >
                    {option.label}
                  </Button>
                ))}
                <Input
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  maxLength={500}
                  placeholder="可选：一句备注（会写进事件时间线）"
                  aria-label="评分备注"
                  className="max-w-xs"
                />
                {item.rating !== null ? (
                  <span className="text-muted-foreground text-xs">
                    已评：{RATINGS.find((option) => option.value === item.rating)?.label ?? item.rating}
                  </span>
                ) : null}
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => draft.mutate()}
                  disabled={draft.isPending}
                  data-testid={`ops-postmortem-${item.id}`}
                  title="起草一份复盘草稿；确认后才进入知识召回"
                >
                  生成复盘
                </Button>
              </div>
              {draft.isError ? (
                <p className="text-destructive mt-2 text-xs" role="alert">
                  起草复盘失败：{errorMessage(draft.error, '未知错误')}
                </p>
              ) : null}
              {rate.isError ? (
                <p className="text-destructive mt-2 text-xs" role="alert">
                  评分失败：{errorMessage(rate.error, '未知错误')}
                </p>
              ) : null}
            </div>
          ) : (
            <p className="text-muted-foreground mt-2 text-sm">没有报告内容。</p>
          )
        ) : (
          <p className="text-muted-foreground mt-2 text-sm">诊断进行中，完成后列表会自动刷新。</p>
        )
      ) : null}
    </li>
  )
}

/**
 * 日志模板与模板异常（WORK-085）。
 *
 * <p>先聚类再给人看：几十万行日志被 Loki 的 pattern ingester 压成几十个模板，这里只展示
 * 「当前窗口 Top 模板」与「近 24 小时的模板级异常」。异常卡只做展示与指路——真正的原因分析
 * 走上面的 AI 诊断（模板异常会自动触发一条诊断，diagnosisId 会回填）。
 *
 * <p>只读，不改路由：挂在既有「运维」页里，避免"新页面没入口等于没上线"。
 */
function PatternSection() {
  const overview = useQuery({
    queryKey: ['admin-ops-patterns'],
    queryFn: () => api.requestEnvelope<PatternOverview>('/api/v1/ops/admin/patterns'),
    refetchInterval: 30_000,
  })

  const top = overview.data?.top ?? []
  const anomalies = overview.data?.anomalies ?? []

  return (
    <Card className="mb-6" data-testid="ops-pattern-card">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <span className="tile-surface border-border text-primary grid size-8 shrink-0 place-items-center rounded-lg border">
            <Activity className="size-4" />
          </span>
          日志模板与异常
        </CardTitle>
        <CardDescription>
          日志先压成模板再看频次：当前窗口 Top 模板、近 24 小时的模板级异常（频次突增 / 新模板 / 模板消失）。
          模板消失往往比报错更危险——它意味着服务静默。
        </CardDescription>
        <CardAction>
          {anomalies.length > 0 ? (
            <Badge variant="destructive" data-testid="ops-pattern-anomaly-count">
              {anomalies.length} 条异常
            </Badge>
          ) : (
            <Badge variant="outline">无异常</Badge>
          )}
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-4">
        {overview.isPending ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Spinner /> 正在读取模板…
          </p>
        ) : overview.isError ? (
          <p className="text-muted-foreground text-sm">
            模板读取失败：{errorMessage(overview.error, '未知错误')}
          </p>
        ) : (
          <>
            <div>
              <p className="text-muted-foreground mb-2 text-xs">当前窗口 Top 模板</p>
              {top.length === 0 ? (
                <p className="text-muted-foreground text-sm" data-testid="ops-pattern-top-empty">
                  暂无模板数据。Loki pattern_ingester 刚开启时可能要等一两个同步周期。
                </p>
              ) : (
                <ul className="divide-border divide-y" data-testid="ops-pattern-top-list">
                  {top.slice(0, 10).map((item) => (
                    <li key={`${item.unit}-${item.templateHash}`} className="flex items-start gap-3 py-2 text-sm">
                      <Badge variant="outline" className="shrink-0">
                        {item.unit}
                      </Badge>
                      <code className="text-muted-foreground min-w-0 flex-1 truncate text-xs">{item.pattern}</code>
                      <span className="text-muted-foreground shrink-0 text-xs">{item.count} 次</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <p className="text-muted-foreground mb-2 text-xs">近 24 小时模板异常</p>
              {anomalies.length === 0 ? (
                <p className="text-muted-foreground text-sm" data-testid="ops-pattern-anomaly-empty">
                  没有检测到模板级异常。
                </p>
              ) : (
                <ul className="divide-border divide-y" data-testid="ops-pattern-anomaly-list">
                  {anomalies.slice(0, 20).map((item) => {
                    const style = PATTERN_KIND[item.kind] ?? { label: item.kind, variant: 'outline' as const }
                    return (
                      <li key={item.id} className="py-2">
                        <div className="flex flex-wrap items-center gap-2 text-sm">
                          <Badge variant={style.variant}>{style.label}</Badge>
                          <Badge variant="outline">{item.unit}</Badge>
                          <span className="text-muted-foreground text-xs">{formatTime(item.createdAt)}</span>
                          {item.diagnosisId ? (
                            <span className="text-muted-foreground text-xs">已触发诊断 #{item.diagnosisId}</span>
                          ) : null}
                        </div>
                        {item.detail ? (
                          <p className="text-muted-foreground mt-1 text-xs leading-relaxed">{item.detail}</p>
                        ) : null}
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function formatTime(value: string): string {
  return formatLocalDateTime(value)
}
