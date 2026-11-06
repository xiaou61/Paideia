import { expect, test } from '@playwright/test'

/**
 * WORK-076 TASK-014：按目录阅读页可打开，左侧目录与右侧阅读区都在。
 *
 * <p>身份由 `global-setup` 的 storageState 预置（与其它学习者端用例同一套），本用例不再自行登录。
 */
test('目录阅读页可打开', async ({ page }) => {
  await page.goto('/#/knowledge/wiki')
  await expect(page.getByTestId('wiki-categories')).toBeVisible()
  await expect(page.getByTestId('wiki-reader')).toBeVisible()
})
