import { describe, expect, it } from 'vitest'

import { configFor, stageCue } from './config'
import type { Identity, JobStage } from './types'

/**
 * 按身份的推荐配置（WORK-010 T-12）。
 *
 * <p>这里验的是"配置真的按身份分开了"，而不是"某个字符串长什么样"——后者改一次文案就红，
 * 前者才是这一层要守的东西。
 */
describe('按身份的推荐配置', () => {
  it('求职者的导航把面试排第一，其它身份没有面试入口（否则切换身份看不出变化）', () => {
    const jobSeeker = configFor('job_seeker')
    const daily = configFor('daily_learning')

    expect(jobSeeker.preferredNav[0]).toBe('interview')
    expect(daily.preferredNav).not.toContain('interview')
  })

  it('每个身份的 preferredNav 都含 digest——漏一处首页排序就取不到那张卡（WORK-027）', () => {
    const all: Identity[] = ['job_seeker', 'postgrad', 'civil_service', 'daily_learning']
    for (const identity of all) {
      expect(configFor(identity).preferredNav).toContain('digest')
    }
  })

  it('留位的三个身份也各有一份配置，不会拿到空壳', () => {
    const placeholders: Identity[] = ['postgrad', 'civil_service', 'daily_learning']
    for (const identity of placeholders) {
      const config = configFor(identity)
      expect(config.preferredNav.length).toBeGreaterThan(0)
      expect(config.interviewIntro.length).toBeGreaterThan(0)
    }
  })

  it('身份缺失或不认识时退回求职者配置——与后端同一个兜底方向', () => {
    // 两处兜底方向必须一致：一边退回 job_seeker、另一边退回默认 harness，
    // 否则"没有画像的新用户"会看到一个与后端行为不符的界面。
    expect(configFor(null)).toEqual(configFor('job_seeker'))
    expect(configFor(undefined)).toEqual(configFor('job_seeker'))
    expect(configFor('nonsense' as Identity)).toEqual(configFor('job_seeker'))
  })

  it('配置里不再有没人读的字段（2026-11-09 清理的回归位）', () => {
    // 这两个字段曾经存在但**没有任何消费者**：preferredCategories 在首页改用 preferredNav
    // 排快捷卡之后就没人读了，assistantGreeting 随助手面板下线一起失效。
    // 留一条断言挡住"又加回来"——加回来的人必须先说清谁在读它。
    const config = configFor('job_seeker') as unknown as Record<string, unknown>
    expect(config).not.toHaveProperty('preferredCategories')
    expect(config).not.toHaveProperty('assistantGreeting')
  })
})

/**
 * 当前阶段 → 首页那条建议（2026-11-09）。
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
