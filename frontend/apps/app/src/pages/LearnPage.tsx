import { createIdentityApi } from '@paideia/identity'
import {
  createLearningApi,
  explainParts,
  normalizeRole,
  pickAdvice,
  ROLES,
  roleMeta,
  ruleReason,
  scoreTracks,
  tierOf,
  type RoleKey,
  type ScoredTrack,
  type TrackSummary,
  type TrackTier,
} from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Card,
  CardContent,
  ErrorAlert,
  Input,
  PageHeader,
} from '@paideia/ui'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  ArrowRight,
  Binary,
  BookOpenCheck,
  Bot,
  Boxes,
  Braces,
  Brain,
  Bug,
  CheckCircle2,
  Coffee,
  Cpu,
  Code2,
  Container,
  Database,
  FolderCode,
  GitBranch,
  Layers,
  Leaf,
  Map as MapIcon,
  Monitor,
  Network,
  Play,
  Puzzle,
  Search,
  Table2,
  Target,
  Terminal,
  Workflow,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { TrackGridSkeleton } from '../components/skeletons'
import { OTHER_GROUP, TRACK_GROUPS, groupDefOf, groupKeyOf } from '../learn-track-groups'

const learning = createLearningApi(api)
const identity = createIdentityApi(api)

/**
 * 每条轨道自己的图标。
 *
 * <p>**这是这一页最重要的一件事**：原来 17 条理论轨道全用同一个 `BookOpenCheck`，
 * 于是 12 张卡上的图标是 12 次重复的同一个装饰——不传递任何信息，只让页面看起来
 * "每张卡都长一样"。按主题给不同的字形之后，扫一眼就能定位到想找的那条。
 *
 * <p>按 slug 查而不是按 kind：kind 只有 reading/course 两种，区分不出主题。
 * 新加的轨道查不到就用兜底图标，不会因为漏配而渲染不出来。
 */
const TRACK_ICONS: Record<string, typeof BookOpenCheck> = {
  git: GitBranch,
  'python-from-zero': Braces,
  'java-from-zero': Coffee,
  'mysql-from-zero': Database,
  spring: Leaf,
  jvm: Cpu,
  'java-project': FolderCode,
  'java-debug': Bug,
  'sql-practice': Table2,
  redis: Layers,
  'network-basics': Network,
  linux: Terminal,
  'linux-from-zero': Terminal,
  docker: Container,
  concurrency: Workflow,
  os: Monitor,
  patterns: Puzzle,
  algorithms: Binary,
  ai: Brain,
  'system-design': Boxes,
  agents: Bot,
}

/**
 * 从零开始（WORK-024 / WORK-038）。
 *
 * <p><b>这不再是知识库</b>：轨道是独立实体，入口、路径与数据都与 `/knowledge` 分开。
 * 用户明确要求"从零开始不要是知识库"——沿着一条轨道一关一关往下走，和"挑一篇文章读"
 * 是两件事，前者需要关卡、顺序与前置依赖，后者只需要分类与列表。
 *
 * <p><b>主题分类 + 搜索</b>（本次）：轨道涨到三十几条后，"核心/进阶/选读"三层已经不够
 * 用来找东西了。这一页改成**按主题分组的目录**，顶部给搜索框与分类 chip；岗位仍只影响
 * 推荐顺序与卡上的「核心/进阶」标记，不构成门禁。
 */
