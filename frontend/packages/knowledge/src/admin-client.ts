import type { ApiClient } from '@paideia/core'

import type { CategoryNode, PageResult } from './types'
import { JSON_HEADERS } from '@paideia/core'

/**
 * 管理端接口的客户端。
 *
 * <p>与学习者侧的 `createKnowledgeApi` 分开导出，而不是并进同一个对象：学习者端**不**
 * import 这个文件，于是管理端路径不会出现在学习者端产物里，两边的权限面在代码层面也就分开了。
 * 后端的隔离仍然靠 `/api/v1/knowledge/admin/**` 的 `hasRole('ADMIN')` 规则——这里只是少带一点死代码。
 */

export interface AdminEntrySummary {
  id: number
  title: string
  status: string
  categoryId: number | null
  categoryName: string | null
  updatedAt: string
}

/** 管理端看到的关系：带 id，学习者侧那三个视角不带。 */
export interface AdminRelation {
  id: number
  fromEntryId: number
  toEntryId: number
  relationType: string
}

export interface AdminEntryDetail {
  id: number
  title: string
  body: string
  categoryId: number | null
  categoryName: string | null
  status: string
  publishedAt: string | null
  relations: AdminRelation[]
}

export interface CategoryInput {
  parentId: number | null
  name: string
  slug: string
  sortOrder?: number
}

export interface EntryInput {
  title: string
  body: string
  categoryId: number | null
  status: 'draft' | 'published'
}

/** 上传成功后的附件信息。`url` 是**稳定标识**，渲染时再由前端补 baseUrl。 */
export interface UploadedFile {
  id: string
  url: string
  contentType: string
  size: number
  originalName: string
}

// ── 文档导入（WORK-076） ─────────────────────────────────────────

/** 单个导入项：附件 id + 文件夹内相对路径（保留目录树，可空）。 */
export interface ImportItem {
  fileId: string
  relativePath: string | null
}

export interface CreateImportResult {
  importIds: number[]
}

export interface KnowledgeImportRow {
  id: number
  fileId: string
  relativePath: string | null
  status: string
  entryId: number | null
  error: string | null
  createdAt: string
  updatedAt: string
}

// ── 检索切片编辑（WORK-076） ─────────────────────────────────────

export interface ChunkView {
  chunkIndex: number
  content: string
  origin: 'auto' | 'manual'
  version: number | null
  conflict: boolean
  orphaned: boolean
}

export interface ChunkEditResult {
  version: number
  embeddingPending: boolean
}

export interface ChunkRevision {
  version: number
  content: string
  editorId: number
  createdAt: string
}

export interface KnowledgeAdminApi {
  categories(): Promise<CategoryNode[]>
  createCategory(input: CategoryInput): Promise<number>
  updateCategory(id: number, input: CategoryInput): Promise<null>
  deleteCategory(id: number): Promise<null>

  entries(status?: 'draft' | 'published'): Promise<PageResult<AdminEntrySummary>>
  entry(id: number): Promise<AdminEntryDetail>
  createEntry(input: EntryInput): Promise<number>
  updateEntry(id: number, input: EntryInput): Promise<null>
  deleteEntry(id: number): Promise<null>

  createRelation(fromEntryId: number, toEntryId: number, relationType: string): Promise<number>
  deleteRelation(id: number): Promise<null>

  uploadFile(file: File): Promise<UploadedFile>

  /** 概念维护（WORK-021）：概念是图谱节点，条目是它的载体。 */
  concepts(): Promise<ConceptSummary[]>
  createConcept(input: ConceptInput): Promise<number>
  updateConcept(id: number, input: ConceptInput): Promise<null>
  deleteConcept(id: number): Promise<null>
  linkConceptEntry(conceptId: number, entryId: number): Promise<null>
  unlinkConceptEntry(conceptId: number, entryId: number): Promise<null>
  createConceptEdge(fromConceptId: number, toConceptId: number, relationType: string): Promise<number>
  deleteConceptEdge(id: number): Promise<null>

  /** 文档导入：登记任务、查状态、重试（WORK-076）。 */
  knowledgeImports: {
    create(categoryId: number | null, items: ImportItem[]): Promise<CreateImportResult>
    list(status?: string): Promise<KnowledgeImportRow[]>
    retry(id: number): Promise<null>
  }

  /** 检索切片：查看、编辑、历史、回滚（WORK-076）。 */
  chunkEdits: {
    list(entryId: number): Promise<ChunkView[]>
    edit(entryId: number, chunkIndex: number, content: string): Promise<ChunkEditResult>
    revisions(entryId: number, chunkIndex: number): Promise<ChunkRevision[]>
    rollback(entryId: number, chunkIndex: number, version: number): Promise<{ version: number }>
  }
}

export interface ConceptSummary {
  id: number
  name: string
  summary: string | null
  categoryId: number | null
  entryLinks: number
  edges: number
}

