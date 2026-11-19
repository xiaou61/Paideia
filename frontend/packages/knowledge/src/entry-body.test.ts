import { describe, expect, it } from 'vitest'

import { formatAnswerParagraphs, formatEssayAnswer, stripDuplicateTitle } from './entry-body'

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

  it('正文以二级标题开头、且与标题同文时也剥掉（面经形态）', () => {
    // 面经管线生成的正文首行就是 `## <题目>`，标题也是这个题目——不剥就重复两遍
    expect(
      stripDuplicateTitle(
        '## MySQL 的 Doublewrite Buffer 解决什么问题？\n\n**结论：** 解决部分写。\n',
        'MySQL 的 Doublewrite Buffer 解决什么问题？',
      ),
    ).toBe('**结论：** 解决部分写。\n')
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

describe('formatAnswerParagraphs', () => {
  it('单段纯文本按句号断成多段', () => {
    expect(formatAnswerParagraphs('第一句。第二句。第三句。')).toBe('第一句。\n\n第二句。\n\n第三句。')
  })

  it('分号也断句，段首「标签：」前缀加粗', () => {
    const input = '箭头函数没有自己的 this，不能 new；普通函数的 this 看调用方式。'
    expect(formatAnswerParagraphs(input)).toBe(
      '箭头函数没有自己的 this，不能 new；\n\n普通函数的 this 看调用方式。',
    )
  })

  it('段首短标签加粗', () => {
    expect(formatAnswerParagraphs('条件调用会让顺序错位：后续 hook 的 state 指针会错位。')).toBe(
      '**条件调用会让顺序错位：** 后续 hook 的 state 指针会错位。',
    )
  })

  it('标签里含逗号时不加粗（那多半是完整句子）', () => {
    const input = '先说结论，再讲原因：这样更清楚。'
    expect(formatAnswerParagraphs(input)).toBe(input)
  })

  it('已经带换行/列表/加粗的 markdown 原样返回', () => {
    const md = '- 要点一\n- 要点二\n\n**结论：** 如此。'
    expect(formatAnswerParagraphs(md)).toBe(md)
  })

  it('含代码块的答案原样返回', () => {
    const md = '看这段：`a = b`，即可。'
    expect(formatAnswerParagraphs(md)).toBe(md)
  })

  it('只有一句时原样返回（不制造空段）', () => {
    expect(formatAnswerParagraphs('只有这一句。')).toBe('只有这一句。')
  })

  it('空串与纯空白原样返回', () => {
    expect(formatAnswerParagraphs('')).toBe('')
    expect(formatAnswerParagraphs('   ')).toBe('   ')
  })

  it('真实面经整段（React Hooks）会被断成多段并加粗要点标签', () => {
    const input =
      'React 在 Fiber 上做的事就是把渲染拆成可中断的小单元，靠优先级调度决定先做谁。条件调用会让顺序错位：A 分支多一个 hook，B 分支少一个，后续 hook 的 state 指针就全部错位。因此 Hooks 只能写在组件顶层，不能放进条件或循环里。'
    expect(formatAnswerParagraphs(input)).toBe(
      'React 在 Fiber 上做的事就是把渲染拆成可中断的小单元，靠优先级调度决定先做谁。\n\n' +
        '**条件调用会让顺序错位：** A 分支多一个 hook，B 分支少一个，后续 hook 的 state 指针就全部错位。\n\n' +
        '因此 Hooks 只能写在组件顶层，不能放进条件或循环里。',
    )
  })
})

describe('formatEssayAnswer', () => {
  it('切掉面经管线追加的来源尾注，只对答案段落化', () => {
    const body =
      '第一句讲机制。第二句讲原因：因为顺序。第三句给结论。\n\n---\n\n> 题目来源：[某公司一面](https://example.com/post/1)\n'
    expect(formatEssayAnswer(body)).toBe(
      '第一句讲机制。\n\n**第二句讲原因：** 因为顺序。\n\n第三句给结论。',
    )
  })

  it('没有尾注时只做段落化', () => {
    expect(formatEssayAnswer('一。二。')).toBe('一。\n\n二。')
  })
})