export function LearnPage() {
  const tracks = useQuery({
    queryKey: ['learning-tracks'],
    queryFn: () => learning.tracks(),
  })
  // 与页头共用同一个 queryKey：切换岗位写回资料后，两处一起更新
  const profile = useQuery({
    queryKey: ['profile'],
    queryFn: () => identity.profile(),
    staleTime: 5 * 60_000,
    retry: false,
  })

  /**
   * 「继续上次」只给**真正开始过**的人：没进度的新用户看见"继续上次"会以为系统记错了，
   * 他们的入口是下面的推荐卡。已全部通关的也不进——那是在催人重刷。
   */
  const next = useMemo(() => {
    const list = tracks.data ?? []
    return (
      list.find(
        (track) => track.passedCount > 0 && track.passedCount < track.stageCount && track.nextTitle !== null,
      ) ?? null
    )
  }, [tracks.data])

  /**
   * 本地选择优先，其次资料里存的岗位，最后兜底默认岗位。
   *
   * <p>先本地再远端是刻意的：切岗位要**立刻**看到地图变化，不能等 PUT 回来。
   * 写回失败也不回滚——这次的选择仍然有效，只是下次进来会回到资料里的值。
   *
   * <p>写回的是**岗位名**不是 `RoleKey`：`targetRole` 是资料页那个自由文本输入框，
   * 面试官读的也是它。写 `backend` 进去，用户会看到自己的目标岗位被改成了英文键值
   * （2026-11-02 审查抓到）。`normalizeRole` 认 label，所以下次读得回来。
   */
  const [picked, setPicked] = useState<RoleKey | null>(null)
  const role = picked ?? normalizeRole(profile.data?.targetRole)
  const meta = roleMeta(role)

  const chooseRole = (next: RoleKey) => {
    setPicked(next)
    const current = profile.data
    if (current === undefined) {
      return
    }
    void identity.saveProfile({ ...current, targetRole: roleMeta(next).label }).catch(() => undefined)
  }

  const scored = useMemo(
    // now 只影响"最近 48 小时动过"的衰减；该字段目前恒为 null，所以它暂不参与打分
    () => scoreTracks(tracks.data ?? [], role, new Date()),
    [tracks.data, role],
  )
  const picks = useMemo(() => pickAdvice(scored, 3), [scored])

  /** 目录筛选：搜索词 + 主题分类。默认「全部」，只影响这一页怎么找内容，不影响岗位推荐。 */
  const [query, setQuery] = useState('')
  const [groupKey, setGroupKey] = useState<string>('all')

  /**
   * AI 推荐理由（WORK-047 的 `POST /learning/advice`，WORK-066 之后这里才有了入口）。
   *
   * <p>**用户点才调**：一次调用会真花积分，进页面就自动润色等于替用户花钱。
   * 失败或平台没配 AI 时服务端返回空数组，卡片继续用规则句——所以这只是锦上添花。
   */
  const [aiReasons, setAiReasons] = useState<Record<string, string>>({})
  const [adviceFailed, setAdviceFailed] = useState(false)
  const explainAdvice = useMutation({
    mutationFn: () =>
      learning.advice({
        role,
        roleLabel: meta.label,
        picks: picks.map((pick) => ({
          slug: pick.slug,
          name: pick.name,
          tier: pick.tier,
          passed: pick.passedCount,
          total: pick.stageCount,
        })),
      }),
    onSuccess: (result) => {
      const reasons: Record<string, string> = {}
      for (const item of result.reasons) {
        reasons[item.slug] = item.reason
      }
      setAiReasons(reasons)
      setAdviceFailed(Object.keys(reasons).length === 0)
    },
    onError: () => setAdviceFailed(true),
  })

  // 换岗位后旧理由不再成立
  useEffect(() => {
    setAiReasons({})
    setAdviceFailed(false)
  }, [role])

  /**
   * 三段地图：**按岗位的 rank 排，不按分数排**。
   *
   * <p>分数回答"接下来先学什么"，段内顺序回答"这个岗位的学习次序"。混用会让同一段里的
   * 卡片顺序每次进来都变，看起来像随机。这里只用于合计进度与推荐，不再直接铺三层。
   */
  const sections = useMemo(() => {
    const buckets: Record<TrackTier, { track: TrackSummary; rank: number }[]> = {
      core: [],
      advanced: [],
      optional: [],
    }
    for (const track of tracks.data ?? []) {
      const { tier, rank } = tierOf(role, track.slug)
      buckets[tier].push({ track, rank })
    }
    for (const list of Object.values(buckets)) {
      list.sort((left, right) => left.rank - right.rank || left.track.name.localeCompare(right.track.name))
    }
    return {
      core: buckets.core.map((item) => item.track),
      advanced: buckets.advanced.map((item) => item.track),
      optional: buckets.optional.map((item) => item.track),
    }
  }, [tracks.data, role])

  /** 核心 + 进阶的合计：选读不该稀释"这个岗位到底有多少要走"。 */
  const roleTotals = useMemo(() => {
    const list = [...sections.core, ...sections.advanced]
    const stages = list.reduce((sum, track) => sum + track.stageCount, 0)
    const passed = list.reduce((sum, track) => sum + track.passedCount, 0)
    return { stages, passed }
  }, [sections])

  /**
   * 主题组的先后**跟着岗位走**。
   *
   * <p>原来组顺序写死在 `TRACK_GROUPS` 里，于是切岗位时只有组内几条卡挪位，
   * 用户看到的是"上面切换、下面不动"（2026-11-06 反馈）——联动做了，但看不见。
   * 这里按「该组在当前岗位里的核心条数 → 进阶条数」重排，切岗位时整页结构换一次。
   * 同分保持 `TRACK_GROUPS` 的原顺序，否则每次进来都像随机。
   */
  const groupOrder = useMemo(() => {
    const weight = new Map<string, { core: number; advanced: number }>()
    for (const track of tracks.data ?? []) {
      const key = groupKeyOf(track.slug)
      const entry = weight.get(key) ?? { core: 0, advanced: 0 }
      const { tier } = tierOf(role, track.slug)
      if (tier === 'core') entry.core += 1
      if (tier === 'advanced') entry.advanced += 1
      weight.set(key, entry)
    }
    const order = [...TRACK_GROUPS.map((group) => group.key), OTHER_GROUP.key]
    const base = new Map(order.map((key, index) => [key, index]))
    return order.sort((left, right) => {
      const a = weight.get(left) ?? { core: 0, advanced: 0 }
      const b = weight.get(right) ?? { core: 0, advanced: 0 }
      return b.core - a.core || b.advanced - a.advanced || base.get(left)! - base.get(right)!
    })
  }, [tracks.data, role])

  /**
   * 主题目录：先按搜索词与所选分类过滤，再按主题分组。
   *
   * <p>组内排序用**岗位 rank**（核心在前），同 rank 按名字——让"这个岗位该先看的"
   * 在每个主题里也浮上来，而不是被字母序打散。
   */
  const catalog = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    const grouped = new Map<string, TrackSummary[]>()
    for (const track of tracks.data ?? []) {
      if (groupKey !== 'all' && groupKeyOf(track.slug) !== groupKey) continue
      if (keyword !== '') {
        const haystack = `${track.name} ${track.summary ?? ''} ${track.slug}`.toLowerCase()
        if (!haystack.includes(keyword)) continue
      }
      const key = groupKeyOf(track.slug)
      const list = grouped.get(key)
      if (list) list.push(track)
      else grouped.set(key, [track])
    }
    return groupOrder
      .filter((key) => (grouped.get(key)?.length ?? 0) > 0)
      .map((key) => {
        const def = groupDefOf(key)
        const list = grouped.get(key) ?? []
        list.sort((left, right) => {
          const rankLeft = tierOf(role, left.slug).rank
          const rankRight = tierOf(role, right.slug).rank
          return rankLeft - rankRight || left.name.localeCompare(right.name)
        })
        return { key, label: def.label, hint: def.hint, tracks: list }
      })
  }, [tracks.data, query, groupKey, role, groupOrder])

  /** 每个分类下的轨道总数（不随搜索变化），供分类 chip 显示数量。 */
  const groupCounts = useMemo(() => {
    const counts = new Map<string, number>()
    for (const track of tracks.data ?? []) {
      const key = groupKeyOf(track.slug)
      counts.set(key, (counts.get(key) ?? 0) + 1)
    }
    return counts
  }, [tracks.data])

  const matchedCount = catalog.reduce((sum, group) => sum + group.tracks.length, 0)

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="从零开始"
        description="按主题找，或被岗位推荐带路。岗位只影响排序，不影响你能看什么。"
      />

      {/* 分区标题不是装饰：没有它，上面这排 chip 会被读成"目录的筛选器"，
          切了下面的内容却没明显变化，就成了 bug 手感（2026-11-06 反馈）。 */}
      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 className="text-lg font-semibold">你的路线</h2>
        <span className="text-muted-foreground text-sm">按「{meta.label}」排 · 岗位只改顺序，不藏内容</span>
      </div>

      <RolePicker role={role} onChoose={chooseRole} />

      {tracks.isSuccess && roleTotals.stages > 0 ? (
        <p className="text-muted-foreground mb-4 text-sm" data-testid="learn-role-progress">
          核心 + 进阶共 {roleTotals.stages} 关 · 已通关 {roleTotals.passed} 关（
          {Math.round((roleTotals.passed / roleTotals.stages) * 100)}%）
        </p>
      ) : null}

      {!meta.available ? (
        <Alert className="mb-6" data-testid="learn-role-empty">
          <AlertTitle>{meta.label}的内容还在建设中</AlertTitle>
          <AlertDescription>
            这个岗位暂时没有专属内容，下面按主题排列全部轨道；岗位推荐会跳过它，不影响你能看什么。
          </AlertDescription>
        </Alert>
      ) : null}

      {tracks.isPending ? <TrackGridSkeleton /> : null}

      {tracks.isError ? (
        <ErrorAlert title="学习轨道加载失败" error={tracks.error} testId="learn-state" />
      ) : null}

      {tracks.isSuccess && tracks.data.length === 0 ? (
        <Alert data-testid="learn-empty">
          <AlertTitle>还没有学习轨道</AlertTitle>
          <AlertDescription>内容导入后这里会出现。</AlertDescription>
        </Alert>
      ) : null}

      {meta.available && picks.length > 0 ? (
        <AdviceCard
          picks={picks}
          aiReasons={aiReasons}
          explaining={explainAdvice.isPending}
          failed={adviceFailed}
          onExplain={() => explainAdvice.mutate()}
        />
      ) : null}

      {/* 「继续上次」：地图里真正该先点的只有一条，别让人自己在卡片里找"下一关" */}
      {tracks.isSuccess && next !== null ? (
        <Link
          to={`/learn/${next.slug}`}
          data-testid="learn-continue"
          className="border-primary/30 bg-primary/[0.04] hover:border-primary/60 group mb-6 flex items-center gap-4 rounded-xl border p-4 transition-colors focus-ring sm:p-5"
        >
          <span className="bg-primary/10 text-primary flex size-11 shrink-0 items-center justify-center rounded-lg">
            <Play className="size-5" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="text-muted-foreground block text-xs">继续上次</span>
            <span className="mt-0.5 block truncate text-base font-medium">{next.name}</span>
            <span className="text-muted-foreground mt-0.5 block truncate text-sm">
              {next.passedCount === 0
                ? `从这里开始：${next.nextTitle ?? '—'}`
                : `已过 ${next.passedCount}/${next.stageCount} 关 · 下一关：${next.nextTitle ?? '—'}`}
            </span>
          </span>
          <ArrowRight className="text-muted-foreground group-hover:text-primary size-5 shrink-0 transition-colors" />
        </Link>
      ) : null}

      {tracks.isSuccess && tracks.data.length > 0 ? (
        <div data-testid="learn-catalog" className="border-border border-t pt-6">
          <div className="mb-4 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <h2 className="text-lg font-semibold">全部轨道</h2>
            <span className="text-muted-foreground text-sm">
              {tracks.data.length} 条 · 按主题分，和岗位无关；组的先后跟着上面的岗位
            </span>
          </div>
          <CatalogToolbar
            query={query}
            onQuery={setQuery}
            groupKey={groupKey}
            onGroup={setGroupKey}
            groupOrder={groupOrder}
            counts={groupCounts}
            total={tracks.data.length}
            matched={matchedCount}
          />
          {catalog.length === 0 ? (
            <Alert data-testid="learn-no-match">
              <AlertTitle>没有匹配的轨道</AlertTitle>
              <AlertDescription>换个关键词，或点「全部」看看所有分类。</AlertDescription>
            </Alert>
          ) : (
            catalog.map((group) => (
              <CategorySection
                key={group.key}
                groupKey={group.key}
                title={group.label}
                hint={group.hint}
                tracks={group.tracks}
                role={role}
              />
            ))
          )}
        </div>
      ) : null}

      {/* 训练营（WORK-029）：它是"还没有任何学习记录的人"的入口，
          所以和轨道并列出现在这一页，而不是只藏在简历页里。 */}
      <Link
        to="/camp"
        data-testid="learn-camp-entry"
        className="border-border bg-card hover:border-primary/40 mt-6 flex items-center gap-4 rounded-xl border p-5 transition-colors"
      >
        <span className="bg-primary/10 text-primary flex size-10 shrink-0 items-center justify-center rounded-lg">
          <Target className="size-5" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-base font-medium">用简历开一个 AI 训练营</span>
          <span className="text-muted-foreground mt-0.5 block text-sm">
            先做诊断题，按薄弱点排每天学什么；走完再复测一次，两次并排看变化。
          </span>
        </span>
        <ArrowRight className="text-muted-foreground size-4 shrink-0" />
      </Link>

      <Card className="mt-6">
        <CardContent className="flex items-start gap-3 py-4">
          <MapIcon className="text-muted-foreground mt-0.5 size-4 shrink-0" />
          <p className="text-muted-foreground text-sm">
            想看自己的学习痕迹？<Link to="/graph" className="text-primary underline-offset-2 hover:underline">
              知识图谱
            </Link>
            会按概念显示掌握度与待复习项。
          </p>
        </CardContent>
      </Card>
    </AppShell>
  )
}