export interface ConceptInput {
  categoryId: number | null
  name: string
  summary?: string | null
}


export function createKnowledgeAdminApi(client: ApiClient): KnowledgeAdminApi {
  const base = '/api/v1/knowledge/admin'

  return {
    categories: () => client.requestEnvelope<CategoryNode[]>(`${base}/categories`),
    createCategory: (input) =>
      client.requestEnvelope<number>(`${base}/categories`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    updateCategory: (id, input) =>
      client.requestEnvelope<null>(`${base}/categories/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    deleteCategory: (id) =>
      client.requestEnvelope<null>(`${base}/categories/${id}`, { method: 'DELETE' }),

    entries: (status) =>
      client.requestEnvelope<PageResult<AdminEntrySummary>>(
        // size 取较大值：管理端要一眼看全，分页留给条目量真的变大时再做
        `${base}/entries?size=200${status === undefined ? '' : `&status=${status}`}`,
      ),
    entry: (id) => client.requestEnvelope<AdminEntryDetail>(`${base}/entries/${id}`),
    createEntry: (input) =>
      client.requestEnvelope<number>(`${base}/entries`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    updateEntry: (id, input) =>
      client.requestEnvelope<null>(`${base}/entries/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    deleteEntry: (id) => client.requestEnvelope<null>(`${base}/entries/${id}`, { method: 'DELETE' }),

    createRelation: (fromEntryId, toEntryId, relationType) =>
      client.requestEnvelope<number>(`${base}/relations`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ fromEntryId, toEntryId, relationType }),
      }),
    deleteRelation: (id) => client.requestEnvelope<null>(`${base}/relations/${id}`, { method: 'DELETE' }),

    uploadFile: (file) => {
      const form = new FormData()
      form.append('file', file)
      // **不要手动设 Content-Type**：multipart 的分隔符由浏览器生成，自己设会把 boundary 写错
      return client.requestEnvelope<UploadedFile>(`${base}/files`, { method: 'POST', body: form })
    },

    concepts: () => client.requestEnvelope<ConceptSummary[]>(`${base}/concepts`),
    createConcept: (input) =>
      client.requestEnvelope<number>(`${base}/concepts`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    updateConcept: (id, input) =>
      client.requestEnvelope<null>(`${base}/concepts/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    deleteConcept: (id) => client.requestEnvelope<null>(`${base}/concepts/${id}`, { method: 'DELETE' }),
    linkConceptEntry: (conceptId, entryId) =>
      client.requestEnvelope<null>(`${base}/concepts/${conceptId}/entries`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ entryId }),
      }),
    unlinkConceptEntry: (conceptId, entryId) =>
      client.requestEnvelope<null>(`${base}/concepts/${conceptId}/entries/${entryId}`, {
        method: 'DELETE',
      }),
    createConceptEdge: (fromConceptId, toConceptId, relationType) =>
      client.requestEnvelope<number>(`${base}/concept-edges`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ fromConceptId, toConceptId, relationType }),
      }),
    deleteConceptEdge: (id) =>
      client.requestEnvelope<null>(`${base}/concept-edges/${id}`, { method: 'DELETE' }),

    knowledgeImports: {
      create: (categoryId, items) =>
        client.requestEnvelope<CreateImportResult>('/api/v1/ai/admin/knowledge-imports', {
          method: 'POST',
          headers: JSON_HEADERS,
          body: JSON.stringify({ categoryId, items }),
        }),
      list: (status) =>
        client.requestEnvelope<KnowledgeImportRow[]>(
          `/api/v1/ai/admin/knowledge-imports${status === undefined ? '' : `?status=${status}`}`,
        ),
      retry: (id) =>
        client.requestEnvelope<null>(`/api/v1/ai/admin/knowledge-imports/${id}/retry`, { method: 'POST' }),
    },

    chunkEdits: {
      list: (entryId) =>
        client.requestEnvelope<ChunkView[]>(`/api/v1/ai/admin/knowledge-entries/${entryId}/chunks`),
      edit: (entryId, chunkIndex, content) =>
        client.requestEnvelope<ChunkEditResult>(
          `/api/v1/ai/admin/knowledge-entries/${entryId}/chunks/${chunkIndex}`,
          { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify({ content }) },
        ),
      revisions: (entryId, chunkIndex) =>
        client.requestEnvelope<ChunkRevision[]>(
          `/api/v1/ai/admin/knowledge-entries/${entryId}/chunks/${chunkIndex}/revisions`,
        ),
      rollback: (entryId, chunkIndex, version) =>
        client.requestEnvelope<{ version: number }>(
          `/api/v1/ai/admin/knowledge-entries/${entryId}/chunks/${chunkIndex}/rollback`,
          { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ version }) },
        ),
    },
  }
}
