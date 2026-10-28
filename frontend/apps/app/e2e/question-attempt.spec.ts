import { expect, test } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 逐题作答上报（WORK-033 T-08）。
 *
 * <p>验的是**浏览器里的真实链路**：点一个选项 → 界面继续本地判分 → 同时发一条
 * `POST /api/v1/learning/attempts`，且请求里的题目身份与正文里的块 id 一致。
 *
 * <p>依赖 `db/devdata` 里带 quiz 的条目 id=10，所以线上跳过——部署实例同号条目内容不同。
 */
test.describe('逐题作答上报', () => {
  test.beforeEach(() => {
    test.skip(REMOTE, '依赖 devdata 里带 quiz 的种子条目 id=10')
  })

  test('答第一题会带着块 id 与题号上报', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/knowledge/entries/10')
    // WORK-048 起题目不再内联：正文末尾有「测一测」入口，点开才是作答面板
    await expect(page.getByTestId('practice-entry')).toBeVisible({ timeout: 20_000 })
    // AC-001：题目不再内联渲染——正文里必须看不到题卡
    await expect(page.getByTestId('quiz')).toHaveCount(0)
    await page.getByTestId('practice-open').click()
    await expect(page.getByTestId('practice-dialog')).toBeVisible()

    const responsePromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/v1/learning/attempts') &&
        response.request().method() === 'POST',
    )
    await page.getByTestId('quiz-option-0-0').click()
    const response = await responsePromise

    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.code).toBe(0)
    expect(body.data.fingerprint).toHaveLength(64)
    expect(typeof body.data.correct).toBe('boolean')

    const sent = JSON.parse(response.request().postData() ?? '{}')
    expect(sent.blockKey).toBe('react-entry-quiz')
    expect(sent.questionKey).toBe('react-entry-quiz#0')
    expect(sent.choice).toBe(0)
  })
})