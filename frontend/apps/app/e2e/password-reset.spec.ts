import { expect, test } from '@playwright/test'

/**
 * 忘记密码的两步界面。
 *
 * <p><b>不真发信</b>：测试环境里邮件走 `InMemoryMailPort` / 日志回退，拿不到真实链接，
 * 因此这里只验"我们这一侧"能验的部分——入口在、两步页面都在、令牌缺失与令牌无效都有可读
 * 提示而不是白屏。真正的全链路（发信 → 点链接 → 旧口令失效 → 令牌一次性）由后端的
 * `PasswordResetIntegrationTest` 覆盖，那里能直接读测试内存端口。
 *
 * <p>用没有会话的上下文：`globalSetup` 把登录态写进 `use.storageState`，而
 * `browser.newContext()` 会继承它——带会话访问 /#/forgot-password 会被 `RequireGuest`
 * 弹回首页，什么都验不到。
 */
test.describe('忘记密码', () => {
  test('登录页有入口，点进去是发链接的表单（带图形验证码）', async ({ browser }) => {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const page = await context.newPage()
    try {
      await page.goto('/#/login')
      await page.getByTestId('login-forgot-password').click()

      await expect(page).toHaveURL(/#\/forgot-password$/)
      await expect(page.getByTestId('forgot-password-form')).toBeVisible()
      await expect(page.getByTestId('forgot-email')).toBeVisible()
      // 图形验证码是发信的硬前置：没有它这个端点就是个免费的邮件轰炸器
      await expect(page.getByTestId('forgot-captcha')).toBeVisible()
      await expect(page.getByTestId('forgot-submit')).toBeEnabled()
    } finally {
      await context.close()
    }
  })

  test('图形验证码答错时不发信，并给出提示', async ({ browser }) => {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const page = await context.newPage()
    try {
      await page.goto('/#/forgot-password')
      await page.getByTestId('forgot-email').fill('nobody@example.com')
      await page.getByTestId('forgot-captcha').fill('definitely-wrong')
      await page.getByTestId('forgot-submit').click()

      await expect(page.getByTestId('forgot-error')).toBeVisible({ timeout: 15_000 })
      // 没发信就不该说"链接已发出"
      await expect(page.getByTestId('forgot-sent')).toHaveCount(0)
    } finally {
      await context.close()
    }
  })

  test('重置页缺少令牌时立刻说明，且给出重新申请的出口', async ({ browser }) => {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const page = await context.newPage()
    try {
      await page.goto('/#/reset-password')

      await expect(page.getByTestId('reset-missing-token')).toBeVisible()
      // 用 role 而不是文本：同一句话在提示正文里也出现了一次，按文本取会命中两个元素
      await expect(page.getByRole('link', { name: '重新申请一封' })).toBeVisible()
      await expect(page.getByTestId('reset-password-form')).toHaveCount(0)
    } finally {
      await context.close()
    }
  })

  test('令牌无效时报"链接无效或已过期"，而不是静默失败', async ({ browser }) => {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const page = await context.newPage()
    try {
      await page.goto('/#/reset-password?token=not-a-real-token-0000000000000000')

      await page.getByTestId('reset-password').fill('a-brand-new-password')
      await page.getByTestId('reset-confirm').fill('a-brand-new-password')
      await page.getByTestId('reset-submit').click()

      await expect(page.getByTestId('reset-error')).toBeVisible({ timeout: 15_000 })
      await expect(page.getByTestId('reset-error')).toContainText(/无效|过期/)
      await expect(page.getByTestId('reset-done')).toHaveCount(0)
    } finally {
      await context.close()
    }
  })
})
