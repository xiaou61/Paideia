import { describe, expect, it } from 'vitest'

import { extractQuizzes } from './practice'

const QUIZ = [
  '```quiz',
  '{"id":"q1","title":"小测","questions":[{"question":"1+1?","options":["1","2"],"answer":1,"explain":"当然是 2"}]}',
  '```',
].join('\n')

describe('extractQuizzes（WORK-048）', () => {
  it('把 quiz 块从正文里摘出来，顺序与数量对得上', () => {
    const markdown = `正文第一段\n\n${QUIZ}\n\n正文第二段`
    const { body, quizzes } = extractQuizzes(markdown)
    expect(quizzes).toHaveLength(1)
    expect(quizzes[0]?.id).toBe('q1')
    expect(quizzes[0]?.questions[0]?.question).toBe('1+1?')
    expect(body).toContain('正文第一段')
    expect(body).toContain('正文第二段')
    expect(body).not.toContain('```quiz')
  })

  it('代码块里的 ```quiz 字样不被误摘——这是不用正则的原因', () => {
    const markdown = ['````text', '```quiz', '{"foo":1}', '```', '````'].join('\n')
    const { body, quizzes } = extractQuizzes(markdown)
    expect(quizzes).toHaveLength(0)
    expect(body).toContain('```quiz')
  })

  it('普通代码块原样保留', () => {
    const markdown = ['```java', 'int a = 1;', '```'].join('\n')
    const { body, quizzes } = extractQuizzes(markdown)
    expect(quizzes).toHaveLength(0)
    expect(body).toBe(markdown)
  })

  it('解析失败的块原样留在正文里，不计入题目数', () => {
    const markdown = ['```quiz', '{ 这不是 JSON', '```'].join('\n')
    const { body, quizzes } = extractQuizzes(markdown)
    expect(quizzes).toHaveLength(0)
    expect(body).toContain('```quiz')
    expect(body).toContain('{ 这不是 JSON')
  })

  it('没有 id 的块得到稳定 key，两次抽取一致', () => {
    const markdown = ['```quiz', '{"questions":[{"question":"q","options":["a","b"],"answer":0}]}', '```'].join('\n')
    const first = extractQuizzes(markdown).quizzes[0]?.id
    const second = extractQuizzes(markdown).quizzes[0]?.id
    expect(first).toBeTruthy()
    expect(first).toBe(second)
  })

  it('未闭合的 quiz 块整段放回，不吞掉后面的正文', () => {
    const markdown = ['正文', '```quiz', '{"questions":[]}', '还没闭合', '后续正文'].join('\n')
    const { body, quizzes } = extractQuizzes(markdown)
    expect(quizzes).toHaveLength(0)
    expect(body).toContain('后续正文')
  })

  it('多个 quiz 块按出现顺序返回', () => {
    const one = ['```quiz', '{"id":"a","questions":[{"question":"q","options":["x","y"],"answer":0}]}', '```'].join('\n')
    const two = ['```quiz', '{"id":"b","questions":[{"question":"q2","options":["x","y"],"answer":1}]}', '```'].join('\n')
    const { quizzes } = extractQuizzes(`${one}\n中间\n${two}`)
    expect(quizzes.map((quiz) => quiz.id)).toEqual(['a', 'b'])
  })

  it('没有围栏时原样返回，不做任何切分', () => {
    const markdown = '只有正文\n第二行'
    expect(extractQuizzes(markdown)).toEqual({ body: markdown, quizzes: [] })
  })
})
