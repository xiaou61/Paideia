import { expect, test } from '@playwright/test'

import { signIn } from './session'

/**
 * WORK-076 TASK-014：按目录阅读页可打开，左侧目录与右侧阅读区都在。
 *
 * <p>**必须自己补登一次**，不能只靠 `global-setup` 的 storageState：refresh token 每次使用都会
 * 轮换（`TokenService#refresh`），而共享的那份只够第一个翻开缓存的上下文用——后面再拿同一个串
 * 去换，后端按"令牌被重复使用"处理，直接吊销整条会话链（`revokeFamily`）。实测：单跑本文件是绿的，
 * 整套跑必红——它是唯一一个不自己补登的用例，前面的用例一跑，共享会话就已经被轮换掉了。
 */
test('目录阅读页可打开', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge/wiki')
  await expect(page.getByTestId('wiki-categories')).toBeVisible()
  await expect(page.getByTestId('wiki-reader')).toBeVisible()
})