/**
 * 岗位选择器。
 *
 * <p>用 `aria-pressed` 而不是只靠颜色：色觉障碍与读屏都要能判断"当前选的是哪个"。
 */
function RolePicker({ role, onChoose }: { role: RoleKey; onChoose: (next: RoleKey) => void }) {
  return (
    <div className="mb-6" data-testid="learn-roles">
      <div role="group" aria-label="目标岗位" className="flex flex-wrap gap-2">
        {ROLES.map((item) => {
          const active = item.key === role
          return (
            <button
              key={item.key}
              type="button"
              data-testid={`learn-role-${item.key}`}
              aria-pressed={active}
              onClick={() => onChoose(item.key)}
              className={`rounded-full border px-4 py-2 text-sm transition-colors ${
                active
                  ? 'border-primary bg-primary/10 text-primary font-medium'
                  : 'border-border bg-card hover:border-primary/40'
              }`}
            >
              {item.label}
              {item.available ? null : <span className="text-muted-foreground ml-1 text-xs">建设中</span>}
            </button>
          )
        })}
      </div>
      <p className="text-muted-foreground mt-2 text-xs">{roleMeta(role).blurb}</p>
    </div>
  )
}

/**
 * 目录工具栏：搜索框 + 主题分类 chip。
 *
 * <p>分类 chip 只显示"有轨道"的分组，避免一排空分类。选中态同时用底色和 `aria-pressed`
 * 表达，不单靠颜色。
 */
