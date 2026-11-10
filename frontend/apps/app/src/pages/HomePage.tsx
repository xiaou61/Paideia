import {
  createKnowledgeApi,
  createLearningApi,
  plainText,
  recentReading,
  type CategoryNode,
} from '@paideia/knowledge'
import { createIdentityApi, NAV_PREFERENCE, stageCue } from '@paideia/identity'
import { AppShell, Button, Card, Input, LoadingRow, Section, Spinner } from '@paideia/ui'
import { useAuth } from '@paideia/auth'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, BookOpen, Container, Database, Dumbbell, Flame, GraduationCap, Layers, MessagesSquare, Network, Newspaper, NotebookPen, Search, Target, Terminal } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'

import { api } from '../api'
import { HomeFirstRun } from '../components/HomeFirstRun'
import { LearnerHeader } from '../components/LearnerHeader'
import { HomeListSkeleton } from '../components/skeletons'
import { errorMessage } from '@paideia/core'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

/**
 * 快捷入口。`key` 与 `NAV_PREFERENCE` 对齐（顺序是常量，2026-11-09 起不再读画像）。
 *
 * <p>只有顺序会变，**集合不变**（面试页是导航里的入口，这里不重复）：首页是"我该从哪开始"，
 * 把身份最相关的那张卡排前面就够了；藏掉入口会让"推荐"变成"限制"。
 */
type QuickLinkKey = 'learn' | 'knowledge' | 'notes' | 'my-assessments' | 'interview' | 'graph' | 'digest'

const QUICK_LINKS: {
  /** 参与身份排序的入口才有 key；`null` 表示永远追加在末尾。 */
  key: QuickLinkKey | null
  to: string
  testId: string
  icon: typeof BookOpen
  title: string
  description: string
}[] = [
  {
    key: 'learn' as const,
    to: '/learn',
    testId: 'home-entry-learn',
    icon: GraduationCap,
    title: '从零开始',
    description: 'Python / Java 两条线，一课二十分钟，边学边练。',
  },
  {
    // Linux / Docker 的直达卡（2026-10-29 用户要求）：它们是「从零开始」里的轨道，
    // 但在进阶栏里排得偏后，首页给一个一次点击的入口。key 为 null —— 与岗位无关，
    // 不参与身份排序（它与 `learn` 共用 key 会被排序时丢掉一张）。
    key: null,
    to: '/learn/linux-from-zero',
    testId: 'home-entry-linux',
    icon: Terminal,
    title: 'Linux 从零开始',
    description: '19 关：权限、管道、进程、systemd、日志、磁盘、SSH，其中 6 关能真跑真判。',
  },
  {
    key: null,
    to: '/learn/docker',
    testId: 'home-entry-docker',
    icon: Container,
    title: 'Docker 从零开始',
    description: '20 关：镜像分层、Dockerfile、卷、网络、Compose、排查与安全。',
  },
  {
    // 自由练习（WORK-032）：key 为 null —— 它不属于任何身份，永远排在最后。
    // 放在这里是因为它正是「关卡里的 SQL 题之外，随手练一段」的入口。
    key: null,
    to: '/practice/sql',
    testId: 'home-entry-sql-practice',
    icon: Database,
    title: 'SQL 自由练习',
    description: '写一段 SQL，在干净的数据库里立刻看到结果，不计分。',
  },
  {
    // 训练营（WORK-034 D-09）：固定卡片，不参与身份排序，先保证可见。
    key: null,
    to: '/camp',
    testId: 'home-entry-camp',
    icon: Dumbbell,
    title: 'AI 训练营',
    description: '先诊断再练：按概念出题，练完复测看进步。',
  },
  {
    key: 'digest' as const,
    to: '/digest',
    testId: 'home-entry-digest',
    icon: Newspaper,
    title: '每日速递',
    description: '每天一次，把前沿新闻、GitHub 热榜与 HN 收在一屏。',
  },
  {
    key: 'interview' as const,
    to: '/interview',
    testId: 'home-entry-interview',
    icon: MessagesSquare,
    title: '模拟面试',
    description: '一次一个问题，结束拿一份结构化评分。',
  },
  {
    key: 'graph' as const,
    to: '/graph',
    testId: 'home-entry-graph',
    icon: Network,
    title: '知识图谱',
    description: '看自己每个知识点掌握到哪一步，哪些该复习了。',
  },
  {
    key: 'knowledge' as const,
    to: '/knowledge',
    testId: 'home-entry-knowledge',
    icon: BookOpen,
    title: '知识库',
    description: '按分类浏览、按关键词检索，打开条目阅读。',
  },
  {
    key: 'notes' as const,
    to: '/notes',
    testId: 'home-entry-notes',
    icon: NotebookPen,
    title: '我的笔记',
    description: '随手记点什么，也能生成只读链接发给别人。',
  },
  {
    key: 'my-assessments' as const,
    to: '/my-assessments',
    testId: 'home-entry-assessments',
    icon: Target,
    title: '我的自评',
    description: '你标记过「懂了／还不懂」的条目。',
  },
]

