import { parseQuiz, type Quiz } from './renderers/schema'

/**
 * 把正文里的 `quiz` 围栏摘出来（WORK-048）。
 *
 * <p><b>为什么在数据层摘，而不是做一个"只收集不渲染"的渲染器</b>：渲染器要在渲染期往共享
 * 状态里写东西，React 严格模式会把渲染跑两遍，于是同一道题被收两次。先摘再渲染是一个纯函数，
 * 可单测、无副作用。
 *
 * <p><b>为什么是行级状态机而不是正则</b>：正文里的代码示例也可能出现 ```quiz 这个字样，
 * 正则匹配会把它当成真题摘走。三态扫描（正文 / 普通代码块 / quiz 块）才能区分两者。
 *
 * <p><b>解析失败块原样留在正文里</b>：宁可让作者看见自己写错的 JSON，也不要静默吞掉——
 * 吞掉的后果是"我明明写了题，页面上没有"，而且没有任何提示。
 */
export interface ExtractResult {
  /** 去掉所有成功解析的 quiz 围栏之后的正文。 */
  body: string
  /** 按出现顺序解析出来的题目。 */
  quizzes: Quiz[]
}

type Mode = 'text' | 'fence' | 'quiz'

const OPEN = /^```([A-Za-z][A-Za-z0-9_-]*)?\s*$/
const CLOSE = /^```\s*$/

export function extractQuizzes(markdown: string): ExtractResult {
  if (!markdown.includes('```')) {
    return { body: markdown, quizzes: [] }
  }
  const lines = markdown.split('\n')
  const kept: string[] = []
  const quizzes: Quiz[] = []
  let mode: Mode = 'text'
  let buffer: string[] = []
  /** 被摘掉的那一段在正文里的起始行号，用来给缺 id 的题生成稳定 key。 */
  let blockStart = 0

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index] ?? ''

    if (mode === 'text') {
      const open = OPEN.exec(line)
      if (open === null) {
        kept.push(line)
        continue
      }
      if (open[1] === 'quiz') {
        mode = 'quiz'
        blockStart = index
        buffer = []
        continue
      }
      // 其它语言的围栏：原样保留，并进入 fence 态——这个状态下不识别 quiz
      mode = 'fence'
      kept.push(line)
      continue
    }

    if (mode === 'fence') {
      kept.push(line)
      if (CLOSE.test(line)) {
        mode = 'text'
      }
      continue
    }

    // mode === 'quiz'
    if (!CLOSE.test(line)) {
      buffer.push(line)
      continue
    }
    const parsed = parseQuiz(buffer.join('\n'))
    if (parsed.ok) {
      const quiz = parsed.value
      quizzes.push({
        ...quiz,
        // 缺 id 的块给一个稳定 key：同一篇里按出现顺序编号，重复渲染结果不变
        id: quiz.id ?? `practice-${blockStart}`,
      })
      // 摘掉整段。开围栏那一行也没进 kept，所以这里什么都不推
    } else {
      // 解析失败：原样放回，作者能看见自己的错
      kept.push(`\`\`\`quiz`)
      kept.push(...buffer)
      kept.push(line)
    }
    mode = 'text'
    buffer = []
  }

  // 未闭合的 quiz 块：整段按原文放回，不当作题目（否则后半篇文章会凭空消失）
  if (mode === 'quiz') {
    kept.push('```quiz')
    kept.push(...buffer)
  }

  return { body: kept.join('\n'), quizzes }
}
