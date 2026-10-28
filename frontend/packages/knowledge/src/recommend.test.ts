import { describe, expect, it } from 'vitest'

import { explainParts, pickAdvice, ruleReason, scoreTracks, type RecommendTrack } from './recommend'
import { DEFAULT_ROLE, normalizeRole, ROLES, roleMeta, tierOf } from './role-map'

const NOW = new Date('2026-10-28T12:00:00Z')

function track(slug: string, passed = 0, stage = 6, extra: Partial<RecommendTrack> = {}): RecommendTrack {
  return { slug, name: slug, stageCount: stage, passedCount: passed, nextTitle: '下一关', ...extra }
}

describe('岗位映射（WORK-047）', () => {
  it('后端岗位的核心与进阶取自表，其余兜底为选读', () => {
    expect(tierOf('backend', 'spring').tier).toBe('core')
    expect(tierOf('backend', 'jvm').tier).toBe('advanced')
    expect(tierOf('backend', 'git').tier).toBe('optional')
  })

  it('没标岗位的 slug 一律选读，且排在最后——新增轨道不会从所有岗位里消失', () => {
    const { tier, rank } = tierOf('backend', 'brand-new-line')
    expect(tier).toBe('optional')
    expect(rank).toBe(Number.MAX_SAFE_INTEGER)
  })

  it('同段内按表里的顺序给 rank', () => {
    expect(tierOf('backend', 'java-from-zero').rank).toBe(0)
    expect(tierOf('backend', 'spring').rank).toBe(1)
  })

  it('自由文本收敛到合法岗位，不认识就退默认', () => {
    expect(normalizeRole('data')).toBe('data')
    expect(normalizeRole('后端')).toBe(DEFAULT_ROLE)
    expect(normalizeRole(null)).toBe(DEFAULT_ROLE)
    expect(normalizeRole(undefined)).toBe(DEFAULT_ROLE)
  })

  it('每个岗位都有 meta，前端岗位明确标成不可用', () => {
    expect(ROLES).toHaveLength(4)
    expect(roleMeta('frontend').available).toBe(false)
    expect(roleMeta('backend').available).toBe(true)
  })
})

describe('打分（WORK-047）', () => {
  it('零进度时核心排在最前', () => {
    const scored = scoreTracks([track('git'), track('redis'), track('jvm')], 'backend', NOW)
    expect(scored.map((item) => item.slug)).toEqual(['redis', 'jvm', 'git'])
  })

  it('全部通关的线不参与推荐', () => {
    const scored = scoreTracks([track('spring', 6, 6), track('redis')], 'backend', NOW)
    expect(scored.map((item) => item.slug)).toEqual(['redis'])
  })

  it('没有关卡的线不参与推荐——推了也点不进去', () => {
    const scored = scoreTracks([track('spring', 0, 0)], 'backend', NOW)
    expect(scored).toEqual([])
  })

  it('岗位没有内容时返回空，不抛错', () => {
    expect(scoreTracks([track('spring'), track('redis')], 'frontend', NOW)).toEqual([])
  })

  it('岗位为空列表时返回空', () => {
    expect(scoreTracks([], 'backend', NOW)).toEqual([])
  })

  it('同输入两次调用顺序完全一致', () => {
    const input = [track('redis'), track('spring'), track('git'), track('jvm', 2)]
    expect(scoreTracks(input, 'backend', NOW).map((item) => item.slug)).toEqual(
      scoreTracks(input, 'backend', NOW).map((item) => item.slug),
    )
  })

  it('最近 48 小时内动过的线被扣分，且分数构成里能看见', () => {
    const fresh = track('redis')
    const stale = track('spring', 0, 6, { lastTouchedAt: '2026-10-28T09:00:00Z' })
    const [first] = scoreTracks([stale, fresh], 'backend', NOW)
    expect(first?.slug).toBe('redis')
    const changed = scoreTracks([stale], 'backend', NOW)[0]
    expect(changed?.parts.recent).toBe(-15)
    expect(explainParts(changed!)).toContain('最近学过')
  })

  it('解析不了的时间串当作没动过，不抛错', () => {
    const scored = scoreTracks([track('redis', 0, 6, { lastTouchedAt: 'not-a-date' })], 'backend', NOW)
    expect(scored[0]?.parts.recent).toBe(0)
  })
})

describe('推荐位（WORK-047）', () => {
  it('前三条没有核心时，用分数最高的核心替换掉最低的一条', () => {
    // 四条进阶/选读都接近学完（分数高），一条核心刚开（分数低）
    const scored = scoreTracks(
      [
        track('jvm', 5),
        track('system-design', 5),
        track('concurrency', 5),
        track('git', 5),
        track('spring', 0),
      ],
      'backend',
      NOW,
    )
    const picks = pickAdvice(scored, 3)
    expect(picks.some((item) => item.tier === 'core')).toBe(true)
    expect(picks).toHaveLength(3)
  })

  it('可推荐项不足三条时有几条给几条', () => {
    const scored = scoreTracks([track('spring')], 'backend', NOW)
    expect(pickAdvice(scored, 3)).toHaveLength(1)
  })

  it('一条都没有时返回空数组', () => {
    expect(pickAdvice([], 3)).toEqual([])
  })
})

describe('规则理由（WORK-047）', () => {
  it('未开始的核心说的是"入口"，并带上真实关卡数', () => {
    const [scored] = scoreTracks([track('spring', 0, 8)], 'backend', NOW)
    const reason = ruleReason(scored!)
    expect(reason).toContain('入口')
    expect(reason).toContain('8 关')
  })

  it('进行中的理由带通过数与下一关标题', () => {
    const [scored] = scoreTracks([track('jvm', 2, 6, { nextTitle: 'GC 日志' })], 'backend', NOW)
    const reason = ruleReason(scored!)
    expect(reason).toContain('2/6')
    expect(reason).toContain('GC 日志')
  })
})