/**
 * 首页 = 仪表盘。
 *
 * <p><b>这里曾经是骨架期的"诊断页"</b>：进去先看到健康检查的原始 JSON 与平台类型，
 * 真正的去处只有下面三张卡。用户 2026-10-15 明确要求去掉那段（"运行状态什么的，这个就去掉呗"）
 * ——诊断信息的读者是开发者，不是学习者，它放在首页只会让人以为这是一套演示环境。
 * 平台标识改挂在 `<html data-platform>` 上（见 `main.tsx`），测试与排障仍有锚点。
 *
 * <p>现在首页回答三件事：**我在哪、最近有什么可读、我的学习痕迹**。三个数字都来自接口，
 * 没有一条是写死的。
 */
/** 面经方向在首页上的短名：分类全名太长，四个入口挤一行会换行。 */
const ESSAY_LABELS: Record<string, string> = {
  'essay-java': 'Java 后端',
  'essay-frontend': '前端',
  'essay-testing': '测试',
  'essay-data': '数据',
}

export function HomePage() {
  const { user } = useAuth()
  // 画像只用来读「当前阶段」那一条建议；入口顺序是常量（2026-11-09 起不再按身份排）
  const { data: profile } = useQuery({
    queryKey: ['profile'],
    queryFn: () => createIdentityApi(api).profile(),
    staleTime: 5 * 60_000,
    retry: false,
  })
  const quickLinks = useMemo(() => {
    const ordered = NAV_PREFERENCE
      .map((key) => QUICK_LINKS.find((item) => item.key === key))
      .filter((item): item is (typeof QUICK_LINKS)[number] => item !== undefined)
    // 常量只表达"谁排前面"，没提到的（含 key=null 的固定卡）追加在后面。
    // **用 includes 判断而不是按 key 去重**：null 不是合法 nav key，去重会把固定卡一起丢掉。
    return [...ordered, ...QUICK_LINKS.filter((item) => !ordered.includes(item))]
  }, [])
  /**
   * 当前阶段的那条建议（2026-11-09）。
   *
   * <p>画像里的「当前阶段」以前只存不读：填了"面试"和没填，首页长得一模一样。
   * 现在三个阶段各接一条站内真实去处（投递→简历体检、笔试→到期复习、面试→模拟面试），
   * 由 `@paideia/identity` 的 `stageCue` 给出；没填阶段就返回 null，这里什么也不渲染。
   */
  const cue = stageCue(profile?.stage)
  const navigate = useNavigate()
  const [keyword, setKeyword] = useState('')
  /**
   * 「继续学习」（WORK-014）：读本地进度，不发请求。
   * 用 `useMemo` 而不是 state：这个值每次进入首页重新算一次就够，也不需要监听其他标签页。
   */
  const recent = useMemo(() => recentReading(3), [])
  /**
   * 服务端的"继续学习"（WORK-020）：跨设备以它为准。
   *
   * <p>本地那份不删——它记录"读到第几步"，服务端记录"这一站通没通过"，两者互补。
   * 接口失败时退回本地，首页那块不会因此整块消失。
   */
  const serverProgress = useQuery({
    queryKey: ['learning-progress', 'home'],
    queryFn: () => learning.progress(6),
    retry: false,
  })
  const continueLearning = useMemo(() => {
    const items = serverProgress.data ?? []
    return items
      .filter((item) => item.status === 'in_progress' || item.status === 'passed')
      .slice(0, 3)
      .map((item) => ({
        entryId: item.entryId,
        title: item.title,
        label: item.status === 'passed' ? '已通过 · 可复习' : '学到一半',
      }))
  }, [serverProgress.data])


  // 一次请求同时供"条目总数"与"最近更新"两块用：首页不该为两个数字打两次接口
  const entries = useQuery({
    queryKey: ['knowledge-entries', null, '', 'home'],
    queryFn: () => knowledge.entries({ size: 4 }),
  })
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  /**
   * 「八股文专区」入口（WORK-025 REQ-004，2026-10-21 补）。
   *
   * <p>**按 slug 找分类，不写死 id**：生产库里它是 20，全新测试库上是 900
   * （`db/devdata/V949` 为避开种子冲突把它挪走了）。写死任何一个都会在另一侧指向空分类。
   *
   * <p>找不到就**不渲染这张卡**——分类不存在时（比如刚部署、定时任务还没建）指向一个
   * 空分类页比不显示入口更糟。
   */
  const essayCategories = useMemo(() => {
    // 四个方向固定顺序：后端 → 前端 → 测试 → 数据。抓取管线一个合集对应一个分类
    // （见 application.yml 的 paideia.ai.essay.sources），顺序按岗位热度排。
    const order = ['essay-java', 'essay-frontend', 'essay-testing', 'essay-data']
    const found = new Map<string, CategoryNode>()
    const walk = (nodes: CategoryNode[]) => {
      for (const node of nodes) {
        if (order.includes(node.slug)) {
          found.set(node.slug, node)
        }
        walk(node.children)
      }
    }
    walk(categories.data ?? [])
    return order.map((slug) => found.get(slug)).filter((node): node is CategoryNode => node !== undefined)
  }, [categories.data])
  const essayIds = useMemo(() => essayCategories.map((category) => category.id), [essayCategories])
  /**
   * 四个方向各有多少题（2026-11-09）。
   *
   * <p>**一次请求拿完**：后端 `/knowledge/categories/counts` 按 id 批量统计已发布条目数。
   * 不在前端按分类各打一次列表接口——那会让首页一次渲染多出四个请求。
   * 拿不到条数时入口照样可用（chip 只是少一个数字），不因为一次统计失败挡住主路径。
   */
  const essayCounts = useQuery({
    queryKey: ['category-counts', essayIds],
    queryFn: () => knowledge.categoryCounts(essayIds),
    enabled: essayIds.length > 0,
    staleTime: 5 * 60_000,
  })
  const essayCountOf = (id: number) => essayCounts.data?.find((item) => item.id === id)?.count
  const assessments = useQuery({
    queryKey: ['my-assessments'],
    queryFn: () => knowledge.myAssessments(),
  })
  /**
   * 三块"今天的我"的数字。都从已有接口算，不新增请求：
   * 待复习来自图谱统计，学到一半来自服务端进度，还不懂来自自评列表。
   */
  const graph = useQuery({
    queryKey: ['learning-graph'],
    queryFn: () => learning.graph(),
    retry: false,
    staleTime: 60_000,
  })
  const dueReview = graph.data?.stats.dueForReview ?? 0
  const unsureCount = useMemo(
    () => (assessments.data ?? []).filter((item) => item.level === 'unsure').length,
    [assessments.data],
  )

  const [firstRunDismissed, setFirstRunDismissed] = useState(
    () => localStorage.getItem('paideia:first-run-dismissed') === '1',
  )
  const firstRun =
    !firstRunDismissed &&
    assessments.isSuccess &&
    serverProgress.isSuccess &&
    graph.isSuccess &&
    (assessments.data?.length ?? 0) === 0 &&
    continueLearning.length === 0 &&
    (graph.data?.stats.touched ?? 0) === 0
  const tracks = useQuery({
    queryKey: ['learning-tracks', 'home-first-run'],
    queryFn: () => learning.tracks(),
    retry: false,
    enabled: firstRun,
  })
  const firstEntryHref = tracks.data?.[0] === undefined ? '/learn' : `/learn/${tracks.data[0].slug}`

  const submitSearch = (event: FormEvent) => {
    event.preventDefault()
    const trimmed = keyword.trim()
    if (trimmed !== '') {
      navigate(`/knowledge/entries?q=${encodeURIComponent(trimmed)}`)
    }
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      {firstRun ? (
        <HomeFirstRun
          entryHref={firstEntryHref}
          onDismiss={() => {
            localStorage.setItem('paideia:first-run-dismissed', '1')
            setFirstRunDismissed(true)
          }}
        />
      ) : null}
      {/* hero：左边是"你要做什么"，右边是"最近有什么"——右侧放真实内容而不是插画，
          一屏之内既有行动也有去处，不会只剩一大片留白 */}
      <section className="border-border from-accent via-background to-background mb-8 grid gap-6 rounded-2xl border bg-gradient-to-br p-5 sm:gap-8 sm:p-8 lg:grid-cols-[1.1fr_1fr] lg:p-10">
        <div className="min-w-0">
          <h1 className="text-2xl sm:text-3xl">你好，{user?.username ?? ''}</h1>
          <p className="text-muted-foreground mt-3 max-w-xl text-base leading-7">
            需要 AI 的时候，正文页能生成摘要、按文章出题自测，复习页还能看错题讲解。
          </p>

          {/*
            阶段建议（2026-11-09）：一条链接，不是一段话。
            放在搜索框上方而不是页底——它是"现在最该做的一件事"，和下面那排"所有入口"是两种信息。
            没填阶段时整块不渲染（`cue === null`），不给没选阶段的人硬塞建议。
          */}
          {cue === null ? null : (
            <Link
              to={cue.to}
              data-testid="home-stage-cue"
              className="border-primary/30 bg-primary/[0.06] hover:border-primary/60 mt-5 flex max-w-xl items-start gap-3 rounded-xl border px-3.5 py-3 transition-colors"
            >
              <Target className="text-primary mt-0.5 size-4 shrink-0" aria-hidden />
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium">{cue.label}</span>
                <span className="text-muted-foreground mt-0.5 block text-xs leading-5">{cue.hint}</span>
              </span>
              <ArrowRight className="text-muted-foreground mt-1 size-4 shrink-0" aria-hidden />
            </Link>
          )}

          <form onSubmit={submitSearch} className="mt-7 flex max-w-lg flex-col items-stretch gap-2 sm:flex-row sm:items-center">
            <div className="relative flex-1">
              <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
              <Input
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="搜一条内容，例如「依赖注入」"
                aria-label="搜索知识库"
                data-testid="home-search"
                className="h-11 rounded-full pl-9"
              />
            </div>
            <Button type="submit" size="lg" className="w-full sm:w-auto" data-testid="home-search-submit">
              搜索
            </Button>
          </form>

          <div className="mt-6 flex flex-wrap items-center gap-2">
            <Button variant="outline" render={<Link to="/knowledge" />} data-testid="hero-open-knowledge">
              去知识库
            </Button>
            <Button variant="ghost" render={<Link to="/knowledge/entries" />}>
              看全部条目
            </Button>
          </div>

          {/*
            库存量放在搜索框下面一行小字里，**不再单独占一排大数字卡**。
            原来这里还有一组「可读条目 / 分类 / 我标记过」，与上面那组
            「待复习 / 学到一半 / 还不懂」是同一个模板重复两遍：前者答"我现在该做什么"，
            后者只是把库存念了一遍，而"我标记过"又与"还不懂"是同一件事。
          */}
          <p className="text-muted-foreground mt-6 text-sm">
            库里共{' '}
            {/* testid 只挂在数字上：e2e 会把这个元素的非数字字符全去掉再与条目总数对照，
                把"22 个分类"一起圈进来会拼成 24022（实测这一条红过）。 */}
            <span className="text-foreground font-medium" data-testid="home-stat-entries">
              {entries.data === undefined ? <Spinner className="inline size-3.5 align-[-2px]" /> : entries.data.total}
            </span>{' '}
            条内容
            {categories.data === undefined ? null : <>, {categories.data.length} 个分类</>}
            {' · '}
            <Link to="/knowledge/entries" className="hover:text-foreground underline underline-offset-2">
              全部条目
            </Link>
          </p>

          {/* hero 左栏的空白由"今天该做什么"填上：三格数字直接可点，
              它们回答的是"我进来该点哪"，而不是"这个站有多大"。
              窄屏也保持三列（不堆成三行）：堆起来要占掉大半屏，而这三格本来就是一句话。 */}
          <div className="mt-8 grid grid-cols-3 gap-2 sm:gap-3 lg:mt-10" data-testid="home-hero-stats">
            <HeroStat
              label="待复习"
              value={dueReview}
              hint={dueReview === 0 ? '暂时没有' : '过期的知识点'}
              to="/review"
            />
            <HeroStat
              label="学到一半"
              value={continueLearning.length}
              hint={continueLearning.length === 0 ? '还没开始' : '接着往下走'}
              to="/learn"
            />
            <HeroStat
              label="还不懂"
              value={unsureCount}
              hint={unsureCount === 0 ? '都还行' : '标过的条目'}
              to="/my-assessments"
            />
          </div>
        </div>

        <Card className="overflow-hidden py-0" data-testid="home-recent">
          <div className="border-border flex items-center justify-between border-b px-5 py-3.5">
            <h2 className="text-sm font-medium">最近更新</h2>
            <Link
              to="/knowledge/entries"
              // py-1 把命中区从 16px 抬到 24px（WCAG 2.5.8 下限）；-my-1 保持视觉位置。
              className="text-muted-foreground hover:text-foreground -my-1 py-1 text-xs"
            >
              全部 →
            </Link>
          </div>
          {entries.isPending ? <HomeListSkeleton count={4} /> : null}
          {entries.isError ? (
            <div className="flex flex-wrap items-center gap-3 p-5" data-testid="home-recent-error">
              <p className="text-muted-foreground text-sm">暂时读不到内容（{errorMessage(entries.error)}）</p>
              <Button variant="outline" size="sm" data-testid="error-retry" onClick={() => void entries.refetch()}>
                重试
              </Button>
            </div>
          ) : null}
          {entries.isSuccess ? (
            <ul className="divide-border divide-y">
              {entries.data.items.slice(0, 4).map((entry) => (
                <li key={entry.id}>
                  <Link
                    to={`/knowledge/entries/${entry.id}`}
                    className="hover:bg-accent/40 group flex items-start gap-3 px-5 py-3.5 transition-colors focus-ring"
                  >
                    <span className="min-w-0 flex-1">
                      <span className="group-hover:text-primary block truncate text-sm font-medium transition-colors">
                        {entry.title}
                      </span>
                      {entry.excerpt === null || plainText(entry.excerpt) === '' ? null : (
                        <span className="text-muted-foreground mt-1 line-clamp-1 block text-xs">
                          {plainText(entry.excerpt)}
                        </span>
                      )}
                    </span>
                    <ArrowRight className="text-muted-foreground group-hover:text-primary mt-0.5 size-4 shrink-0 transition-colors" />
                  </Link>
                </li>
              ))}
              {entries.data.items.length === 0 ? (
                <li className="text-muted-foreground p-5 text-sm">知识库里还没有内容。</li>
              ) : null}
            </ul>
          ) : null}
        </Card>
      </section>

      {/*
        面经专区（2026-11-09 从「所有入口」列表底部提到 hero 正下方）。
        原来它只是一行不起眼的索引项，而且只连 essay-java 一个分类——前端/测试/数据三类
        根本进不去。现在改成一张带主色的横幅：四个方向各一个 chip，点哪个进哪个分类。
        数据来自已经加载的 categories 树，**不额外发请求**（条数在分类页里显示）。
      */}
      {essayCategories.length === 0 ? null : (
        <section
          data-testid="home-essay-banner"
          className="border-primary/25 from-primary/[0.09] via-primary/[0.05] to-background mb-6 rounded-2xl border bg-gradient-to-r px-4 py-3.5 sm:px-5"
        >
          {/*
            一行装下：左边是什么、右边去哪。900 高的屏上它必须完整露在首屏里——
            留两行说明就又被挤到折线以下，等于白提上来（2026-11-09 实测）。
          */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
            <div className="flex min-w-0 items-center gap-2.5">
              <Flame className="text-primary size-5 shrink-0" aria-hidden />
              <div className="min-w-0">
                <h2 className="text-base leading-6 font-semibold">面试前，刷一遍面经</h2>
                <p className="text-muted-foreground text-xs leading-5">
                  每天从牛客面经里抽题、AI 逐题解析，四个方向按岗位挑
                </p>
              </div>
            </div>
            <div className="ml-auto flex flex-wrap gap-2">
              {essayCategories.map((category) => (
                <Link
                  key={category.id}
                  to={`/knowledge/categories/${category.id}`}
                  data-testid={`home-essay-${category.slug}`}
                  className="border-primary/30 bg-background/70 hover:border-primary hover:bg-primary hover:text-primary-foreground inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm font-medium transition-colors"
                >
                  {ESSAY_LABELS[category.slug] ?? category.name}
                  {essayCountOf(category.id) === undefined ? null : (
                    <span className="tabular-nums opacity-70">{essayCountOf(category.id)}</span>
                  )}
                  <ArrowRight className="size-3.5" aria-hidden />
                </Link>
              ))}
            </div>
          </div>
        </section>
      )}

      {/* 「继续学习」：服务端有记录就优先用它（跨设备一致），否则退回本地阅读痕迹。
          只有真读过才出现——空着一条"还没开始"只是噪音。 */}
      {continueLearning.length > 0 ? (
        <Section
          title="继续学习"
          description="接着上次那一站往下走。"
          actions={
            <Button variant="ghost" size="sm" render={<Link to="/learn" />}>
              全部学习线
            </Button>
          }
        >
          <div className="grid gap-3 sm:grid-cols-3" data-testid="home-continue-learning">
            {continueLearning.map((item) => (
              <Link
                key={item.entryId}
                to={`/knowledge/entries/${item.entryId}`}
                data-testid={`home-continue-${item.entryId}`}
                className="border-border bg-card hover:border-primary/40 hover:shadow-card group flex items-start gap-3 rounded-xl border p-4 transition-all hover:-translate-y-0.5"
              >
                <span className="min-w-0 flex-1">
                  <span className="group-hover:text-primary block truncate text-sm font-medium transition-colors">
                    {item.title}
                  </span>
                  <span className="text-muted-foreground mt-1 block text-xs">{item.label}</span>
                </span>
                <ArrowRight className="text-muted-foreground group-hover:text-primary mt-0.5 size-4 shrink-0 transition-colors" />
              </Link>
            ))}
          </div>
        </Section>
      ) : null}

      {continueLearning.length > 0 || recent.length === 0 ? null : (
        <Section
          title="继续学习"
          description="接着上次那一条读下去。"
          actions={
            <span className="text-muted-foreground text-xs">进度只保存在这台设备上</span>
          }
        >
          <div className="grid gap-3 sm:grid-cols-3" data-testid="home-recent-reading">
            {recent.map((item) => (
              <Link
                key={item.entryId}
                to={`/knowledge/entries/${item.entryId}`}
                data-testid={`home-recent-${item.entryId}`}
                className="border-border bg-card hover:border-primary/40 hover:shadow-card group flex items-start gap-3 rounded-xl border p-4 transition-all hover:-translate-y-0.5"
              >
                <span className="min-w-0 flex-1">
                  <span className="group-hover:text-primary block truncate text-sm font-medium transition-colors">
                    {item.title}
                  </span>
                  <span className="text-muted-foreground mt-1 block text-xs">
                    上次阅读 {new Date(item.visited).toLocaleString('zh-CN', { dateStyle: 'short', timeStyle: 'short' })}
                  </span>
                </span>
                <ArrowRight className="text-muted-foreground group-hover:text-primary mt-0.5 size-4 shrink-0 transition-colors" />
              </Link>
            ))}
          </div>
        </Section>
      )}

      <Section
        title="按分类浏览"
        description="先看分类，点进去才是条目。"
        actions={
          <Button variant="ghost" size="sm" render={<Link to="/knowledge" />}>
            全部分类
          </Button>
        }
      >
        {categories.isPending ? (
          <LoadingRow label="加载分类…" />
        ) : null}
        {categories.isSuccess && categories.data.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有建分类。</p>
        ) : null}
        {categories.isSuccess && categories.data.length > 0 ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="home-categories">
            {categories.data.slice(0, 6).map((category) => (
              <Link
                key={category.id}
                to={`/knowledge/categories/${category.id}`}
                className="border-border bg-card hover:border-primary/40 hover:shadow-card group flex items-center justify-between rounded-xl border p-4 transition-all hover:-translate-y-0.5"
              >
                <span className="flex min-w-0 items-center gap-3">
                  <span className="tile-surface border-border flex size-9 shrink-0 items-center justify-center rounded-lg border">
                    <Layers className="text-primary size-4" />
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium">{category.name}</span>
                    <span className="text-muted-foreground block text-xs">
                      {category.children.length === 0 ? '暂无子分类' : `${category.children.length} 个子分类`}
                    </span>
                  </span>
                </span>
                <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
              </Link>
            ))}
          </div>
        ) : null}
      </Section>

      {/*
        其余入口用**一行一条的索引**，不是 12 张同尺寸的卡片。
        12 张一模一样的卡等于 12 个平等的选项，也就是没有推荐——眼睛只能从头扫到尾。
        索引行按身份排好的顺序从上往下读，图标退到左侧做锚点，标题与说明挤在一行里。
        所有 `data-testid` 与链接目标保持不变（e2e 与导航入口都依赖它们）。
      */}
      <Section
        title="所有入口"
        description="面试与速递在前，其余按常用程度排。"
      >
        <ul className="border-border divide-border divide-y overflow-hidden rounded-xl border" data-testid="home-entries">
          {quickLinks.map((item) => (
            <li key={item.to}>
              <Link
                to={item.to}
                data-testid={item.testId}
                className="hover:bg-accent/40 group flex items-center gap-3.5 px-4 py-3 transition-colors focus-ring"
              >
                <span className="text-muted-foreground group-hover:text-primary flex size-8 shrink-0 items-center justify-center rounded-lg transition-colors">
                  <item.icon className="size-4" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="group-hover:text-primary block text-sm font-medium transition-colors">
                    {item.title}
                  </span>
                  <span className="text-muted-foreground mt-0.5 block truncate text-xs">{item.description}</span>
                </span>
                <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
              </Link>
            </li>
          ))}
        </ul>
      </Section>
    </AppShell>
  )
}

/** hero 左栏的一格小数字：比大卡片轻，用来填住"今天该做什么"那块空白。 */
function HeroStat({
  label,
  value,
  hint,
  to,
}: {
  label: string
  value: number
  hint: string
  to: string
}) {
  return (
    <Link
      to={to}
      className="border-border/70 bg-background/70 hover:border-primary/40 group rounded-lg border p-3 transition-colors focus-ring"
    >
      <span className="text-muted-foreground block text-xs">{label}</span>
      <span className="mt-1 flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:gap-1.5">
        <span className="text-xl font-semibold tracking-tight">{value}</span>
        <span className="text-muted-foreground text-xs sm:truncate">{hint}</span>
      </span>
    </Link>
  )
}
