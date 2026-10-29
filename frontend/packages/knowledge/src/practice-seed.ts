import type { Quiz } from '@paideia/ui'

import type { AnsweredChoice } from './learning-types'

/**
 * 练习册的「上次做到哪」。
 *
 * <p>放在独立模块而不是组件里：这两件事是纯映射与纯计数，单测不需要 DOM、也不需要
 * 把整个 `@paideia/ui` 拖进测试环境。判错不报错，只会让用户看见错的分数。
 */

/**
 * 服务端记录的「上次选了什么」摊回练习册内部形状：`quiz.id → 每题的选择`。
 *
 * <p>题号约定与服务端写入完全一致（`questionKey = blockKey#index`），这里按同一个约定比对，
 * 不解字符串——两处约定一旦分叉，恢复出来的答案会落到别的题上。
 */
export function restoreAnswers(
  quizzes: Quiz[],
  previous: AnsweredChoice[] | undefined,
): Record<string, (number | null)[]> {
  const restored: Record<string, (number | null)[]> = {}
  if (previous === undefined) {
    return restored
  }
  for (const quiz of quizzes) {
    if (quiz.id === undefined) {
      continue
    }
    const list: (number | null)[] = quiz.questions.map(() => null)
    let touched = false
    quiz.questions.forEach((question, index) => {
      const saved = previous.find(
        (answer) => answer.blockKey === quiz.id && answer.questionKey === `${quiz.id}#${index}`,
      )
      // 选项数变了（题被改过但指纹恰好没变是不可能的；这里仍是防御）就丢掉这条
      if (saved !== undefined && saved.choice < question.options.length) {
        list[index] = saved.choice
        touched = true
      }
    })
    if (touched) {
      restored[quiz.id] = list
    }
  }
  return restored
}

/** 已答几题、答对几题。卡片提示与面板抬头都用它，避免两处各算一遍。 */
export function summarizeAnswers(
  quizzes: Quiz[],
  answers: Record<string, (number | null)[]>,
): { answered: number; correct: number } {
  let answered = 0
  let correct = 0
  for (const quiz of quizzes) {
    const list = answers[quiz.id ?? ''] ?? []
    quiz.questions.forEach((question, index) => {
      const choice = list[index]
      if (choice === undefined || choice === null) {
        return
      }
      answered += 1
      if (choice === question.answer) {
        correct += 1
      }
    })
  }
  return { answered, correct }
}
