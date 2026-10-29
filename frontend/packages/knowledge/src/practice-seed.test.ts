import type { Quiz } from '@paideia/ui'
import { describe, expect, it } from 'vitest'

import { restoreAnswers, summarizeAnswers } from './practice-seed'

const QUIZ: Quiz = {
  id: 'java-main',
  questions: [
    { question: '运行编译好的类？', options: ['java Main', 'java Main.class'], answer: 0 },
    { question: '7 / 2 的结果？', options: ['3.5', '3', '4'], answer: 1 },
  ],
}

describe('restoreAnswers', () => {
  it('按 blockKey + questionKey 恢复上次的选择', () => {
    const restored = restoreAnswers([QUIZ], [
      { blockKey: 'java-main', questionKey: 'java-main#0', choice: 1 },
      { blockKey: 'java-main', questionKey: 'java-main#1', choice: 1 },
    ])
    expect(restored['java-main']).toEqual([1, 1])
  })

  it('只答过一部分时，没答的题留空（可以接着做）', () => {
    const restored = restoreAnswers([QUIZ], [
      { blockKey: 'java-main', questionKey: 'java-main#0', choice: 0 },
    ])
    expect(restored['java-main']).toEqual([0, null])
  })

  it('别的题块的作答不串进这一块', () => {
    const restored = restoreAnswers([QUIZ], [
      { blockKey: 'other', questionKey: 'other#0', choice: 1 },
    ])
    expect(restored).toEqual({})
  })

  it('选项数变少时丢掉那条，不把越界下标当成已答', () => {
    const restored = restoreAnswers([QUIZ], [
      { blockKey: 'java-main', questionKey: 'java-main#0', choice: 2 },
    ])
    expect(restored).toEqual({})
  })
})

describe('summarizeAnswers', () => {
  it('答对与已答分开数：已答 2、答对 1', () => {
    expect(summarizeAnswers([QUIZ], { 'java-main': [1, 1] })).toEqual({ answered: 2, correct: 1 })
  })

  it('没答过是 0 / 0', () => {
    expect(summarizeAnswers([QUIZ], {})).toEqual({ answered: 0, correct: 0 })
  })
})
