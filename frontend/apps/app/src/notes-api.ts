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
}

export interface NoteInput {
  entryId: number | null
  title: string
  body: string
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
