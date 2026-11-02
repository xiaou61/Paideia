import { describe, expect, it } from 'vitest'

import { extractPlanSuggestions, stripPlanBlocks } from './plan'

const valid = '```plan\n[{"date":"2026-11-02","goal":"过第 3 关","reason":"卡在这里最久"}]\n```'

describe('计划候选解析', () => {
  it('取出候选并从正文里去掉计划块', () => {
    const { suggestions, text } = extractPlanSuggestions(`今天先做这两件：\n${valid}\n有问题再问我。`)
    expect(suggestions).toEqual([{ date: '2026-11-02', goal: '过第 3 关', reason: '卡在这里最久' }])
    expect(text).toBe('今天先做这两件：\n\n有问题再问我。')
  })

  it('最多三条：模型给多了只取前三条', () => {
    const many = JSON.stringify(
      Array.from({ length: 5 }, (_, i) => ({ date: '2026-11-02', goal: `第 ${i} 件`, reason: '' })),
    )
    expect(extractPlanSuggestions('```plan\n' + many + '\n```').suggestions).toHaveLength(3)
  })

  it('JSON 坏了或字段不合格时整块不采用，原文保留', () => {
    for (const body of ['not json', '[{"date":"11-02","goal":"x"}]', '[{"date":"2026-11-02","goal":"  "}]']) {
      const source = '```plan\n' + body + '\n```'
      expect(extractPlanSuggestions(source).suggestions).toEqual([])
      expect(stripPlanBlocks(source)).toBe(source)
    }
  })

  it('没有计划块时原样返回', () => {
    expect(stripPlanBlocks('就是一段普通回答')).toBe('就是一段普通回答')
  })
})
