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

  test('答完小测后「已读完」写进服务端，换一个浏览器上下文仍然在', async ({ page, browser }) => {
    await page.goto('/#/source/mybatis-source')
    await page.locator('[data-testid^="source-chapter-"]').first().click()
    await expect(page.getByTestId('source-walk')).toBeVisible({ timeout: 15_000 })

    // 总览那一关的 3 道题，答案分别是选项 1 / 2 / 0（见 00-总览.md）
    await page.getByTestId('quiz-option-0-1').click()
    await page.getByTestId('quiz-option-1-2').click()
    await page.getByTestId('quiz-option-2-0').click()
    // 通关规则是「动手题与小测都要 ≥80%」：总览的动手题是填空，选对再检查
    await page.getByTestId('challenge-blank-0').selectOption('0')
    await page.getByTestId('challenge-check').click()
    // 上报是 600ms 去抖的，等它发出去
    await page.waitForTimeout(2500)

    const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173'
    const fresh = await browser.newPage({ baseURL })
    try {
      await signIn(fresh)
      await fresh.goto('/#/source/mybatis-source')
      const first = fresh.locator('[data-testid^="source-chapter-"]').first()
      await expect(first).toBeVisible({ timeout: 15_000 })
      await expect(first).toContainText('已读完')
    } finally {
      await fresh.close()
    }
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