function CatalogToolbar({
  query,
  onQuery,
  groupKey,
  onGroup,
  groupOrder,
  counts,
  total,
  matched,
}: {
  query: string
  onQuery: (next: string) => void
  groupKey: string
  onGroup: (next: string) => void
  /** 已按岗位相关度排好的组 key：chip 的顺序与下面分组的顺序必须一致。 */
  groupOrder: string[]
  counts: Map<string, number>
  total: number
  matched: number
}) {
  const chips = [
    { key: 'all', label: '全部', count: total },
    ...groupOrder
      .filter((key) => key !== OTHER_GROUP.key && (counts.get(key) ?? 0) > 0)
      .map((key) => ({ key, label: groupDefOf(key).label, count: counts.get(key) ?? 0 })),
  ]
  const otherCount = counts.get(OTHER_GROUP.key) ?? 0
  if (otherCount > 0) {
    chips.push({ key: OTHER_GROUP.key, label: OTHER_GROUP.label, count: otherCount })
  }

  return (
    <section className="mb-6" data-testid="learn-catalog-tools">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative min-w-[220px] flex-1">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
          <Input
            data-testid="learn-search"
            type="search"
            value={query}
            onChange={(event) => onQuery(event.target.value)}
            placeholder="搜索轨道：Java、Docker、智能体…"
            aria-label="搜索学习轨道"
            className="h-10 pl-9"
          />
        </div>
        <span className="text-muted-foreground shrink-0 text-sm" data-testid="learn-search-count">
          共 {matched} 条
        </span>
      </div>

      <div role="group" aria-label="按主题筛选" className="mt-3 flex flex-wrap gap-2">
        {chips.map((chip) => {
          const active = groupKey === chip.key
          return (
            <button
              key={chip.key}
              type="button"
              data-testid={`learn-category-${chip.key}`}
              aria-pressed={active}
              onClick={() => onGroup(chip.key)}
              className={`rounded-full border px-3 py-1.5 text-sm transition-colors ${
                active
                  ? 'border-primary bg-primary/10 text-primary font-medium'
                  : 'border-border bg-card hover:border-primary/40'
              }`}
            >
              {chip.label}
              <span className="text-muted-foreground ml-1 text-xs tabular-nums">{chip.count}</span>
            </button>
          )
        })}
      </div>
    </section>
  )
}

