import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

/**
 * 诊断评分与好评率（WORK-043 AC-010）。
 *
 * <p>**不自己造一份 DONE 诊断**：那需要一次真实上游调用与真实积分；而且本地 e2e 的后端连的是
 * 测试库，里面有没有已完成的诊断不由本测试决定。所以：库里有已完成的诊断就展开它、验证三个评分
 * 按钮与好评率文案都在，没有就跳过——评分本身的正确性由 `OpsFeedbackIntegrationTest` 覆盖。
 */
async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('admin-console')).toBeVisible()
}

test('已完成的诊断能展开并出现三档评分', async ({ page }) => {
  await signIn(page)
  await page.getByRole('link', { name: '运维' }).click()
  await expect(page.getByTestId('ops-diagnosis-card')).toBeVisible()

  const finished = page.getByRole('button', { name: /已完成/ }).first()
  if ((await finished.count()) === 0) {
    test.skip(true, '测试库没有已完成的诊断；评分逻辑由集成用例覆盖')
  }

  await finished.click()
  // 报告渲染是异步的（详情单独取），评分区跟着报告一起出现
  await expect(page.getByText('这份诊断准不准？')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('ops-rate-1')).toBeVisible()
  await expect(page.getByTestId('ops-rate-0')).toBeVisible()
  await expect(page.getByTestId('ops-rate--1')).toBeVisible()
  await expect(page.getByLabel('评分备注')).toBeVisible()
})
