import { expect, test, type Page } from '@playwright/test'

import { signIn } from './session'

/**
 * 外观页与换肤（WORK-079）。
 *
 * <p>断言的都是"用户视角能观察到的东西"：入口在不在、点一下页面颜色变没变、刷新后记不记得、
 * 第一次绘制前属性有没有落、打印出来是不是纸色。**不断言具体颜色的十六进制**——皮肤会被调色，
 * 那种断言只会把用例变成"改一次色板红一次"的负担。
 *
 * <p>每个用例拿到的都是全新的浏览器上下文（`global-setup` 的 storageState），
 * 所以上一个用例选过的皮肤不会漏到下一个。
 */

/** 当前生效的令牌值（浏览器会把 `var()` 链在这里解析掉）。 */
async function token(page: Page, name: string): Promise<string> {
  return page.evaluate((key) => getComputedStyle(document.documentElement).getPropertyValue(key).trim(), name)
}

async function openAppearance(page: Page): Promise<void> {
  await signIn(page)
  await page.getByTestId('account-menu-trigger').click()
  await page.getByTestId('account-appearance').click()
  await expect(page.getByTestId('skin-filter')).toBeVisible()
}

test('账号菜单里的「外观」能进到外观页，并列出全部皮肤', async ({ page }) => {
  await openAppearance(page)
  await expect(page).toHaveURL(/#\/settings\/appearance/)

  // 皮肤数量与注册表一致是有意义的断言：漏登记会造成"点了没反应"，多登记就是死代码
  await expect(page.locator('[data-testid^="skin-card-"]')).toHaveCount(17)
  await expect(page.getByTestId('skin-card-default')).toHaveAttribute('aria-pressed', 'true')
})

test('选一个皮肤后立即全站生效，刷新后仍然记得', async ({ page }) => {
  await openAppearance(page)
  const before = await token(page, '--background')

  await page.getByTestId('skin-card-paper').click()

  await expect(page.locator('html')).toHaveAttribute('data-skin', 'paper')
  const after = await token(page, '--background')
  expect(after, '换成暖纸后 --background 没变，说明皮肤块没生效').not.toBe(before)

  await page.reload()
  // 首屏脚本把它写回去：这里**不做任何交互**，属性就该已经在
  await expect(page.locator('html')).toHaveAttribute('data-skin', 'paper')
  expect(await token(page, '--background')).toBe(after)
})

test('首屏脚本在第一次绘制前就落好皮肤（不闪一下默认主题）', async ({ page }) => {
  await openAppearance(page)
  await page.getByTestId('skin-card-terminal').click()
  await expect(page.locator('html')).toHaveAttribute('data-skin', 'terminal')

  // domcontentloaded 时 React 还没挂载完，属性只能来自 `<head>` 里的内联脚本。
  // 这一条就是"没有闪一下黑白再变成终端"的可验证形式。
  await page.goto('/#/settings/appearance', { waitUntil: 'domcontentloaded' })
  await expect(page.locator('html')).toHaveAttribute('data-skin', 'terminal')
})

test('关键词筛选能缩小范围，无匹配时给空状态', async ({ page }) => {
  await openAppearance(page)

  await page.getByTestId('skin-filter').fill('终端')
  await expect(page.getByTestId('skin-card-terminal')).toBeVisible()
  await expect(page.getByTestId('skin-card-paper')).toHaveCount(0)

  await page.getByTestId('skin-filter').fill('这个词不可能有皮肤')
  await expect(page.getByTestId('skin-empty')).toBeVisible()
  await expect(page.locator('[data-testid^="skin-card-"]')).toHaveCount(0)
})

test('深色皮肤下打印回到纸色（不会打出满页黑底）', async ({ page }) => {
  await openAppearance(page)
  await page.getByTestId('skin-card-contrast').click()
  // contrast 的暗色是纯黑，最坏情况
  await page.getByTestId('theme-toggle').click()
  await expect(page.locator('html')).toHaveClass(/dark/)

  await page.emulateMedia({ media: 'print' })
  const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
  expect(background, `打印媒体下 body 背景应是纸色，实际是 ${background}`).toBe('rgb(255, 255, 255)')
})
