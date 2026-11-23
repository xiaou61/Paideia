import { expect, type APIRequestContext } from '@playwright/test'

import { ADMIN } from './env'

/**
 * 图谱夹具：现造一条概念并挂上一个已发布条目，跑完删掉。
 *
 * <p>概念由管理员建立，本地种子里没有。没有夹具的话"有节点"的分支永远走不到，
 * 那几条用例就成了只会跳过的摆设。`graph.spec.ts` 与 `appearance.spec.ts`（AC-007 换肤跟随）
 * 都要用到它，所以放在这里共用一份——两份拷贝迟早会漂移。
 */

/** 用管理员身份拿一个令牌。**只用于造/删夹具**，不参与被测页面。 */
async function adminToken(request: APIRequestContext): Promise<string> {
  const response = await request.post('/api/v1/auth/login', {
    data: { identifier: ADMIN.identifier, password: ADMIN.password },
  })
  expect(response.ok(), '管理员登录失败：夹具没法造').toBeTruthy()
  const body = (await response.json()) as { data: { accessToken: string } }
  return `Bearer ${body.data.accessToken}`
}

export interface Fixture {
  cleanup: () => Promise<void>
}

export async function createConceptFixture(request: APIRequestContext): Promise<Fixture> {
  const bearer = await adminToken(request)
  const name = `e2e 图谱概念 ${Date.now()}`

  const created = await request.post('/api/v1/knowledge/admin/concepts', {
    headers: { Authorization: bearer },
    data: { name, summary: 'e2e 自动创建，跑完删除' },
  })
  expect(created.ok()).toBeTruthy()
  const id = ((await created.json()) as { data: number }).data

  // 挂一个已发布条目，让节点带载体（也顺带走了"挂条目"这条写路径）。
  // 这一步失败必须先把概念删掉再抛：否则用例红了，夹具却留在库里，下一轮"节点数"就对不上。
  try {
    const entries = await request.get('/api/v1/knowledge/entries?size=1', {
      headers: { Authorization: bearer },
    })
    expect(entries.ok(), '取条目失败：夹具没法挂载体').toBeTruthy()
    const list = (await entries.json()) as { data: { items: { id: number }[] } }
    const entryId = list.data.items[0]?.id
    if (entryId !== undefined) {
      const attached = await request.post(`/api/v1/knowledge/admin/concepts/${id}/entries`, {
        headers: { Authorization: bearer },
        data: { entryId },
      })
      expect(attached.ok(), '挂条目失败：夹具没法让节点带载体').toBeTruthy()
    }
  } catch (error) {
    await request.delete(`/api/v1/knowledge/admin/concepts/${id}`, {
      headers: { Authorization: bearer },
    })
    throw error
  }

  return {
    cleanup: async () => {
      const deleted = await request.delete(`/api/v1/knowledge/admin/concepts/${id}`, {
        headers: { Authorization: bearer },
      })
      // 404 说明已被删（上一轮中断、或并发的另一条用例），不算失败；其它非 2xx 要显式红，
      // 否则夹具会无声地积在库里。
      expect(deleted.ok() || deleted.status() === 404, '夹具清理失败：概念残留在库里').toBeTruthy()
    },
  }
}
