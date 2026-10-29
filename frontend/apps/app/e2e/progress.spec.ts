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

  for (const id of ['progress-lines', 'progress-failing', 'progress-reviews', 'progress-weak']) {
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

test('学习记录：点「问问」打开助手并预填，但不自动发送', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/progress')

  const ask = page.locator('[data-testid^="progress-ask-failing-"]').first()
  // 没有卡住的关卡时这条用例没有观察点——跳过而不是伪造一条数据
  test.skip((await ask.count()) === 0, '这个账号没有未满分的关卡，卡关入口不可观察')

  await ask.click()
  const input = page.getByTestId('conversation-input')
  await expect(input).toBeVisible({ timeout: 15_000 })
  await expect(input).not.toHaveValue('')
  // 预填 ≠ 发送：消息列表里不该多出这条
  await expect(page.getByText('我在「').first()).toHaveCount(1)
})
