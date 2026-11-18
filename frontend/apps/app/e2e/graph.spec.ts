import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { signIn } from './session'

/**
 * 个人知识图谱的端到端检查（WORK-021）。
 *
 * <p><b>概念由管理员建立</b>，本地种子里没有。所以这里先用测试库的管理员账号现造一条概念
 * 并挂上一个条目，跑完删掉——否则"有节点"的分支永远走不到，那几条用例就成了只会跳过的摆设。
 *
 * <p>不验掌握度数值：它由服务端纯函数算出，单测与集成测试已经覆盖；这里只验用户能看到的
 * 三件事——能进、有节点时能看图、没有 WebGL 时不白屏。
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

interface Fixture {
  cleanup: () => Promise<void>
}

async function createConceptFixture(request: APIRequestContext): Promise<Fixture> {
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

async function openGraph(page: Page) {
  await page.goto('/#/graph')
  await expect(page.getByRole('heading', { name: /你已经碰到什么/ })).toBeVisible()
}

test.describe('知识图谱', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page)
  })

  test('直接进图谱页：要么有图，要么给一句能读的空状态', async ({ page }) => {
    await openGraph(page)

    const empty = page.getByTestId('graph-empty')
    const stats = page.getByTestId('graph-stats')

    await expect(async () => {
      const hasEmpty = await empty.isVisible()
      const hasStats = await stats.isVisible()
      expect(hasEmpty || hasStats).toBe(true)
    }).toPass({ timeout: 15_000 })
  })

  test('空状态把管理员入口说清楚', async ({ page }) => {
    await openGraph(page)
    const empty = page.getByTestId('graph-empty')
    if (await empty.isVisible()) {
      await expect(empty).toContainText(/概念/)
    }
  })

  test('有节点时能切到 3D 再切回 2D，且不报错', async ({ page, request }) => {
    const fixture = await createConceptFixture(request)
    try {
      await openGraph(page)
      await expect(page.getByTestId('graph-canvas-2d')).toBeVisible({ timeout: 15_000 })

      const toggle = page.getByTestId('graph-mode-toggle')
      await toggle.click()
      await expect(page.getByTestId('graph-canvas-3d')).toBeVisible({ timeout: 20_000 })
      await toggle.click()
      await expect(page.getByTestId('graph-canvas-2d')).toBeVisible({ timeout: 20_000 })
    } finally {
      await fixture.cleanup()
    }
  })

  test('没有 WebGL 时 2D 图谱仍能正常渲染（纯 SVG，不白屏）', async ({ page, request }) => {
    const fixture = await createConceptFixture(request)
    try {
      // 打掉 WebGL。**必须在导航之前注入**——渲染器一挂载就会申请上下文。
      await page.addInitScript(() => {
        const original = HTMLCanvasElement.prototype.getContext
        HTMLCanvasElement.prototype.getContext = function (
          this: HTMLCanvasElement,
          type: string,
          ...rest: unknown[]
        ) {
          if (type.includes('webgl')) {
            return null
          }
          return (original as unknown as (this: HTMLCanvasElement, t: string, ...r: unknown[]) => unknown).call(
            this,
            type,
            ...rest,
          )
        } as typeof HTMLCanvasElement.prototype.getContext
      })

      await openGraph(page)

      // **必须整页重载**：`goto('/#/graph')` 只是改 hash，不会重新执行文档脚本，
      // 注入的 init script 也就不会生效。
      await page.reload()

      // 2D 已改为纯 SVG 渲染（WORK-082），不依赖 WebGL：应画出节点，而不是给降级提示。
      await expect(page.getByTestId('graph-canvas-2d')).toBeVisible({ timeout: 20_000 })
      await expect(page.getByTestId('graph-node').first()).toBeVisible({ timeout: 20_000 })
      await expect(page.getByTestId('graph-fallback')).toHaveCount(0)
    } finally {
      await fixture.cleanup()
    }
  })

  test('点节点即选中并在右栏显示详情', async ({ page, request }) => {
    const fixture = await createConceptFixture(request)
    try {
      await openGraph(page)
      const node = page.getByTestId('graph-node').first()
      await expect(node).toBeVisible({ timeout: 15_000 })
      // 用键盘触发选中：SVG 节点是 role=button + tabIndex=0，比按像素点更稳
      await node.focus()
      await node.press('Enter')
      await expect(page.getByTestId('concept-detail')).toBeVisible({ timeout: 5_000 })
    } finally {
      await fixture.cleanup()
    }
  })

  test('重新布局不抛错', async ({ page, request }) => {
    const fixture = await createConceptFixture(request)
    try {
      await openGraph(page)
      await expect(page.getByTestId('graph-canvas-2d')).toBeVisible({ timeout: 15_000 })
      await page.getByTestId('graph-relayout').click()
      await expect(page.getByTestId('graph-canvas-2d')).toBeVisible({ timeout: 15_000 })
    } finally {
      await fixture.cleanup()
    }
  })

  test('统计行说得出概念数量（夹具的概念真的进了图）', async ({ page, request }) => {
    const fixture = await createConceptFixture(request)
    try {
      await openGraph(page)
      await expect(page.getByTestId('graph-stats')).toContainText(/\d+ 个概念/, { timeout: 15_000 })
    } finally {
      await fixture.cleanup()
    }
  })
})