/**
 * 一个主题分类：可折叠的标题 + 卡片网格。
 *
 * <p>分成多段后一屏会很长，所以每段都做成原生 `<details>`（默认展开、可收起），
 * 折叠状态直接拿到键盘与读屏语义，不用自己补 aria。
 */
function CategorySection({
  groupKey,
  title,
  hint,
  tracks,
  role,
}: {
  groupKey: string
  title: string
  hint: string
  tracks: TrackSummary[]
  role: RoleKey
}) {
  const [open, setOpen] = useState(true)
  const passed = tracks.reduce((sum, track) => sum + track.passedCount, 0)
  const stages = tracks.reduce((sum, track) => sum + track.stageCount, 0)
  return (
    <details
      className="mt-2 mb-8"
      data-testid={`learn-group-${groupKey}`}
      open={open}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="flex cursor-pointer list-none flex-wrap items-baseline gap-x-3 gap-y-1 [&::-webkit-details-marker]:hidden">
        <h2 className="text-lg font-semibold">{title}</h2>
        <span className="text-muted-foreground text-sm" data-testid={`learn-group-${groupKey}-count`}>
          {tracks.length} 条 · {stages} 关 · 已通关 {passed}
        </span>
        <span className="text-primary text-xs">{open ? '收起' : '展开'}</span>
      </summary>
      <p className="text-muted-foreground mt-1 mb-4 text-sm">{hint}</p>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {/* 基宽必须显式 `grid-cols-1`：隐式列是 auto（按 min-content 撑），360px 下卡片
            会被内容撑到 394px、整页横向滚 50px。`grid-cols-1` 是 minmax(0,1fr)，卡片才肯缩。 */}
        {tracks.map((track) => (
          <TrackCard key={track.id} track={track} role={role} />
        ))}
      </div>
    </details>
  )
}

