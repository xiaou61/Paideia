import { describe, expect, it } from 'vitest'

import { stripDuplicateTitle } from './entry-body'

describe('stripDuplicateTitle', () => {
  it('正文一级标题与条目标题同文时剥掉', () => {
    expect(stripDuplicateTitle('# 26 · 枚举与常量\n\n## 这一课你要会\n', '26 · 枚举与常量')).toBe(
      '## 这一课你要会\n',
    )
  })

  it('正文只写课程名、标题带课号（总览形态）也剥掉', () => {
    expect(stripDuplicateTitle('# Java 从零开始\n\n这门课写给…\n', '00 · Java 从零开始')).toBe(
      '这门课写给…\n',
    )
  })

  it('标题与正文不同就原样返回', () => {
    const md = '# 另一个标题\n\n正文\n'
    expect(stripDuplicateTitle(md, '00 · Java 从零开始')).toBe(md)
  })

  it('正文以二级标题开头时不动', () => {
    const md = '## 这一关要练什么\n\n正文\n'
    expect(stripDuplicateTitle(md, '对象与集合')).toBe(md)
  })

  it('一级标题不在开头时不动', () => {
    const md = '先说一句。\n\n# 对象与集合\n\n正文\n'
    expect(stripDuplicateTitle(md, '对象与集合')).toBe(md)
  })

  it('没传标题时不动', () => {
    const md = '# 26 · 枚举与常量\n\n## 这一课你要会\n'
    expect(stripDuplicateTitle(md, undefined)).toBe(md)
  })

  it('只有标题一行时返回空串', () => {
    expect(stripDuplicateTitle('# 26 · 枚举与常量\n', '26 · 枚举与常量')).toBe('')
  })

  it('前后空白与课号差异不影响判等', () => {
    expect(stripDuplicateTitle('\n\n#   枚举与常量  \n\n## 讲解\n', '26 · 枚举与常量')).toBe('## 讲解\n')
  })
})
