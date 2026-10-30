import { describe, expect, it } from 'vitest'

import { linkifyCitations } from './citation'

describe('linkifyCitations', () => {
  it('把完整的 [[uri]] 换成带占位前缀的链接', () => {
    expect(linkifyCitations('见 [[entry:123]] 这篇')).toBe('见 [entry:123](https://paideia.invalid/entry:123) 这篇')
    expect(linkifyCitations('[[note:45]]')).toBe('[note:45](https://paideia.invalid/note:45)')
  })

  it('有附件标签时用标签当链接文字', () => {
    const labels = new Map([['entry:123', '第 13 关 异常']])
    expect(linkifyCitations('见 [[entry:123]]', labels)).toBe('见 [第 13 关 异常](https://paideia.invalid/entry:123)')
  })

  it('没闭合的引用原样留着（流式输出到一半不能变形）', () => {
    expect(linkifyCitations('见 [[entry:12')).toBe('见 [[entry:12')
    expect(linkifyCitations('见 [[entry:12]] 和 [[note:')).toBe(
      '见 [entry:12](https://paideia.invalid/entry:12) 和 [[note:',
    )
  })

  it('不是 uri 的方括号不动（数组、markdown 链接语法）', () => {
    expect(linkifyCitations('`a[[0]]` 与 [[标题]]')).toBe('`a[[0]]` 与 [[标题]]')
  })

  it('标签里的方括号不会把链接写坏', () => {
    const labels = new Map([['entry:1', '数组[下标]']])
    expect(linkifyCitations('[[entry:1]]', labels)).toBe('[数组（下标）](https://paideia.invalid/entry:1)')
  })
})
