import type { ApiClient } from '@paideia/core'
import { JSON_HEADERS } from '@paideia/core'

import type {
  CampAnswer,
  CampCreated,
  CampForm,
  CampItem,
  CampPlan,
  CampResult,
  CampSummary,
} from './types'

/**
 * 训练营的 API 客户端（WORK-029）。
 *
 * <p>**每条路径都不带 userId**：归属由令牌决定，所以这个客户端在构造上就调不到别人的营。
 * 后端拿不到（不存在 / 不是本人的）一律 404。
 *
 * <p>两个方法标了「计费」：开营与复测各是一次上游调用，按钮上必须写明。
 */
export interface CampApi {
  list(): Promise<CampSummary[]>
  get(id: number): Promise<CampSummary>
  /** **计费**：一次上游调用（出诊断卷）。 */
  create(resumeDocumentId: number, days: 3 | 7): Promise<CampCreated>
  remove(id: number): Promise<void>

  /** 取某一卷的题。**不含答案与解析**。 */
  items(id: number, form: CampForm): Promise<CampItem[]>
  submit(id: number, form: CampForm, answers: CampAnswer[]): Promise<CampResult>
  result(id: number, form: CampForm): Promise<CampResult>

  /** **计费**：一次上游调用（出复测卷）。 */
  retest(id: number): Promise<CampCreated>

  /** **免费**：纯函数排期。 */
  buildPlan(id: number): Promise<CampPlan>
  plan(id: number): Promise<CampPlan>
  markTask(id: number, taskId: number, done: boolean): Promise<boolean>
}

export function createCampApi(api: ApiClient): CampApi {
  const base = '/api/v1/ai/camps'

  return {
    list: () => api.requestEnvelope<CampSummary[]>(base),

    get: (id) => api.requestEnvelope<CampSummary>(`${base}/${id}`),

    create: (resumeDocumentId, days) =>
      api.requestEnvelope<CampCreated>(base, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ resumeDocumentId, days }),
      }),

    remove: (id) => api.requestEnvelope<void>(`${base}/${id}`, { method: 'DELETE' }),

    items: (id, form) =>
      api.requestEnvelope<CampItem[]>(`${base}/${id}/items?form=${encodeURIComponent(form)}`),

    submit: (id, form, answers) =>
      api.requestEnvelope<CampResult>(`${base}/${id}/submit`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ form, answers }),
      }),

    result: (id, form) =>
      api.requestEnvelope<CampResult>(`${base}/${id}/result?form=${encodeURIComponent(form)}`),

    retest: (id) => api.requestEnvelope<CampCreated>(`${base}/${id}/retest`, { method: 'POST' }),

    buildPlan: (id) => api.requestEnvelope<CampPlan>(`${base}/${id}/plan`, { method: 'POST' }),

    plan: (id) => api.requestEnvelope<CampPlan>(`${base}/${id}/plan`),

    markTask: (id, taskId, done) =>
      api.requestEnvelope<boolean>(`${base}/${id}/tasks/${taskId}`, {
        method: 'PATCH',
        headers: JSON_HEADERS,
        body: JSON.stringify({ done }),
      }),
  }
}
