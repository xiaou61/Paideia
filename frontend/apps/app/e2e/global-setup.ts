import { chromium, type FullConfig } from '@playwright/test'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

import { signIn } from './session'

/** storageState 落在系统临时目录：它含令牌，不能进仓库。 */
export const AUTH_FILE = join(tmpdir(), 'paideia-app-e2e-auth.json')

/**
 * 登录一次，把会话存下来给所有用例复用。
 *
 * <p>本地跑本来也不需要，但它让"打线上实例"这条路径可行——线上按来源限流，逐条登录必撞 429。
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'http://127.0.0.1:5173'
  // 本机没装与 Playwright 版本配套的浏览器时，用系统 Chrome（E2E_CHANNEL=chrome）。
  // 不设就是默认行为，CI 与其它机器不受影响。
  const channel = process.env.E2E_CHANNEL
  const browser = await chromium.launch(
    channel === undefined ? {} : { channel: channel as 'chrome' },
  )
  const context = await browser.newContext({ baseURL })
  const page = await context.newPage()
  await signIn(page)
  await context.storageState({ path: AUTH_FILE })
  await browser.close()
}
