import { describe, expect, it } from 'vitest'

import {
  adaptiveContextText,
  computeAbility,
  difficultyLabel,
  normalizeDimension,
} from './adaptive'
import type { InterviewEvaluationView } from './types'

function view(
  id: number,
  overall: number,
  dimensions: Array<[string, number]>,
  improvements: string[] = [],
): InterviewEvaluationView {
  return {
    id,
    conversationId: id * 10,
    createdAt: '2026-11-01T00:00:00Z',
    evaluation: {
      overall,
      dimensions: dimensions.map(([name, score]) => ({ name, score, comment: '' })),
      strengths: [],
      improvements,
    },
  }
}

describe('computeAbility', () => {
  it('没有任何场次时返回 null（不注入自适应上下文）', () => {
    expect(computeAbility([])).toBeNull()
  })

  it('近期场次权重更高：新近低分会把能力拉低', () => {
    // 输入是新 → 旧：最近一场 1 分，上一场 5 分
    const brief = computeAbility([
      view(2, 1, [['技术深度', 1]]),
      view(1, 5, [['技术深度', 5]]),
    ])
    expect(brief).not.toBeNull()
    // (1*1 + 5*0.65) / 1.65 = 2.575 → 2.6，明显低于中点 3
    expect(brief!.dimensions[0]?.ability).toBe(2.6)
    expect(brief!.sessions).toBe(2)
  })

  it('重点挑出最弱的一到两个维度', () => {
    const brief = computeAbility([
      view(1, 3, [
        ['技术深度', 2],
        ['表达结构', 4],
        ['项目经验', 2],
        ['应变能力', 4],
      ]),
    ])
    expect(brief!.focus).toEqual(['技术深度', '项目经验'])
  })

  it('按加权总分分难度档', () => {
    expect(computeAbility([view(1, 4.4, [['技术深度', 4]])])!.difficulty).toBe('hard')
    expect(computeAbility([view(1, 3.0, [['技术深度', 3]])])!.difficulty).toBe('medium')
    expect(computeAbility([view(1, 2.0, [['技术深度', 2]])])!.difficulty).toBe('basic')
  })

  it('旧数据的维度名按关键词归一到固定四维', () => {
    const brief = computeAbility([
      view(1, 3, [
        ['技术准确性', 3],
        ['表达清晰度', 3],
        ['项目经历', 3],
        ['应变', 3],
      ]),
    ])
    const names = brief!.dimensions.map((item) => item.name)
    expect(names).toContain('技术深度')
    expect(names).toContain('表达结构')
    expect(names).toContain('项目经验')
    expect(names).toContain('应变能力')
  })
})

describe('normalizeDimension', () => {
  it('固定名原样返回，别名归一', () => {
    expect(normalizeDimension('技术深度')).toBe('技术深度')
    expect(normalizeDimension('深度')).toBe('技术深度')
    expect(normalizeDimension('沟通表达')).toBe('表达结构')
  })
})

describe('adaptiveContextText', () => {
  it('包含重点维度与难度，并带上改进建议', () => {
    const brief = computeAbility([
      view(1, 2.5, [
        ['技术深度', 2],
        ['表达结构', 3],
        ['项目经验', 2],
        ['应变能力', 3],
      ], ['多准备量化指标']),
    ])!
    const text = adaptiveContextText(brief, ['多准备量化指标'])
    expect(text).toContain('本场自适应设置')
    expect(text).toContain('本场重点考察')
    expect(text).toContain('起始难度')
    expect(text).toContain('多准备量化指标')
    expect(difficultyLabel(brief.difficulty)).toContain('基础')
  })
})
