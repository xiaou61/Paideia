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
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
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
  Map,
  Monitor,
  Network,
  Play,
  Puzzle,
  Table2,
  Target,
  Terminal,
  Workflow,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

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
 * <p>**分成理论栏与实战栏**（WORK-038）：理论读完能解释，实战做完能验收。两栏只是分组，
 * 不做任何门禁——用户可以从任何一边进。空轨道也列出来：让它显示"内容准备中"
 * 比让它消失更像一个产品。
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
   * 「继续上次」选哪条：优先**已经开始但没走完**的（有进度说明用户在意它），
   * 否则取第一条还没开始的。已全部通关的不进继续条——那是在催人重刷。
   */
  const next = useMemo(() => {
    const list = tracks.data ?? []
    const started = list.find(
      (track) => track.passedCount > 0 && track.passedCount < track.stageCount && track.nextTitle !== null,
    )
    if (started !== undefined) {
      return started
    }
    return list.find((track) => track.nextTitle !== null) ?? null
  }, [tracks.data])

  /**
   * 本地选择优先，其次资料里存的岗位，最后兜底默认岗位。
   *
   * <p>先本地再远端是刻意的：切岗位要**立刻**看到地图变化，不能等 PUT 回来。
   * 写回失败也不回滚——这次的选择仍然有效，只是下次进来会回到资料里的值。
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
    void identity.saveProfile({ ...current, targetRole: next }).catch(() => undefined)
  }

  const scored = useMemo(
    // now 只影响"最近 48 小时动过"的衰减；该字段目前恒为 null，所以它暂不参与打分
    () => scoreTracks(tracks.data ?? [], role, new Date()),
    [tracks.data, role],
  )
  const picks = useMemo(() => pickAdvice(scored, 3), [scored])

  /**
   * 三段地图：**按岗位的 rank 排，不按分数排**。
   *
   * <p>分数回答"接下来先学什么"，段内顺序回答"这个岗位的学习次序"。混用会让同一段里的
   * 卡片顺序每次进来都变，看起来像随机。
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

  // ---- AI 理由：按「岗位 + 自然日」缓存，失败就退回规则句 ----
  const dayKey = new Date().toISOString().slice(0, 10)
  const cacheKey = `paideia:advice:${role}:${dayKey}`
  const [aiReasons, setAiReasons] = useState<Record<string, string>>({})
  const [nonce, setNonce] = useState(0)
  const pickedKey = picks.map((item) => item.slug).join(',')

  useEffect(() => {
    if (picks.length === 0) {
      setAiReasons({})
      return
    }
    const cached = readAdviceCache(cacheKey)
    if (cached !== null) {
      setAiReasons(cached)
      return
    }
    let alive = true
    void learning
      .advice({
        role,
        roleLabel: meta.label,
        picks: picks.map((item) => ({
          slug: item.slug,
          name: item.name,
          tier: item.tier,
          passed: item.passedCount,
          total: item.stageCount,
        })),
      })
      .then((result) => {
        if (!alive || result.reasons.length === 0) {
          return
        }
        const mapped = Object.fromEntries(result.reasons.map((item) => [item.slug, item.reason]))
        setAiReasons(mapped)
        writeAdviceCache(cacheKey, mapped)
      })
      // 理由缺失是产品允许状态：不设置错误、不打扰用户
      .catch(() => undefined)
    return () => {
      alive = false
    }
    // pickedKey 是 picks 的内容键：数组每次渲染都是新引用，直接依赖会无限重跑
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cacheKey, pickedKey, nonce])

  const shuffle = () => {
    clearAdviceCache(cacheKey)
    setNonce((value) => value + 1)
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="从零开始"
        description="选一个岗位，按它的顺序走。岗位只影响推荐顺序，不影响你能看什么。"
      />

      <RolePicker role={role} onChoose={chooseRole} />

      {!meta.available ? (
        <Alert className="mb-6" data-testid="learn-role-empty">
          <AlertTitle>{meta.label}的内容还在建设中</AlertTitle>
          <AlertDescription>
            这个岗位暂时没有专属内容，下面按 Git、网络、算法三条通用线排序；其余都在「选读」里，
            不影响你能看什么。
          </AlertDescription>
        </Alert>
      ) : null}

      {tracks.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="learn-state">
          <Spinner />
          加载学习轨道…
        </p>
      ) : null}

      {tracks.isError ? (
        <Alert variant="destructive" data-testid="learn-state">
          <AlertTitle>学习轨道加载失败</AlertTitle>
          <AlertDescription>{(tracks.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {tracks.isSuccess && tracks.data.length === 0 ? (
        <Alert data-testid="learn-empty">
          <AlertTitle>还没有学习轨道</AlertTitle>
          <AlertDescription>内容导入后这里会出现。</AlertDescription>
        </Alert>
      ) : null}

      {meta.available && picks.length > 0 ? (
        <AdviceCard picks={picks} reasons={aiReasons} onShuffle={shuffle} />
      ) : null}

      {/* 「继续上次」：地图里真正该先点的只有一条，别让人自己在卡片里找"下一关" */}
      {tracks.isSuccess && next !== null ? (
        <Link
          to={`/learn/${next.slug}`}
          data-testid="learn-continue"
          className="border-primary/30 bg-primary/[0.04] hover:border-primary/60 group mb-6 flex items-center gap-4 rounded-xl border p-4 transition-colors sm:p-5"
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

      {tracks.isSuccess ? (
        <>
          <TrackSection
            testId="learn-core"
            title="核心"
            hint="这个岗位没人能跳过的部分。先走完这几条，再谈其他。"
            tracks={sections.core}
            emptyText="这个岗位的核心内容还没导入。"
          />
          <TrackSection
            testId="learn-advanced"
            title="进阶"
            hint="有核心打底之后才看得懂的部分，顺序没有硬性要求。"
            tracks={sections.advanced}
            emptyText="进阶内容还在准备中。"
          />
          <TrackSection
            testId="learn-optional"
            title="选读"
            hint="加分项，不是门槛。面试前翻一翻，或者遇到具体问题时再回来。"
            tracks={sections.optional}
            emptyText="没有选读内容。"
          />
        </>
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
          <Map className="text-muted-foreground mt-0.5 size-4 shrink-0" />
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
 * 推荐卡：三条"接下来先学这些"。
 *
 * <p>AI 写的那句话**只是锦上添花**：请求失败、没积分、模型抽风时都退回规则句，
 * 卡片本身永远在。所以这里没有任何 loading 与 error 态——那会让一次可选的润色
 * 看起来像页面依赖它。
 */
function AdviceCard({
  picks,
  reasons,
  onShuffle,
}: {
  picks: ScoredTrack[]
  reasons: Record<string, string>
  onShuffle: () => void
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
        <button
          type="button"
          onClick={onShuffle}
          data-testid="learn-advice-shuffle"
          className="text-muted-foreground hover:text-foreground ml-auto text-xs underline underline-offset-2"
        >
          换一批
        </button>
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
                  {reasons[pick.slug] ?? ruleReason(pick)}
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

      <button
        type="button"
        onClick={() => setShowWhy((value) => !value)}
        data-testid="learn-advice-why"
        className="text-muted-foreground hover:text-foreground mt-2 text-xs underline underline-offset-2"
      >
        {showWhy ? '收起推荐依据' : '为什么是这三条'}
      </button>
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
 * 推荐理由的本地缓存：键里带岗位与自然日，所以同一份理由一天只买一次。
 *
 * <p>读写都吞异常：隐私模式或配额满时按"没有缓存"处理——一个可选的缓存不该让整页报错。
 */
function readAdviceCache(key: string): Record<string, string> | null {
  try {
    const raw = window.localStorage.getItem(key)
    if (raw === null) {
      return null
    }
    const parsed = JSON.parse(raw) as unknown
    if (parsed === null || typeof parsed !== 'object') {
      return null
    }
    const result: Record<string, string> = {}
    for (const [slug, reason] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof reason === 'string') {
        result[slug] = reason
      }
    }
    return Object.keys(result).length === 0 ? null : result
  } catch {
    return null
  }
}

function writeAdviceCache(key: string, reasons: Record<string, string>): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(reasons))
  } catch {
    // 存不下就算了：下次重新请求一次，功能不受影响
  }
}

function clearAdviceCache(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // 同上
  }
}

/** 一栏轨道：理论或实战。空的时候给明确空状态，不留一片空白。 */
function TrackSection({
  testId,
  title,
  hint,
  tracks,
  emptyText,
}: {
  testId: string
  title: string
  hint: string
  tracks: TrackSummary[]
  emptyText?: string
}) {
  const passed = tracks.reduce((sum, track) => sum + track.passedCount, 0)
  const stages = tracks.reduce((sum, track) => sum + track.stageCount, 0)
  return (
    <section className="mt-2 mb-8" data-testid={testId}>
      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 className="text-lg font-semibold">{title}</h2>
        <span className="text-muted-foreground text-sm" data-testid={`${testId}-count`}>
          {tracks.length} 条 · {stages} 关 · 已通关 {passed}
        </span>
      </div>
      <p className="text-muted-foreground mb-4 text-sm">{hint}</p>
      {tracks.length === 0 ? (
        <p className="border-border text-muted-foreground rounded-xl border border-dashed p-5 text-sm"
           data-testid={`${testId}-empty`}>
          {emptyText ?? '还没有内容。'}
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {/* 基宽必须显式 `grid-cols-1`：隐式列是 auto（按 min-content 撑），360px 下卡片
              会被内容撑到 394px、整页横向滚 50px。`grid-cols-1` 是 minmax(0,1fr)，卡片才肯缩。 */}
          {tracks.map((track) => (
            <TrackCard key={track.id} track={track} />
          ))}
        </div>
      )}
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
 */
function TrackCard({ track }: { track: TrackSummary }) {
  const percent = track.stageCount === 0
    ? 0
    : Math.round((track.passedCount / track.stageCount) * 100)
  const Icon = TRACK_ICONS[track.slug] ?? (track.mode === 'practice' ? Code2 : BookOpenCheck)
  const done = track.stageCount > 0 && track.passedCount === track.stageCount
  const started = track.passedCount > 0 && !done
  return (
    <Link
      to={`/learn/${track.slug}`}
      data-testid={`learn-track-${track.slug}`}
      data-mode={track.mode}
      data-progress={done ? 'done' : started ? 'started' : 'idle'}
      className="border-border bg-card hover:border-primary/40 hover:shadow-card group flex h-full flex-col rounded-xl border p-5 transition-all"
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
