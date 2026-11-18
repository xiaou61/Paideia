import { expect, test, type Page } from '@playwright/test'

import { signIn } from './session'

/**
 * 试卷流程端到端（WORK-083 TASK-007）。
 *
 * <p>钉不变量：试卷页露出组卷表单；点「组卷」进入作答页（题库为空时生成 0 题试卷也算成功，
 * 由服务端 shortfall 如实反映）；窄屏不横向滚动。
 */

function noHorizontalScroll(page: Page) {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
}

test('试卷页加载并露出组卷表单', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/papers')
  await expect(page.getByTestId('papers-title')).toHaveText('试卷')
  await expect(page.getByTestId('papers-create')).toBeVisible()
})

test('点组卷后进入作答页', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/papers')
  await page.getByTestId('papers-create').click()
  await expect(page).toHaveURL(/#\/papers\/\d+$/)
  await expect(page.getByTestId('paper-title')).toBeVisible()
})

test('390px 下试卷页无横向滚动', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 })
  await signIn(page)
  await page.goto('/#/papers')
  await expect(page.getByTestId('papers-title')).toBeVisible()
  expect(await noHorizontalScroll(page)).toBeLessThanOrEqual(1)
})
