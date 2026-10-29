import {
  createKnowledgeApi,
  createLearningApi,
  plainText,
  recentReading,
  type CategoryNode,
} from '@paideia/knowledge'
import { configFor, createIdentityApi } from '@paideia/identity'
import { AppShell, Button, Card, Input, Section, Spinner } from '@paideia/ui'
import { useAuth } from '@paideia/auth'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, BookOpen, Container, Database, Dumbbell, FileQuestion, GraduationCap, Layers, MessagesSquare, Network, Newspaper, NotebookPen, Search, Sparkles, Target, Terminal } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'

import { api } from '../api'
import { HomeFirstRun } from '../components/HomeFirstRun'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

/**
 * 快捷入口。`key` 与身份的 `preferredNav` 对齐（WORK-010 T-12）。
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
    // 不参与身份排序（它与 `learn` 共用 key 会被排序丢掉一张，见下面「源码解读」的注释）。
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
    // 源码解读是知识库里的一个分类；总览条目由作者发布后挂在这里。
    // **它不参与身份排序**（key 为 null）：与"知识库"共用同一个 key 会让排序时只取到
    // 知识库那张卡、把这张整个丢掉（实测：首页入口消失，e2e 直接红）。
    key: null,
    to: '/knowledge/entries?q=源码解读',
    testId: 'home-entry-source-reading',
    icon: Sparkles,
    title: '源码解读',
    description: '跟着架构地图一步步读真实开源代码，边猜边练。',
  },
  {
    key: 'notes' as const,
    to: '/notes',
    testId: 'home-entry-notes',
    icon: NotebookPen,
    title: '我的笔记',
    description: '随手记点什么，或让助手把答案存进来。',
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
export function HomePage() {
  const { user } = useAuth()
  // 推荐顺序按身份来（WORK-010 T-12）。读不到身份就用默认：首页不能因为推荐接口慢而空掉
  const { data: profile } = useQuery({
    queryKey: ['profile'],
    queryFn: () => createIdentityApi(api).profile(),
    staleTime: 5 * 60_000,
    retry: false,
  })
  const quickLinks = useMemo(() => {
    const config = configFor(profile?.identity)
    const ordered = config.preferredNav
      .map((key) => QUICK_LINKS.find((item) => item.key === key))
      .filter((item): item is (typeof QUICK_LINKS)[number] => item !== undefined)
    // 配置只表达"谁排前面"，没提到的（含 key=null 的固定卡）追加在后面。
    // **用 includes 判断而不是按 key 去重**：null 不是合法 nav key，去重会把固定卡一起丢掉。
    return [...ordered, ...QUICK_LINKS.filter((item) => !ordered.includes(item))]
  }, [profile?.identity])
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
  const essayCategory = useMemo(() => {
    const find = (nodes: CategoryNode[]): CategoryNode | undefined => {
      for (const node of nodes) {
        if (node.slug === 'essay-java') {
          return node
        }
        const hit = find(node.children)
        if (hit !== undefined) {
          return hit
        }
      }
      return undefined
    }
    return find(categories.data ?? [])
  }, [categories.data])
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
            右下角的「助手」会读当前内容、记得你说过的话，也能把答案存成笔记。
          </p>

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
          {entries.isPending ? (
            <div className="text-muted-foreground flex items-center gap-2 p-5 text-sm">
              <Spinner />
              加载中…
            </div>
          ) : null}
          {entries.isError ? (
            <p className="text-muted-foreground p-5 text-sm">
              暂时读不到内容（{(entries.error as Error).message}）
            </p>
          ) : null}
          {entries.isSuccess ? (
            <ul className="divide-border divide-y">
              {entries.data.items.slice(0, 4).map((entry) => (
                <li key={entry.id}>
                  <Link
                    to={`/knowledge/entries/${entry.id}`}
                    className="hover:bg-accent/40 group flex items-start gap-3 px-5 py-3.5 transition-colors"
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
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Spinner />
            加载分类…
          </p>
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
        description={profile?.identity === undefined ? undefined : '按你的身份排过序。'}
      >
        <ul className="border-border divide-border divide-y overflow-hidden rounded-xl border" data-testid="home-entries">
          {quickLinks.map((item) => (
            <li key={item.to}>
              <Link
                to={item.to}
                data-testid={item.testId}
                className="hover:bg-accent/40 group flex items-center gap-3.5 px-4 py-3 transition-colors"
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
          {/*
            八股文专区（WORK-025 REQ-004）。它是知识库里的一个分类，不是独立页面，
            所以不进 `QUICK_LINKS`（那张表要求静态 `to`，而这里的 id 必须运行时解析）。
          */}
          {essayCategory === undefined ? null : (
            <li>
              <Link
                to={`/knowledge/categories/${essayCategory.id}`}
                data-testid="home-entry-essay"
                className="hover:bg-accent/40 group flex items-center gap-3.5 px-4 py-3 transition-colors"
              >
                <span className="text-muted-foreground group-hover:text-primary flex size-8 shrink-0 items-center justify-center rounded-lg transition-colors">
                  <FileQuestion className="size-4" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="group-hover:text-primary block text-sm font-medium transition-colors">
                    八股文专区
                  </span>
                  <span className="text-muted-foreground mt-0.5 block truncate text-xs">
                    每天从面经里抽题，AI 逐题解析，面试前刷一遍。
                  </span>
                </span>
                <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
              </Link>
            </li>
          )}
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
      className="border-border/70 bg-background/70 hover:border-primary/40 group rounded-lg border p-3 transition-colors"
    >
      <span className="text-muted-foreground block text-xs">{label}</span>
      <span className="mt-1 flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:gap-1.5">
        <span className="text-xl font-semibold tracking-tight">{value}</span>
        <span className="text-muted-foreground text-xs sm:truncate">{hint}</span>
      </span>
    </Link>
  )
}
