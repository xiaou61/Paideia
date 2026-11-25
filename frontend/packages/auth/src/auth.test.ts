import { describe, expect, it, vi } from 'vitest'

import { createAuthorizedFetch } from './authorized-fetch'
import { redirectTarget } from './require-auth'
import { createTokenStore } from './token-store'

function memoryStorage() {
  const store = new Map<string, string>()
  return {
    store,
    storage: {
      get: (key: string) => store.get(key) ?? null,
      set: (key: string, value: string) => {
        store.set(key, value)
      },
      remove: (key: string) => {
        store.delete(key)
      },
    },
  }
}

function jsonResponse(status: number, body: unknown = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('令牌存储', () => {
  it('access 只放内存，落盘的只有 refresh', () => {
    const { storage, store } = memoryStorage()
    const tokens = createTokenStore(storage)

    tokens.set({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    expect(tokens.access()).toBe('access-1')
    expect([...store.keys()]).toEqual(['refresh-token'])
    expect(store.get('refresh-token')).toBe('refresh-1')
  })

  it('重新构造时能从存储里恢复 refresh，但拿不到 access', () => {
    const { storage } = memoryStorage()
    createTokenStore(storage).set({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    const restored = createTokenStore(storage)

    expect(restored.refresh()).toBe('refresh-1')
    expect(restored.access()).toBeNull()
  })

  it('clear 会清掉内存与存储两处', () => {
    const { storage, store } = memoryStorage()
    const tokens = createTokenStore(storage)
    tokens.set({ accessToken: 'a', refreshToken: 'r' })

    tokens.clear()

    expect(tokens.access()).toBeNull()
    expect(tokens.refresh()).toBeNull()
    expect(store.has('refresh-token')).toBe(false)
  })

  it('只有新登录和登出才结束原会话', () => {
    const tokens = createTokenStore(memoryStorage().storage)
    const guest = tokens.sessionSignal()
    tokens.startSession({ accessToken: 'a', refreshToken: 'r' })
    const signedIn = tokens.sessionSignal()
    expect(guest.aborted).toBe(true)
    tokens.set({ accessToken: 'a2', refreshToken: 'r2' })
    expect(tokens.sessionSignal()).toBe(signedIn)
    expect(signedIn.aborted).toBe(false)
    tokens.clear()
    expect(signedIn.aborted).toBe(true)
  })

  it('存储清理失败时内存照样清掉，且不把异常抛给调用方', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const storage = {
      get: () => 'refresh-1',
      set: () => {},
      // 隐私模式/缓存接口异常：remove 会抛
      remove: () => {
        throw new Error('storage disabled')
      },
    }
    const tokens = createTokenStore(storage)

    expect(() => tokens.clear()).not.toThrow()
    // 内存必须清掉，否则界面会停在"已登录"
    expect(tokens.access()).toBeNull()
    expect(tokens.refresh()).toBeNull()
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})

describe('带令牌的 fetch', () => {
  it('请求会带上 Authorization 头', async () => {
    const { storage } = memoryStorage()
    const tokens = createTokenStore(storage)
    tokens.set({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    const baseFetch = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse(200))
    const fetchWithAuth = createAuthorizedFetch({
      tokens,
      refresh: async () => true,
      onSessionLost: () => {},
      baseFetch: baseFetch as unknown as typeof fetch,
    })

    await fetchWithAuth('/api/v1/me')

    const init = baseFetch.mock.calls[0]?.[1]
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-1')
  })

  it('并发多个 401 只触发一次刷新，然后各自重放', async () => {
    const { storage } = memoryStorage()
    const tokens = createTokenStore(storage)
    tokens.set({ accessToken: 'stale', refreshToken: 'refresh-1' })

    let calls = 0
    const baseFetch = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => {
      calls += 1
      // 前两次（并发的两个原请求）返回 401，重放时返回 200
      return calls <= 2 ? jsonResponse(401) : jsonResponse(200)
    })
    const refresh = vi.fn(async () => {
      // 刷新有延迟，模拟真实网络：这正是并发窗口的来源
      await new Promise((resolve) => setTimeout(resolve, 10))
      tokens.set({ accessToken: 'fresh', refreshToken: 'refresh-2' })
      return true
    })
    const fetchWithAuth = createAuthorizedFetch({
      tokens,
      refresh,
      onSessionLost: () => {},
      baseFetch: baseFetch as unknown as typeof fetch,
    })

    const [first, second] = await Promise.all([fetchWithAuth('/api/v1/a'), fetchWithAuth('/api/v1/b')])

    // 核心断言：refresh 是一次性的，必须只调用一次
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(first.status).toBe(200)
    expect(second.status).toBe(200)
    // 重放时用的是新令牌
    const replayed = baseFetch.mock.calls[2]?.[1]
    expect(new Headers(replayed?.headers).get('Authorization')).toBe('Bearer fresh')
  })

  it.each([200, 401])('does not retry a stale request in a new session (HTTP %i)', async (status) => {
    const tokens = createTokenStore(memoryStorage().storage)
    tokens.startSession({ accessToken: 'a', refreshToken: 'a-refresh' })
    let finish!: (response: Response) => void
    const delayed = new Promise<Response>((resolve) => { finish = resolve })
    const baseFetch = vi.fn<typeof fetch>(() => delayed)
    const refresh = vi.fn(async () => true)
    const fetchWithAuth = createAuthorizedFetch({ tokens, refresh, onSessionLost: vi.fn(), baseFetch })

    const pending = fetchWithAuth('/api/v1/me')
    tokens.clear()
    tokens.startSession({ accessToken: 'b', refreshToken: 'b-refresh' })
    expect(baseFetch.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
    finish(jsonResponse(status))

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    expect(refresh).not.toHaveBeenCalled()
    expect(baseFetch).toHaveBeenCalledTimes(1)
  })

  it('新会话的请求不复用旧会话正在执行的刷新', async () => {
    const tokens = createTokenStore(memoryStorage().storage)
    tokens.startSession({ accessToken: 'a', refreshToken: 'a-refresh' })
    let finish!: (ok: boolean) => void
    const delayed = new Promise<boolean>((resolve) => { finish = resolve })
    const refresh = vi.fn(async () => true).mockImplementationOnce(() => delayed)
    const baseFetch = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(jsonResponse(200))
    const onSessionLost = vi.fn()
    const fetchWithAuth = createAuthorizedFetch({ tokens, refresh, onSessionLost, baseFetch })

    const old = fetchWithAuth('/api/v1/a')
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    tokens.startSession({ accessToken: 'b', refreshToken: 'b-refresh' })
    await expect(fetchWithAuth('/api/v1/b')).resolves.toMatchObject({ status: 200 })
    finish(false)
    await expect(old).rejects.toMatchObject({ name: 'AbortError' })

    expect(onSessionLost).not.toHaveBeenCalled()
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(baseFetch.mock.calls.map(([url]) => url)).toEqual(['/api/v1/a', '/api/v1/b', '/api/v1/b'])
  })

  it('保留调用方的取消信号，不重放取消的请求', async () => {
    const tokens = createTokenStore(memoryStorage().storage)
    const cancel = new AbortController()
    const baseFetch = vi.fn<typeof fetch>(async (_input, init) => {
      cancel.abort()
      expect(init?.signal?.aborted).toBe(true)
      return jsonResponse(401)
    })
    const refresh = vi.fn(async () => true)
    const fetchWithAuth = createAuthorizedFetch({ tokens, refresh, onSessionLost: vi.fn(), baseFetch })

    await expect(fetchWithAuth('/api/v1/me', { signal: cancel.signal })).rejects.toMatchObject({ name: 'AbortError' })
    expect(refresh).not.toHaveBeenCalled()
    expect(tokens.sessionSignal().aborted).toBe(false)
  })

  it('刷新失败时会话结束，只回调一次且不重放', async () => {
    const { storage } = memoryStorage()
    const tokens = createTokenStore(storage)
    tokens.set({ accessToken: 'stale', refreshToken: 'refresh-1' })
    const baseFetch = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse(401))
    const onSessionLost = vi.fn()
    const fetchWithAuth = createAuthorizedFetch({
      tokens,
      refresh: async () => false,
      onSessionLost,
      baseFetch: baseFetch as unknown as typeof fetch,
    })

    const response = await fetchWithAuth('/api/v1/me')

    expect(response.status).toBe(401)
    expect(onSessionLost).toHaveBeenCalledTimes(1)
    // 原请求 + 一次刷新判定，没有额外的重放
    expect(baseFetch).toHaveBeenCalledTimes(1)
  })

  it('认证端点自身的 401 不触发刷新（那是业务结果，不是令牌过期）', async () => {
    const { storage } = memoryStorage()
    const tokens = createTokenStore(storage)
    const baseFetch = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse(401))
    const refresh = vi.fn(async () => true)
    const fetchWithAuth = createAuthorizedFetch({
      tokens,
      refresh,
      onSessionLost: () => {},
      baseFetch: baseFetch as unknown as typeof fetch,
    })

    const response = await fetchWithAuth('/api/v1/auth/login', { method: 'POST' })

    expect(response.status).toBe(401)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('刷新本身抛错时也按会话结束处理，不把 rejection 漏给调用方', async () => {
    const { storage } = memoryStorage()
    const tokens = createTokenStore(storage)
    tokens.set({ accessToken: 'stale', refreshToken: 'refresh-1' })
    const baseFetch = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse(401))
    const onSessionLost = vi.fn()
    const fetchWithAuth = createAuthorizedFetch({
      tokens,
      // 网络断了：刷新是 reject 而不是 resolve(false)
      refresh: async () => {
        throw new Error('network down')
      },
      onSessionLost,
      baseFetch: baseFetch as unknown as typeof fetch,
    })

    const response = await fetchWithAuth('/api/v1/me')

    expect(response.status).toBe(401)
    expect(onSessionLost).toHaveBeenCalledTimes(1)
  })
})

describe('登录后回跳地址', () => {
  it('只接受站内相对路径', () => {
    expect(redirectTarget('?redirect=%2Fcourses%2F1')).toBe('/courses/1')
  })

  it('挡掉协议相对、反斜杠与空白字符这些绕过写法', () => {
    // //evil.com —— 协议相对地址，浏览器会跳到外站
    expect(redirectTarget('?redirect=%2F%2Fevil.com')).toBe('/')
    // /\evil.com —— 反斜杠会被浏览器归一化成 `/`
    expect(redirectTarget('?redirect=%2F%5Cevil.com')).toBe('/')
    // "/\t//evil.com" —— 制表符会被浏览器直接剥掉
    expect(redirectTarget('?redirect=%2F%09%2F%2Fevil.com')).toBe('/')
    // 绝对地址
    expect(redirectTarget('?redirect=https%3A%2F%2Fevil.com')).toBe('/')
    // 没有该参数
    expect(redirectTarget('')).toBe('/')
  })
})
