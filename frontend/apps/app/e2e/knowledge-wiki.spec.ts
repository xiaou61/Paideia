import { expect, test, type Page } from '@playwright/test'

import { LEARNER } from './env'

async function signIn(page: Page) {
  await page.goto('/')
  await expect(page.getByTestId('login-identifier')).toBeVisible()
  await page.getByTestId('login-identifier').fill(LEARNER.identifier)
  await page.getByTestId('login-password').fill(LEARNER.password)
  await page.getByTestId('login-submit').click()
  // 登录后应离开登录页
  await expect(page.getByTestId('login-submit')).toHaveCount(0)
}

/** WORK-076 TASK-014：按目录阅读页可打开，左侧目录与右侧阅读区都在。 */
test('目录阅读页可打开', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge/wiki')
  await expect(page.getByTestId('wiki-categories')).toBeVisible()
  await expect(page.getByTestId('wiki-reader')).toBeVisible()
})
