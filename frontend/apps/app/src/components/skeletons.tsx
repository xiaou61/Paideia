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
 * 条目列表：与 `CategoryEntriesPage` 右栏的**单列行**同构（标题一行 + 摘要一行 + 元信息一行）。
 *
 * <p>2026-11-11 定稿版：列表是"左侧分类目录 + 右侧条目行"，所以骨架也是一个带外框的列表，
 * 而不是卡片网格。行高照真实的 `px-5 py-4` + 三行文字抄，差太多就等于换了一种跳动。
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
    <div
      className="border-border divide-border divide-y overflow-hidden rounded-xl border"
      data-testid={testId}
      aria-busy="true"
    >
      <LoadingAnnounce label={label} />
      {KEYS.slice(0, count).map((key) => (
        <div key={key} className="flex items-start gap-4 px-5 py-4">
          <div className="min-w-0 flex-1 space-y-2">
            <Skeleton className="h-4 w-3/5" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-24" />
          </div>
          <Skeleton className="mt-1 size-4 shrink-0 rounded-sm" />
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
