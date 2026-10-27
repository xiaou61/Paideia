import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

/**
 * 数据大屏（WORK-045）。
 *
 * <p>只验"页面接上了、该有的区块都在、断点能看见、三档视口不横向溢出"。
 * 数字准确性与断点规则由后端集成测试守（`AnalyticsIntegrationTest` /
 * `AnalyticsBrokenRuleTest`）——前端不该重算一遍再断言一次。
 */
async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('admin-console')).toBeVisible()
}

async function openAnalytics(page: Page) {
  await signIn(page)
  // 入口必须在导航里——只在首页放卡片不算（项目规则：新页面必须有导航项）
  await page.getByRole('link', { name: '数据大屏' }).click()
  await expect(page.getByTestId('analytics-flows')).toBeVisible()
}

test('导航能进数据大屏，五个区块都在', async ({ page }) => {
  await openAnalytics(page)

  await expect(page.getByTestId('analytics-flows')).toBeVisible()
  await expect(page.getByTestId('analytics-volume')).toBeVisible()
  await expect(page.getByTestId('analytics-usage')).toBeVisible()
  await expect(page.getByTestId('analytics-ai')).toBeVisible()
  await expect(page.getByTestId('analytics-search')).toBeVisible()
  await expect(page.getByTestId('analytics-monitoring')).toBeVisible()
})

test('六条链各自渲染，节点带 state 属性', async ({ page }) => {
  await openAnalytics(page)

  for (const key of ['knowledge', 'digest', 'ai', 'learning', 'resume', 'sql']) {
    await expect(page.getByTestId(`analytics-flow-${key}`)).toBeVisible()
  }

  // 每个节点都有 data-state，前端靠它上色；缺了会出现"全白但看不出为什么"
  const states = await page.locator('[data-testid^="analytics-node-"]').evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('data-state')),
  )
  expect(states.length).toBeGreaterThan(0)
  expect(states.every((state) => state !== null)).toBe(true)
})

test('SQL 链最后一步显示「未采集」而不是 0', async ({ page }) => {
  await openAnalytics(page)

  // 「结果查看」没有采集：把它画成 0 会让人以为"没人看"，而真相是"没记录"
  await expect(page.getByTestId('analytics-node-sql-3')).toHaveAttribute(
    'data-state',
    'not-collected',
  )
})

test('监控层不可达时给出明确提示，业务区块仍在', async ({ page }) => {
  await openAnalytics(page)

  // 本地 e2e 的后端可能连不上 Prometheus：两种状态都算接上了，
  // 但"不可达"必须有一句明说，不能静默留空
  const down = page.getByTestId('analytics-monitoring-down')
  if ((await down.count()) > 0) {
    await expect(down).toBeVisible()
    await expect(page.getByTestId('analytics-flows')).toBeVisible()
  } else {
    await expect(page.getByText('请求/秒')).toBeVisible()
  }
})

test('三档视口都不出现横向滚动', async ({ page }) => {
  await openAnalytics(page)

  for (const viewport of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport)
    // 等一次布局：柱状用的是百分比高度，窗口变化后要重排
    await page.waitForTimeout(150)
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow, `${viewport.width} 宽出现横向溢出 ${overflow}px`).toBeLessThanOrEqual(1)
  }
})
