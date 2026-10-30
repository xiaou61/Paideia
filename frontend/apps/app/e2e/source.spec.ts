import { expect, test } from '@playwright/test'

import { clickNav, signIn } from './session'

/**
 * 源码精读的端到端检查（WORK-057）。
 *
 * <p>断言**结构与交互**，不断言正文措辞：章节标题会改，但「开关变了步骤就变」不该改。
 *
 * <p>依赖内容已导入（`content/source/mybatis`）。本地没导入时这些用例会红——
 * 所以它主要用来验线上或刚导入过的环境。
 */
test.describe('源码精读', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page)
  })

  test('页头有独立入口，项目卡是 MyBatis', async ({ page }) => {
    await page.goto('/#/')
    await clickNav(page, 'learner-nav-source')

    await expect(page).toHaveURL(/#\/source/)
    await expect(page.getByRole('heading', { name: '源码精读', exact: true })).toBeVisible()
    const card = page.getByTestId('source-project-mybatis-source')
    await expect(card).toBeVisible({ timeout: 15_000 })
    await expect(card).toContainText('MyBatis')
  })

  test('项目页给出章节顺序，首章是总览', async ({ page }) => {
    await page.goto('/#/source/mybatis-source')
    const chapters = page.locator('[data-testid^="source-chapter-"]')
    await expect(chapters.first()).toBeVisible({ timeout: 15_000 })
    expect(await chapters.count()).toBeGreaterThanOrEqual(20)
    await expect(chapters.first()).toContainText('总览')
  })

  test('章节页能取到整份源码，下一步会换高亮行', async ({ page }) => {
    await page.goto('/#/source/mybatis-source')
    await page.locator('[data-testid^="source-chapter-"]').first().click()

    await expect(page.getByTestId('source-walk')).toBeVisible({ timeout: 15_000 })
    const focus = page.getByTestId('source-walk-focus-line').first()
    await expect(focus).toBeVisible()
    const before = await focus.textContent()

    await page.getByTestId('source-walk-next').click()
    await expect(page.getByTestId('source-walk-focus-line').first()).not.toHaveText(before ?? '')
  })

  test('切换执行器后步骤真的变了', async ({ page }) => {
    await page.goto('/#/source/mybatis-source')
    // 第 3 关是 Configuration：这一关才有 executor 这个分支
    await page.locator('[data-testid^="source-chapter-"]').nth(2).click()
    await expect(page.getByTestId('source-walk')).toBeVisible({ timeout: 15_000 })

    const steps = page.getByTestId('source-walk-step')
    await expect(steps.filter({ hasText: 'BATCH' })).toHaveCount(0)
    await page.getByTestId('source-walk-control-executor').selectOption('BATCH')
    await expect(steps.filter({ hasText: 'BATCH' })).toHaveCount(1)
  })

  test('24 章逐章都能打开并渲染出源码块', async ({ page }) => {
    await page.goto('/#/source/mybatis-source')
    const links = page.locator('[data-testid^="source-chapter-"]')
    await expect(links.first()).toBeVisible({ timeout: 15_000 })
    const hrefs = await links.evaluateAll((nodes) =>
      nodes.map((node) => (node as HTMLAnchorElement).getAttribute('href') ?? ''),
    )
    expect(hrefs.length).toBe(24)
    for (const href of hrefs) {
      await page.goto(`/${href}`)
      await expect(page.getByTestId('source-walk')).toBeVisible({ timeout: 15_000 })
      await expect(page.getByTestId('source-walk-focus-line').first()).toBeVisible()
    }
  })

  test('375px 下正文没有横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    await page.goto('/#/source/mybatis-source')
    await page.locator('[data-testid^="source-chapter-"]').first().click()
    await expect(page.getByTestId('source-walk')).toBeVisible({ timeout: 15_000 })

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)
  })
})
