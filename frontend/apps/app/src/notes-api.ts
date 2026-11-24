import type { ApiClient } from '@paideia/core'

import { api } from './api'
import { JSON_HEADERS } from '@paideia/core'

/** 列表项：**不含正文全文**，带一段服务端截好的摘要（前 200 字，未清洗）。 */
export interface NoteSummary {
  id: number
  entryId: number | null
  title: string
  excerpt: string | null
  updatedAt: string | null
}

export interface NoteView extends NoteSummary {
  body: string
  createdAt: string | null
  /** 所属知识库；旧版扁平笔记路径下可能为默认库 id。 */
  bookId?: number | null
  kind?: string
  parentId?: number | null
  sortOrder?: number
}

export interface NoteInput {
  entryId: number | null
  title: string
  body: string
}

/** 知识库（个人知识库）摘要。 */
export interface BookView {
  id: number
  name: string
  description: string | null
  /** private | public；公开库的整库分享链接永久有效。 */
  visibility: string
  /** #RRGGBB 主题色，可为空。 */
  accentColor: string | null
  tags: string[]
  sortOrder: number
  /** 库内文档数（不含目录）。 */
  documentCount: number
  createdAt: string | null
  updatedAt: string | null
}

export interface BookInput {
  name: string
  description?: string | null
  visibility?: 'private' | 'public'
  accentColor?: string | null
  tags?: string[]
  sortOrder?: number
}

/** 目录树里的一个节点：扁平一行，父子关系由 parentId 表示。 */
export interface NoteNodeRow {
  id: number
  kind: 'doc' | 'folder' | string
  parentId: number | null
  sortOrder: number
  title: string
  excerpt: string | null
  updatedAt: string | null
}

/** 一篇文档（含正文）。 */
export interface DocView {
  id: number
  bookId: number | null
  kind: string
  parentId: number | null
  sortOrder: number
  entryId: number | null
  title: string
  body: string
  createdAt: string | null
  updatedAt: string | null
}

export interface DocNodeInput {
  bookId: number
  parentId: number | null
  kind: 'doc' | 'folder'
  entryId?: number | null
  title: string
  body: string
}

export interface LinkRef {
  noteId: number
  title: string
}

export interface NoteLinks {
  outbound: LinkRef[]
  inbound: LinkRef[]
}

export interface ImportResult {
  folders: number
  documents: number
}

export interface NotesApi {
  list(): Promise<NoteSummary[]>
  read(id: number): Promise<NoteView>
  create(input: NoteInput): Promise<NoteView>
  update(id: number, input: NoteInput): Promise<NoteView>
  remove(id: number): Promise<boolean>
  /** 生成一条分享链接的 id。链接本身由界面按当前站点拼出来。 */
  share(id: number): Promise<string>
  /** 撤回。 */
  revoke(shareId: string): Promise<boolean>
  /** 我在这条笔记上还有效的分享 id（否则离开页面就撤不回来）。 */
  activeShares(id: number): Promise<string[]>
  /** 拼出可发给别人的链接（由界面按当前站点拼）。 */
  shareLink(shareId: string): string

  // ── 个人知识库与文档树（WORK-087） ──
  listBooks(): Promise<BookView[]>
  createBook(input: BookInput): Promise<BookView>
  updateBook(id: number, input: BookInput): Promise<BookView>
  removeBook(id: number): Promise<boolean>
  tree(bookId: number): Promise<NoteNodeRow[]>
  createNode(input: DocNodeInput): Promise<DocView>
  updateDoc(id: number, input: { entryId: number | null; title: string; body: string }): Promise<DocView>
  moveNode(id: number, parentId: number | null, sortOrder: number): Promise<void>
  removeNode(id: number): Promise<boolean>
  search(keyword: string): Promise<NoteSummary[]>
  links(id: number): Promise<NoteLinks>
  exportDocBlob(id: number): Promise<Blob>
  exportBookBlob(bookId: number): Promise<Blob>
  importZip(bookId: number, file: File): Promise<ImportResult>
  importMarkdown(bookId: number, title: string, text: string): Promise<ImportResult>
  shareBook(bookId: number): Promise<string>
  activeBookShares(bookId: number): Promise<string[]>
}

/**
 * 笔记端点客户端。
 *
 * <p>与知识库那份同形：只包路径与请求体，鉴权与 401 刷新重放交给 `api`。
 *
 * <p>**放在应用里而不是单独开一个包**：目前只有学习者端这一个消费者。拆包要有第二个消费者
 * 才划算（与后端"第二个消费者出现才提升为共享"是同一把尺子）。
 */
