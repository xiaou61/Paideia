import { expect, test } from '@playwright/test'
// 手写 mock 加真类型标注：接口形状一改，tsc 立刻报错（e2e 在 tsconfig 的 include 里）。
// 2026-10-23 的 datasets 事故就是手写 mock 不报错导致的。
import type { ReviewDueList, ReviewSession, ReviewSubmitResult } from '@paideia/knowledge'

import { REMOTE } from './env'
import { clickNav, signIn } from './session'

/** 复习页与入口（WORK-034 S1）。线上没有种子进度，只验入口与页面可达。 */
test.describe('复习', () => {
  test.beforeEach(() => {
    test.skip(REMOTE, '依赖本地种子账号；线上只验过接口')
  })

  test('从「学习」组进复习页，列表或空态二选一', async ({ page }) => {
    await signIn(page)
    await clickNav(page, 'learner-nav-review')
    await expect(page).toHaveURL(/#\/review/)

    await expect(
      page.getByTestId('review-list').or(page.getByTestId('review-empty')),
    ).toBeVisible({ timeout: 20_000 })
  })

  test('点开始复习：要么进入会话，要么给降级入口', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/review')
    const start = page.getByTestId('review-start')
    if (await start.isVisible()) {
      await start.click()
      await expect(
        page.getByTestId('review-session').or(page.getByTestId('review-fallback')),
      ).toBeVisible({ timeout: 40_000 })
    }
  })

  test('375px 下复习页不横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 })
    await signIn(page)
    await page.goto('/#/review')
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth,
    )
    expect(overflow).toBeLessThanOrEqual(0)
  })

  test('整轮会话：答题、交卷、看到解析与下次到期日（接口打桩）', async ({ page }) => {
    await page.route('**/api/v1/learning/reviews', (route) =>
      route.fulfill({
        json: {
          code: 0,
          message: 'ok',
          traceId: 'e2e',
          data: {
            total: 1,
            items: [
              {
                entryId: 42,
                title: '到期条目',
                conceptId: 1,
                conceptName: '索引',
                reviewDueAt: '2026-10-22T00:00:00Z',
                stage: 2,
              },
            ],
          } satisfies ReviewDueList,
        },
      }),
    )

    await page.route('**/api/v1/learning/reviews/sessions', (route) =>
      route.fulfill({
        json: {
          code: 0,
          message: 'ok',
          traceId: 'e2e',
          data: {
            sessionId: 1,
            expiresAt: '2026-10-23T12:00:00Z',
            fallback: [],
            items: [
              { itemId: 0, conceptId: 1, stem: '第一题', options: ['A', 'B', 'C', 'D'] },
              { itemId: 1, conceptId: 1, stem: '第二题', options: ['A', 'B', 'C', 'D'] },
            ],
          } satisfies ReviewSession,
        },
      }),
    )
    await page.route('**/api/v1/learning/reviews/sessions/*/answers', (route) =>
      route.fulfill({
        json: {
          code: 0,
          message: 'ok',
          traceId: 'e2e',
          data: {
            results: [
              { itemId: 0, conceptId: 1, correct: true, explanation: '第一题解析' },
              { itemId: 1, conceptId: 1, correct: false, explanation: '第二题解析' },
            ],
            schedule: [{ entryId: 42, stage: 3, reviewDueAt: '2026-11-12T00:00:00Z' }],
          } satisfies ReviewSubmitResult,
        },
      }),
    )
    await signIn(page)
    await page.goto('/#/review')
    await page.getByTestId('review-start').click()
    await expect(page.getByTestId('review-session')).toBeVisible()
    await page.getByTestId('review-option-0').click()
    await page.getByTestId('review-next').click()
    await page.getByTestId('review-option-1').click()
    await page.getByTestId('review-next').click()

    await expect(page.getByTestId('review-result')).toBeVisible()
    await expect(page.getByText('第一题解析')).toBeVisible()
    await expect(page.getByText(/下一次复习/)).toBeVisible()
  })

  test('空态给出两个去处', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/review')
    const empty = page.getByTestId('review-empty')
    if (await empty.isVisible()) {
      await expect(empty.getByRole('link', { name: '去从零开始' })).toHaveAttribute('href', /\/learn/)
      await expect(empty.getByRole('link', { name: '去知识库' })).toHaveAttribute('href', /\/knowledge/)
    }
  })
})