import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

/**
 * 运维页的诊断区（WORK-042）。
 *
 * <p>**刻意不点「开始诊断」**：本地 e2e 的后端用的是 `application-local.yml` 里的真实平台凭据，
 * 点一下就是一次真实上游调用与真实积分消耗。提交 → 落库 → 报告展示由后端集成用例覆盖
 * （`OpsDiagnosisIntegrationTest`，模型打桩）。这里只证明"页面接上了、空状态/列表说得清楚"。
 */
async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
}

test('运维页有 AI 诊断区，记录列表或空状态都有明确文案', async ({ page }) => {
  await signIn(page)
  await expect(page.getByTestId('admin-console')).toBeVisible()

  await page.getByRole('link', { name: '运维' }).click()

  await expect(page.getByTestId('ops-diagnosis-card')).toBeVisible()
  await expect(page.getByLabel('诊断关注点')).toBeVisible()
  await expect(page.getByTestId('ops-diagnose')).toBeEnabled()

  // 本地/测试库可能已有诊断记录；两种状态都说明接口接上了。
  await expect(
    page.getByTestId('ops-diagnosis-empty').or(page.getByTestId('ops-diagnosis-list')),
  ).toBeVisible()
})

test('运维页仍保留 Grafana 面板入口', async ({ page }) => {
  await signIn(page)
  await page.getByRole('link', { name: '运维' }).click()

  await expect(page.getByTitle('Paideia 运维面板')).toBeVisible()
})
