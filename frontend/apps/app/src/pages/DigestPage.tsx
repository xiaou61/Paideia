import { createDigestApi, DIGEST_GROUPS, type DigestItem } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
  PageHeader,
  Spinner,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, CalendarDays, ExternalLink, Flame, NotebookPen, Star } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const digest = createDigestApi(api)

/**
 * 每日技术速递（WORK-027）。
 *
 * <p>一屏看完「今天值得知道的事」：四个固定分组（前沿新闻 / GitHub 热榜 / 社区热帖 / HN），
 * 每条一句话摘要 + 来源 + 原文链接。
 *
 * <p><b>不做按身份分组</b>：该需求已在 2026-10-21 撤销——后端只有 `job_seeker` 一个可选身份，
 * "切换身份看到不同源"这个动作不存在。分组顺序写死：news → github → community → hackernews。
 *
 * <p><b>降级态显示原文标题而不是空摘要</b>：AI 失败那天条目仍然入库（标题+链接本身有价值），
 * 界面必须让人看出"这条没摘要"，而不是显示一片空白让人以为坏了。
 *
 * <p><b>2026-10-22 改的默认日期</b>：原来固定停在「今天」，而采集是每天 07:00 才跑。
 * 于是当天 00:00–07:00 打开页面必然空白，明明昨天的内容就在旁边。现在**进入页面就落到
 * 最近一个有内容的日期**，只有用户主动点「今天」才看今天。
 */
