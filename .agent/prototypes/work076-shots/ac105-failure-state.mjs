/**
 * WORK-076 AC-105 前端失败态取证（一次性脚本，非产品代码）。
 *
 * 用法：node .agent/prototypes/work076-shots/ac105-failure-state.mjs <截图目录>
 * 前置：`pnpm --filter @paideia/admin built` 产物已存在，并已起 `vite preview --port 5184`。
 *
 * 做法：拦截全部 `/api/v1/**`，让导入列表返回一条 `failed` 任务。验三件事：
 *   1) 状态列显示「失败」；2) 说明列显示错误原因；3) 「重试」可点，点了真的发出 retry 请求。
 * 不打真实上游、不连数据库、不花积分。
 */
import { chromium } from '@playwright/test'

const OUT = process.argv[2]
const BASE = 'http://127.0.0.1:5184'
const envelope = (data) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 0, message: 'ok', data }),
})

const failedRow = {
  id: 7,
  fileId: 'f-123',
  relativePath: '导入测试/子目录/坏文件.pdf',
  status: 'failed',
  entryId: null,
  error: 'PDF 文本抽取失败：文档没有文字层（可能是扫描件）',
  createdAt: '2026-11-06T09:00:00Z',
  updatedAt: '2026-11-06T09:01:00Z',
}

const browser = await chromium.launch({ channel: 'chrome' })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' })
await context.addInitScript(() => {
  window.localStorage.setItem('paideia:refresh-token', 'seed-refresh')
  window.localStorage.setItem('paideia:theme', 'dark')
})
const page = await context.newPage()

let retryCalls = 0
await page.route('**/api/v1/**', async (route) => {
  const url = route.request().url()
  if (url.includes('/auth/refresh')) {
    return route.fulfill(envelope({
      accessToken: 'a', accessExpiresAt: '2099-01-01T00:00:00Z',
      refreshToken: 'r2', refreshExpiresAt: '2099-01-01T00:00:00Z',
      user: { id: 1, username: 'admin', email: 'a@b.c', role: 'admin' },
    }))
  }
  if (/\/admin\/knowledge-imports\/\d+\/retry$/.test(url)) {
    retryCalls += 1
    return route.fulfill(envelope(null))
  }
  if (url.includes('/admin/knowledge-imports')) {
    return route.fulfill(envelope([failedRow]))
  }
  if (url.includes('/account/profile')) {
    return route.fulfill(envelope({ identity: 'job_seeker', identityLabel: '求职者', targetRole: null,
      targetCity: null, timeline: null, stage: null, selectableIdentities: ['job_seeker'] }))
  }
  return route.fulfill(envelope(null))
})

await page.goto(BASE + '/#/knowledge/imports', { waitUntil: 'domcontentloaded' })
const row = page.getByTestId('import-row-7')
await row.waitFor({ timeout: 20000 })

const statusText = (await row.locator('td').nth(2).textContent() ?? '').trim()
const reasonText = (await row.locator('td').nth(4).textContent() ?? '').trim()
const retry = page.getByTestId('import-retry-7')
const retryEnabled = await retry.isEnabled()

await page.waitForTimeout(300)
await page.screenshot({ path: OUT + '/01-import-failed.png', fullPage: true })

await retry.click()
await page.waitForTimeout(600)

console.log(JSON.stringify({ statusText, reasonText, retryEnabled, retryCalls }))
await browser.close()
