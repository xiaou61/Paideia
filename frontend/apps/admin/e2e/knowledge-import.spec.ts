import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

/** 管理端登录（与 knowledge.spec 同形）。 */
async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('admin-console')).toBeVisible()
}

/**
 * WORK-076 TASK-012：文档导入页可进入、可展示任务表与选择文件夹入口。
 *
 * <p>只验页面与路由，不真正上传（上传后要跑模型蒸馏，依赖平台 AI 凭据，不适合放在默认 e2e 里）。
 */
test('文档导入页可打开并显示任务表', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge/imports')
  await expect(page.getByTestId('import-table')).toBeVisible()
  await expect(page.getByTestId('import-folder-input')).toBeAttached()
})
