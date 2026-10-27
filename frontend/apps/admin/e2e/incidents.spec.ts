import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

/**
 * 事件页（WORK-043 P0）。
 *
 * <p>只证明「从侧栏进得去、空状态/列表说得清楚」；事件创建/去重/重开/抑制由
 * `OpsHookIntegrationTest` 与 `OpsIncidentMapperTest` 覆盖，不在 e2e 里伪造 webhook。
 */
async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
}

test('事件页从侧栏进得去，空状态/列表有明确文案', async ({ page }) => {
  await signIn(page)
  await expect(page.getByTestId('admin-console')).toBeVisible()

  await page.getByRole('link', { name: '事件' }).click()

  await expect(page.getByRole('heading', { name: '事件' })).toBeVisible()
  await expect(
    page.getByTestId('incidents-empty').or(page.getByTestId('incidents-list')),
  ).toBeVisible()
})
