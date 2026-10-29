import type { ApiClient } from '@paideia/core'

import type { AdviceRequest, AdviceResult, AnsweredChoice, ConceptGraph, EntryProgressView, LearningLine, ProgressReport, ReviewDueList, ReviewSession, ReviewSubmitResult, TrackMap, TrackSummary } from './learning-types'

/**
 * 学习线 / 学习记录的客户端（WORK-020）。
 *
 * <p>与知识库客户端同一装配来源（带令牌的 `ApiClient`），本包仍然不认识令牌。
 *
 * <p>**没有"我的课"这类方法**：线清单由服务端从分类标记投影出来，前端只读。
 */
export function createLearningApi(client: ApiClient) {
  return {
    lines: () => client.requestEnvelope<{ lines: LearningLine[] }>('/api/v1/learning/lines'),

    /**
     * 推荐理由润色（WORK-047）。
     *
     * <p>服务端**只写理由、不改排序**：入参就是已经排好的 Top 3。失败时调用方按
     * "没有理由"处理，用规则句顶上——所以这里不需要重试。
     */
    advice: (input: AdviceRequest) =>
      client.requestEnvelope<AdviceResult>('/api/v1/learning/advice', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),

    progress: (limit?: number) =>
      client.requestEnvelope<EntryProgressView[]>(
        `/api/v1/learning/progress${limit === undefined ? '' : `?limit=${limit}`}`,
      ),

    entry: (entryId: number) =>
      client.requestEnvelope<EntryProgressView>(`/api/v1/learning/entries/${entryId}`),

    report: (entryId: number, body: ProgressReport) =>
      client.requestEnvelope<EntryProgressView>(`/api/v1/learning/entries/${entryId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),

    reset: (entryId: number) =>
      client.requestEnvelope<null>(`/api/v1/learning/entries/${entryId}`, { method: 'DELETE' }),

    /** 到期复习列表（WORK-034 S1）。空列表是正常结果。 */
    reviews: () => client.requestEnvelope<ReviewDueList>('/api/v1/learning/reviews'),

    /** 开始复习会话（WORK-034 S2）：同日重复调用返回同一份题。 */
    startReview: () =>
      client.requestEnvelope<ReviewSession>('/api/v1/learning/reviews/sessions', {
        method: 'POST',
      }),

    /** 交卷：判定、排期与掌握度都由服务端完成。 */
    submitReview: (sessionId: number, answers: { itemId: number; choice: number }[]) =>
      client.requestEnvelope<ReviewSubmitResult>(
        `/api/v1/learning/reviews/sessions/${sessionId}/answers`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ answers }),
        },
      ),

    /** 逐题作答（WORK-033）：只发选择，判定与掌握度更新都在服务端。 */
    attempt: (input: { entryId: number; blockKey: string; questionKey: string; choice: number }) =>
      client.requestEnvelope<{ correct: boolean; fingerprint: string }>('/api/v1/learning/attempts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),

    /** 我在这条里已答过的题：练习册打开时恢复上次的作答。 */
    answered: (entryId: number) =>
      client.requestEnvelope<AnsweredChoice[]>(`/api/v1/learning/entries/${entryId}/attempts`),

    /** 我的知识图谱（WORK-021）。掌握度是服务端现算的，前端不缓存也不推导。 */
    graph: () => client.requestEnvelope<ConceptGraph>('/api/v1/learning/graph'),

    /** 学习轨道（WORK-024）。与知识库无关的独立入口。 */
    tracks: () => client.requestEnvelope<TrackSummary[]>('/api/v1/learn/tracks'),

    /** 一条轨道的关卡地图：按章节分组，每格带 done/current。 */
    trackMap: (slug: string) =>
      client.requestEnvelope<TrackMap>(`/api/v1/learn/tracks/${encodeURIComponent(slug)}`),
  }
}

export type LearningApi = ReturnType<typeof createLearningApi>
