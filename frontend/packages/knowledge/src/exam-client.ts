import type { ApiClient } from '@paideia/core'
import { JSON_HEADERS } from '@paideia/core'

import type {
  AttemptResult,
  PaperResult,
  PaperRuleInput,
  PaperSummary,
  PaperView,
  PracticeFilter,
  PracticeQuestion,
  WrongQuestion,
} from './exam-types'

/**
 * 试卷 / 刷题 / 错题本客户端（WORK-083）。
 *
 * <p>与 learning-client 同一装配来源（带令牌的 ApiClient）；本包不认识令牌。
 * 卷面与刷题响应都不含答案，答案只在作答后由服务端判定返回 `correct`。
 */
export function createExamApi(client: ApiClient) {
  return {
    practice: (filter: PracticeFilter) =>
      client.requestEnvelope<PracticeQuestion[]>('/api/v1/learning/practice/sessions', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(filter),
      }),

    attempt: (input: { entryId: number; blockKey: string; questionKey: string; choice: number }) =>
      client.requestEnvelope<AttemptResult>('/api/v1/learning/attempts', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    papers: () => client.requestEnvelope<PaperSummary[]>('/api/v1/learning/papers'),

    createPaper: (rule: PaperRuleInput) =>
      client.requestEnvelope<PaperView>('/api/v1/learning/papers', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(rule),
      }),

    paper: (id: number) => client.requestEnvelope<PaperView>(`/api/v1/learning/papers/${id}`),

    deletePaper: (id: number) =>
      client.requestEnvelope<null>(`/api/v1/learning/papers/${id}`, { method: 'DELETE' }),

    submitPaper: (id: number, answers: Record<number, number>) =>
      client.requestEnvelope<PaperResult>(`/api/v1/learning/papers/${id}/submit`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(answers),
      }),

    paperResult: (id: number) =>
      client.requestEnvelope<PaperResult>(`/api/v1/learning/papers/${id}/result`),

    wrongQuestions: (params?: { conceptId?: number; categoryId?: number; limit?: number }) => {
      const query = new URLSearchParams()
      if (params?.conceptId !== undefined) query.set('conceptId', String(params.conceptId))
      if (params?.categoryId !== undefined) query.set('categoryId', String(params.categoryId))
      if (params?.limit !== undefined) query.set('limit', String(params.limit))
      const suffix = query.toString() ? `?${query.toString()}` : ''
      return client.requestEnvelope<WrongQuestion[]>(`/api/v1/learning/wrong-questions${suffix}`)
    },
  }
}

export type ExamApi = ReturnType<typeof createExamApi>
