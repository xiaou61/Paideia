import { Skeleton } from '@paideia/ui'

/**
 * 列表/卡片的骨架屏（2026-11-10）。
 *
 * <p><b>为什么不再用转圈</b>：转圈只说明"在忙"，不说明"会出来什么"。骨架按真实内容的
 * 尺寸先把位置占住，数据到手时文字是**填进已有骨架**而不是把整页往下推——这正是
 * Linear / Vercel 这类产品在列表页的做法，观感差别主要来自"页面跳不跳"。
 *
 * <p>每块骨架的高宽都照着真实元素抄（封面 80px、标题一行、摘要两行、页脚一行），
 * 差太多就等于换了一种跳动。它们只在 `isPending` 期间出现，不参与任何交互。
 *
 * <p>`sr-only` 的"加载中…"是给读屏的：一串空盒子对屏幕阅读器没有任何意义，
 * 而 e2e 失败时也能从 `data-testid` 元素的文字里看出"当时还在加载"。
 */

const KEYS = ['a', 'b', 'c', 'd', 'e', 'f'] as const

function LoadingAnnounce({ label }: { label: string }) {
  return <span className="sr-only">{label}</span>
}

/**
 * 条目列表：与 `CategoryEntriesPage` 的密排表格行同构（序号 + 标题 + 右对齐日期）。
 *
 * <p>2026-11-11 随列表改版重画：原来是"封面 80px + 标题 + 两行摘要"的卡片骨架，容器是
 * 三列网格；列表改成单列表格行之后，那份骨架会先摆出六张卡再把它们收成一张表，比不画还糟。
 * 行高照真实的 `py-3` + `leading-6` 抄，宽度用 3/5 的标题条——真实标题两行截断时的常见长度。
 */
export function EntryListSkeleton({
  count = 8,
  testId = 'entry-state',
  label = '加载条目…',
}: {
  count?: number
  testId?: string
  label?: string
}) {
  return (
    <div data-testid={testId} aria-busy="true">
      <LoadingAnnounce label={label} />
      {Array.from({ length: count }, (_, index) => index).map((index) => (
        <div
          key={index}
          className="border-border flex items-center gap-4 border-b px-4 py-3 last:border-b-0"
        >
          <Skeleton className="h-3 w-4 shrink-0 rounded-sm" />
          <Skeleton className="h-4 flex-1" style={{ maxWidth: `${55 + ((index * 7) % 30)}%` }} />
          <Skeleton className="hidden h-3 w-16 shrink-0 sm:block" />
        </div>
      ))}
    </div>
  )
}

/** 首页「最近更新」：几条标题 + 摘要行，尺寸照抄真实列表项。 */
export function HomeListSkeleton({
  count = 4,
  testId = 'home-list-skeleton',
  label = '加载最新内容…',
}: {
  count?: number
  testId?: string
  label?: string
}) {
  return (
    <div className="divide-border divide-y" data-testid={testId} aria-busy="true">
      <LoadingAnnounce label={label} />
      {KEYS.slice(0, count).map((key) => (
        <div key={key} className="space-y-2 px-5 py-3.5">
          <Skeleton className="h-4 w-3/5" />
          <Skeleton className="h-3 w-full" />
          <Skeleton className="h-3 w-4/5" />
        </div>
      ))}
    </div>
  )
}

/** 学习线卡片：与 `LearnPage` 的轨道卡同构（标题 + 一句说明 + 进度条位置）。 */
export function TrackGridSkeleton({
  count = 6,
  testId = 'learn-state',
  label = '加载学习轨道…',
}: {
  count?: number
  testId?: string
  label?: string
}) {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid={testId} aria-busy="true">
      <LoadingAnnounce label={label} />
      {KEYS.slice(0, count).map((key) => (
        <div key={key} className="border-border bg-card flex h-full flex-col rounded-xl border p-5">
          <div className="flex items-start gap-3">
            <Skeleton className="size-10 shrink-0 rounded-lg" />
            <div className="min-w-0 flex-1 space-y-2">
              <Skeleton className="h-4 w-2/3" />
              <Skeleton className="h-3 w-1/2" />
            </div>
          </div>
          <Skeleton className="mt-4 h-3 w-full" />
          <Skeleton className="mt-2 h-3 w-4/5" />
          <Skeleton className="mt-5 h-1.5 w-full rounded-full" />
        </div>
      ))}
    </div>
  )
}

/** 笔记列表：左竖条 + 标题 + 一行摘要，与左侧列表项同构。 */
export function NoteListSkeleton({
  count = 5,
  testId = 'notes-skeleton',
  label = '加载笔记…',
}: {
  count?: number
  testId?: string
  label?: string
}) {
  return (
    <div className="divide-border divide-y" data-testid={testId} aria-busy="true">
      <LoadingAnnounce label={label} />
      {KEYS.slice(0, count).map((key) => (
        <div key={key} className="border-l-2 border-transparent px-3.5 py-3">
          <Skeleton className="h-3.5 w-3/5" />
          <Skeleton className="mt-2 h-3 w-4/5" />
        </div>
      ))}
    </div>
  )
}

/** 每日速递：分组标题 + 条目卡片（标题 + 摘要 + 元信息）。 */
export function DigestListSkeleton({
  count = 3,
  testId = 'digest-skeleton',
  label = '加载速递…',
}: {
  count?: number
  testId?: string
  label?: string
}) {
  return (
    <div className="mt-6 space-y-8" data-testid={testId} aria-busy="true">
      <LoadingAnnounce label={label} />
      {KEYS.slice(0, 2).map((groupKey) => (
        <div key={groupKey} className="space-y-3">
          <Skeleton className="h-4 w-24" />
          {KEYS.slice(0, count).map((key) => (
            <div key={`${groupKey}-${key}`} className="border-border bg-card rounded-xl border p-4">
              <Skeleton className="h-4 w-3/5" />
              <Skeleton className="mt-2 h-3 w-full" />
              <Skeleton className="mt-2 h-3 w-4/5" />
              <Skeleton className="mt-3 h-3 w-20" />
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}
