import { expect, test, type Page, type Request, type Route } from '@playwright/test'

const auth = '/api/v1/auth'
const notes = '/api/v1/notes'
const users = '/api/v1/account/admin/users'
const a = { id: '1', username: 'synthetic-account-a' }
const b = { id: '2', username: 'synthetic-account-b' }
const aRow = 'synthetic A private row'
const aLate = 'synthetic A delayed response'
const bRow = 'synthetic B private row'

function gate() {
  let release!: () => void
  const wait = new Promise<void>((resolve) => { release = resolve })
  return { wait, release }
}

function ok(route: Route, data: unknown) {
  return route.fulfill({ json: { code: 0, message: '', data, traceId: null } })
}

function note(id: number, title: string) {
  return { id, title, entryId: null, excerpt: null, updatedAt: null }
}

function userPage(username: string) {
  return {
    page: 1, size: 20, total: 1,
    items: [{ id: 42, username, email: 'fixture@example.invalid', role: 'learner', createdAt: null,
      balance: 0, usedCredits: 0, runCount: 0, lastActiveAt: null }],
  }
}

async function login(page: Page, account: typeof a) {
  await page.getByTestId('login-identifier').fill(account.username)
  await page.getByTestId('login-password').fill('synthetic-form-input')
  await page.getByTestId('login-submit').click()
}

