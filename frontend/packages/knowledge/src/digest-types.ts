/**
 * 每日速递的类型（WORK-027）。
 *
 * <p>**分组顺序是固定的**：先新闻、再 GitHub 热榜、再社区、最后 HN。
 * 「按身份筛源」已在 2026-10-21 撤销（后端 `Identity.selectable()` 只对 job_seeker 返回 true，
 * 界面上另外三个是 disabled 留位，"切换身份"这个动作不存在），所以这里不做身份分支。
 */
export type DigestGroupKey = 'news' | 'github' | 'community' | 'hackernews'

export const DIGEST_GROUPS: ReadonlyArray<{ key: DigestGroupKey; title: string }> = [
  { key: 'news', title: '前沿新闻' },
  { key: 'github', title: 'GitHub 热榜' },
  { key: 'community', title: '社区热帖' },
  { key: 'hackernews', title: 'Hacker News' },
]

export interface DigestItem {
  id: number
  groupKey: DigestGroupKey
  sourceKey: string
  sourceName: string
  title: string
  /** 我们自己写的一句话摘要；降级态为 null */
  summary: string | null
  url: string
  /** 源自身的信号：HN 点数、GitHub 昨日新增 star；其它源为 null */
  heat: number | null
  /** GitHub 的昨日新增 star（= heat）；其它源为 null */
  starsDay: number | null
  /** GitHub 的近 7 日新增 star；其它源为 null */
  starsWeek: number | null
  meta: string | null
  /** 仓库主语言（只有 GitHub 条目有）。前端按它分组，不要从 meta 里解析。 */
  language: string | null
  /** 原文发布时间（epoch 毫秒） */
  publishedAt: number | null
  /** ready | degraded。degraded 表示 AI 没能写摘要，界面显示原文标题 */
  summaryState: string
}

export type DigestByGroup = Record<DigestGroupKey, DigestItem[]>

export interface DigestWeekly {
  weekStart: string
  body: string
  itemCount: number
}
