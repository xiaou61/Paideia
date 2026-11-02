import type { ApiClient } from '@paideia/core'
import { JSON_HEADERS } from '@paideia/core'

import type {
  Checkup,
  ResumeAnalysis,
  ResumeData,
  ResumeDocument,
  ResumeDocumentSummary,
  ResumeDraft,
  ResumeHistoryEntry,
  ResumeProposal,
  ResumeVersionDetail,
  ResumeVersionSummary,
} from './types'

/**
 * 简历的 API 客户端（WORK-018）。
 *
 * <p>**每一条路径都不带 userId**：归属一律由令牌决定，所以这个客户端在构造上就调不到
 * 别人的简历。后端拿不到（不存在 / 不是本人的）一律回 404。
 *
 * <p>`parse` 走 multipart，不能复用 JSON 那条 `requestEnvelope` 的 Content-Type。
 */
export interface ResumeApi {
  parse(file: File): Promise<ResumeDraft>

  list(): Promise<ResumeDocumentSummary[]>
  create(input: { title: string; data: ResumeData; plainText: string }): Promise<ResumeDocument>
  get(id: number): Promise<ResumeDocument>
  save(id: number, input: { title: string; data: ResumeData; revision: number }): Promise<ResumeDocument>
  remove(id: number): Promise<void>
  rename(id: number, title: string): Promise<ResumeDocumentSummary>
  setDefault(id: number): Promise<ResumeDocumentSummary>

  history(id: number): Promise<ResumeHistoryEntry[]>
  historyData(id: number, historyId: number): Promise<ResumeData>
  restore(id: number, historyId: number, revision: number): Promise<ResumeDocument>
  checkup(id: number): Promise<Checkup>

  createVersion(
    documentId: number,
    input: { company: string | null; role: string | null; jdText: string },
  ): Promise<number>
  listVersions(documentId: number): Promise<ResumeVersionSummary[]>
  getVersion(versionId: number): Promise<ResumeVersionDetail>
  removeVersion(versionId: number): Promise<void>

  /** **计费**：一次上游调用。按钮上必须写明。 */
  analyze(versionId: number): Promise<ResumeAnalysis>
  /** **计费**：一次上游调用，且会替换旧提案。 */
  propose(versionId: number): Promise<ResumeProposal[]>
  listProposals(versionId: number): Promise<ResumeProposal[]>
  updateProposal(
    proposalId: number,
    input: { accepted?: boolean; proposedText?: string },
  ): Promise<ResumeProposal>
  /** **免费**：只跑确定性闸门。 */
  finalize(versionId: number): Promise<ResumeVersionDetail>
}

export function createResumeApi(client: ApiClient): ResumeApi {
  return {
    parse: (file) => {
      const form = new FormData()
      form.append('file', file)
      // 不设 Content-Type：让浏览器自己带 boundary，手写会漏掉它
      return client.requestEnvelope<ResumeDraft>('/api/v1/ai/resumes/parse', {
        method: 'POST',
        body: form,
      })
    },

    list: () => client.requestEnvelope<ResumeDocumentSummary[]>('/api/v1/ai/resumes'),
    create: (input) =>
      client.requestEnvelope<ResumeDocument>('/api/v1/ai/resumes', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    get: (id) => client.requestEnvelope<ResumeDocument>(`/api/v1/ai/resumes/${id}`),
    save: (id, input) =>
      client.requestEnvelope<ResumeDocument>(`/api/v1/ai/resumes/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    remove: (id) =>
      client.requestEnvelope<void>(`/api/v1/ai/resumes/${id}`, { method: 'DELETE' }),
    rename: (id, title) =>
      client.requestEnvelope<ResumeDocumentSummary>(`/api/v1/ai/resumes/${id}/title`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify({ title }),
      }),
    setDefault: (id) =>
      client.requestEnvelope<ResumeDocumentSummary>(`/api/v1/ai/resumes/${id}/default`, {
        method: 'PUT',
      }),

    history: (id) => client.requestEnvelope<ResumeHistoryEntry[]>(`/api/v1/ai/resumes/${id}/history`),
    historyData: (id, historyId) =>
      client.requestEnvelope<ResumeData>(`/api/v1/ai/resumes/${id}/history/${historyId}`),
    restore: (id, historyId, revision) =>
      client.requestEnvelope<ResumeDocument>(
        `/api/v1/ai/resumes/${id}/history/${historyId}/restore?revision=${revision}`,
        { method: 'POST' },
      ),
    checkup: (id) => client.requestEnvelope<Checkup>(`/api/v1/ai/resumes/${id}/checkup`),

    createVersion: (documentId, input) =>
      client.requestEnvelope<number>(`/api/v1/ai/resumes/${documentId}/versions`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    listVersions: (documentId) =>
      client.requestEnvelope<ResumeVersionSummary[]>(`/api/v1/ai/resumes/${documentId}/versions`),
    getVersion: (versionId) =>
      client.requestEnvelope<ResumeVersionDetail>(`/api/v1/ai/versions/${versionId}`),
    removeVersion: (versionId) =>
      client.requestEnvelope<void>(`/api/v1/ai/versions/${versionId}`, { method: 'DELETE' }),

    analyze: (versionId) =>
      client.requestEnvelope<ResumeAnalysis>(`/api/v1/ai/versions/${versionId}/analysis`, {
        method: 'POST',
      }),
    propose: (versionId) =>
      client.requestEnvelope<ResumeProposal[]>(`/api/v1/ai/versions/${versionId}/proposals`, {
        method: 'POST',
      }),
    listProposals: (versionId) =>
      client.requestEnvelope<ResumeProposal[]>(`/api/v1/ai/versions/${versionId}/proposals`),
    updateProposal: (proposalId, input) =>
      client.requestEnvelope<ResumeProposal>(`/api/v1/ai/proposals/${proposalId}`, {
        method: 'PATCH',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    finalize: (versionId) =>
      client.requestEnvelope<ResumeVersionDetail>(`/api/v1/ai/versions/${versionId}/finalize`, {
        method: 'POST',
      }),
  }
}
