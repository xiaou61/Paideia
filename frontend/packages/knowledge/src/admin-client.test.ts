import type { ApiClient } from '@paideia/core'
import { describe, expect, it } from 'vitest'

import { createKnowledgeAdminApi } from './admin-client'

/** 记录请求的假客户端：只验“调了哪个路径、什么方法、什么载荷”，不发网络。 */
function fakeClient() {
  const calls: { path: string; init?: RequestInit }[] = []
  const client = {
    requestEnvelope: (path: string, init?: RequestInit) => {
      calls.push({ path, init })
      return Promise.resolve(null)
    },
  } as unknown as ApiClient
  return { client, calls }
}

describe('createKnowledgeAdminApi 的 WORK-076 扩展', () => {
  it('登记导入：POST 到 ai admin，带 categoryId 与 items', async () => {
    const { client, calls } = fakeClient()
    await createKnowledgeAdminApi(client).knowledgeImports.create(5, [
      { fileId: 'f1', relativePath: '第一章/a.pdf' },
    ])
    expect(calls[0]!.path).toBe('/api/v1/ai/admin/knowledge-imports')
    expect(calls[0]!.init?.method).toBe('POST')
    expect(JSON.parse(String(calls[0]!.init?.body))).toEqual({
      categoryId: 5,
      items: [{ fileId: 'f1', relativePath: '第一章/a.pdf' }],
    })
  })

  it('列表带状态过滤', async () => {
    const { client, calls } = fakeClient()
    await createKnowledgeAdminApi(client).knowledgeImports.list('failed')
    expect(calls[0]!.path).toBe('/api/v1/ai/admin/knowledge-imports?status=failed')
  })

  it('编辑切片：PUT 到对应条目与下标，带 content', async () => {
    const { client, calls } = fakeClient()
    await createKnowledgeAdminApi(client).chunkEdits.edit(7, 2, '改后')
    expect(calls[0]!.path).toBe('/api/v1/ai/admin/knowledge-entries/7/chunks/2')
    expect(calls[0]!.init?.method).toBe('PUT')
    expect(JSON.parse(String(calls[0]!.init?.body))).toEqual({ content: '改后' })
  })

  it('回滚切片：POST 带 version', async () => {
    const { client, calls } = fakeClient()
    await createKnowledgeAdminApi(client).chunkEdits.rollback(7, 2, 3)
    expect(calls[0]!.path).toBe('/api/v1/ai/admin/knowledge-entries/7/chunks/2/rollback')
    expect(calls[0]!.init?.method).toBe('POST')
    expect(JSON.parse(String(calls[0]!.init?.body))).toEqual({ version: 3 })
  })
})
