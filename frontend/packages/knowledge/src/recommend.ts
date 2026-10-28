import { type RoleKey, type TrackTier, roleMeta, tierOf } from './role-map'

/**
 * 岗位推荐打分（WORK-047）。
 *
 * <p>**纯函数**：同一组入参永远得到同一个结果，`now` 由调用方注入——函数体里不许出现
 * `Date.now()`，否则测试会随运行日期漂移。
 *
 * <p><b>AI 不参与排序</b>。排序必须可测、可复现：同一个人今天和明天看到的顺序跳来跳去，
 * 会让人觉得推荐是随机的。模型只负责把这三条的理由写得好听一点。
 */

export interface RecommendTrack {
  slug: string
  name: string
  stageCount: number
  passedCount: number
  /** 下一关标题；没有时用于占位。 */
  nextTitle?: string | null
  /** 最近一次进度更新时间（ISO 串）。没有进度记录时为 null。 */
  lastTouchedAt?: string | null
}

export interface ScoreParts {
  /** 岗位权重：核心 60 / 进阶 40 / 选读 20。 */
  role: number
  /** 未完成度：(1 - 通过/总关) × 40。 */
  remaining: number
  /** 从零开始的加分：还没开始时 +10。 */
  fresh: number
  /** 最近 48 小时动过的惩罚（负数或 0）。 */
  recent: number
}

export interface ScoredTrack extends RecommendTrack {
  tier: TrackTier
  rank: number
  score: number
  parts: ScoreParts
}

const ROLE_WEIGHT: Record<TrackTier, number> = { core: 60, advanced: 40, optional: 20 }
const RECENT_HOURS = 48
const RECENT_PENALTY = -15

/** 判断"最近动过"：只认可解析的时间串，解析不了当作没动过。 */
function touchedRecently(lastTouchedAt: string | null | undefined, now: Date): boolean {
  if (lastTouchedAt === null || lastTouchedAt === undefined) {
    return false
  }
  const at = Date.parse(lastTouchedAt)
  if (Number.isNaN(at)) {
    return false
  }
  return now.getTime() - at < RECENT_HOURS * 3600_000
}

/**
 * 打分并排序。**已全部通关的、以及没有关卡的线直接排除**——推了也点不进去。
 */
export function scoreTracks(tracks: RecommendTrack[], role: RoleKey, now: Date): ScoredTrack[] {
  // 没有内容支撑的岗位（当前是前端）不给推荐：兜底规则会把每一条都算成"选读"，
  // 于是照样能凑出三条——那不是推荐，是随机。页面对这种岗位有专门的空状态。
  if (!roleMeta(role).available) {
    return []
  }
  return tracks
    .filter((track) => track.stageCount > 0 && track.passedCount < track.stageCount)
    .map((track) => {
      const { tier, rank } = tierOf(role, track.slug)
      const remainingRatio = 1 - track.passedCount / track.stageCount
      const parts: ScoreParts = {
        role: ROLE_WEIGHT[tier],
        remaining: Math.round(remainingRatio * 40),
        fresh: track.passedCount === 0 ? 10 : 0,
        recent: touchedRecently(track.lastTouchedAt, now) ? RECENT_PENALTY : 0,
      }
      return {
        ...track,
        tier,
        rank,
        parts,
        score: parts.role + parts.remaining + parts.fresh + parts.recent,
      }
    })
    .sort(
      (left, right) =>
        right.score - left.score ||
        left.rank - right.rank ||
        left.slug.localeCompare(right.slug),
    )
}

/**
 * 取推荐位。
 *
 * <p>规则：按分数取前 `size` 条，然后**至少保一条核心**——如果核心段还有没学完的，
 * 而结果里一条核心都没有，就用分数最高的核心替换掉分数最低的非核心项。
 *
 * <p>为什么需要这条：纯按分数排，一条"进阶且快学完"的线会排在"核心且还没开始"之前，
 * 而后者才是这个岗位的入口。
 */
export function pickAdvice(scored: ScoredTrack[], size = 3): ScoredTrack[] {
  const picks = scored.slice(0, size)
  if (picks.length === 0 || picks.some((item) => item.tier === 'core')) {
    return picks
  }
  const bestCore = scored.find((item) => item.tier === 'core')
  if (bestCore === undefined) {
    return picks
  }
  // 换掉分数最低的那条（picks 已按分数降序，所以是最后一条）
  return [...picks.slice(0, size - 1), bestCore].sort(
    (left, right) => right.score - left.score || left.rank - right.rank,
  )
}

/**
 * 规则理由：AI 不可用时它就是最终形态，所以它自己必须说人话、且带真实数字。
 *
 * <p>**所有数字都来自入参**，模板里没有写死的关卡数——写死过一次之后，
 * 所有轨道的推荐理由都会说"6 关"，而其中一半不是 6 关。
 */
export function ruleReason(track: ScoredTrack): string {
  if (track.passedCount === 0) {
    return track.tier === 'core'
      ? `这个岗位的入口，你还没开始。共 ${track.stageCount} 关，一关一关走就够。`
      : `还没碰过。共 ${track.stageCount} 关。`
  }
  const next = track.nextTitle === null || track.nextTitle === undefined
    ? ''
    : `，下一关是「${track.nextTitle}」`
  return `已经过了 ${track.passedCount}/${track.stageCount} 关${next}。`
}

/** 推荐依据的一行文案，给"展开看打分"用。 */
export function explainParts(track: ScoredTrack): string {
  const tierLabel = track.tier === 'core' ? '核心' : track.tier === 'advanced' ? '进阶' : '选读'
  const pieces = [`岗位定位 ${tierLabel} +${track.parts.role}`]
  pieces.push(`未完成 +${track.parts.remaining}`)
  if (track.parts.fresh > 0) {
    pieces.push(`从零开始 +${track.parts.fresh}`)
  }
  if (track.parts.recent < 0) {
    pieces.push(`最近学过 ${track.parts.recent}`)
  }
  return `${pieces.join(' · ')} = ${track.score}`
}
