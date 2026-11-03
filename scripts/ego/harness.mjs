/**
 * WORK-059 ego 点击 harness。
 *
 * 只在 ego-browser 的 Node 运行时里使用：`taskSpace` / Page 是全局对象。
 * 不引入 Playwright；只用 ego 使用指南列出的 Page API。
 */
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
export const ROOT = path.resolve(HERE, '../..')

const DEFAULT_TIMEOUT = 15_000

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function createSession({ page, baseUrl, role, runId, artifactsDir }) {
  const session = {
    page,
    baseUrl,
    role,
    runId,
    prefix: `e2e-click-${runId}-`,
    artifactsDir,
    steps: [],

    async goto(hashPath) {
      const url = hashPath.startsWith('http') ? hashPath : `${baseUrl}${hashPath}`
      await page.goto(url)
      await page.waitForFunction(
        () => document.readyState === 'complete' && document.body && document.body.innerText.trim().length > 0,
        undefined,
        { timeout: DEFAULT_TIMEOUT },
      )
    },

    async click(text, label) {
      await page.click(`text="${text}"`, label ? { label } : undefined)
    },

    async clickRole(roleName, name, label) {
      await page.click(`loc=role:${roleName}[name='${name}']`, label ? { label } : undefined)
    },

    async clickCss(selector, label) {
      await page.click(`loc=css:${selector}`, label ? { label } : undefined)
    },

    async clickSelector(selector, label) {
      await page.click(selector, label ? { label } : undefined)
    },

    // 有些页面同时渲染桌面/移动两套列表，另一套是 display:none；ego 的 click 会因
    // “匹配到隐藏元素”而失败。这里只点第一个有布局尺寸的元素。
    async clickFirstVisible(selector, label) {
      const clicked = await page.evaluate((sel) => {
        const element = [...document.querySelectorAll(sel)].find((item) => item.getBoundingClientRect().width > 0)
        if (!element) return false
        element.click()
        return true
      }, selector)
      if (!clicked) throw new Error(`没有可见元素：${selector}`)
    },

    async focus(selector) {
      const focused = await page.evaluate((sel) => {
        const element = document.querySelector(sel)
        if (!element) return false
        element.focus()
        return true
      }, selector)
      if (!focused) throw new Error(`找不到可聚焦元素：${selector}`)
    },

    async fill(selector, value) {
      await page.fill(selector, value)
    },

    async selectOption(selector, value) {
      await page.selectOption(selector, value)
    },

    async press(key) {
      await page.press(key)
    },

    async type(text) {
      await page.keyboard.type(text)
    },

    async expectText(text, timeout = DEFAULT_TIMEOUT) {
      await page.waitForFunction(
        (needle) => document.body.innerText.includes(needle),
        text,
        { timeout },
      )
    },

    async expectNoText(text, timeout = DEFAULT_TIMEOUT) {
      await page.waitForFunction(
        (needle) => !document.body.innerText.includes(needle),
        text,
        { timeout },
      )
    },

    async expectUrl(fragment, timeout = DEFAULT_TIMEOUT) {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        if ((await page.url()).includes(fragment)) return
        await sleep(150)
      }
      throw new Error(`URL 未包含「${fragment}」，当前是 ${await page.url()}`)
    },

    async waitFor(selector, state = 'visible', timeout = DEFAULT_TIMEOUT) {
      await page.waitForSelector(`loc=css:${selector}`, { state, timeout })
    },

    async waitForAny(selectors, timeout = DEFAULT_TIMEOUT) {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        for (const selector of selectors) {
          if (await session.count(selector)) return selector
        }
        await sleep(150)
      }
      throw new Error(`等待超时，以下都没有出现：${selectors.join(' | ')}`)
    },

    async textOf(selector) {
      return page.evaluate((sel) => document.querySelector(sel)?.textContent?.trim() ?? '', selector)
    },

    async valueOf(selector) {
      return page.evaluate((sel) => document.querySelector(sel)?.value ?? '', selector)
    },

    async reload() {
      await page.reload()
      await page.waitForFunction(
        () => document.readyState === 'complete' && document.body && document.body.innerText.trim().length > 0,
        undefined,
        { timeout: DEFAULT_TIMEOUT },
      )
    },

    async count(selector) {
      return page.evaluate((sel) => document.querySelectorAll(sel).length, selector)
    },

    async evaluate(fn, arg = null) {
      return page.evaluate(fn, arg)
    },

    async currentUser() {
      return page.evaluate(
        () => document.querySelector('[data-testid="current-user"]')?.textContent?.trim() ?? '',
      )
    },

    async login(credentials) {
      await session.goto('/#/login')
      const hasForm = await page.evaluate(() => !!document.querySelector('[data-testid="login-form"]'))
      if (!hasForm) {
        const current = await session.currentUser()
        if (current === credentials.username) return
        await session.logout()
        await session.goto('/#/login')
      }
      await session.fill('[data-testid="login-identifier"]', credentials.username)
      await session.fill('[data-testid="login-password"]', credentials.password)
      await session.clickCss('[data-testid="login-submit"]', '提交登录')
      await session.waitFor('[data-testid="account-menu-trigger"]')
      const current = await session.currentUser()
      if (current !== credentials.username) {
        throw new Error(`登录后身份是「${current}」，期望「${credentials.username}」`)
      }
    },

    async logout() {
      await session.clickCss('[data-testid="account-menu-trigger"]', '打开账号菜单')
      await session.waitFor('[data-testid="logout"]')
      await session.clickCss('[data-testid="logout"]', '登出')
      await session.waitFor('[data-testid="login-form"]')
    },

    async hotkey(keys) {
      await page.keyboard.press(keys)
    },

    async step(name, fn) {
      const record = { name, status: 'running', durationMs: 0 }
      session.steps.push(record)
      const started = Date.now()
      try {
        await fn()
        record.status = 'passed'
      } catch (error) {
        record.status = 'failed'
        error.step = name
        throw error
      } finally {
        record.durationMs = Date.now() - started
      }
    },

    async shot(name) {
      await mkdir(artifactsDir, { recursive: true })
      const safe = name.replace(/[\\/:*?"<>|\s]+/g, '-')
      const file = path.join(artifactsDir, `${safe}.png`)
      await page.screenshot({ path: file })
      return file
    },

    safeName(suffix) {
      return `${session.prefix}${suffix}`
    },

    assertSafeTarget(text) {
      if (!text.includes(session.prefix)) {
        throw new Error(`拒绝删除「${text}」：标题不含运行前缀 ${session.prefix}`)
      }
    },
  }

  return session
}
