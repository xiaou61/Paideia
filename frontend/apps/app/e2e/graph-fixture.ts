import { expect, type APIRequestContext } from '@playwright/test'

/**
 * 图谱夹具：现造一条概念并挂上一个已发布条目，跑完删掉。
 *
 * <p>概念由管理员建立，本地种子里没有。没有夹具的话"有节点"的分支永远走不到，
 * 那几条用例就成了只会跳过的摆设。`graph.spec.ts` 与 `appearance.spec.ts`（AC-007 换肤跟随）
 * 都要用到它，所以放在这里共用一份——两份拷贝迟早会漂移。
 */

const ADMIN = {
  identifier: process.env.E2E_ADMIN_IDENTIFIER ?? 'seed-admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'seed-admin-password',
}

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

  // 挂一个已发布条目，让节点带载体（也顺带走了"挂条目"这条写路径）
  const entries = await request.get('/api/v1/knowledge/entries?size=1', {
    headers: { Authorization: bearer },
  })
  const list = (await entries.json()) as { data: { items: { id: number }[] } }
  const entryId = list.data.items[0]?.id
  if (entryId !== undefined) {
    await request.post(`/api/v1/knowledge/admin/concepts/${id}/entries`, {
      headers: { Authorization: bearer },
      data: { entryId },
    })
  }

  return {
    cleanup: async () => {
      await request.delete(`/api/v1/knowledge/admin/concepts/${id}`, {
        headers: { Authorization: bearer },
      })
    },
  }
}