export function createNotesApi(client: ApiClient): NotesApi {
  const base = '/api/v1/notes'
  const bookBase = '/api/v1/note-books'

  /** 下载二进制（导出 zip / md）：走 stream，复用同一套鉴权与 401 刷新。 */
  async function download(path: string): Promise<Blob> {
    const response = await client.stream(path, { headers: { Accept: '*/*' } })
    return response.blob()
  }

  return {
    list: () => client.requestEnvelope<NoteSummary[]>(base),
    read: (id) => client.requestEnvelope<NoteView>(`${base}/${id}`),
    create: (input) =>
      client.requestEnvelope<NoteView>(base, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    update: (id, input) =>
      client.requestEnvelope<NoteView>(`${base}/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    remove: (id) => client.requestEnvelope<boolean>(`${base}/${id}`, { method: 'DELETE' }),
    share: (id) => client.requestEnvelope<string>(`${base}/${id}/share`, { method: 'POST' }),
    revoke: (shareId) => client.requestEnvelope<boolean>(`/api/v1/shares/${shareId}`, { method: 'DELETE' }),
    activeShares: (id) => client.requestEnvelope<string[]>(`${base}/${id}/shares`),

    // ── 个人知识库与文档树（WORK-087） ──
    listBooks: () => client.requestEnvelope<BookView[]>(bookBase),
    createBook: (input) =>
      client.requestEnvelope<BookView>(bookBase, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    updateBook: (id, input) =>
      client.requestEnvelope<BookView>(`${bookBase}/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    removeBook: (id) => client.requestEnvelope<boolean>(`${bookBase}/${id}`, { method: 'DELETE' }),
    tree: (bookId) => client.requestEnvelope<NoteNodeRow[]>(`${bookBase}/${bookId}/tree`),
    createNode: (input) =>
      client.requestEnvelope<DocView>(base, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    updateDoc: (id, input) =>
      client.requestEnvelope<DocView>(`${base}/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
    moveNode: (id, parentId, sortOrder) =>
      client
        .requestEnvelope<null>(`${base}/${id}/move`, {
          method: 'PUT',
          headers: JSON_HEADERS,
          body: JSON.stringify({ parentId, sortOrder }),
        })
        .then(() => undefined),
    removeNode: (id) => client.requestEnvelope<boolean>(`${base}/${id}`, { method: 'DELETE' }),
    search: (keyword) =>
      client.requestEnvelope<NoteSummary[]>(`${base}/search?q=${encodeURIComponent(keyword)}`),
    links: (id) => client.requestEnvelope<NoteLinks>(`${base}/${id}/links`),
    exportDocBlob: (id) => download(`${base}/${id}/export`),
    exportBookBlob: (bookId) => download(`${bookBase}/${bookId}/export`),
    importZip: (bookId, file) => {
      const form = new FormData()
      form.append('file', file)
      return client.requestEnvelope<ImportResult>(`${bookBase}/${bookId}/import/zip`, {
        method: 'POST',
        body: form,
      })
    },
    importMarkdown: (bookId, title, text) => {
      const form = new FormData()
      form.append('file', new Blob([text], { type: 'text/markdown' }), 'note.md')
      return client.requestEnvelope<ImportResult>(
        `${bookBase}/${bookId}/import/markdown?title=${encodeURIComponent(title)}`,
        { method: 'POST', body: form },
      )
    },
    shareBook: (bookId) =>
      client.requestEnvelope<string>(`${bookBase}/${bookId}/share`, { method: 'POST' }),
    activeBookShares: (bookId) =>
      client.requestEnvelope<string[]>(`${bookBase}/${bookId}/shares`),

    // 分享链接由界面按当前站点拼：后端不知道自己是哪个域名（本地、隧道、线上各不相同）
    shareLink: (shareId) => `${window.location.origin}/#/share/${shareId}`,
  }
}

/** 应用级单例，页面直接 import。 */
export const notes = createNotesApi(api)

/**
 * 把助手给出的一段内容（整段回答，或它拟好的草稿）存成**一条新**笔记。
 *
 * <p>不往已有笔记里追加：「作为一段落进笔记」听起来是追加，但"追加到哪一条"要猜——同一轮里
 * 用户可能已经改过那条笔记。新建一条不破坏任何已有内容，用户想合并自己在笔记页里做。
 *
 * <p>标题：助手已经拟好标题的草稿要**原样保留**它的标题；整段回答没有标题时才从正文第一行推
 * （模型产出的正文通常第一行就是要点）。都取不到就退成"来自助手的笔记"。
 */
export async function saveNote(
  note: { title?: string; body: string },
  entryId: number | null,
): Promise<NoteView> {
  const derived =
    note.title ??
    note.body
      .split('\n')
      .map((line) => line.trim())
      .find((line) => line !== '')
  const title = derived === undefined || derived === '' ? '来自助手的笔记' : derived.slice(0, 60)
  return notes.create({ entryId, title, body: note.body })
}
