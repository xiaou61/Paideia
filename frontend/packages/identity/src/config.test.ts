import { describe, expect, it } from 'vitest'

import { INTERVIEW_INTRO, NAV_PREFERENCE, stageCue } from './config'
import type { JobStage } from './types'

/**
 * 导航顺序与阶段提示（2026-11-09 起不再按身份分叉）。
 *
 * <p>这里验的是"顺序里该有的都在、该在前面的在前"，而不是某一段文案长什么样——
 * 后者改一次措辞就红，前者才是这一层要守的东西。
 */
describe('导航推荐顺序', () => {
  it('面试与速递排在最前：求职者每天要用的是这两处，而不是泛读知识库', () => {
    expect(NAV_PREFERENCE[0]).toBe('interview')
    expect(NAV_PREFERENCE[1]).toBe('digest')
  })

  it('知识库与笔记在列表里——漏一处首页排序就取不到那张卡', () => {
    expect(NAV_PREFERENCE).toContain('knowledge')
    expect(NAV_PREFERENCE).toContain('notes')
  })

  it('没有重复项：重复会让首页因为同一张卡被取两次而少一张', () => {
    expect(new Set(NAV_PREFERENCE).size).toBe(NAV_PREFERENCE.length)
  })
})

describe('面试开场说明', () => {
  it('非空，且写清了"一次只问一个问题"这条硬规则', () => {
    expect(INTERVIEW_INTRO.length).toBeGreaterThan(0)
    expect(INTERVIEW_INTRO).toContain('一次问一个问题')
  })
})

/**
 * 当前阶段 → 首页那条建议。
 *
 * <p>它存在的理由：画像的 `stage` 以前只存不读，填了跟没填一样。这张表把三个阶段
 * 各接一条真实去处，所以两件事必须验——**每个阶段都有去处**、**没填就什么都不给**。
 */
describe('当前阶段的提示', () => {
  const stages: JobStage[] = ['applying', 'written_test', 'interview']

  it('三个阶段各有一条提示，且都指向站内真实路径', () => {
    const targets = new Set<string>()
    for (const stage of stages) {
      const cue = stageCue(stage)
      expect(cue, `阶段 ${stage} 没有提示`).not.toBeNull()
      expect(cue?.to.startsWith('/')).toBe(true)
      expect(cue?.label.length).toBeGreaterThan(0)
      expect(cue?.hint.length).toBeGreaterThan(0)
      targets.add(cue!.to)
    }
    // 三条去处不能是同一个页面，否则"按阶段推荐"等于没做
    expect(targets.size).toBe(3)
  })

  it('没填阶段（或读到脏值）时不给提示，不硬塞建议', () => {
    expect(stageCue(null)).toBeNull()
    expect(stageCue(undefined)).toBeNull()
    expect(stageCue('nonsense' as JobStage)).toBeNull()
  })
})
