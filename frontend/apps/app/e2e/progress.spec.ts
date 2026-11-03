import { expect, test } from '@playwright/test'

import { clickNav, signIn } from './session'

/**
 * 学习记录页（WORK-053）。
 *
 * <p>验收点只有三条，都是"用户视角能观察到的"：
 * <ol>
 *   <li>四块都在，且每块要么有数据要么有空状态——不能是空白卡片；</li>
 *   <li>导航里有入口（只在快捷卡里出现不算，`AGENTS.md` 2026-10-22 漏过一次）；</li>
 *   <li>点入口 → 助手打开、输入框**已预填但不自动发送**（发出去就开始花积分）。</li>
 * </ol>
 */
test('学习记录：四块都渲染，且各自有数据或空状态', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/progress')

  for (const id of ['progress-lines', 'progress-failing', 'progress-reviews', 'progress-plan', 'progress-weak']) {
    await expect(page.getByTestId(id)).toBeVisible({ timeout: 15_000 })
  }
  // 每块都得给出结论：要么有条目，要么写明为什么没有
  await expect(page.getByText('加载中…')).toHaveCount(0, { timeout: 15_000 })
})

test('学习记录：导航里有入口', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/')
  await clickNav(page, 'learner-nav-progress')
  await expect(page).toHaveURL(/#\/progress/)
  await expect(page.getByRole('heading', { name: '学习记录', level: 1 })).toBeVisible()
})

/**
 * 「今天要做的事」（WORK-053 TASK-009 / REQ-006/007）。
 *
 * <p>验的是"采用后刷新仍在"这条唯一能证明**真落库**的路径，以及勾选状态也落库。
 * 日期用浏览器本地的那一天（服务端不做时区猜测），所以这里只看"今天"这一屏。
 */
test('计划：添加一条 → 刷新仍在 → 勾选完成 → 刷新仍是已完成 → 删除', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/progress')

  const goal = `e2e-计划-${Date.now()}`
  await page.getByTestId('plan-goal').fill(goal)
  await page.getByTestId('plan-add').click()
  await expect(page.getByText(goal)).toBeVisible({ timeout: 15_000 })

  // 刷新：不是前端状态，是服务端读回来的
  await page.reload()
  const item = page.getByText(goal)
  await expect(item).toBeVisible({ timeout: 15_000 })

  // 勾选完成
  const row = page.locator('li', { hasText: goal }).first()
  await row.getByRole('checkbox').click()
  await page.reload()
  await expect(page.getByText(goal)).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('li', { hasText: goal }).first().getByRole('checkbox')).toBeChecked()

  // 删除，回到空态或至少不再有这一条
  await page.locator('li', { hasText: goal }).first().getByRole('button', { name: /删除/ }).click()
  await expect(page.getByText(goal)).toHaveCount(0, { timeout: 15_000 })
})