export function DigestPage() {
  /** undefined = 还没定（等日期列表回来），null = 用户明确要看今天。 */
  const [date, setDate] = useState<string | null | undefined>(undefined)

  /** GitHub 组的口径：今日（d1）/ 本周（d7）。只影响该组，不影响顶部「日报/周报」页签。 */
  const [githubWindow, setGithubWindow] = useState<'day' | 'week'>('day')

  /** GitHub 组的语言筛选：null = 全部。只影响该组，新闻/HN/社区没有语言概念。 */
  const [language, setLanguage] = useState<string | null>(null)

  const dates = useQuery({
    queryKey: ['digest-dates'],
    queryFn: () => digest.dates(30),
  })

  // 日期列表回来后落到最近有内容的一天。只做一次：用户之后自己点的日期不能被覆盖。
  useEffect(() => {
    if (date === undefined && dates.isSuccess) {
      setDate(dates.data[0] ?? null)
    }
  }, [date, dates.isSuccess, dates.data])

  const selected = typeof date === 'string' ? date : undefined
  const current = useQuery({
    queryKey: ['digest', selected ?? 'today'],
    queryFn: () => digest.byDate(selected),
    // 还没定日期时先不取，避免白跑一次「今天」再被覆盖
    enabled: date !== undefined,
  })

  const weekly = useQuery({
    queryKey: ['digest-weekly'],
    queryFn: () => digest.weekly(),
  })

  const total = DIGEST_GROUPS.reduce((sum, group) => sum + (current.data?.[group.key]?.length ?? 0), 0)

  const available = dates.data ?? []
  const index = selected === undefined ? -1 : available.indexOf(selected)
  const newer = index > 0 ? available[index - 1] : undefined
  const older = index >= 0 && index < available.length - 1 ? available[index + 1] : undefined

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="今天值得知道的事"
        description="每天一次，把前沿新闻、GitHub 热榜、社区热帖与 Hacker News 收在一屏。每条可一键存成笔记。"
      />

      <Tabs defaultValue="daily">
        <TabsList>
          <TabsTrigger value="daily" data-testid="digest-tab-daily">
            日报
          </TabsTrigger>
          <TabsTrigger value="weekly" data-testid="digest-tab-weekly">
            周报
          </TabsTrigger>
        </TabsList>

        <TabsContent value="daily">
          <div className="mt-4 flex flex-wrap items-center gap-2" data-testid="digest-date-picker">
            <Button
              variant={date === null ? 'default' : 'outline'}
              size="sm"
              onClick={() => setDate(null)}
            >
              今天
            </Button>
            {available.slice(0, 7).map((option) => (
              <Button
                key={option}
                variant={selected === option ? 'default' : 'outline'}
                size="sm"
                onClick={() => setDate(option)}
                data-testid="digest-date-option"
              >
                {option}
              </Button>
            ))}
          </div>

          {/* 往日翻页：日期按钮只有 7 个，更早的内容不能只能靠猜 URL */}
          {available.length > 0 ? (
            <div className="text-muted-foreground mt-3 flex items-center gap-2 text-sm">
              <Button
                variant="ghost"
                size="sm"
                disabled={newer === undefined}
                onClick={() => setDate(newer ?? null)}
                data-testid="digest-newer"
              >
                <ArrowLeft className="mr-1 size-4" />
                更新
              </Button>
              <span className="flex items-center gap-1.5">
                <CalendarDays className="size-4" />
                {selected ?? '今天'}
                <Badge variant="secondary">{total} 条</Badge>
              </span>
              <Button
                variant="ghost"
                size="sm"
                disabled={older === undefined}
                onClick={() => setDate(older ?? null)}
                data-testid="digest-older"
              >
                更早
                <ArrowRight className="ml-1 size-4" />
              </Button>
            </div>
          ) : null}

          {current.isPending ? (
            <div className="mt-8 flex justify-center">
              <Spinner />
            </div>
          ) : current.isError ? (
            <Alert variant="destructive" className="mt-6">
              <AlertTitle>速递加载失败</AlertTitle>
              <AlertDescription>稍后重试一下。这不会影响其它功能。</AlertDescription>
            </Alert>
          ) : total === 0 ? (
            <Alert className="mt-6" data-testid="digest-empty">
              <AlertTitle>这一天还没有速递</AlertTitle>
              <AlertDescription>
                {available.length === 0
                  ? '采集每天 08:00 自动跑一次，跑完这里就会出现内容。'
                  : `最近一份是 ${available[0]}，点上面的日期就能看。`}
              </AlertDescription>
            </Alert>
          ) : (
            <div className="mt-6 space-y-10">
              {DIGEST_GROUPS.map((group) => {
                const raw = current.data?.[group.key] ?? []
                if (raw.length === 0) {
                  return null
                }
                const isGithub = group.key === 'github'
                /**
                 * 语言：优先用独立字段；字段为空时从旧 `meta` 里解析。
                 *
                 * <p>为什么留这条兼容：`language` 列是 2026-10-27 才加的，**此前入库的行
                 * 全是 NULL**，而旧 meta 的格式恰好是 `JavaScript · 48530★`。不留兼容的话，
                 * 新代码上线当天到第二天 08:00 之间，语言筛选器整个不出现——用户看到的是
                 * "改了个寂寞"。下一次采集把旧行覆盖掉之后，这个 fallback 自然失效。
                 */
                const languageOf = (item: DigestItem): string | null => {
                  if (item.language !== null) {
                    return item.language
                  }
                  const legacy = item.meta?.match(/^([A-Za-z+#.\s]+?)\s*·/)
                  return legacy?.[1]?.trim() ?? null
                }
                // GitHub 组按真实增量重排；其它组保持服务端的 heat 顺序
                const sorted = isGithub
                  ? [...raw].sort((left, right) =>
                      githubWindow === 'week'
                        ? (right.starsWeek ?? -1) - (left.starsWeek ?? -1)
                        : (right.starsDay ?? -1) - (left.starsDay ?? -1),
                    )
                  : raw
                // 语言筛选只作用于 GitHub 组：新闻/HN/社区没有语言概念
                const items = isGithub && language !== null
                  ? sorted.filter((item) => languageOf(item) === language)
                  : sorted
                // 语言清单按条数降序，取前 8 个（再多就变成一堵墙）
                const languages = isGithub
                  ? [...new Set(sorted.map(languageOf).filter((value): value is string => value !== null))]
                      .map((name) => ({ name, count: sorted.filter((item) => languageOf(item) === name).length }))
                      .sort((left, right) => right.count - left.count)
                      .slice(0, 8)
                  : []
                const withHeat = items.filter((item) => item.heat !== null)
                const highCut = Math.ceil(withHeat.length * 0.25)
                const midCut = Math.ceil(withHeat.length * 0.6)
                return (
                  <section
                    key={group.key}
                    id={`digest-${group.key}`}
                    data-testid={`digest-group-${group.key}`}
                    // scroll-mt 让锚点跳转后标题不被 sticky 页头盖住
                    className="scroll-mt-20"
                  >
                    <h2 className="mb-3 flex items-center gap-2 text-lg font-semibold">
                      {group.title}
                      <Badge variant="secondary">{items.length}</Badge>
                      <span className="text-muted-foreground text-xs font-normal">
                        {isGithub
                          ? githubWindow === 'week'
                            ? '按本周新增 star'
                            : '按昨日新增 star'
                          : '按热度排序'}
                      </span>
                      {isGithub ? (
                        <div
                          className="ml-auto flex items-center gap-1"
                          data-testid="digest-github-window"
                        >
                          <Button
                            variant={githubWindow === 'day' ? 'default' : 'outline'}
                            size="sm"
                            onClick={() => setGithubWindow('day')}
                            data-testid="digest-github-window-day"
                          >
                            今日
                          </Button>
                          <Button
                            variant={githubWindow === 'week' ? 'default' : 'outline'}
                            size="sm"
                            onClick={() => setGithubWindow('week')}
                            data-testid="digest-github-window-week"
                          >
                            本周
                          </Button>
                        </div>
                      ) : null}
                    </h2>

                    {/* 语言筛选：GitHub 组原来是一坨混排，30 条里 Python/Rust/TS 各占几条，
                        想看某一门语言只能自己扫。按条数降序给前 8 个语言做开关。 */}
                    {languages.length > 1 ? (
                      <div className="mb-3 flex flex-wrap items-center gap-1.5" data-testid="digest-language-filter">
                        <button
                          type="button"
                          onClick={() => { setLanguage(null) }}
                          data-testid="digest-language-all"
                          className={`rounded-md border px-2 py-0.5 text-xs transition-colors ${
                            language === null ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:border-primary/40'
                          }`}
                        >
                          全部 {sorted.length}
                        </button>
                        {languages.map((entry) => (
                          <button
                            key={entry.name}
                            type="button"
                            onClick={() => { setLanguage(language === entry.name ? null : entry.name) }}
                            data-testid={`digest-language-${entry.name}`}
                            className={`rounded-md border px-2 py-0.5 text-xs transition-colors ${
                              language === entry.name ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:border-primary/40'
                            }`}
                          >
                            {entry.name} {entry.count}
                          </button>
                        ))}
                      </div>
                    ) : null}
                    {/*
                      热榜用**排名行**，不是 60 张同尺寸卡片。
                      原来每条都是一张 205px 高的卡、3 个药丸标签加一个常驻按钮，
                      60 条要滚 6800px，而且卡片里最该被读的数字（热度）只是个装饰。
                      排名行把「第几」放到最左边，标题一行、摘要一行，元信息压到底部一行。
                      两列是因为单列拉满 1326px 时一行太长，眼睛要从头扫到尾才读完标题。
                    */}
                    <ol className="border-border grid overflow-hidden rounded-xl border md:grid-cols-2">
                      {items.map((item, index) => (
                        <DigestRow
                          key={item.id}
                          item={item}
                          rank={index + 1}
                          githubWindow={isGithub ? githubWindow : undefined}
                          tier={
                            item.heat === null
                              ? null
                              : index < highCut
                                ? 'high'
                                : index < midCut
                                  ? 'mid'
                                  : 'low'
                          }
                        />
                      ))}
                    </ol>
                  </section>
                )
              })}
            </div>
          )}
        </TabsContent>

        <TabsContent value="weekly">
          {weekly.isPending ? (
            <div className="mt-8 flex justify-center">
              <Spinner />
            </div>
          ) : weekly.data === null || weekly.data === undefined ? (
            <Empty className="mt-6 border" data-testid="digest-weekly-missing">
              <EmptyHeader>
                <EmptyTitle>本周周报尚未生成</EmptyTitle>
                <EmptyDescription>周报在每周初从上一周的日报聚合，一次 AI 调用写成。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <Card className="mt-6" data-testid="digest-weekly-body">
              <CardContent className="pt-6">
                <p className="text-muted-foreground mb-3 text-sm">
                  {weekly.data.weekStart} 那一周 · 基于 {weekly.data.itemCount} 条速递
                </p>
                <div className="leading-relaxed whitespace-pre-wrap">{weekly.data.body}</div>
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </AppShell>
  )
}

/**
 * 一条速递卡片。降级态用灰字标出"没摘要"，不显示空白。
 *
 * <p>整卡可点（原文链接铺满卡片），「存成笔记」压在右下角并阻止冒泡——原来只有标题那行
 * 是链接，点卡片别处没反应。内容做三行截断，卡片高度才齐。
 */
/**
 * GitHub 自动封面。
 *
 * <p>GitHub 会给每个仓库生成一张 1200×600 的 OG 图（仓库名、描述、语言占比条、star/贡献者数）。
 * 用它当封面比自己造占位图强：**信息是真的**，而且是零维护——仓库改描述，图跟着变。
 *
 * <p>哈希位固定写 `1`：实测随机哈希会 429，写死 `1` 稳定 200。
 *
 * <p>**只给前 3 名显示**。这不是审美取舍，是实测逼出来的：14 张并发时，
 * 9 秒只加载完 5 张（GitHub OG 对同源并发有限流），用户滚动到这里看到的是
 * 一排空白框逐个冒出来——比没有封面更糟。单张实测 0.3 秒，3 张并发能稳定在 1 秒内。
 * 顺带的好处是前三名本来就是这个榜单的视觉焦点，封面正好用在该用的地方。
 *
 * <p>代价如实记在这里：**这张图是英文的**，与全站中文界面不一致。所以它压到 96px 高，
 * 读者读的是我们写的中文摘要，封面只负责"这仓库长什么样"。
 * `loading="lazy"` + 失败即隐藏，不留破图标。
 */
function RepoCover({ repo }: { repo: string }) {
  const [failed, setFailed] = useState(false)
  if (failed) {
    return null
  }
  return (
    <img
      src={`https://opengraph.githubassets.com/1/${repo}`}
      alt=""
      aria-hidden
      loading="lazy"
      decoding="async"
      onError={() => { setFailed(true) }}
      className="border-border hidden h-16 w-32 shrink-0 rounded-md border object-cover object-left-top sm:block"
      data-testid="digest-repo-cover"
    />
  )
}

/**
 * 一条热榜行。
 *
 * <p>形态是**排名 + 标题 + 摘要 + 一行元信息**，不是卡片。理由：
 * <ul>
 *   <li>热榜的第一信息是「第几」，卡片形态把它丢了，读者只能自己数。</li>
 *   <li>60 条 × 205px 的卡片要滚 6800px；同样的内容用行只需要约一半，而且能一眼扫完。</li>
 *   <li>元信息（来源、热度、档位、时间）从标题上方挪到下方一行，标题不再被三四个药丸挤在中间。</li>
 * </ul>
 *
 * <p>「存成笔记」不再常驻：60 个按钮在页面上是 60 个噪声。改成悬停/聚焦时才出现，
 * 触屏没有悬停，所以 `focus-within` 也让它出现——键盘与触屏都够得到。
 */
function DigestRow({
  item,
  rank,
  tier,
  githubWindow,
}: {
  item: DigestItem
  /** 组内排名，从 1 开始。热榜没有排名等于没有热榜。 */
  rank: number
  /** 组内热度档位：前 25% 高、40% 较高、其余不标。null = 这条没有热度信号。 */
  tier: 'high' | 'mid' | 'low' | null
  /** 只对 GitHub 组有值：决定显示昨日还是本周的数字。 */
  githubWindow?: 'day' | 'week'
}) {
  const degraded = item.summaryState === 'degraded'
  const tierLabel = tier === 'high' ? '高热' : tier === 'mid' ? '较热' : null
  const github = item.groupKey === 'github'
  const stars = github ? (githubWindow === 'week' ? item.starsWeek : item.starsDay) : null
  // 「存成笔记」是纯前端跳转（I-16）：把标题与摘要预填进新建笔记页，用户可改再存
  const noteLink = `/notes/new?title=${encodeURIComponent(item.title)}&content=${encodeURIComponent(
    (item.summary ?? '') + '\n\n> 来源：' + item.url,
  )}`

  return (
    <li
      data-testid="digest-item"
      data-rank={rank}
      // 网格里用 border 而不是 divide：divide-y 在双列下会把"每一行"的线画在单个格子上，
      // 左右两列的线会错开。border-b + 右列 border-l 才是双列下唯一稳定的画法。
      className="border-border group/row hover:bg-accent/40 focus-within:bg-accent/40 relative flex gap-3 border-b px-3 py-3 transition-colors sm:gap-4 sm:px-4 md:[&:nth-child(2n)]:border-l md:[&:nth-last-child(-n+2)]:border-b-0"
    >
      {/* 排名：前三是热的，其余退成灰号 */}
      <span
        className={`mt-0.5 w-5 shrink-0 text-right text-sm font-semibold tabular-nums ${
          rank <= 3 ? 'text-primary' : 'text-muted-foreground/60'
        }`}
        aria-label={`第 ${rank} 名`}
      >
        {rank}
      </span>

      {/* 只给前三名出封面：14 张并发 9 秒才加载 5 张，用户看到的是逐个冒出的空白框 */}
      {github && rank <= 3 ? <RepoCover repo={item.title} /> : null}

      <div className="min-w-0 flex-1">
        <a
          href={item.url}
          target="_blank"
          rel="noreferrer"
          // py-0.5 把命中区抬到 24px 以上（WCAG 2.5.8），视觉上仍是紧凑的一行标题
          className="group-hover/row:text-primary block py-0.5 font-medium leading-snug transition-colors hover:underline"
          data-testid="digest-title"
        >
          {item.title}
          <ExternalLink className="ml-1 inline size-3 align-baseline opacity-40" />
        </a>

        {degraded ? (
          <p className="text-muted-foreground mt-1 text-sm" data-testid="digest-degraded">
            这条还没写好摘要
          </p>
        ) : item.summary === null ? null : (
          <p
            // 两列布局下每列约 660px，两行摘要够用；单列时也只两行，保持行高一致
            className="text-muted-foreground mt-1 line-clamp-2 text-sm leading-relaxed"
            data-testid="digest-summary"
          >
            {item.summary}
          </p>
        )}

        <div className="text-muted-foreground mt-1.5 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs">
          <span>{item.sourceName}</span>
          {github ? (
            <span className="flex items-center gap-0.5" data-testid="digest-github-stars">
              <Star className="size-3" />
              {githubWindow === 'week'
                ? `本周 +${formatStars(stars)}`
                : `+${formatStars(stars)} 昨日`}
            </span>
          ) : item.heat === null ? null : (
            <span className="flex items-center gap-0.5" data-testid="digest-heat">
              <Flame className="size-3" />
              {item.heat}
            </span>
          )}
          {tierLabel === null ? null : (
            <span
              className={tier === 'high' ? 'text-primary font-medium' : ''}
              data-testid="digest-heat-tier"
            >
              {tierLabel}
            </span>
          )}
          {item.meta === null ? null : <span>{item.meta}</span>}
        </div>
      </div>

      {/* 常驻按钮改成悬停/聚焦才出现：60 个常驻按钮是 60 个噪声。
          触屏没有悬停，所以 `max-sm:` 下保持 60% 可见。
          **opacity-0 的元素仍在 Tab 顺序里**，所以 focus-visible 必须能把它拉回 100%，
          否则键盘用户会停在一个完全看不见的按钮上。 */}
      <Link
        to={noteLink}
        data-testid="digest-to-note"
        title="存成笔记"
        aria-label={`把「${item.title}」存成笔记`}
        className="text-muted-foreground hover:text-primary focus-visible:text-primary flex size-8 shrink-0 items-center justify-center rounded-md opacity-0 transition-all group-hover/row:opacity-100 group-focus-within/row:opacity-100 focus-visible:opacity-100 max-sm:opacity-60"
      >
        <NotebookPen className="size-4" />
      </Link>
    </li>
  )
}

/** GitHub 的增量显示；null 只在旧数据/降级时出现。 */
function formatStars(value: number | null): string {
  return (value ?? 0).toLocaleString('zh-CN')
}