/**
 * 推荐卡：三条"接下来先学这些"。
 *
 * <p>AI 写的那句话**只是锦上添花**：请求失败、没积分、模型抽风时都退回规则句，
 * 卡片本身永远在。所以这里没有任何 loading 与 error 态——那会让一次可选的润色
 * 看起来像页面依赖它。
 */
function AdviceCard({
  picks,
  aiReasons,
  explaining,
  failed,
  onExplain,
}: {
  picks: ScoredTrack[]
  aiReasons: Record<string, string>
  explaining: boolean
  failed: boolean
  onExplain: () => void
}) {
  const [showWhy, setShowWhy] = useState(false)
  return (
    <section
      className="border-primary/25 bg-primary/[0.03] mb-6 rounded-xl border p-5"
      data-testid="learn-advice"
    >
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold">接下来先学这三条</h2>
        <span className="text-muted-foreground text-xs">按岗位和你现在的进度算的，每天更新一次</span>
      </div>

      <ol className="divide-border divide-y">
        {picks.map((pick, index) => (
          <li key={pick.slug}>
            <Link
              to={`/learn/${pick.slug}`}
              data-testid={`learn-advice-${pick.slug}`}
              className="hover:bg-background/60 group flex items-center gap-3 rounded-lg px-1 py-3 transition-colors"
            >
              <span className="bg-primary/10 text-primary flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-medium">
                {index + 1}
              </span>
              <span className="min-w-0 flex-1">
                <span className="group-hover:text-primary block text-sm font-medium transition-colors">
                  {pick.name}
                </span>
                <span
                  className="text-muted-foreground mt-0.5 block text-xs"
                  data-testid={`learn-advice-reason-${pick.slug}`}
                >
                  {aiReasons[pick.slug] ?? ruleReason(pick)}
                </span>
              </span>
              <span className="text-muted-foreground shrink-0 text-xs tabular-nums">
                {pick.passedCount}/{pick.stageCount}
              </span>
              <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
            </Link>
          </li>
        ))}
      </ol>

      <div className="mt-2 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={onExplain}
          disabled={explaining || picks.length === 0}
          data-testid="learn-advice-explain"
          className="text-primary hover:text-primary/80 text-xs underline underline-offset-2 disabled:opacity-60"
        >
          {explaining ? 'AI 正在写…' : '让 AI 说说为什么'}
        </button>
        <button
          type="button"
          onClick={() => setShowWhy((value) => !value)}
          data-testid="learn-advice-why"
          className="text-muted-foreground hover:text-foreground text-xs underline underline-offset-2"
        >
          {showWhy ? '收起推荐依据' : '为什么是这三条'}
        </button>
      </div>
      {failed ? (
        <p className="text-muted-foreground mt-2 text-xs" data-testid="learn-advice-error">
          AI 暂时用不了（未配置或额度不足），上面是规则算出来的理由。
        </p>
      ) : null}
      {showWhy ? (
        <ul className="text-muted-foreground mt-2 space-y-1 font-mono text-xs">
          {picks.map((pick) => (
            <li key={pick.slug}>
              {pick.slug}：{explainParts(pick)}
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}

/**
 * 一张轨道卡。
 *
 * <p>三条与"千篇一律"直接相关的决定：
 * <ol>
 *   <li>**图标按轨道区分**（见 `TRACK_ICONS`），不再所有理论轨道共用一个书图标。</li>
 *   <li>**没开始的轨道不画进度条**。0% 的灰条读起来像"这里有个坏掉的元素"，
 *       而且 17 条里 15 条都是 0，于是整页变成 15 条一模一样的灰线。没进度就
 *       换成"8 关待开始"的文案，有进度才画条——进度条从此是一个有信息量的信号。</li>
 *   <li>**正在学的那条给状态徽章**（已通关 / 进行中 / 待开始）。一屏 12 张卡里
 *       只有一条是"你正在做的"，它必须自己站出来。</li>
 * </ol>
 *
 * <p>另加一个极轻的「核心 / 进阶」小标：分类分组后，岗位优先级不该丢，但它只做提示，
 * 不是门禁。
 */
function TrackCard({ track, role }: { track: TrackSummary; role: RoleKey }) {
  const percent = track.stageCount === 0
    ? 0
    : Math.round((track.passedCount / track.stageCount) * 100)
  const Icon = TRACK_ICONS[track.slug] ?? (track.mode === 'practice' ? Code2 : BookOpenCheck)
  const done = track.stageCount > 0 && track.passedCount === track.stageCount
  const started = track.passedCount > 0 && !done
  const { tier } = tierOf(role, track.slug)
  return (
    <Link
      to={`/learn/${track.slug}`}
      data-testid={`learn-track-${track.slug}`}
      data-mode={track.mode}
      data-progress={done ? 'done' : started ? 'started' : 'idle'}
      className="border-border bg-card hover:border-primary/40 hover:shadow-card group flex h-full flex-col rounded-xl border focus-ring p-5 transition-all"
    >
      <div className="flex items-start gap-3">
        <span
          className={`flex size-10 shrink-0 items-center justify-center rounded-lg ${
            // 有进度的用品牌色底，没开始的用中性底：颜色本身就是"这条和你有关"
            started || done ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'
          }`}
        >
          <Icon className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="min-w-0 truncate text-base font-medium">{track.name}</h3>
            {tier === 'core' ? (
              <span className="text-primary shrink-0 text-xs">核心</span>
            ) : tier === 'advanced' ? (
              <span className="text-muted-foreground shrink-0 text-xs">进阶</span>
            ) : null}
            {done ? (
              <span className="text-success flex shrink-0 items-center gap-1 text-xs">
                <CheckCircle2 className="size-3.5" />
                已通关
              </span>
            ) : started ? (
              <span className="text-primary shrink-0 text-xs">进行中</span>
            ) : null}
          </div>
          <p className="text-muted-foreground mt-0.5 text-xs">
            {track.stageCount === 0
              ? '内容准备中'
              : started || done
                ? `${track.stageCount} 关 · 已通关 ${track.passedCount}`
                : `${track.stageCount} 关待开始`}
          </p>
        </div>
      </div>

      {track.summary === null ? null : (
        <p className="text-muted-foreground mt-3 line-clamp-2 text-sm">{track.summary}</p>
      )}

      {/* 只有真开始过才画进度条：0% 的灰条在 15 张卡上重复，等于没有进度信息 */}
      {started || done ? (
        <div
          className="bg-muted mt-4 h-1.5 w-full overflow-hidden rounded-full"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={track.stageCount}
          aria-valuenow={track.passedCount}
          aria-label={`${track.name} 进度`}
        >
          <div
            className={`h-full rounded-full transition-all ${done ? 'bg-success' : 'bg-primary'}`}
            style={{ width: `${percent}%` }}
          />
        </div>
      ) : (
        <div className="mt-4 h-1.5" aria-hidden />
      )}

      {/* mt-auto 把"下一关"压到卡片底部：摘要行数不同时，同排卡片的这一行才对得齐 */}
      <div className="mt-auto flex items-center justify-between gap-2 pt-4">
        <span className="text-muted-foreground min-w-0 truncate text-sm">
          {track.stageCount === 0
            ? '内容准备中'
            : done
              ? '全部通关'
              : `下一关：${track.nextTitle ?? '—'}`}
        </span>
        <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
      </div>
    </Link>
  )
}
