import type { TokenStore } from './token-store'

/**
 * 带令牌的 fetch：自动附加 Authorization、并在 401 时刷新一次后重放原请求。
 *
 * <p><b>并发只刷新一次，这是本文件存在的核心理由。</b> refresh 是一次性的——十个并发请求
 * 各自去刷新的话，第一个成功、其余九个拿的是已作废的 refresh，必然失败，表现为
 * "随机掉登录、还很难复现"。所以这里把刷新合并成一个共享的 in-flight Promise。
 *
 * <p><b>重放最多一次</b>：刷新成功后只重放一次原请求。不再递归，否则刷新接口自身返回 401 时
 * 会形成循环。
 *
 * <p>同一请求的发送、响应和重放绑定发起时的会话。凭据明确失效由认证上下文清理。
 */

export interface AuthorizedFetchOptions {
  tokens: TokenStore
  /** 用 refresh 换一组新令牌；返回是否成功。实现见认证上下文。 */
  refresh: () => Promise<boolean>
  /** 会话已失效（刷新失败）。 */
  onSessionLost: () => void
  /** 便于测试注入；默认真实 fetch。 */
  baseFetch?: typeof fetch
}

/** 认证端点自身不参与"刷新后重放"：它们的 401 是有意义的业务结果，不是令牌过期。 */
function isAuthEndpoint(url: string): boolean {
  return url.includes('/api/v1/auth/')
}

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input
  }
  if (input instanceof URL) {
    return input.href
  }
  return input.url
}

export function createAuthorizedFetch(options: AuthorizedFetchOptions): typeof fetch {
  const base = options.baseFetch ?? globalThis.fetch
  let inFlightRefresh: { signal: AbortSignal; promise: Promise<boolean> } | null = null

  const send = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined))
    const access = options.tokens.access()
    if (access !== null && access !== '') {
      headers.set('Authorization', `Bearer ${access}`)
    }
    return base(input, { ...init, headers })
  }

  return async (input, init) => {
    const session = options.tokens.sessionSignal()
    const callerSignal = init?.signal ?? (input instanceof Request ? input.signal : undefined)
    const signal = callerSignal ? AbortSignal.any([session, callerSignal]) : session
    const initWithSignal = { ...init, signal }
    const sendInSession = async () => {
      signal.throwIfAborted()
      const result = await send(input, initWithSignal)
      signal.throwIfAborted()
      return result
    }
    const response = await sendInSession()
    if (response.status !== 401 || isAuthEndpoint(urlOf(input))) {
      return response
    }

    // 共享同一次刷新：所有并发到达的 401 等的是同一个 Promise。
    // 必须接住 rejection——刷新本身抛错时若不转成 false，await 会把异常继续往上冒，
    // 于是 onSessionLost() 不会被调用，调用方拿到的是未处理的拒绝而不是那次 401 响应。
    if (inFlightRefresh?.signal !== session) {
      const promise = options.refresh().catch(() => false).finally(() => {
        if (inFlightRefresh?.promise === promise) inFlightRefresh = null
      })
      inFlightRefresh = { signal: session, promise }
    }
    const refreshed = await inFlightRefresh.promise
    signal.throwIfAborted()
    if (!refreshed) {
      options.onSessionLost()
      return response
    }
    return sendInSession()
  }
}
