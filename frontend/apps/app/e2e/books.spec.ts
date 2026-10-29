import { expect, test } from '@playwright/test'

import { clickNav, signIn } from './session'

/**
 * 书籍详解的端到端检查（WORK-046）。
 *
 * <p>断言的是**结构**：入口可达、书卡在、点进去章节按顺序排。不断言正文写了什么——
 * 正文由内容源决定，改一篇不该让 e2e 红。
 *
 * <p>书卡用 `books-man-month` 这个 slug 定位：本地由 `db/devdata/V956` 播种，
 * 线上由 `content/reading/books-man-month` 导入，两边同名。
 */
test.describe('书籍详解', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page)
  })

  test('页头有独立入口，点进去是书单', async ({ page }) => {
    await page.goto('/#/')
    await clickNav(page, 'learner-nav-books')

    await expect(page).toHaveURL(/#\/books/)
    await expect(page.getByRole('heading', { name: '书籍详解' })).toBeVisible()
  })

  test('书单里有书卡，篇数由分类下的条目数得出', async ({ page }) => {
    await page.goto('/#/books')

    await expect(page.getByTestId('books-grid')).toBeVisible({ timeout: 15_000 })
    const card = page.getByTestId('book-card-books-man-month')
    await expect(card).toBeVisible()
    await expect(card).toContainText('人月神话')
    // 篇数由分类下的条目数决定，不写死：本地种子是 3 条、线上内容源是 5 条
    await expect(card).toContainText(/共 \d+ 篇/)
  })

  test('点书进章节目录，首条是总览且带序号', async ({ page }) => {
    await page.goto('/#/books')

    // 章节目录的条数必须等于书单卡片上的篇数（同一个"分类下条目数"口径），
    // 而不是写死某个数字——本地种子与线上内容源的条数不同。
    const card = page.getByTestId('book-card-books-man-month')
    // 卡片先渲染成「共 0 篇」，等条目列表回来才变成真实篇数，所以要等一个正数
    await expect(card).toContainText(/共 [1-9]\d* 篇/, { timeout: 15_000 })
    const cardText = await card.textContent()
    const expected = Number(cardText?.match(/共 (\d+) 篇/)?.[1] ?? 0)
    expect(expected).toBeGreaterThan(0)

    await card.click()
    await expect(page).toHaveURL(/#\/books\/books-man-month/)
    const chapters = page.locator('[data-testid^="book-chapter-"]')
    await expect(chapters).toHaveCount(expected)

    // 顺序由 sortOrder 决定，不是接口返回的发布时间倒序：首条必须是 order 0 的总览
    await expect(chapters.first()).toContainText('总览')
    await expect(chapters.first()).toContainText('1')
  })

  test('章节能点进正文', async ({ page }) => {
    await page.goto('/#/books/books-man-month')
    await page.locator('[data-testid^="book-chapter-"]').first().click()

    await expect(page).toHaveURL(/#\/knowledge\/entries\/\d+/)
  })
})