test('account switch isolates cached and delayed data; ordinary renewal keeps the cache', async ({ page, baseURL }, testInfo) => {
  const admin = testInfo.project.name === 'admin'
  const path = admin ? '/#/users' : '/#/notes/all'
  const endpoint = admin ? users : notes
  const currentUser = page.getByTestId(admin ? 'admin-current-user' : 'current-user')
  const lateStarted = gate()
  const releaseLate = gate()
  const lateSettled = gate()
  const lateRouteDone = gate()
  const writeStarted = gate()
  const releaseWrite = gate()
  const writeRouteDone = gate()
  const bStarted = gate()
  const releaseB = gate()
  let active = ''
  let oldRequest: Request | undefined
  const requestDone = (request: Request) => { if (request === oldRequest) lateSettled.release() }
  page.on('requestfinished', requestDone)
  page.on('requestfailed', requestDone)
  let refreshes = 0
  let aListRequests = 0
  let bListRequests = 0
  let writes = 0
  const unexpected: string[] = []

  function issued(account: typeof a, generation: number) {
    return {
      user: { ...account, email: null, role: admin ? 'admin' : 'learner' },
      accessToken: `${account.id}-fixture-access-${generation}`,
      refreshToken: `${account.id}-fixture-refresh-${generation}`,
      accessExpiresAt: '2099-01-01T00:00:00Z', refreshExpiresAt: '2099-01-01T00:00:00Z',
    }
  }
  const aInitial = issued(a, 1)
  const aRenewed = issued(a, 2)
  const bInitial = issued(b, 1)

  await page.context().route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    const method = request.method()
    if (url.origin === baseURL) {
      if (request.resourceType() !== 'fetch' && request.resourceType() !== 'xhr'
        && !/^\/(api|actuator)(\/|$)/.test(pathname)) return route.continue()
      if (pathname === `${auth}/oauth/providers` && method === 'GET') return ok(route, { providers: [] })
      if (pathname === '/actuator/health' && method === 'GET' && admin) return route.fulfill({ json: { status: 'UP' } })
      if (pathname === `${auth}/login` && method === 'POST') {
        const { identifier } = request.postDataJSON()
        if (identifier === a.username || identifier === b.username) {
          const session = identifier === a.username ? aInitial : bInitial
          active = session.user.id
          return ok(route, session)
        }
      }
      if (pathname === `${auth}/refresh` && method === 'POST' && active === a.id
        && request.postDataJSON().refreshToken === aInitial.refreshToken) {
        refreshes++
        return ok(route, aRenewed)
      }
      if (pathname === `${auth}/logout` && method === 'POST' && active === a.id
        && request.postDataJSON().refreshToken === aRenewed.refreshToken) {
        active = ''
        return ok(route, null)
      }

      const bearer = request.headers()['authorization']
      const fromA = active === a.id && [aInitial, aRenewed].some(({ accessToken }) => bearer === `Bearer ${accessToken}`)
      const fromB = active === b.id && bearer === `Bearer ${bInitial.accessToken}`
      if (admin && fromA && method === 'POST' && pathname === `${users}/42/role`) {
        writes++
        writeStarted.release()
        await releaseWrite.wait
        try {
          return await ok(route, userPage(aRow).items[0])
        } finally {
          writeRouteDone.release()
        }
      }
      if ((fromA || fromB) && method === 'GET') {
        const query = url.searchParams.get('query')
        const primary = pathname === endpoint && query === null
        const renew = admin ? pathname === users && query === 'renewal' : pathname === `${notes}/1`
        const late = admin ? pathname === users && query === 'pending' : pathname === `${notes}/2`
        if (primary) {
          if (fromA) aListRequests++
          else {
            bListRequests++
            bStarted.release()
            await releaseB.wait
          }
          const name = fromA ? aRow : bRow
          return ok(route, admin ? userPage(name) : [note(1, name), note(2, 'Open fixture detail')])
        }
        if (renew && fromA) {
          if (bearer === `Bearer ${aInitial.accessToken}`) return route.fulfill({ status: 401, json: { code: 401, message: 'Fixture access expired' } })
          return ok(route, admin ? userPage('Renewed result') : { ...note(1, 'Renewed result'), body: '', createdAt: null })
        }
        if (late) {
          if (fromA) {
            oldRequest = request
            lateStarted.release()
            await releaseLate.wait
          }
          const name = fromA ? aLate : 'B result after switch'
          try {
            await ok(route, admin ? userPage(name) : { ...note(2, name), body: '', createdAt: null })
          } finally {
            if (fromA) lateRouteDone.release()
          }
          return
        }
        if (!admin && /^\/api\/v1\/notes\/[12]\/shares$/.test(pathname)) return ok(route, [])
      }
    }
    unexpected.push(`${method} ${url.origin}${pathname}`)
    return route.abort()
  })

  const search = page.getByTestId('admin-user-search')
  const noteTitle = page.getByTestId('note-title')
  try {
    await page.goto(path)
    await login(page, a)
    await expect(currentUser).toHaveText(a.username)
    await expect(page.getByText(aRow, { exact: true })).toBeVisible()

    if (admin) {
      await search.fill('renewal')
      await expect(page.getByText('Renewed result', { exact: true })).toBeVisible()
      await expect(search).toHaveValue('renewal')
    } else {
      await page.getByTestId('note-item-1').click()
      await expect(noteTitle).toHaveValue('Renewed result')
      await expect(page.getByText(aRow, { exact: true })).toBeVisible()
    }
    expect(refreshes).toBe(1)
    expect(aListRequests).toBe(1)

    if (admin) {
      // 模拟查询库的离线事件，浏览器仍可调用本地夹具。写不能暂停等待未来账号上线。
      await page.evaluate(() => window.dispatchEvent(new Event('offline')))
      await page.getByTestId('admin-user-edit-42').click()
      await page.getByTestId('admin-user-set-admin').click()
      await writeStarted.wait
      await page.getByTestId('admin-user-cancel').click()
      await page.evaluate(() => window.dispatchEvent(new Event('online')))
    }
    if (admin) await search.fill('pending')
    else await page.getByTestId('note-item-2').click()
    await lateStarted.wait

    const logout = page.waitForResponse((response) => new URL(response.url()).pathname === `${auth}/logout`)
    if (!admin) await page.getByTestId('account-menu-trigger').click()
    await page.getByTestId(admin ? 'admin-logout' : 'logout').click()
    expect((await logout).ok()).toBe(true)
    await expect(page.getByTestId('login-form')).toBeVisible()
    // Observe even transient previous-account content, starting before B's login.
    const monitor = await page.evaluateHandle((markers) => {
      const seen = new Set<string>()
      const scan = (text: string | null | undefined) => {
        for (const marker of markers) if (text?.includes(marker)) seen.add(marker)
      }
      const sample = (records: MutationRecord[]) => {
        scan(document.body.textContent)
        scan(document.querySelector<HTMLInputElement>('[data-testid="note-title"]')?.value)
        for (const record of records) {
          scan(record.oldValue)
          for (const node of record.addedNodes) scan(node.textContent)
        }
      }
      const observer = new MutationObserver(sample)
      observer.observe(document.body, { childList: true, subtree: true, characterData: true,
        characterDataOldValue: true, attributes: true, attributeFilter: ['value'], attributeOldValue: true })
      sample([])
      return { read: () => [...seen], stop: () => observer.disconnect() }
    }, [aRow, aLate, 'Renewed result', a.username])

    await login(page, b)
    await expect(currentUser).toHaveText(b.username)
    await bStarted.wait
    await expect(page.getByText(aRow, { exact: true })).toHaveCount(0)
    releaseB.release()
    await expect(page.getByText(bRow, { exact: true })).toBeVisible()
    if (admin) {
      await expect(search).toHaveValue('')
      await search.fill('pending')
      await expect(page.getByText('B result after switch', { exact: true })).toBeVisible()
    } else {
      await expect(page.getByTestId('notes-placeholder')).toBeVisible()
      await page.getByTestId('note-item-2').click()
      await expect(noteTitle).toHaveValue('B result after switch')
    }

    // Browser cancellation and delivery are both valid; never ignore an unrelated route error.
    releaseLate.release()
    await Promise.all([lateRouteDone.wait, lateSettled.wait])
    if (admin) {
      releaseWrite.release()
      await writeRouteDone.wait
    }
    const failure = oldRequest?.failure()
    if (failure) expect(failure.errorText).toBe('net::ERR_ABORTED')
    else expect((await oldRequest?.response())?.status()).toBe(200)
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    await expect(currentUser).toHaveText(b.username)
    if (admin) await expect(page.getByText('B result after switch', { exact: true })).toBeVisible()
    else await expect(noteTitle).toHaveValue('B result after switch')
    expect(await monitor.evaluate((state) => { state.stop(); return state.read() })).toEqual([])
    expect(writes).toBe(admin ? 1 : 0)
    expect(bListRequests).toBe(1)
    expect(unexpected).toEqual([])
  } finally {
    releaseLate.release()
    releaseB.release()
    releaseWrite.release()
  }
})
