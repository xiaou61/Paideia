import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

/** 与 concepts.spec 同一套登录：管理端没有独立的 session 模块。 */
async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('admin-console')).toBeVisible()
}

/**
 * AI 调用统计两个页面（WORK-022 AC-017）。
 *
 * <p>断言的是**用户能看到的东西**：卡片出得来、筛选可用、点行能展开步骤表、
 * 没数据时是空状态而不是白屏。数字对不对由后端集成测试负责，这里不重复验证统计口径。
 */
test.describe('AI 调用统计', () => {
  test('概览页：侧栏能进，卡片与状态分布出得来', async ({ page }) => {
    await signIn(page)
    await page.goto(`${ADMIN_ENTRY}#/ai-runs/overview`)

    await expect(page.getByRole('heading', { name: 'AI 调用' })).toBeVisible()
    // 六张卡：调用次数 / 成功率 / 耗时 / 首字 / 扣分
    await expect(page.getByTestId('runs-total')).toBeVisible()
    await expect(page.getByTestId('runs-success-rate')).toBeVisible()
    await expect(page.getByTestId('runs-p50')).toBeVisible()
    await expect(page.getByTestId('runs-credits')).toBeVisible()
    // 没有数据时是空状态，不是白屏（AC-017）
    const empty = page.getByTestId('runs-overview-empty')
    const byKind = page.getByTestId('runs-by-kind')
    await expect(empty.or(byKind)).toBeVisible()
  })

  test('明细页：筛选控件可用，点一行能展开步骤表', async ({ page }) => {
    await signIn(page)
    await page.goto(`${ADMIN_ENTRY}#/ai-runs/list`)

    await expect(page.getByTestId('runs-filter-kind')).toBeVisible()
    await expect(page.getByTestId('runs-filter-status')).toBeVisible()

    const table = page.getByTestId('runs-table')
    const empty = page.getByTestId('runs-list-empty')
    await expect(table.or(empty)).toBeVisible()

    // 有数据时点第一行：应出现步骤表，或至少给出"这一轮没有步骤"的可读提示
    if (await table.isVisible()) {
      const firstRow = table.locator('tbody tr').first()
      await firstRow.click()
      await expect(page.getByTestId('run-spans').or(page.getByTestId('run-spans-empty'))).toBeVisible()
    }
  })

  test('概览页：有数据时出趋势图，采集健康那一行也在', async ({ page }) => {
    await signIn(page)
    await page.goto(`${ADMIN_ENTRY}#/ai-runs/overview`)

    // 采集健康是**无条件**要出的：它回答"这份统计可不可信"，没数据时更该能看到
    await expect(page.getByTestId('runs-health-inline')).toBeVisible()

    // 趋势图只在有调用时渲染（空数据走上面的空状态分支）
    const trend = page.getByTestId('runs-trend')
    const empty = page.getByTestId('runs-overview-empty')
    await expect(trend.or(empty)).toBeVisible()
  })

  test('调用大屏：侧栏能进，时间轴与调用流出得来，点一轮能看瀑布', async ({ page }) => {
    await signIn(page)
    // 入口必须在侧栏里（只在首页快捷卡里出现不算）
    await page.goto(`${ADMIN_ENTRY}#/ai-runs/overview`)
    const entry = page.getByRole('link', { name: '调用大屏' })
    await expect(entry).toBeVisible()
    await entry.click()

    await expect(page.getByRole('heading', { name: 'AI 调用大屏' })).toBeVisible()
    await expect(page.getByTestId('screen-timeline-track')).toBeVisible()
    // 大屏不带侧栏：进来之后那一列不该还在
    await expect(page.getByRole('link', { name: '调用大屏' })).toHaveCount(0)

    const feed = page.getByTestId('screen-feed')
    const empty = page.getByTestId('screen-empty')
    await expect(feed.or(empty)).toBeVisible()

    if (await feed.isVisible()) {
      const first = feed.locator('button').first()
      if (await first.count() > 0) {
        await first.click()
        await expect(page.getByTestId('screen-spans').or(page.getByTestId('screen-spans-empty')))
          .toBeVisible()
      }
    }
  })

  test('反馈页：侧栏能进，空状态与筛选都在', async ({ page }) => {
    await signIn(page)
    await page.goto(`${ADMIN_ENTRY}#/ai-runs/feedback`)

    await expect(page.getByRole('heading', { name: '回答反馈' })).toBeVisible()
    await expect(page.getByTestId('feedback-filter-rating')).toBeVisible()
    await expect(page.getByTestId('feedback-filter-reason')).toBeVisible()

    const table = page.getByTestId('feedback-table')
    const empty = page.getByTestId('feedback-empty')
    await expect(table.or(empty)).toBeVisible()
  })
})
