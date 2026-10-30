import { describe, expect, it } from 'vitest'

import { findMention, stripMention } from './mention'

describe('findMention', () => {
  it('行首与空白后的 @ 都认', () => {
    expect(findMention('@', 1)).toEqual({ start: 0, query: '' })
    expect(findMention('看这个 @异', 6)).toEqual({ start: 4, query: '异' })
  })

  it('取光标前最后一个 @，且只在光标之后还没写空白时生效', () => {
    const text = '@旧的 @新'
    expect(findMention(text, text.length)).toEqual({ start: 4, query: '新' })
    expect(findMention('@旧的 后面还有字', 9)).toBeNull()
  })

  it('邮箱里的 @ 不算引用', () => {
    expect(findMention('mail a@b', 8)).toBeNull()
  })

  it('光标之后的内容不影响判断（只看光标前）', () => {
    const text = '@异常'
    // 光标停在 @ 与"异"之间：查询串是空的，选择器该弹
    expect(findMention(text, 1)).toEqual({ start: 0, query: '' })
  })

  it('查询串过长或光标越界时不做引用', () => {
    expect(findMention(`@${'x'.repeat(41)}`, 42)).toBeNull()
    expect(findMention('@a', 99)).toBeNull()
    expect(findMention('@a', -1)).toBeNull()
  })
})

describe('stripMention', () => {
  it('只删 @查询，前后文字原样保留', () => {
    // "请解释 " (0-3) + "@异常" (4-6) + " 的传播" (7-)
    expect(stripMention('请解释 @异常 的传播', 4, 7)).toBe('请解释  的传播')
  })
})
