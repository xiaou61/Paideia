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
        eyebrow="每日速递"
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
                // GitHub 组按真实增量重排；其它组保持服务端的 heat 顺序
                const items = isGithub
                  ? [...raw].sort((left, right) =>
                      githubWindow === 'week'
                        ? (right.starsWeek ?? -1) - (left.starsWeek ?? -1)
                        : (right.starsDay ?? -1) - (left.starsDay ?? -1),
                    )
                  : raw
                const withHeat = items.filter((item) => item.heat !== null)
                const highCut = Math.ceil(withHeat.length * 0.25)
                const midCut = Math.ceil(withHeat.length * 0.6)
                return (
                  <section key={group.key} data-testid={`digest-group-${group.key}`}>
                    <h2 className="mb-3 flex items-center gap-2 text-lg font-medium">
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
                    <div className="grid gap-3 md:grid-cols-2">
                      {items.map((item, index) => (
                        <DigestCard
                          key={item.id}
                          item={item}
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
                    </div>
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
function DigestCard({
  item,
  tier,
  githubWindow,
}: {
  item: DigestItem
  /** 组内热度档位：前 25% 高、40% 较高、其余不标。null = 这条没有热度信号。 */
  tier: 'high' | 'mid' | 'low' | null
  /** 只对 GitHub 组有值：决定卡片显示昨日还是本周的数字。 */
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
    <Card
      data-testid="digest-item"
      className="group hover:border-primary/40 hover:shadow-card flex flex-col transition-all"
    >
      <CardContent className="flex flex-1 flex-col gap-2.5 pt-5">
        <div className="text-muted-foreground flex flex-wrap items-center gap-2 text-xs">
          <Badge variant="outline">{item.sourceName}</Badge>
          {github ? (
            <span className="flex items-center gap-1" data-testid="digest-github-stars">
              <Star className="size-3.5" />
              {githubWindow === 'week'
                ? `本周 +${formatStars(stars)}★`
                : `+${formatStars(stars)}★ 昨日`}
            </span>
          ) : item.heat === null ? null : (
            <span className="flex items-center gap-1" data-testid="digest-heat">
              <Flame className="size-3.5" />
              {item.heat}
            </span>
          )}
          {github && githubWindow !== 'week' && item.starsWeek !== null ? (
            <span className="text-muted-foreground">本周 +{formatStars(item.starsWeek)}★</span>
          ) : null}
          {tierLabel === null ? null : (
            <Badge
              variant={tier === 'high' ? 'default' : 'secondary'}
              data-testid="digest-heat-tier"
            >
              {tierLabel}
            </Badge>
          )}
          {item.meta === null ? null : <span>{item.meta}</span>}
        </div>
        <a
          href={item.url}
          target="_blank"
          rel="noreferrer"
          className="group-hover:text-primary line-clamp-2 font-medium leading-snug transition-colors hover:underline"
          data-testid="digest-title"
        >
          {item.title}
          <ExternalLink className="ml-1 inline size-3 align-baseline opacity-50" />
        </a>
        {degraded ? (
          <p className="text-muted-foreground text-sm" data-testid="digest-degraded">
            这条还没写好摘要
          </p>
        ) : (
          <p className="text-muted-foreground line-clamp-3 text-sm leading-relaxed" data-testid="digest-summary">
            {item.summary}
          </p>
        )}
        <div className="mt-auto flex justify-end pt-1">
          <Button variant="ghost" size="sm" render={<Link to={noteLink} data-testid="digest-to-note" />}>
            <NotebookPen className="mr-1 size-4" />
            存成笔记
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

/** GitHub 的增量显示；null 只在旧数据/降级时出现。 */
function formatStars(value: number | null): string {
  return (value ?? 0).toLocaleString('zh-CN')
}
