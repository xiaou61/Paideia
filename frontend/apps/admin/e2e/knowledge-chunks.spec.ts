import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('admin-console')).toBeVisible()
}

/** WORK-076 TASK-013：切片页路由可达并渲染切片表（空表也可）。 */
test('切片编辑页可打开并显示切片表', async ({ page }) => {
  await signIn(page)
  // 用不存在的条目 1 也可：页面会渲染空表而不是崩溃
  await page.goto('/#/knowledge/entries/1/chunks')
  await expect(page.getByTestId('chunks-table')).toBeVisible()
})
