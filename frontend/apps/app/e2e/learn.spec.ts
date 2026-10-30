import { expect, test, type Page } from '@playwright/test'

import { signIn } from './session'

/**
 * 从零开始的端到端检查（WORK-024）。
 *
 * <p><b>形态已经不是知识库</b>：`/learn` 列的是**轨道**，点进去是**关卡地图**，
 * 再点是关卡正文。这条链路正是用户要的"闯关"形态，所以 e2e 也要按这个走，
 * 不再断言旧的"线卡片"。
 *
 * <p><b>断言规则而不是课名</b>：不断言"一定有 Java"，只要求"至少有一条轨道、
 * 能进地图、地图里有可点的关卡"。线上内容没导入时那条轨道会显示"内容准备中"，
 * 但页面结构仍然成立。
 */

/** 第一张轨道卡。 */
function firstTrack(page: Page) {
  return page.locator('[data-testid^="learn-track-"]').first()
}

test.describe('从零开始', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page)
  })

  test('首页入口进得去，轨道卡由服务端渲染', async ({ page }) => {
    await page.goto('/#/')
    await expect(page.getByTestId('home-entry-learn')).toBeVisible()
    await page.getByTestId('home-entry-learn').click()

    await expect(page).toHaveURL(/#\/learn/)
    await expect(page.getByRole('heading', { name: /从零开始/ })).toBeVisible()

    // 轨道集合来自服务端：只要求"至少有一条"
    await expect(firstTrack(page)).toBeVisible({ timeout: 15_000 })
  })

  test('岗位选择器与三段地图都在，切岗位后选中态跟着变', async ({ page }) => {
    await page.goto('/#/learn')

    await expect(page.getByTestId('learn-roles')).toBeVisible({ timeout: 15_000 })
    // 三段始终渲染（空段给明确空状态），所以这不依赖库里有没有内容
    await expect(page.getByTestId('learn-core')).toBeVisible()
    await expect(page.getByTestId('learn-advanced')).toBeVisible()
    await expect(page.getByTestId('learn-optional')).toBeVisible()

    // 岗位选择是**存在账号上**的（WORK-047 落库），所以不能假设默认是哪一个：
    // 上一轮跑过这条用例的账号可能已经把目标岗位改走了。先确认"恰好一个被选中"。
    const pressed = page.locator('[data-testid^="learn-role-"][aria-pressed="true"]')
    await expect(pressed).toHaveCount(1, { timeout: 10_000 })

    const backend = page.getByTestId('learn-role-backend')
    const aiAlgo = page.getByTestId('learn-role-ai-algo')
    const backendWasOn = (await backend.getAttribute('aria-pressed')) === 'true'
    const [from, to] = backendWasOn ? [backend, aiAlgo] : [aiAlgo, backend]

    await to.click()
    await expect(to).toHaveAttribute('aria-pressed', 'true')
    await expect(from).toHaveAttribute('aria-pressed', 'false')

    // 切回去：别把账号的目标岗位留给下一条用例
    await from.click()
    await expect(from).toHaveAttribute('aria-pressed', 'true')
  })

  test('推荐卡：每条理由都带真实数字，不是空泛的鼓励', async ({ page }) => {
    await page.goto('/#/learn')
    const reasons = page.locator('[data-testid^="learn-advice-reason-"]')
    await expect(page.getByTestId('learn-roles')).toBeVisible({ timeout: 15_000 })
    // 库里有未完成的轨道才有推荐；没有时这一页本来就该只显示空地图
    test.skip((await reasons.count()) === 0, '本地库里没有未完成的轨道，推荐卡无从生成')

    expect(await reasons.count()).toBeLessThanOrEqual(3)
    const texts = await reasons.evaluateAll((nodes) =>
      nodes.map((node) => node.textContent?.trim() ?? ''),
    )
    // AC-004：理由必须引用具体数字（"6 关中过了 2 关"），不能是"建议你努力"
    expect(texts.every((text) => /\d/.test(text) && text.length > 0)).toBe(true)
  })

  test('点轨道进入关卡地图，地图里有关卡', async ({ page }) => {
    await page.goto('/#/learn')
    const track = firstTrack(page)
    await expect(track).toBeVisible({ timeout: 15_000 })
    await track.click()

    // 地图页：URL 带 slug，且渲染出关卡格子
    await expect(page).toHaveURL(/#\/learn\/[a-z0-9-]+/)
    await expect(page.getByTestId('track-map')).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('[data-testid^="stage-"]').first()).toBeVisible()
  })

  test('关卡全部可进：只有 done 与 current，没有锁定态', async ({ page }) => {
    await page.goto('/#/learn')
    await firstTrack(page).click()
    await expect(page.getByTestId('track-map')).toBeVisible({ timeout: 15_000 })

    // 带 data-status 的才是关卡格；`stage-state-N` / `stage-level-N` 是格子内部的图标与徽章
    const stages = page.locator('[data-testid^="stage-"][data-status]')
    const count = await stages.count()
    expect(count).toBeGreaterThan(0)

    const statuses = await stages.evaluateAll((nodes) =>
      nodes.map((node) => node.getAttribute('data-status')),
    )
    // 解锁不再拦人：每一关都只能是 done 或 current，任何一关都不该是 locked
    expect(statuses.every((status) => status === 'done' || status === 'current')).toBe(true)
    expect(statuses).not.toContain('locked')

    // 而且它们都是链接：最后一关也能点进去
    const last = stages.last()
    expect(await last.evaluate((node) => node.tagName)).toBe('A')
  })

  test('从任意一关都能点到正文（含最后一关）', async ({ page }) => {
    await page.goto('/#/learn')
    await firstTrack(page).click()
    await expect(page.getByTestId('track-map')).toBeVisible({ timeout: 15_000 })

    // 直接点最后一关：没有解锁门槛，它必须能进
    const last = page.locator('[data-testid^="stage-"][data-status]').last()
    await expect(last).toBeVisible()
    await last.click()

    await expect(page).toHaveURL(/#\/knowledge\/entries\/\d+/)
    await expect(page.getByTestId('markdown-body')).toBeVisible({ timeout: 20_000 })
  })

  test('难度可见且能筛选：入门与深入分得开', async ({ page }) => {
    await page.goto('/#/learn')
    await firstTrack(page).click()
    await expect(page.getByTestId('track-map')).toBeVisible({ timeout: 15_000 })

    // 每关都有难度徽章，且文案是大白话
    const badges = page.locator('[data-testid^="stage-level-"]')
    expect(await badges.count()).toBeGreaterThan(0)
    const labels = await badges.evaluateAll((nodes) =>
      nodes.map((node) => node.textContent?.trim() ?? ''),
    )
    expect(labels.some((label) => label === '入门' || label === '进阶' || label === '深入')).toBe(true)

    // 筛选"入门"后，剩下的关卡都必须是入门
    const beginnerFilter = page.getByTestId('level-filter-beginner')
    if ((await beginnerFilter.count()) > 0) {
      const before = await page.locator('[data-testid^="stage-"]').count()
      await beginnerFilter.click()
      const after = await page.locator('[data-testid^="stage-"]').count()
      expect(after).toBeLessThanOrEqual(before)
      expect(after).toBeGreaterThan(0)
      const levels = await page.locator('[data-testid^="stage-level-"]').evaluateAll((nodes) =>
        nodes.map((node) => node.textContent?.trim() ?? ''),
      )
      expect(levels.every((label) => label === '入门')).toBe(true)
    }
  })

  test('轨道与地图在 360px 下不横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 780 })
    await page.goto('/#/learn')
    await firstTrack(page).waitFor({ timeout: 15_000 })
    let overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)

    await firstTrack(page).click()
    await page.getByTestId('track-map').waitFor({ timeout: 15_000 })
    overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)
  })

  test('每一关都能递给助手：点「问助手」后上下文里出现这一关（WORK-058）', async ({ page }) => {
    await page.goto('/#/learn')
    await firstTrack(page).click()
    await expect(page.getByTestId('track-map')).toBeVisible({ timeout: 15_000 })

    // 关卡格本身仍是 <a>；「问助手」按钮是它的兄弟节点（按钮套在链接里是嵌套可交互内容）
    const row = page.locator('[data-testid^="stage-"][data-status]').first().locator('..')
    await row.getByRole('button', { name: '问助手' }).click()

    // 点了就打开面板并把这一关放进上下文条；**不发消息**，所以不烧积分、也不依赖上游
    await expect(page.getByTestId('assistant-shell')).toBeVisible()
    await expect(page.getByTestId('conversation-context')).toContainText('附着 1 个对象')
    const chips = page.getByTestId('conversation-attachments')
    await expect(chips).toBeVisible()
    await expect(chips).toContainText('关')

    // 再取下：从**面板上的附件条**撤（面板是浮层，会挡住关卡右侧那颗按钮——这正是浮窗的常态）
    await page.locator('[data-testid^="detach-"]').first().click()
    await expect(page.getByTestId('conversation-context')).toHaveText('没有条目上下文，可直接提问。')
  })
})
