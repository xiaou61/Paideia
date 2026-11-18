import { expect, test, type Page } from '@playwright/test'

import { signIn } from './session'

/**
 * 刷题页与错题本的端到端检查（WORK-083 TASK-006/007）。
 *
 * <p>只钉不变量：页面能加载、筛选项在、窄屏不横向滚动。题库索引在测试库里可能为空，
 * 因此不断言"一定有题"——空态也是合法终态（刷题页与错题本各自都有明确的空文案）。
 */

function noHorizontalScroll(page: Page) {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
}

test('刷题页加载：标题与筛选项可见，且给出题目或空态', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/practice')
  await expect(page.getByTestId('practice-title')).toHaveText('刷题')
  await expect(page.getByText('只刷未做')).toBeVisible()
  await expect(page.getByText('只刷错题')).toBeVisible()
  await expect(
    page.getByTestId('practice-question').or(page.getByTestId('practice-empty')),
  ).toBeVisible()
})

test('390px 下刷题页无横向滚动', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 })
  await signIn(page)
  await page.goto('/#/practice')
  await expect(page.getByTestId('practice-title')).toBeVisible()
  expect(await noHorizontalScroll(page)).toBeLessThanOrEqual(1)
})

test('错题本页加载：标题可见，给出题目或空态', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/wrong-book')
  await expect(page.getByRole('heading', { name: '错题本' })).toBeVisible()
  // 空态与「有错题」两种终态都合法：空态有专文案，有题时列表里有 li。
  // 不要用 .or() 直接 toBeVisible——空的 <ul> 与空态 <p> 会同时在 DOM 里，strict 会判 2 个元素。
  await expect(async () => {
    const empty = await page.getByTestId('wrong-empty').isVisible().catch(() => false)
    const rows = await page.getByTestId('wrong-list').locator('li').count()
    expect(empty || rows > 0).toBe(true)
  }).toPass({ timeout: 10_000 })
})

test('390px 下错题本页无横向滚动', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 })
  await signIn(page)
  await page.goto('/#/wrong-book')
  await expect(page.getByRole('heading', { name: '错题本' })).toBeVisible()
  expect(await noHorizontalScroll(page)).toBeLessThanOrEqual(1)
})
